package uts.sdk.modules.fnosAuth

import android.content.Context
import android.content.SharedPreferences
import io.dcloud.uts.UTSAndroid
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 飞牛 NAS 的 Web 端口（默认 5667）使用自签证书：
 * 证书 O=fnOS / CN=fnOS，SAN 里只有 DNS:fnOS，用 IP 访问时证书链与域名校验必然失败。
 *
 * 这里采用「首次使用信任」(TOFU)：
 *   - 不校验证书链，只记住首次连接看到的叶证书 SHA-256 指纹；
 *   - 指纹变化时拒绝一次并清除旧指纹，下一次连接重新信任。
 *
 * 为什么允许指纹变化：飞牛证书约三个月自动续期一次，直接锁死会让用户彻底连不上，
 * 且恢复方式（清应用数据）在电视上极难操作。这个取舍与参考实现 NasPhotoTV 一致。
 */
class FnOsTofuTrust(private val prefs: SharedPreferences?) : X509TrustManager, HostnameVerifier {

    @Volatile
    private var lastLeafPin: String? = null

    /** 最近一次是否因指纹变化而拒绝，供 UI 给出可读原因 */
    @Volatile
    var pinMismatch: Boolean = false
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // 客户端证书不校验
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("certificate chain is empty")
        // 先把指纹记下来，真正的比对放在 verify(hostname) 里做（那里才知道主机名）
        lastLeafPin = FnOsCrypto.sha256Hex(leaf.encoded)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun verify(hostname: String, session: SSLSession?): Boolean {
        val pin = lastLeafPin ?: return false
        val host = hostname.substringBefore(':')
        val stored = prefs?.getString(host, null)
        return when {
            stored == null -> {
                // 首次见到的证书：信任并记住
                prefs?.edit()?.putString(host, pin)?.apply()
                true
            }

            stored.equals(pin, ignoreCase = true) -> true

            else -> {
                pinMismatch = true
                prefs?.edit()?.remove(host)?.apply()
                false
            }
        }
    }
}

/**
 * TLS 上下文容器：HTTPS（HttpsURLConnection）和 WSS（裸 SSLSocket）共用同一套
 * TrustManager 与指纹存储，保证「同一个 NAS 只信任一份指纹」。
 */
class FnOsTls {

    private val prefs: SharedPreferences? = runCatching {
        UTSAndroid.getAppContext()?.getSharedPreferences("fnos_cert_pins", Context.MODE_PRIVATE)
    }.getOrNull()

    val trust: FnOsTofuTrust = FnOsTofuTrust(prefs)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")

    init {
        sslContext.init(null, arrayOf<TrustManager>(trust), SecureRandom())
    }
}

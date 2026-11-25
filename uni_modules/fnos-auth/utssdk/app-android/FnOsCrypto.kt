package uts.sdk.modules.fnosAuth

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * fnOS 登录用到的加密原语（移植自 SMTM-PH/NasPhotoTV 的 FnOsCrypto.kt）。
 *
 * 登录流程：
 *   1. WebSocket 上取服务端 RSA 公钥（X.509 SubjectPublicKeyInfo，PEM）；
 *   2. 客户端随机生成 32 字符 ASCII 作为 AES-256 key、16 字节 IV；
 *   3. 用该 AES key 以 AES/CBC/PKCS5Padding 加密登录 JSON；
 *   4. 再用服务端 RSA 公钥以 RSA/ECB/PKCS1Padding 加密这个 AES key；
 *   5. 把 iv / rsa / aes 三段 base64 放进 "encrypted" 信封发回去。
 *
 * 这里不依赖任何三方库，全部走 Android 内置的 JCE，避免插件引入 okhttp/bouncycastle
 * 带来的打包依赖与体积问题。
 */
object FnOsCrypto {

    private val random = SecureRandom()
    private const val HEX = "0123456789abcdef"
    private const val KEY_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** 与 JS 端一致的 md5(utf8 string) 小写十六进制 */
    fun md5(input: String): String = md5Hex(input.toByteArray(Charsets.UTF_8))

    fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return hex(digest)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hex(digest)
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v shr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    /** 32 位随机字母数字，直接当 AES-256 的 key 字节用 */
    fun randomAscii(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(KEY_CHARS[random.nextInt(KEY_CHARS.length)]) }
        return sb.toString()
    }

    fun randomBytes(length: Int): ByteArray {
        val out = ByteArray(length)
        random.nextBytes(out)
        return out
    }

    /** [byteCount] 个随机字节 → 2*byteCount 位十六进制（did / reqid 用） */
    fun randomHex(byteCount: Int): String = hex(randomBytes(byteCount))

    fun aesCbcEncrypt(plain: ByteArray, asciiKey: String, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(asciiKey.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(plain)
    }

    /**
     * PKCS#1 v1.5 加密。
     *
     * 注意：fnOS 下发的 pub 是标准 PEM（-----BEGIN PUBLIC KEY-----，即 X.509
     * SubjectPublicKeyInfo），不是 PKCS#1 的 BEGIN RSA PUBLIC KEY，所以这里统一用
     * X509EncodedKeySpec 解析；两种 PEM 混用会直接抛 InvalidKeySpecException。
     */
    fun rsaEncrypt(pem: String, data: ByteArray): ByteArray {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.decode(body, Base64.DEFAULT)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data)
    }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun b64Decode(text: String): ByteArray = Base64.decode(text, Base64.DEFAULT)
}

package uts.sdk.modules.fnosAuth

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.dcloud.uts.UTSAndroid
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/** 业务异常：message 直接展示给用户，httpStatus 非 0 表示这是网关层的 HTTP 错误 */
class FnOsException(message: String, val httpStatus: Int = 0) : Exception(message)

/**
 * fnOS 登录会话（Kotlin 版，移植自 SMTM-PH/NasPhotoTV 的 FnOsApi.kt）。
 *
 * 对外只暴露「回调式」接口，所有网络与加密都在后台线程完成，结果统一回调到主线程：
 *
 *   login(host, port, user, pass, accessCode) -> JSON
 *   verifyTwoFactor(code)                    -> JSON
 *   stat()                                   -> JSON
 *
 * 返回 JSON 而不是 Kotlin data class，是为了避开 UTS 与 Kotlin 之间的类型映射，
 * UTS 侧用 JSON.parseObject 解析即可。
 *
 * token 只保存在内存里（与参考实现一致）：冷启动会重新登录一次；账号密码、did、
 * 证书指纹写入 SharedPreferences，保证「信任此设备」与自动重登可用。
 */
object FnOsLogin {

    private const val TAG = "FnOsLogin"

    /** UTS 侧诊断日志（release 包 console.log 会被剥掉，只有这里还能看到） */
    fun log(msg: String) {
        Log.i("FnOsApp", msg)
    }
    private const val SALT = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
    private const val SECRET = "EAECCF25-80A6-4666-A7C2-A76904A74AB6"
    private const val ZERO_BACK_ID = "0000000000000000"
    private const val PREF_NAME = "fnos_auth"
    private const val UA = "NASPhotoTV-UniAppX/1.0 (Android TV)"
    private const val TIMEOUT_MS = 12000
    private const val ERRNO_INVALID_CODE = 135168

    /**
     * 「登录设备管理」的展示字段（T4.9，已按 fnOS Web 端实测对齐）：
     * NAS 把 deviceName 按 `-` 拆成两段 —— 第一段当类型/系统（副行小标题）、第二段当客户端名（大标题），
     * 分别显示成「客户端名」标题 + 「类型 登录于 …」副行；
     * 拆不出第二段就整条落成「未知设备」。
     * deviceType 只认 Browser / PC（其余都落「手机」那个通用图标，NAS 前端没有 TV 图标），TV 端归为 Mobile。
     */
    private const val CLIENT_NAME = "NAS Photo TV"
    private const val DEVICE_TYPE = "Mobile"

    private val random = SecureRandom()

    /** 搜索结果里可能装照片数组的键（顺序 = 优先猜测顺序，见 searchPhotoArray） */
    private val SEARCH_ARRAY_KEYS = arrayOf(
        "list", "items", "photos", "photoList", "records", "files", "result", "data", "hits"
    )
    private val lock = Any()
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val tls: FnOsTls by lazy { FnOsTls() }
    private val prefs: SharedPreferences? by lazy {
        runCatching {
            UTSAndroid.getAppContext()?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }.getOrNull()
    }

    @Volatile
    private var token: String = ""

    @Volatile
    private var secret: String = ""

    @Volatile
    private var backId: String = ZERO_BACK_ID

    /**
     * 参数哈希规则：false = RAW（已实测这台 NAS 可用），收到业务码 5000 时自动切到 ENCODED。
     * 注意 query 串始终 URL 编码，只有参与签名的 payload 受这个开关影响。
     */
    @Volatile
    private var signEncoded: Boolean = false

    @Volatile
    private var currentHost: String = ""

    @Volatile
    private var currentPort: Int = 5667

    @Volatile
    private var currentAccessCode: String = ""

    /** true = HTTPS（默认 5667，自签证书 TOFU）；false = 纯 HTTP（默认 5666） */
    @Volatile
    private var httpsEnabled: Boolean = true

    /** 缩略图下载线程池：限制并发，避免电视上一屏几十张图同时打满网络 */
    private val thumbPool = Executors.newFixedThreadPool(4)

    /** 缩略图磁盘缓存目录（应用 cache 目录，系统可回收） */
    private val thumbDir: File? by lazy {
        val ctx = UTSAndroid.getAppContext()
        if (ctx == null) {
            null
        } else {
            val dir = File(ctx.cacheDir, "fnos_thumbs")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir
        }
    }

    /** 等待两步验证的半登录会话；非 null 表示 WebSocket 必须保持打开 */
    private var pending: Pending2FA? = null

    // ------------------------------------------------------------------ 对外 API

    fun login(
        host: String,
        port: Number,
        useHttps: Boolean,
        username: String,
        password: String,
        accessCode: String,
        callback: (String) -> Unit
    ) {
        val h = normalizeHost(host)
        val p = port.toInt()
        val u = username.trim()
        val ac = accessCode.trim()

        Thread {
            val json = try {
                if (h.isEmpty()) {
                    errorJson("请先填写 NAS 地址")
                } else if (u.isEmpty()) {
                    errorJson("请先填写飞牛账号")
                } else {
                    synchronized(lock) {
                        // 先落盘：即使本次失败，地址与账号也会保留，方便下一步「自动重登」
                        saveConfigInternal(h, p, useHttps, u, password, ac)
                        currentHost = h
                        currentPort = p
                        httpsEnabled = useHttps
                        currentAccessCode = ac
                        val outcome = doLogin(h, p, useHttps, u, password, ac)
                        // ⚠️ **必须把 token 落到内存**（2026-11-24 定位到的根因）。
                        // `doLogin()` 只负责握手并返回结果，它**不会**写 `token` 字段；
                        // 以前这里只 `outcomeJson()`，于是 UI 登录成功后 `getToken()` 依然是空串：
                        //   ① 首页判断「有没有 token」→ 一直是空 → 反复 reLaunch 登录页（用户看到「循环闪登录」）；
                        //   ② 每个接口再走一次 `ensureToken()` → **又登录一次**，把上一次的 AccessToken 顶掉 → 401。
                        // 两步验证那条路（verifyTwoFactor）本来就有 `token = newToken`，所以只有普通登录会踩。
                        if (outcome.ok) {
                            token = outcome.token
                            secret = outcome.secret
                        }
                        outcomeJson(outcome)
                    }
                }
            } catch (e: Throwable) {
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    fun verifyTwoFactor(code: String, callback: (String) -> Unit) {
        val digits = code.filter { it.isDigit() }
        Thread {
            val json = try {
                synchronized(lock) {
                    if (digits.length != 6) {
                        need2faJson("请输入 6 位动态验证码")
                    } else {
                        val p = pending ?: throw FnOsException("两步验证会话已过期，请重新登录")
                        val payload = JSONObject()
                        payload.put("req", "user.2fa.loginVerify")
                        payload.put("reqid", nextReqId())
                        payload.put("si", p.si)
                        payload.put("accessToken", p.accessToken)
                        payload.put("code", digits)
                        // 与飞牛 Web 端一致：勾选「信任此设备」时 stay=2，服务端按 did 记住本机
                        payload.put("stay", 2)
                        payload.put("isTrustedDevice", true)
                        payload.put("deviceName", clientDeviceName())
                        payload.put("deviceType", DEVICE_TYPE)
                        payload.put("did", p.did)

                        val reply = wsRequest(p.ws, envelope(p.pub, payload))
                        val errno = reply.optInt("errno", 0)
                        val result = reply.optString("result")
                        if (errno != 0 || result != "succ") {
                            if (errno == ERRNO_INVALID_CODE) {
                                need2faJson("动态验证码错误或已过期，请重新输入")
                            } else {
                                // 会话已不可用：清掉挂起状态，允许用户重新登录
                                pending = null
                                runCatching { p.ws.close() }
                                throw FnOsException(loginErrorText(errno, result))
                            }
                        } else {
                            val newToken = reply.optString("token").trim()
                            if (newToken.isEmpty()) {
                                throw FnOsException("两步验证通过，但 NAS 没有返回 AccessToken")
                            }
                            backId = normalizeBackId(reply.optString("backId"))
                            token = newToken
                            secret = reply.optString("secret")
                            pending = null
                            runCatching { p.ws.close() }
                            outcomeJson(Outcome(true, false, newToken, secret, ""))
                        }
                    }
                }
            } catch (e: Throwable) {
                // 网络/解析异常后临时凭证不再可靠，清掉半登录会话
                val p = pending
                pending = null
                runCatching { p?.ws?.close() }
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    fun getToken(): String = token

    fun logout() {
        token = ""
        secret = ""
        backId = ZERO_BACK_ID
        val p = pending
        pending = null
        runCatching { p?.ws?.close() }
        FnOsHttp.clearCookies()
        // 媒体代理也要停：退出登录后不该再留着能代取 NAS 字节的本机服务
        FnOsMediaProxy.stop()
    }

    /**
     * 退出应用（首页 / 登录页的「退出」确认后调用）。
     *
     * 用 finishAffinity 关掉整个任务回到电视桌面：
     * uni-app x 里没有 plus.runtime.quit，也不去猜 `uni.exit` 之类的 API；
     * 拿不到 Activity（极早期的调用）时静默忽略，不影响页面继续用。
     */
    fun quitApp() {
        val activity = UTSAndroid.getUniActivity() ?: return
        runCatching { activity.finishAffinity() }
    }

    fun saveConfig(
        host: String,
        port: Number,
        useHttps: Boolean,
        username: String,
        password: String,
        accessCode: String
    ) {
        saveConfigInternal(normalizeHost(host), port.toInt(), useHttps, username.trim(), password, accessCode.trim())
    }

    fun loadConfig(): String {
        val obj = JSONObject()
        val p = prefs
        if (p == null || !p.getBoolean("saved", false)) {
            obj.put("saved", false)
            obj.put("host", "")
            obj.put("port", 5667)
            obj.put("https", false)
            obj.put("username", "")
            obj.put("password", "")
            obj.put("accessCode", "")
            return obj.toString()
        }
        obj.put("saved", true)
        obj.put("host", p.getString("host", "") ?: "")
        obj.put("port", p.getInt("port", 5667))
        obj.put("https", p.getBoolean("https", true))
        obj.put("username", p.getString("username", "") ?: "")
        obj.put("password", p.getString("password", "") ?: "")
        obj.put("accessCode", p.getString("accessCode", "") ?: "")
        return obj.toString()
    }

    /** 用已保存的账号做一次「token 有效性 + 相册统计」冒烟测试 */
    fun stat(callback: (String) -> Unit) {
        Thread {
            val json = try {
                synchronized(lock) {
                    var accessToken = ensureToken()
                    var reply = apiGet("/p/api/v1/user_photo/stat", accessToken)
                    if (reply.code == 401 || reply.code == 5001) {
                        // token 失效：清掉重新登录一次，再重试
                        token = ""
                        accessToken = ensureToken()
                        reply = apiGet("/p/api/v1/user_photo/stat", accessToken)
                    }
                    if (reply.code != 0) {
                        errorJson("业务请求失败（code " + reply.code + "）：" + reply.message)
                    } else {
                        val obj = JSONObject()
                        obj.put("ok", true)
                        obj.put("need2fa", pending != null)
                        obj.put("photoCount", reply.data.optInt("photoCount", 0))
                        obj.put("videoCount", reply.data.optInt("videoCount", 0))
                        obj.put("error", "")
                        obj.toString()
                    }
                }
            } catch (e: Throwable) {
                if (pending != null) need2faJson(describe(e)) else errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    // ------------------------------------------------------------------ 登录主流程

    private fun doLogin(
        host: String,
        port: Int,
        useHttps: Boolean,
        username: String,
        password: String,
        accessCode: String
    ): Outcome {
        currentHost = host
        currentPort = port
        httpsEnabled = useHttps
        currentAccessCode = accessCode

        // 上一次两步验证没走完：丢掉旧连接，避免 socket 泄漏
        val stale = pending
        pending = null
        runCatching { stale?.ws?.close() }

        if (accessCode.isNotEmpty()) {
            verifyAccessCode(host, port, accessCode, useHttps)
        }

        val ws = FnOsWebSocket.connect(
            host,
            port,
            "/websocket?type=main",
            tls.sslContext,
            tls.trust,
            TIMEOUT_MS,
            useHttps
        )
        var keepSocket = false
        try {
            backId = ZERO_BACK_ID
            val hello = wsRequest(
                ws,
                JSONObject().put("req", "util.crypto.getRSAPub").put("reqid", nextReqId())
            )
            val helloErrno = hello.optInt("errno", 0)
            if (helloErrno != 0) {
                throw FnOsException("登录握手被拒绝（errno " + helloErrno + "）")
            }
            // si 必须按字符串原样回传：按数字解析会丢精度，服务端回 errno 8192
            val pub = hello.optString("pub")
            val si = hello.optString("si")
            if (pub.isEmpty() || si.isEmpty()) {
                throw FnOsException("NAS 没有返回 RSA 公钥")
            }

            // 固定 did：服务端按它记住「信任此设备」，否则每次冷启动都要重输动态码
            val did = deviceId()
            val payload = JSONObject()
            payload.put("req", "user.login")
            payload.put("reqid", nextReqId())
            payload.put("si", si)
            payload.put("user", username)
            payload.put("password", password)
            payload.put("stay", true)
            payload.put("deviceType", DEVICE_TYPE)
            payload.put("deviceName", clientDeviceName())
            payload.put("did", did)

            val reply = wsRequest(ws, envelope(pub, payload))
            val errno = reply.optInt("errno", 0)
            val result = reply.optString("result")
            if (errno != 0 || result != "succ") {
                throw FnOsException(loginErrorText(errno, result))
            }

            backId = normalizeBackId(reply.optString("backId"))
            val newToken = reply.optString("token").trim()
            if (newToken.isNotEmpty()) {
                return Outcome(true, false, newToken, reply.optString("secret"), "")
            }

            // token 为空：可能是两步验证，也可能是协议字段变化
            val bound = flag(reply, "isBindTwofaSecret")
            val enforced = flag(reply, "isTwofaEnforced")
            val emailOnly = reply.optString("secureEmail").isNotEmpty()
            if (bound) {
                val tempToken = reply.optString("accessToken").trim()
                if (tempToken.isEmpty()) {
                    throw FnOsException("NAS 要求两步验证，但没有返回临时凭证")
                }
                // 保留 socket：动态码必须和这次登录在同一连接上提交
                pending = Pending2FA(ws, pub, si, tempToken, did)
                keepSocket = true
                return Outcome(false, true, "", "", "该账号已开启两步验证，请输入 6 位动态验证码")
            }
            if (enforced) {
                if (emailOnly) {
                    throw FnOsException("该账号使用邮箱两步验证，电视端收不到邮件：请先在飞牛 App 中改为验证器（动态码）")
                }
                throw FnOsException("该账号被要求开启两步验证，请先在飞牛 App 中绑定验证器")
            }
            throw FnOsException("登录成功但 NAS 没有返回 AccessToken")
        } finally {
            if (!keepSocket && pending == null) {
                runCatching { ws.close() }
            }
        }
    }

    /** 登录 / 两步验证共用的加密信封：AES-256-CBC + RSA 加密 AES key */
    private fun envelope(pub: String, payload: JSONObject): JSONObject {
        val aesKey = FnOsCrypto.randomAscii(32)
        val iv = FnOsCrypto.randomBytes(16)
        return JSONObject()
            .put("req", "encrypted")
            .put("iv", FnOsCrypto.b64(iv))
            .put("rsa", FnOsCrypto.b64(FnOsCrypto.rsaEncrypt(pub, aesKey.toByteArray(Charsets.UTF_8))))
            .put(
                "aes",
                FnOsCrypto.b64(
                    FnOsCrypto.aesCbcEncrypt(
                        payload.toString().toByteArray(Charsets.UTF_8), aesKey, iv
                    )
                )
            )
    }

    private fun wsRequest(ws: FnOsWebSocket, message: JSONObject): JSONObject {
        ws.sendText(message.toString())
        val text = ws.readText()
        return JSONObject(text)
    }

    // ------------------------------------------------------------------ HTTP 业务接口

    private fun ensureToken(): String {
        val existing = token
        if (existing.isNotEmpty()) {
            return existing
        }
        val cfg = readConfig() ?: throw FnOsException("尚未登录：请先填写服务器与账号")
        if (cfg.username.isEmpty()) {
            throw FnOsException("尚未登录：请先填写账号")
        }
        val outcome = doLogin(cfg.host, cfg.port, cfg.useHttps, cfg.username, cfg.password, cfg.accessCode)
        if (!outcome.ok) {
            throw FnOsException(outcome.error)
        }
        token = outcome.token
        secret = outcome.secret
        return outcome.token
    }

    private fun apiGet(
        path: String,
        accessToken: String,
        params: List<Pair<String, String>> = emptyList()
    ): ApiReply {
        var encoded = signEncoded
        var signAttempt = 0
        var accessRetried = false
        // AccessToken 失效（服务端在应用退出后作废）时只重新登录一次
        var tokenRefreshed = false
        var ak = accessToken

        while (true) {
            val payload = payloadOf(params, encoded)
            val query = payloadOf(params, true)
            val url = scheme() + "://" + currentHost + ":" + currentPort + path +
                (if (query.isEmpty()) "" else "?" + query)
            val headers = LinkedHashMap<String, String>()
            headers["AccessToken"] = ak
            headers["authx"] = authxHeader(path, payload)
            headers["User-Agent"] = UA

            val reply = FnOsHttp.request(url, headers, tls, httpsEnabled)

            if (reply.status == 401) {
                // ① 配了访问码的 NAS：401 多半是访问码 cookie 过期，重新校验一次
                if (currentAccessCode.isNotEmpty() && !accessRetried) {
                    accessRetried = true
                    verifyAccessCode(currentHost, currentPort, currentAccessCode, httpsEnabled)
                    continue
                }
                // ② AccessToken 失效：**必须清掉重登再重试**。
                //    服务端在应用退出后会把旧 AccessToken 作废，而它只保存在内存里，
                //    于是重新进应用后手里那条永远是死的 —— 以前这里直接抛异常，
                //    结果就是首页/图库一直显示「访问码已失效（HTTP 401）」，
                //    只能靠跳到登录页手动登录一次才恢复（2026-11-24 实测定位）。
                if (!tokenRefreshed) {
                    tokenRefreshed = true
                    ak = synchronized(lock) {
                        token = ""
                        ensureToken()
                    }
                    continue
                }
                throw FnOsException("访问码已失效或未通过校验（HTTP 401）", 401)
            }
            if (reply.status !in 200..299) {
                throw FnOsException("NAS 返回 HTTP " + reply.status, reply.status)
            }
            val json = try {
                JSONObject(reply.body)
            } catch (e: Throwable) {
                throw FnOsException("NAS 返回的不是 JSON")
            }
            val code = if (json.has("code")) json.optInt("code") else json.optInt("errno", -1)
            if (code == 5000 && signAttempt == 0) {
                // 参数哈希历史上有 RAW / ENCODED 两种规则，换一种再试
                signAttempt++
                encoded = !encoded
                signEncoded = encoded
                continue
            }
            if (code != 0) {
                val message = json.optString("msg").ifBlank { json.optString("result") }
                return ApiReply(code, message, JSONObject(), json)
            }
            val data = json.opt("data")
            return ApiReply(0, "", if (data is JSONObject) data else JSONObject(), json)
        }
    }

    /**
     * POST JSON 接口（`video/decode/play` 这类）。
     *
     * 签名规则与 GET 相同，只是 payload 换成请求体原文（fnOS 的 POST 接口惯例）；
     * 若服务端判定参数非法（code 5000），再按「排序后的 k=v」重试一次。
     */
    private fun apiPost(path: String, accessToken: String, body: String): ApiReply {
        val url = scheme() + "://" + currentHost + ":" + currentPort + path
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = UA
        var ak = accessToken
        headers["AccessToken"] = ak
        var payload = body
        var attempt = 0
        // 与 apiGet 一致：401 时清 token 重登一次再重试（见 apiGet 里的注释）
        var tokenRefreshed = false
        while (true) {
            headers["authx"] = authxHeader(path, payload)
            val reply = FnOsHttp.post(url, headers, body, tls, httpsEnabled)
            if (reply.status == 401) {
                if (!tokenRefreshed) {
                    tokenRefreshed = true
                    ak = synchronized(lock) {
                        token = ""
                        ensureToken()
                    }
                    headers["AccessToken"] = ak
                    continue
                }
                throw FnOsException("未授权（HTTP 401）", 401)
            }
            if (reply.status !in 200..299) {
                throw FnOsException("NAS 返回 HTTP " + reply.status, reply.status)
            }
            val json = try {
                JSONObject(reply.body)
            } catch (e: Throwable) {
                throw FnOsException("接口返回的不是 JSON")
            }
            val code = if (json.has("code")) json.optInt("code") else json.optInt("errno", -1)
            if (code == 5000 && attempt == 0 && body.isNotEmpty()) {
                attempt++
                payload = bodyPayload(body, true)
                continue
            }
            if (code != 0) {
                val message = json.optString("msg").ifBlank { json.optString("result") }
                return ApiReply(code, message, JSONObject(), json)
            }
            val data = json.opt("data")
            return ApiReply(0, "", if (data is JSONObject) data else JSONObject(), json)
        }
    }

    /** 把 JSON 请求体转成排序后的 `k=v&...` 签名串（code 5000 时的兜底尝试） */
    private fun bodyPayload(body: String, encoded: Boolean): String {
        val obj = try {
            JSONObject(body)
        } catch (e: Throwable) {
            return body
        }
        val keys = obj.keys().asSequence().toList().sorted()
        val sb = StringBuilder()
        for (k in keys) {
            if (sb.isNotEmpty()) {
                sb.append('&')
            }
            val raw = obj.opt(k)
            val v = if (raw == null || raw == JSONObject.NULL) "" else raw.toString()
            sb.append(k).append('=')
            sb.append(if (encoded) java.net.URLEncoder.encode(v, "UTF-8") else v)
        }
        return sb.toString()
    }

    /**
     * fnOS 的 authx 签名：
     *   sign = md5(SALT_path_nonce_timestamp_md5(参数串)_SECRET)
     * path 必须带 /p 前缀，timestamp 是毫秒（不是秒）。
     */
    private fun authxHeader(path: String, payload: String): String {
        val nonce = (100000 + random.nextInt(900000)).toString()
        val timestamp = System.currentTimeMillis().toString()
        val sign = FnOsCrypto.md5(
            listOf(
                SALT, path, nonce, timestamp, FnOsCrypto.md5(payload), SECRET
            ).joinToString("_")
        )
        return "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + sign
    }

    private fun payloadOf(params: List<Pair<String, String>>, encoded: Boolean): String {
        if (params.isEmpty()) {
            return ""
        }
        val sorted = params.sortedBy { it.first }
        val sb = StringBuilder()
        for (p in sorted) {
            if (sb.isNotEmpty()) {
                sb.append('&')
            }
            if (encoded) {
                sb.append(URLEncoder.encode(p.first, "UTF-8"))
                sb.append('=')
                sb.append(URLEncoder.encode(p.second, "UTF-8"))
            } else {
                sb.append(p.first).append('=').append(p.second)
            }
        }
        return sb.toString()
    }

    /** 访问码网关校验：成功时服务端下发 os-access-code cookie，由 [FnOsHttp] 保存 */
    private fun verifyAccessCode(host: String, port: Int, accessCode: String, useHttps: Boolean) {
        val encoded = FnOsCrypto.b64(accessCode.toByteArray(Charsets.UTF_8))
        val headers = LinkedHashMap<String, String>()
        headers["x-access-code"] = encoded
        headers["x-access-source"] = "web"
        headers["User-Agent"] = UA
        val base = (if (useHttps) "https" else "http") + "://" + host + ":" + port
        val reply = FnOsHttp.request(base + "/access_code_verify", headers, tls, useHttps)
        if (reply.status == 401) {
            throw FnOsException("访问码不正确，请在设置里重新填写", 401)
        }
        if (reply.status !in 200..299) {
            throw FnOsException("访问码校验失败（HTTP " + reply.status + "）", reply.status)
        }
    }

    // ------------------------------------------------------------------ 设备与配置

    private fun deviceId(): String {
        val p = prefs ?: return FnOsCrypto.randomHex(12)
        val stored = (p.getString("did", "") ?: "").trim()
        if (stored.length == 24) {
            return stored
        }
        val id = FnOsCrypto.randomHex(12)
        p.edit().putString("did", id).apply()
        return id
    }

    /**
     * deviceName 必须「<类型>-<客户端>」：NAS 设备管理按 `-` 拆两段展示，见 DEVICE_TYPE 注释。
     * 第一段是副行（小标题）：TV 客户端固定写 "TV"；第二段是标题（大标题）：NAS Photo TV。
     * 参考飞牛手机 App 的 `Android 16-Xiaomi 24031PN0DC`。
     */
    private fun clientDeviceName(): String = "TV-" + CLIENT_NAME

    private fun nextReqId(): String = FnOsCrypto.randomHex(4) + backId + FnOsCrypto.randomHex(2)

    private fun normalizeBackId(value: String): String =
        if (value.length == 16) value else ZERO_BACK_ID

    private fun normalizeHost(raw: String): String {
        var h = raw.trim()
        h = h.removePrefix("https://")
        h = h.removePrefix("http://")
        while (h.endsWith("/")) {
            h = h.substring(0, h.length - 1)
        }
        return h
    }

    private fun saveConfigInternal(
        host: String,
        port: Int,
        useHttps: Boolean,
        username: String,
        password: String,
        accessCode: String
    ) {
        val p = prefs ?: return
        p.edit()
            .putBoolean("saved", true)
            .putString("host", host)
            .putInt("port", port)
            .putBoolean("https", useHttps)
            .putString("username", username)
            .putString("password", password)
            .putString("accessCode", accessCode)
            .apply()
    }

    private fun readConfig(): Config? {
        val p = prefs ?: return null
        if (!p.getBoolean("saved", false)) {
            return null
        }
        return Config(
            normalizeHost(p.getString("host", "") ?: ""),
            p.getInt("port", 5667),
            p.getBoolean("https", true),
            p.getString("username", "") ?: "",
            p.getString("password", "") ?: "",
            p.getString("accessCode", "") ?: ""
        )
    }

    private fun scheme(): String = if (httpsEnabled) "https" else "http"

    /** 把服务端返回的相对路径拼成绝对 URL（缩略图、视频流都用它） */
    private fun urlFor(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path
        }
        return scheme() + "://" + currentHost + ":" + currentPort + "/" + path.trimStart('/')
    }

    // ------------------------------------------------------------------ 图库

    /**
     * 服务端的日级时间轴（`/p/api/v1/gallery/timeline`，实测返回
     * `{list:[{year,month,day,itemCount}]}`，倒序、约 700 条）。
     *
     * 这里把它压缩成「年份 → offset」：offset 为该年第一天在整个图库里的下标，
     * 右侧时间轴点击某一年时，直接用它跳页，不需要先翻过几万张照片。
     */
    fun timeline(callback: (String) -> Unit) {
        Thread {
            val json = try {
                synchronized(lock) {
                    var accessToken = ensureToken()
                    var reply = apiGet("/p/api/v1/gallery/timeline", accessToken)
                    if (reply.code == 401 || reply.code == 5001) {
                        token = ""
                        accessToken = ensureToken()
                        reply = apiGet("/p/api/v1/gallery/timeline", accessToken)
                    }
                    if (reply.code != 0) {
                        errorJson("时间轴获取失败（code " + reply.code + "）")
                    } else {
                        timelineJson(reply.data)
                    }
                }
            } catch (e: Throwable) {
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    private fun timelineJson(data: JSONObject): String {
        val list = data.optJSONArray("list") ?: JSONArray()
        val years = JSONArray()
        val months = JSONArray()
        var total = 0
        var lastYear = -1
        var lastMonthKey = ""
        for (i in 0 until list.length()) {
            val day = list.optJSONObject(i) ?: continue
            val count = day.optInt("itemCount", 0)
            if (count <= 0) {
                continue
            }
            val year = day.optInt("year", 0)
            if (year != lastYear) {
                lastYear = year
                val item = JSONObject()
                item.put("year", year)
                item.put("offset", total)
                years.put(item)
            }
            // 月份层级：同一年内按月份首次出现的位置记 offset，供时间轴展开用
            val month = day.optInt("month", 0)
            val monthKey = "$year-$month"
            if (monthKey != lastMonthKey) {
                lastMonthKey = monthKey
                val mi = JSONObject()
                mi.put("year", year)
                mi.put("month", month)
                mi.put("offset", total)
                months.put(mi)
            }
            total += count
        }
        val obj = JSONObject()
        obj.put("ok", true)
        obj.put("total", total)
        obj.put("years", years)
        obj.put("months", months)
        obj.put("error", "")
        return obj.toString()
    }

    /** 拉一页图库（时间倒序，offset/limit）；字段精简后回传，减少 UTS 侧解析量 */
    fun page(offset: Number, limit: Number, callback: (String) -> Unit) {
        val off = offset.toInt()
        val lim = limit.toInt()
        Thread {
            val json = try {
                synchronized(lock) {
                    var accessToken = ensureToken()
                    val params = listOf(
                        "start_time" to "1970:01:01 00:00:00",
                        "end_time" to "2099:12:31 23:59:59",
                        "mode" to "index",
                        "limit" to lim.toString(),
                        "offset" to off.toString()
                    )
                    var reply = apiGet("/p/api/v1/gallery/getList", accessToken, params)
                    if (reply.code == 401 || reply.code == 5001) {
                        token = ""
                        accessToken = ensureToken()
                        reply = apiGet("/p/api/v1/gallery/getList", accessToken, params)
                    }
                    if (reply.code != 0) {
                        errorJson("图库加载失败（code " + reply.code + "）")
                    } else {
                        pageJson(reply.data)
                    }
                }
            } catch (e: Throwable) {
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    /**
     * 「实况照片」标记。
     *
     * ⚠️ 2026-11-24 真机日志确认：`isLive` 是**枚举码，不是布尔**——
     * `0` = 普通，`2` = 实况（图库第二张就是 2）。以前写的是 `optInt("isLive") == 1`，
     * 所以网页上有实况角标、TV 端既没角标也不会自动播。
     *
     * 现在：数字/布尔/字符串都按「非 0 即实况」判，再补一条
     * `thumbnail.liveVideoUrl` 非空的兜底（个别固件可能只给这个）。
     */
    private fun liveFlag(raw: JSONObject, thumb: JSONObject?): Boolean {
        val v = raw.opt("isLive") ?: raw.optJSONObject("additional")?.opt("isLive")
        var code = 0
        if (v is Number) {
            code = v.toInt()
        } else if (v is Boolean) {
            code = if (v) 1 else 0
        } else if (v is String) {
            val t = v.trim()
            code = t.toIntOrNull() ?: (if (t.equals("true", true)) 1 else 0)
        }
        if (code > 0) {
            return true
        }
        return thumb != null && thumb.optString("liveVideoUrl").isNotEmpty()
    }

    private fun pageJson(data: JSONObject): String {
        val list = data.optJSONArray("list") ?: JSONArray()
        val items = JSONArray()
        for (i in 0 until list.length()) {
            val raw = list.optJSONObject(i) ?: continue
            val thumb = raw.optJSONObject("additional")?.optJSONObject("thumbnail")
            val item = JSONObject()
            item.put("id", raw.optLong("id", 0L).toString())
            item.put("dt", raw.optString("dateTime"))
            item.put("cat", raw.optString("category"))
            item.put("w", raw.optInt("width", 0))
            item.put("h", raw.optInt("height", 0))
            // 实况标记：`isLive` 是枚举码（实测 0=普通、2=实况），不是布尔，详见 liveFlag
            item.put("live", liveFlag(raw, thumb))
            // 实测：xxs=60x80(2KB) / xs=180x240(9KB) / s=240x320(15KB) / m=1280(257KB)
            // 网格支持 8/6/4 列切换，单格 179~371px，统一用 s(240x320) 保证清晰；查看器再用 m。
            item.put("thumb", thumb?.optString("sUrl") ?: "")
            item.put("medium", thumb?.optString("sUrl") ?: "")
            // 查看器原图：不同固件字段名不一致，按 originalUrl → lUrl → mUrl 退化
            item.put("original", pickImageUrl(thumb, "originalUrl", "lUrl", "mUrl"))
            item.put("name", raw.optString("fileName").ifEmpty { raw.optString("name") })
            // 视频文件 / 实况片段地址：thumbnail.videoUrl 为首选，不同固件还可能叫 liveVideoUrl，
            // 再不行退到条目级 videoUrl（字段名未在真实数据上全部确认，容错取第一个非空）
            item.put("video", videoUrlOf(raw, thumb))
            // 视频时长（秒）：网格角标显示用，照片恒为 0（见 mediaDurationOf）
            item.put("duration", mediaDurationOf(raw))
            items.put(item)
        }
        val obj = JSONObject()
        obj.put("ok", true)
        obj.put("items", items)
        obj.put("error", "")
        return obj.toString()
    }

    /**
     * 相册列表：`/p/api/v1/album/list?sort_by=album_name&sort_direction=desc&offset=0&limit=1000`。
     *
     * Web 端「相册」页用的就是它；返回结构与 `album/photo/belong` 基本一致
     * （albumName / photoCount / videoCount / shared / posterUpId + posterUUID 拼封面）。
     * 不同固件字段名略有出入，这里按候选顺序容错，避免整页空白。
     */
    fun albums(offset: Number, limit: Number, callback: (String) -> Unit) {
        val off = offset.toInt()
        val lim = limit.toInt()
        Thread {
            val json = try {
                synchronized(lock) {
                    var accessToken = ensureToken()
                    val params = listOf(
                        "sort_by" to "album_name",
                        "sort_direction" to "desc",
                        "offset" to off.toString(),
                        "limit" to lim.toString()
                    )
                    var reply = apiGet("/p/api/v1/album/list", accessToken, params)
                    if (reply.code == 401 || reply.code == 5001) {
                        token = ""
                        accessToken = ensureToken()
                        reply = apiGet("/p/api/v1/album/list", accessToken, params)
                    }
                    if (reply.code != 0) {
                        errorJson("相册列表加载失败（code " + reply.code + "）")
                    } else {
                        albumsJson(reply.data)
                    }
                }
            } catch (e: Throwable) {
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    private fun albumsJson(data: JSONObject): String {
        val list = data.optJSONArray("list") ?: JSONArray()
        val items = JSONArray()
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            val item = JSONObject()
            item.put("id", a.optLong("albumId", a.optLong("id", 0L)))
            item.put("name", firstText(a, "albumName", "name", "title"))
            val photoCount = a.optInt("photoCount", 0)
            val videoCount = a.optInt("videoCount", 0)
            val sum = photoCount + videoCount
            item.put("count", if (sum > 0) sum else a.optInt("count", a.optInt("itemCount", 0)))
            item.put("shared", albumSharedOf(a))
            item.put("cover", albumCoverOf(a))
            // 实测只有宝宝相册是 category=1（带 birthday），其余为 0
            item.put("category", a.optInt("category", 0))
            val start = firstText(a, "startDateTime", "endDateTime", "createTime")
            item.put("month", monthText(start))
            item.put("start", start)
            items.put(item)
        }
        val obj = JSONObject()
        obj.put("ok", true)
        obj.put("items", items)
        obj.put("total", data.optInt("total", items.length()))
        obj.put("error", "")
        return obj.toString()
    }

    /** 共享标记：不同固件可能是 shared=1 / isShared=1，也可能是布尔 */
    private fun albumSharedOf(a: JSONObject): Boolean {
        if (a.optBoolean("shared", false) || a.optBoolean("isShared", false)) {
            return true
        }
        return a.optInt("shared", 0) == 1 || a.optInt("isShared", 0) == 1
    }

    /** 相册封面：优先服务端直接给的 URL，其次 posterUpId + posterUUID 拼（与 albumsOf 同一规则） */
    private fun albumCoverOf(a: JSONObject): String {
        val direct = firstText(a, "cover", "coverUrl", "coverImg", "posterUrl", "thumbnailUrl")
        if (direct.isNotEmpty()) {
            return direct
        }
        val posterUUID = firstText(a, "posterUUID", "posterUuid")
        val posterUpId = a.optLong("posterUpId", 0L)
        if (posterUUID.isEmpty() || posterUpId <= 0L) {
            return ""
        }
        return "/p/api/v1/stream/p/t/" + posterUpId + "/xs/" + posterUUID
    }

    /**
     * 相册详情：`/p/api/v1/album/item?album_id=`（相册内页的头部信息）。
     * 返回结构与 `album/list` 的一项完全一致，复用同一套字段容错。
     */
    fun albumDetail(id: Number, callback: (String) -> Unit) {
        val albumId = id.toLong()
        Thread {
            val json = try {
                synchronized(lock) {
                    var accessToken = ensureToken()
                    val params = listOf("album_id" to albumId.toString())
                    var reply = apiGet("/p/api/v1/album/item", accessToken, params)
                    if (reply.code == 401 || reply.code == 5001) {
                        token = ""
                        accessToken = ensureToken()
                        reply = apiGet("/p/api/v1/album/item", accessToken, params)
                    }
                    if (reply.code != 0) {
                        errorJson("相册信息获取失败（code " + reply.code + "）")
                    } else {
                        val data = reply.data
                        val obj = JSONObject()
                        obj.put("ok", true)
                        obj.put("error", "")
                        obj.put("id", data.optLong("albumId", albumId))
                        obj.put("name", firstText(data, "albumName", "name", "title"))
                        obj.put("photoCount", data.optInt("photoCount", 0))
                        obj.put("videoCount", data.optInt("videoCount", 0))
                        obj.put("cover", albumCoverOf(data))
                        obj.put("start", firstText(data, "startDateTime"))
                        obj.put("end", firstText(data, "endDateTime"))
                        obj.put("shared", albumSharedOf(data))
                        obj.put("owner", firstText(data, "ownerName"))
                        obj.toString()
                    }
                }
            } catch (e: Throwable) {
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    /**
     * 相册内照片：`/p/api/v1/album/photos?album_id=&sort_by=date_time&sort_direction=&offset=&limit=`。
     *
     * 这是网页版相册内页真正用的接口（`album/normal/getList` 是另一套按天视图的接口，
     * 列表项**不带 `additional.thumbnail`**、也没有 `isLive`，实况标识就没了）。
     * 本接口的列表项与 `gallery/getList` 完全同构（含 `additional.thumbnail` /
     * `isLive` / `mediaDuration` / `photoUUID`），所以解析直接复用 `pageJson`。
     */
    fun albumPhotos(
        id: Number,
        offset: Number,
        limit: Number,
        asc: Boolean,
        callback: (String) -> Unit
    ) {
        val albumId = id.toLong()
        val off = offset.toInt()
        val lim = limit.toInt()
        val direction = if (asc) "asc" else "desc"
        Thread {
            val json = try {
                synchronized(lock) {
                    var accessToken = ensureToken()
                    val params = listOf(
                        "album_id" to albumId.toString(),
                        "sort_by" to "date_time",
                        "sort_direction" to direction,
                        "offset" to off.toString(),
                        "limit" to lim.toString()
                    )
                    var reply = apiGet("/p/api/v1/album/photos", accessToken, params)
                    if (reply.code == 401 || reply.code == 5001) {
                        token = ""
                        accessToken = ensureToken()
                        reply = apiGet("/p/api/v1/album/photos", accessToken, params)
                    }
                    if (reply.code != 0) {
                        errorJson("相册照片加载失败（code " + reply.code + "）")
                    } else {
                        pageJson(reply.data)
                    }
                }
            } catch (e: Throwable) {
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    /**
     * 当前登录用户：`GET /p/api/v1/user/info`。
     *
     * 设置页的「当前状态」用它验证 token 是否真的有效（只看 token 非空不可靠：
     * 隔夜 / NAS 改密码后 token 可能是失效串）。未登录或失效时 `ok=false`。
     * 返回 `{ok, name, isAdmin, id, error}`；`data` 形如
     * `{"id":1,"name":"xxx","isAdmin":true,"nasUid":1000}`。
     */
    fun userInfo(callback: (String) -> Unit) {
        Thread {
            val json = try {
                synchronized(lock) {
                    val accessToken = ensureToken()
                    val reply = apiGet("/p/api/v1/user/info", accessToken)
                    if (reply.code != 0) {
                        userInfoJson(
                            false, "", false, 0L,
                            reply.message.ifBlank { "token 已失效（code " + reply.code + "）" }
                        )
                    } else {
                        userInfoJson(
                            true,
                            reply.data.optString("name"),
                            reply.data.optBoolean("isAdmin", false),
                            reply.data.optLong("id", 0L),
                            ""
                        )
                    }
                }
            } catch (e: Throwable) {
                userInfoJson(false, "", false, 0L, describe(e))
            }
            post(callback, json)
        }.start()
    }

    private fun userInfoJson(ok: Boolean, name: String, isAdmin: Boolean, id: Long, error: String): String {
        val obj = JSONObject()
        obj.put("ok", ok)
        obj.put("name", name)
        obj.put("isAdmin", isAdmin)
        obj.put("id", id)
        obj.put("error", error)
        return obj.toString()
    }

    /**
     * 智能场景分类：`/p/api/v1/ai-smart-rec/categories?limit=-1&mainCategory=scene`。
     *
     * 首页大屏（`pages/home/home.uvue`）的数据源，对应网页版 `/p/smart_category` 页面。
     * 服务端返回 `{list:[{name, itemCount, poster:{photoId, uuid}}]}`（实测 54 项，
     * 按数量倒序，**没有 total**），海报流地址由 `poster` 拼：
     * `/p/api/v1/stream/p/t/{photoId}/{size}/{uuid}`。
     * 尺寸实测：xxs/xs/s/m 可用、`o` 是原图（3MB 别用）、`l` 是 404。
     */
    fun smartCategories(callback: (String) -> Unit) {
        Thread {
            val json = try {
                synchronized(lock) {
                    var accessToken = ensureToken()
                    val params = listOf("limit" to "-1", "mainCategory" to "scene")
                    var reply = apiGet("/p/api/v1/ai-smart-rec/categories", accessToken, params)
                    if (reply.code == 401 || reply.code == 5001) {
                        token = ""
                        accessToken = ensureToken()
                        reply = apiGet("/p/api/v1/ai-smart-rec/categories", accessToken, params)
                    }
                    if (reply.code != 0) {
                        errorJson("场景分类加载失败（code " + reply.code + "）")
                    } else {
                        smartCategoriesJson(reply.data)
                    }
                }
            } catch (e: Throwable) {
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    private fun smartCategoriesJson(data: JSONObject): String {
        val list = data.optJSONArray("list") ?: JSONArray()
        val items = JSONArray()
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val name = c.optString("name").trim()
            if (name.isEmpty()) {
                continue
            }
            val poster = c.optJSONObject("poster")
            val photoId = if (poster == null) 0L else poster.optLong("photoId", 0L)
            val uuid = if (poster == null) "" else poster.optString("uuid").trim()
            val item = JSONObject()
            item.put("name", name)
            // 老固件可能没有 itemCount，退到 count
            item.put("count", c.optInt("itemCount", c.optInt("count", 0)))
            if (photoId > 0L && uuid.isNotEmpty()) {
                val base = "/p/api/v1/stream/p/t/" + photoId + "/"
                item.put("cover", base + "m/" + uuid)
                item.put("thumb", base + "s/" + uuid)
            } else {
                item.put("cover", "")
                item.put("thumb", "")
            }
            items.put(item)
        }
        val obj = JSONObject()
        obj.put("ok", true)
        obj.put("items", items)
        obj.put("error", "")
        return obj.toString()
    }

    /**
     * AI 搜索：`POST /p/api/v1/magic-search/do`，请求体 `{"keyword":"海边","antiFilters":[]}`。
     *
     * 搜索结果的返回结构**没有**和其它列表接口对齐（可能直接给数组，也可能按场景 / 分组
     * 再包一层），所以解析走「先找到照片数组，再按图库字段容错；缺流地址时用
     * id + photoUUID 拼 `/p/api/v1/stream/p/t/{id}/{size}/{uuid}`」这一套，
     * 见 [searchJsonFrom] / [collectSearchPhotos] / [searchItem]。
     * 大屏搜索页（`pages/search/search.uvue`）用它。
     */
    fun search(keyword: String, callback: (String) -> Unit) {
        val kw = keyword.trim()
        if (kw.isEmpty()) {
            post(callback, searchJson(JSONArray(), 0))
            return
        }
        Thread {
            val json = try {
                synchronized(lock) {
                    var accessToken = ensureToken()
                    val body = JSONObject()
                        .put("keyword", kw)
                        .put("antiFilters", JSONArray())
                        .toString()
                    var reply = apiPost("/p/api/v1/magic-search/do", accessToken, body)
                    if (reply.code == 401 || reply.code == 5001) {
                        token = ""
                        accessToken = ensureToken()
                        reply = apiPost("/p/api/v1/magic-search/do", accessToken, body)
                    }
                    if (reply.code != 0) {
                        errorJson("搜索失败（code " + reply.code + "）" + reply.message)
                    } else {
                        searchJsonFrom(reply.root)
                    }
                }
            } catch (e: Throwable) {
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    private fun searchJsonFrom(root: JSONObject): String {
        val raw = root.opt("data")
        val found = JSONArray()
        var total = 0
        if (raw is JSONArray) {
            collectSearchPhotos(raw, found, 2)
        } else if (raw is JSONObject) {
            total = raw.optInt("total", 0)
            val arr = searchPhotoArray(raw)
            if (arr != null) {
                collectSearchPhotos(arr, found, 2)
            }
        }
        val items = JSONArray()
        for (i in 0 until found.length()) {
            val o = found.optJSONObject(i) ?: continue
            items.put(searchItem(o))
        }
        return searchJson(items, if (total > 0) total else items.length())
    }

    private fun searchJson(items: JSONArray, total: Int): String {
        val obj = JSONObject()
        obj.put("ok", true)
        obj.put("items", items)
        obj.put("total", total)
        obj.put("error", "")
        return obj.toString()
    }

    /** 一个对象是否像图库列表项：有 photoUUID，或 id + 时间 / 类型 / 缩略图字段 */
    private fun looksLikePhoto(o: JSONObject): Boolean {
        if (firstText(o, "photoUUID", "photoUuid").isNotEmpty()) {
            return true
        }
        val id = o.optLong("id", o.optLong("photoId", o.optLong("upId", 0L)))
        if (id <= 0L) {
            return false
        }
        return o.has("dateTime") || o.has("category") || o.has("additional") || o.has("isLive")
    }

    /** 在对象里找「第一个元素像照片」的数组：已知键优先，再兜底扫全部键 */
    private fun searchPhotoArray(o: JSONObject): JSONArray? {
        for (key in SEARCH_ARRAY_KEYS) {
            val arr = o.optJSONArray(key) ?: continue
            if (arr.length() == 0) {
                continue
            }
            val first = arr.optJSONObject(0)
            if (first != null && looksLikePhoto(first)) {
                return arr
            }
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val arr = o.optJSONArray(keys.next()) ?: continue
            if (arr.length() == 0) {
                continue
            }
            val first = arr.optJSONObject(0)
            if (first != null && looksLikePhoto(first)) {
                return arr
            }
        }
        return null
    }

    /** 把结果数组（可能按场景 / 分组再包一层）摊平成照片数组 */
    private fun collectSearchPhotos(arr: JSONArray, out: JSONArray, depth: Int) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (looksLikePhoto(o)) {
                out.put(o)
                continue
            }
            if (depth <= 0) {
                continue
            }
            val nested = searchPhotoArray(o) ?: continue
            collectSearchPhotos(nested, out, depth - 1)
        }
    }

    /**
     * 搜索结果的单项 → 与 [pageJson] 同构的精简项。
     *
     * 搜索结果可能不带 `additional.thumbnail`，此时用 `id + photoUUID` 拼流地址
     * （`/p/api/v1/stream/p/t/{id}/{size}/{uuid}`，实测 xxs/xs/s/m 可用）。
     * 视频 / 实况片段地址仍只认 `videoUrl` 字段，不自己猜流路径。
     */
    private fun searchItem(raw: JSONObject): JSONObject {
        val thumb = raw.optJSONObject("additional")?.optJSONObject("thumbnail")
            ?: raw.optJSONObject("thumbnail")
        val id = raw.optLong("id", raw.optLong("photoId", raw.optLong("upId", 0L)))
        val uuid = firstText(raw, "photoUUID", "photoUuid")
        // 网格缩略图只用小尺寸：s → xs → m，**不要**退到 originalUrl（原图几 MB，格子才 88rpx）
        var thumbPath = pickImageUrl(thumb, "sUrl", "xsUrl", "mUrl")
        var original = pickImageUrl(thumb, "originalUrl", "lUrl", "mUrl")
        if (id > 0L && uuid.isNotEmpty()) {
            val base = "/p/api/v1/stream/p/t/" + id + "/"
            if (thumbPath.isEmpty()) {
                thumbPath = base + "s/" + uuid
            }
            if (original.isEmpty()) {
                original = base + "m/" + uuid
            }
        }
        // 与 pageJson 同一套判定（isLive 是枚举码，见 liveFlag）
        val live = liveFlag(raw, thumb)
        val seconds = mediaDurationOf(raw)
        var cat = firstText(raw, "category", "photoCategory", "mediaType")
        if (cat.isEmpty()) {
            cat = if (!live && seconds > 0.0) "video" else "photo"
        }
        val item = JSONObject()
        item.put("id", id.toString())
        item.put("dt", firstText(raw, "dateTime", "photoDateTime", "createTime"))
        item.put("cat", if (cat == "video") "video" else "photo")
        item.put("w", raw.optInt("width", 0))
        item.put("h", raw.optInt("height", 0))
        item.put("live", live)
        item.put("thumb", thumbPath)
        item.put("medium", thumbPath)
        item.put("original", original)
        item.put("name", firstText(raw, "fileName", "name"))
        item.put("video", videoUrlOf(raw, thumb))
        item.put("duration", seconds)
        return item
    }

    /**
     * 照片详情：`/p/api/v1/gallery/getOne?id=`。
     *
     * `getList` 只给 id/时间/尺寸，文件大小、相机型号、曝光参数、存储路径、GPS
     * 这些详情字段只有 getOne 才有（Web 端「信息」面板就是拿这个接口）。
     */
    fun detail(id: String, callback: (String) -> Unit) {
        val photoId = id.trim()
        if (photoId.isEmpty()) {
            post(callback, errorJson("缺少照片 ID"))
            return
        }
        Thread {
            val json = try {
                synchronized(lock) {
                    var accessToken = ensureToken()
                    val params = listOf("id" to photoId)
                    var reply = apiGet("/p/api/v1/gallery/getOne", accessToken, params)
                    if (reply.code == 401 || reply.code == 5001) {
                        token = ""
                        accessToken = ensureToken()
                        reply = apiGet("/p/api/v1/gallery/getOne", accessToken, params)
                    }
                    if (reply.code != 0) {
                        errorJson("照片详情获取失败（code " + reply.code + "）")
                    } else {
                        // 相册 / 人物是附加信息，单独失败不影响详情本体
                        val albums = try {
                            albumsOf(photoId)
                        } catch (e: Throwable) {
                            JSONArray()
                        }
                        val persons = try {
                            personsOf(photoId)
                        } catch (e: Throwable) {
                            JSONArray()
                        }
                        detailJson(reply.data, albums, persons)
                    }
                }
            } catch (e: Throwable) {
                errorJson(describe(e))
            }
            post(callback, json)
        }.start()
    }

    private fun detailJson(data: JSONObject, albums: JSONArray, persons: JSONArray): String {
        val obj = JSONObject()
        obj.put("ok", true)
        obj.put("error", "")
        obj.put("fileName", data.optString("fileName"))
        obj.put("filePath", data.optString("filePath"))
        obj.put("fileSize", fileSizeText(data))
        obj.put("mp", data.optString("mp"))
        obj.put("width", data.optInt("width", 0))
        obj.put("height", data.optInt("height", 0))
        obj.put("dateTime", data.optString("photoDateTime").ifEmpty { data.optString("dateTime") })
        obj.put("camera", cameraText(data))
        obj.put("aperture", apertureText(data))
        obj.put("exposure", exposureText(data))
        obj.put("iso", isoText(data))
        obj.put("focal", focalText(data))
        // 实测：geo = "中国,广西...,涠洲岛"；latitude/longitude 是字符串
        obj.put("geo", firstText(data, "geo", "address"))
        obj.put("latitude", doubleField(data, "latitude"))
        obj.put("longitude", doubleField(data, "longitude"))
        obj.put("albums", albums)
        obj.put("persons", persons)
        obj.put("description", data.optString("description"))
        return obj.toString()
    }

    /** 所属相册：/p/api/v1/album/photo/belong?id= */
    private fun albumsOf(photoId: String): JSONArray {
        val reply = apiGet("/p/api/v1/album/photo/belong", ensureToken(), listOf("id" to photoId))
        if (reply.code != 0) {
            return JSONArray()
        }
        val list = reply.data.optJSONArray("list") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            val item = JSONObject()
            item.put("name", a.optString("albumName"))
            val start = a.optString("startDateTime").ifEmpty { a.optString("endDateTime") }
            item.put("month", monthText(start))
            item.put("count", a.optInt("photoCount", 0) + a.optInt("videoCount", 0))
            item.put("shared", a.optInt("shared", 0) == 1)
            // 相册封面：posterUpId 是照片 id，posterUUID 是流地址里的 uuid
            val posterUUID = a.optString("posterUUID")
            val posterUpId = a.optLong("posterUpId", 0L)
            val cover = if (posterUUID.isEmpty() || posterUpId <= 0L) {
                ""
            } else {
                "/p/api/v1/stream/p/t/" + posterUpId + "/xs/" + posterUUID
            }
            item.put("cover", cover)
            out.put(item)
        }
        return out
    }

    /**
     * 照片中的人物：/p/api/v1/ai-person/personInPhoto?id=
     *
     * 头像不能直接用 personInPhoto 的 faceId —— 那是「这张照片里的那张脸」，
     * 常是侧脸。Web 版人物头像用的是人物档案封面：`ai-person/list` 里该 person 的 faceId，
     * 所以这里先拉一次人物列表做映射（213 条左右，局域网很小），查不到再退回本图的 faceId。
     */
    private fun personsOf(photoId: String): JSONArray {
        val covers = try {
            personFaceCovers()
        } catch (e: Throwable) {
            JSONArray()
        }
        val reply = apiGet("/p/api/v1/ai-person/personInPhoto", ensureToken(), listOf("id" to photoId))
        if (reply.code != 0) {
            return JSONArray()
        }
        val list = reply.data.optJSONArray("list") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val person = list.optJSONObject(i) ?: continue
            val name = person.optString("name")
            if (name.isEmpty()) {
                continue
            }
            val personId = person.optLong("personId", 0L)
            var faceId = person.optLong("faceId", 0L)
            for (j in 0 until covers.length()) {
                val c = covers.optJSONObject(j) ?: continue
                if (c.optLong("personId", 0L) == personId) {
                    val cover = c.optLong("faceId", 0L)
                    if (cover > 0L) {
                        faceId = cover
                    }
                    break
                }
            }
            val item = JSONObject()
            item.put("name", name)
            // 头像：/p/api/v1/stream/face/{faceId}
            item.put("avatar", if (faceId <= 0L) "" else "/p/api/v1/stream/face/" + faceId)
            out.put(item)
        }
        return out
    }

    /** 人物 id → 档案封面 faceId（ai-person/list 的 id / faceId） */
    private fun personFaceCovers(): JSONArray {
        val params = listOf(
            "getAll" to "true",
            "limit" to "-1",
            "orderBy" to "0"
        )
        val reply = apiGet("/p/api/v1/ai-person/list", ensureToken(), params)
        if (reply.code != 0) {
            return JSONArray()
        }
        val list = reply.data.optJSONArray("list") ?: return JSONArray()
        val out = JSONArray()
        for (i in 0 until list.length()) {
            val p = list.optJSONObject(i) ?: continue
            val item = JSONObject()
            item.put("personId", p.optLong("id", 0L))
            item.put("faceId", p.optLong("faceId", 0L))
            out.put(item)
        }
        return out
    }

    /** "2023:11:17 20:26:24" → "2023年11月" */
    private fun monthText(dt: String): String {
        if (dt.length < 7) {
            return ""
        }
        val year = dt.substring(0, 4)
        val month = dt.substring(5, 7).toIntOrNull() ?: return ""
        return year + "年" + month.toString() + "月"
    }

    /** 经纬度在 getOne 里是字符串，这里统一转 number */
    private fun doubleField(data: JSONObject, key: String): Double {
        if (!data.has(key) || data.isNull(key)) {
            return 0.0
        }
        val raw = data.opt(key)
        if (raw is Number) {
            return raw.toDouble()
        }
        if (raw is String) {
            return raw.toDoubleOrNull() ?: 0.0
        }
        return 0.0
    }

    /** 取第一个存在且非空的字段（org.json 的 opt 对 JSON null 会返回 NULL 对象） */
    private fun firstText(data: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!data.has(key) || data.isNull(key)) {
                continue
            }
            val s = data.opt(key)?.toString() ?: ""
            if (s.isNotEmpty()) {
                return s
            }
        }
        return ""
    }

    /** fileSize 可能是字节数（Number），也可能已经是 "2.03 MB" 这种字符串 */
    private fun fileSizeText(data: JSONObject): String {
        if (!data.has("fileSize") || data.isNull("fileSize")) {
            return ""
        }
        val raw = data.opt("fileSize")
        if (raw is Number) {
            val bytes = raw.toDouble()
            if (bytes <= 0.0) {
                return ""
            }
            if (bytes >= 1048576.0) {
                return "%.2f MB".format(bytes / 1048576.0)
            }
            if (bytes >= 1024.0) {
                return "%.0f KB".format(bytes / 1024.0)
            }
            return bytes.toLong().toString() + " B"
        }
        return raw?.toString() ?: ""
    }

    /** "Apple" + "iPhone 14" → "Apple iPhone 14"；model 已带品牌时不重复 */
    private fun cameraText(data: JSONObject): String {
        val make = firstText(data, "make")
        val model = firstText(data, "model")
        if (model.isEmpty()) {
            return make
        }
        if (make.isEmpty() || model.startsWith(make, ignoreCase = true)) {
            return model
        }
        return make + " " + model
    }

    /** 光圈：服务端可能给 1.5 / "1.5" / "f/1.5" */
    private fun apertureText(data: JSONObject): String {
        val s = firstText(data, "fNumber", "fumber", "aperture")
        if (s.isEmpty()) {
            return ""
        }
        return if (s.startsWith("f", ignoreCase = true)) s else "f/" + s
    }

    /** 快门：数值（秒）时尽量还原成 1/xxx s 的写法 */
    private fun exposureText(data: JSONObject): String {
        if (!data.has("exposureTime") || data.isNull("exposureTime")) {
            return ""
        }
        val raw = data.opt("exposureTime")
        if (raw is Number) {
            val v = raw.toDouble()
            if (v > 0.0 && v < 1.0) {
                return "1/" + Math.round(1.0 / v).toString() + "s"
            }
            return trimNumber(v) + "s"
        }
        val s = raw?.toString() ?: ""
        if (s.isEmpty()) {
            return ""
        }
        return if (s.endsWith("s")) s else s + "s"
    }

    private fun isoText(data: JSONObject): String {
        val s = firstText(data, "isoSpeedRatings", "iso", "ISO")
        if (s.isEmpty()) {
            return ""
        }
        return if (s.startsWith("ISO", ignoreCase = true)) s else "ISO" + s
    }

    private fun focalText(data: JSONObject): String {
        val s = firstText(data, "focalLength")
        if (s.isEmpty()) {
            return ""
        }
        return if (s.endsWith("mm")) s else s + "mm"
    }

    private fun trimNumber(v: Double): String {
        return if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    }

    /** thumbnail 里按优先级取第一个非空 URL（原图字段在不同固件里叫法不同） */
    private fun pickImageUrl(thumb: JSONObject?, vararg keys: String): String {
        if (thumb == null) {
            return ""
        }
        for (key in keys) {
            val url = thumb.optString(key, "")
            if (url.isNotEmpty()) {
                return url
            }
        }
        return ""
    }

    /**
     * 视频 / 实况片段地址。
     *
     * 首选 `additional.thumbnail.videoUrl`（实况照片实测可用）；部分固件写在
     * `liveVideoUrl`，或者提到条目层。这里按序取第一个非空，取不到就回空串，
     * 由页面显示「该视频没有可播放地址」，不要自己拼流地址（协议没确认过）。
     */
    private fun videoUrlOf(raw: JSONObject, thumb: JSONObject?): String {
        val fromThumb = pickImageUrl(thumb, "videoUrl", "liveVideoUrl")
        if (fromThumb.isNotEmpty()) {
            return fromThumb
        }
        return raw.optString("videoUrl", "")
    }

    /**
     * 视频时长（**秒**，照片为 0.0）。
     *
     * fnOS 的相册列表项（web 端 Photos 模型）字段名是 `mediaDuration`，单位秒
     * （web 端用 dayjs.duration(mediaDuration, 'seconds') 格式化后画在缩略图角标上）。
     * 老固件可能叫 `duration` / `videoDuration`，按序取第一个 > 0 的值；
     * 万一哪版给的是毫秒（> 24 小时的值），按秒看待没有意义，除以 1000 兜底。
     */
    private fun mediaDurationOf(raw: JSONObject): Double {
        val keys = arrayOf("mediaDuration", "duration", "videoDuration")
        for (key in keys) {
            if (!raw.has(key) || raw.isNull(key)) {
                continue
            }
            val v = raw.optDouble(key, 0.0)
            if (v > 0.0) {
                return if (v > 86400.0) v / 1000.0 else v
            }
        }
        return 0.0
    }

    /**
     * 下载缩略图到应用缓存目录，回传本地路径。
     *
     * 为什么必须落到本地：fnOS 的图片流（`/p/api/v1/stream/...`）只认
     * `AccessToken` 头，实测把 token 放 query 会 401；而 uvue 的 <image> 无法加请求头。
     */
    fun thumb(path: String, callback: (String) -> Unit) {
        if (path.isEmpty()) {
            post(callback, thumbJson("", "empty path"))
            return
        }
        val cached = cachedThumb(path)
        if (cached != null) {
            post(callback, thumbJson(cached, ""))
            return
        }
        thumbPool.execute {
            val json = try {
                thumbJson(downloadThumb(path), "")
            } catch (e: Throwable) {
                thumbJson("", describe(e))
            }
            post(callback, json)
        }
    }

    /**
     * 通用图片下载（查看器用）：鉴权、磁盘缓存、线程池与 thumb 完全一致，
     * 只是缓存文件保留原图扩展名（原图可能是 png / heic，写死 .jpg 有解码风险）。
     * 缓存 key = md5(path)，而 sUrl / originalUrl 的路径不同，天然按尺寸区分。
     */
    fun image(path: String, callback: (String) -> Unit) {
        if (path.isEmpty()) {
            post(callback, thumbJson("", "empty path"))
            return
        }
        val cached = cachedImage(path)
        if (cached != null) {
            post(callback, thumbJson(cached, ""))
            return
        }
        thumbPool.execute {
            val json = try {
                thumbJson(downloadImage(path), "")
            } catch (e: Throwable) {
                thumbJson("", describe(e))
            }
            post(callback, json)
        }
    }

    /**
     * 实况照片的片段 / 视频文件的流下载（查看器播实况与视频都用它）。
     *
     * 为什么也要落盘：与图片同一个原因 —— 流地址只认 `AccessToken` 头，
     * 而 uvue 的 <video> 在 VDOM 模式下拿不到请求头（header 属性是 Vapor 接口），
     * 下载成本地文件最稳，也顺便复用了「同一 path 只下一次」的缓存。
     *
     * 实况片段只有 1~3 秒（几十 KB ~ 几 MB），下载几乎无感；
     * 视频文件可能是几百 MB，所以走 [downloadVideo] 的流式写盘，并且页面会显示
     * 「正在准备视频…」的等待态。想边下边播需要在插件里加本地回环代理，见 AGENTS 5.11。
     */
    fun video(path: String, callback: (String) -> Unit) {
        if (path.isEmpty()) {
            post(callback, thumbJson("", "empty path"))
            return
        }
        val cached = cachedVideo(path)
        if (cached != null) {
            post(callback, thumbJson(cached, ""))
            return
        }
        thumbPool.execute {
            val json = try {
                thumbJson(downloadVideo(path), "")
            } catch (e: Throwable) {
                thumbJson("", describe(e))
            }
            post(callback, json)
        }
    }

    /**
     * 把接口里的相对路径拼成 NAS 绝对地址（**不带鉴权**，直接给播放器是 401 的）。
     *
     * 2026-11-15 实测：`<video :header>` 在 uni-app x 真机上并没有把 header 交给 ExoPlayer
     * （直链 MP4 与 HLS 都报 `HttpDataSource$InvalidResponseCodeException: 401`），
     * 所以播放地址请用 [mediaUrl]（走本机回环代理）；`remoteUrl` 只用于兜底下载等场景。
     */
    fun remoteUrl(path: String): String {
        return if (path.isEmpty()) "" else urlFor(path)
    }

    /**
     * 给 `<video>` 用的**本机代理地址**：`http://127.0.0.1:<port>/v/<base64url(真实地址)>`。
     *
     * 为什么绕：NAS 媒体流只认 `AccessToken` 头（query / cookie 实测都是 401），而播放器发不出
     * 这个头。于是由进程内的 [FnOsMediaProxy] 去 NAS 取字节（自己补 header、透传 `Range`），
     * 播放器只跟本机回环打交道 —— 既能播、也能拖进度。HLS 的 m3u8 与分片同样走它。
     *
     * 代理起不来时退回直链（多半播不了，会落到「下载到本地再播」的兜底）。
     */
    fun mediaUrl(path: String): String {
        if (path.isEmpty()) {
            return ""
        }
        val absolute = urlFor(path)
        val port = FnOsMediaProxy.start { target, range -> openAuthed(target, range) }
        if (port <= 0) {
            return absolute
        }
        return FnOsMediaProxy.wrap(absolute)
    }

    /** 媒体代理专用：带 `AccessToken`（+ `Range`）的原始连接，由代理自己流式转发 */
    private fun openAuthed(target: String, range: String?): HttpURLConnection {
        val headers = LinkedHashMap<String, String>()
        val accessToken = synchronized(lock) { ensureToken() }
        if (accessToken.isNotEmpty()) {
            headers["AccessToken"] = accessToken
        }
        headers["User-Agent"] = UA
        if (range != null && range.isNotEmpty()) {
            headers["Range"] = range
        }
        return FnOsHttp.open(target, headers, tls, httpsEnabled, 8000, 30000)
    }

    /**
     * 视频清晰度列表：`GET /p/api/v1/video/decode/info?id=`。
     *
     * 抄自 fnOS web 端相册播放器（`useGetDecodeInfoQuery`）：`data.quality[]` 每项是
     * `{resolution, bitrate, progressive}`，**原始画质那一项没有 bitrate**，
     * web 端也正因为如此只把有 bitrate 的转码档放进「清晰度」菜单。
     */
    fun videoInfo(id: String, callback: (String) -> Unit) {
        Thread {
            val json = try {
                val accessToken = synchronized(lock) { ensureToken() }
                val reply = apiGet("/p/api/v1/video/decode/info", accessToken, listOf("id" to id))
                if (reply.code != 0) {
                    JSONObject().put("qualities", JSONArray())
                        .put("error", reply.message.ifBlank { "清晰度接口返回 code " + reply.code })
                } else {
                    val arr = reply.data.optJSONArray("quality") ?: JSONArray()
                    val out = JSONArray()
                    for (i in 0 until arr.length()) {
                        val q = arr.optJSONObject(i) ?: continue
                        val item = JSONObject()
                        val res = q.opt("resolution")
                        item.put("resolution", if (res == null || res == JSONObject.NULL) "" else res.toString())
                        item.put("bitrate", q.optLong("bitrate", 0L))
                        item.put("progressive", q.optBoolean("progressive", false))
                        out.put(item)
                    }
                    // 源视频的编码宽高与旋转角：`data.videoStreamInfo[0]`。
                    // 竖屏手机视频实测是 `width=3840 height=2160 rotation=-90`（帧是横的 + 旋转标记），
                    // 而 NAS 的转码流（HLS/TS）不带旋转信息 → 竖屏会被转成躺倒的横向画面，
                    // 只能靠客户端补旋转（见 PhotoViewer 的 vw-video 样式）。
                    var srcW = 0
                    var srcH = 0
                    var rotation = 0
                    val streams = reply.data.optJSONArray("videoStreamInfo")
                    if (streams != null && streams.length() > 0) {
                        val vs = streams.optJSONObject(0)
                        if (vs != null) {
                            srcW = vs.optInt("width", 0)
                            srcH = vs.optInt("height", 0)
                            rotation = vs.optInt("rotation", 0)
                        }
                    }
                    JSONObject().put("qualities", out).put("error", "")
                        .put("sourceWidth", srcW).put("sourceHeight", srcH).put("rotation", rotation)
                }
            } catch (e: Throwable) {
                JSONObject().put("qualities", JSONArray()).put("error", describe(e))
            }
            post(callback, json.toString())
        }.start()
    }

    /**
     * 申请一路转码流：`POST /p/api/v1/video/decode/play`。
     *
     * 参数照抄 web 端：H.264 + AAC 双声道（TV 盒子兼容性最好，HEVC 10bit 很多盒子放不了）。
     * 返回的 `data.playLink` 是一段查询串，拼到 `/p/api/v1/video/decode/stream` 后面
     * 就是 HLS（m3u8）地址。
     *
     * `startPosition`（秒）只在 > 0 时才放进请求体：T4.8 的 HEVC 兜底用它从当前进度续播。
     * **这个字段没在 NAS 前端 bundle 里确认过**，所以填 0 时一律不发，
     * 保证旧的「清晰度三档」链路请求体与实测一致（多一个未知字段就可能 code 5000）。
     * 验证方式：`node tools/fnos-video.mjs` 会单独打一次带 startPosition 的请求。
     */
    fun videoPlay(id: String, bitrate: Number, resolution: String, startPosition: Number, callback: (String) -> Unit) {
        Thread {
            val json = try {
                val accessToken = synchronized(lock) { ensureToken() }
                val params = JSONObject()
                    .put("id", id.toLongOrNull() ?: 0L)
                    .put("bitrate", bitrate.toLong())
                    .put("resolution", resolution)
                    .put("audioIndex", -2)
                    .put("audioEncoder", "aac")
                    .put("videoEncoder", "h264")
                    .put("channels", 2)
                if (startPosition.toDouble() > 0.0) {
                    params.put("startPosition", startPosition.toDouble())
                }
                val body = params.toString()
                val reply = apiPost("/p/api/v1/video/decode/play", accessToken, body)
                val link = reply.data.optString("playLink")
                if (link.isEmpty()) {
                    JSONObject().put("path", "")
                        .put("error", reply.message.ifBlank { "NAS 没有返回播放地址" })
                } else {
                    JSONObject().put("path", "/p/api/v1/video/decode/stream" + link).put("error", "")
                }
            } catch (e: Throwable) {
                JSONObject().put("path", "").put("error", describe(e))
            }
            post(callback, json.toString())
        }.start()
    }

    private fun thumbJson(path: String, error: String): String {
        val obj = JSONObject()
        obj.put("path", path)
        obj.put("error", error)
        return obj.toString()
    }

    private fun cacheFile(path: String, ext: String): File? {
        val dir = thumbDir ?: return null
        return File(dir, FnOsCrypto.md5(path) + "." + ext)
    }

    private fun thumbFile(path: String): File? = cacheFile(path, "jpg")

    /**
     * 从 URL 里取扩展名。没有扩展名时退回 jpg：BitmapFactory 按文件头解码，
     * 这样也能直接复用 thumb 已经下好的同一路径缓存。
     */
    private fun imageExt(path: String): String {
        val clean = path.substringBefore('?')
        val slash = clean.lastIndexOf('/')
        val name = if (slash >= 0) clean.substring(slash + 1) else clean
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) {
            return "jpg"
        }
        val ext = name.substring(dot + 1).lowercase()
        return if (ext.length in 1..5 && ext.all { it.isLetterOrDigit() }) ext else "jpg"
    }

    private fun imageFile(path: String): File? = cacheFile(path, imageExt(path))

    /** 视频统一存成 .mp4：实况片段是 H.264/AAC 的 mp4，扩展名只影响个别播放器嗅探 */
    private fun videoFile(path: String): File? = cacheFile(path, "mp4")

    private fun cachedThumb(path: String): String? {
        val file = thumbFile(path) ?: return null
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    private fun cachedImage(path: String): String? {
        val file = imageFile(path) ?: return null
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    private fun cachedVideo(path: String): String? {
        val file = videoFile(path) ?: return null
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    private fun downloadThumb(path: String): String {
        val cached = cachedThumb(path)
        if (cached != null) {
            return cached
        }
        val file = thumbFile(path) ?: throw FnOsException("缓存目录不可用")
        return downloadTo(path, file)
    }

    private fun downloadImage(path: String): String {
        val cached = cachedImage(path)
        if (cached != null) {
            return cached
        }
        val file = imageFile(path) ?: throw FnOsException("缓存目录不可用")
        return downloadTo(path, file)
    }

    /**
     * 视频下载：**流式写盘**（不能用 [downloadTo]，那条路会把整个响应读进内存）。
     *
     * 先写 `.part` 临时文件，成功后改名：中途失败 / 进程被杀都不会留下半截文件，
     * 否则下次 `cachedVideo()` 会把它当命中缓存，播出来是坏的。
     */
    private fun downloadVideo(path: String): String {
        val cached = cachedVideo(path)
        if (cached != null) {
            return cached
        }
        val file = videoFile(path) ?: throw FnOsException("缓存目录不可用")
        val part = File(file.parentFile, file.name + ".part")
        try {
            Log.i(TAG, "video download start " + path)
            downloadVideoTo(path, part)
            Log.i(TAG, "video download done " + (part.length() / 1024) + "KB -> " + file.name)
            if (!part.renameTo(file)) {
                // 个别文件系统不支持原地改名：流式复制一次再删临时文件（不要 readBytes，会占内存）
                part.copyTo(file, overwrite = true)
                part.delete()
            }
            return file.absolutePath
        } catch (e: Throwable) {
            runCatching { if (part.exists()) part.delete() }
            throw e
        }
    }

    /** 视频流落盘；token 过期（401/403）时重登一次再试 */
    private fun downloadVideoTo(path: String, file: File) {
        var accessToken = synchronized(lock) { ensureToken() }
        val headers = LinkedHashMap<String, String>()
        headers["AccessToken"] = accessToken
        headers["User-Agent"] = UA
        try {
            FnOsHttp.download(urlFor(path), headers, tls, httpsEnabled, file)
        } catch (e: FnOsException) {
            if (e.httpStatus == 401 || e.httpStatus == 403) {
                accessToken = synchronized(lock) {
                    token = ""
                    ensureToken()
                }
                headers["AccessToken"] = accessToken
                FnOsHttp.download(urlFor(path), headers, tls, httpsEnabled, file)
            } else {
                throw e
            }
        }
    }

    /** 带 AccessToken 取图并落盘；token 过期（401/403）时重登一次再试 */
    private fun downloadTo(path: String, file: File): String {
        // 只在取 token 时短暂持锁，下载过程不占锁，否则 4 个线程会退化成串行
        var accessToken = synchronized(lock) { ensureToken() }
        val headers = LinkedHashMap<String, String>()
        headers["AccessToken"] = accessToken
        headers["User-Agent"] = UA
        val bytes = try {
            FnOsHttp.bytes(urlFor(path), headers, tls, httpsEnabled)
        } catch (e: FnOsException) {
            if (e.httpStatus == 401 || e.httpStatus == 403) {
                accessToken = synchronized(lock) {
                    token = ""
                    ensureToken()
                }
                headers["AccessToken"] = accessToken
                FnOsHttp.bytes(urlFor(path), headers, tls, httpsEnabled)
            } else {
                throw e
            }
        }
        file.writeBytes(bytes)
        return file.absolutePath
    }

    // ------------------------------------------------------------------ 文案与工具

    private fun loginErrorText(errno: Int, result: String): String {
        return when (errno) {
            401 -> "会话被拒绝：可能需要访问码，或上一次登录状态没有重置"
            8192 -> "登录参数被拒绝（si 必须以字符串原样回传）"
            131072 -> "账号或密码错误"
            131089 -> "登录失败次数过多，NAS 已临时封禁本机 IP，请稍后再试"
            135168 -> "动态验证码错误或已过期"
            else -> if (result.isNotEmpty() && result != "succ") {
                "登录失败：" + result
            } else {
                "登录失败（errno " + errno + "）"
            }
        }
    }

    /** 服务端的布尔字段并不总是 JSON 布尔：不同版本会回 true / "true" / 1 */
    private fun flag(json: JSONObject, name: String): Boolean {
        val raw = json.opt(name) ?: return false
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toInt() != 0
            is String -> raw.equals("true", ignoreCase = true) || raw == "1"
            else -> false
        }
    }

    private fun describe(e: Throwable): String {
        if (e is FnOsException) {
            return e.message ?: "连接失败"
        }
        if (e is java.net.UnknownHostException) {
            return "找不到主机：请检查服务器地址"
        }
        if (e is java.net.ConnectException) {
            return "无法连接：确认端口是飞牛的 HTTPS 端口（默认 5667）"
        }
        if (e is java.net.SocketTimeoutException) {
            return "连接超时：检查地址、端口与局域网连通性"
        }
        if (e is javax.net.ssl.SSLException) {
            return if (tls.trust.pinMismatch) {
                "NAS 证书指纹已变化，已清除旧指纹，请再试一次"
            } else {
                "TLS 握手失败：" + (e.message ?: "自签证书")
            }
        }
        if (e is java.io.IOException) {
            return "网络错误：" + (e.message ?: e.javaClass.simpleName)
        }
        if (e is org.json.JSONException) {
            return "NAS 返回的数据无法解析"
        }
        return e.message ?: e.javaClass.simpleName
    }

    private fun post(callback: (String) -> Unit, json: String) {
        mainHandler.post(Runnable { callback(json) })
    }

    private fun outcomeJson(o: Outcome): String {
        val obj = JSONObject()
        obj.put("ok", o.ok)
        obj.put("need2fa", o.need2fa)
        obj.put("token", o.token)
        obj.put("secret", o.secret)
        obj.put("error", o.error)
        return obj.toString()
    }

    private fun errorJson(message: String): String {
        val obj = JSONObject()
        obj.put("ok", false)
        obj.put("need2fa", false)
        obj.put("token", "")
        obj.put("secret", "")
        obj.put("error", message)
        return obj.toString()
    }

    private fun need2faJson(message: String): String {
        val obj = JSONObject()
        obj.put("ok", false)
        obj.put("need2fa", true)
        obj.put("token", "")
        obj.put("secret", "")
        obj.put("error", message)
        return obj.toString()
    }

    // ------------------------------------------------------------------ 内部类型

    private class Outcome(
        val ok: Boolean,
        val need2fa: Boolean,
        val token: String,
        val secret: String,
        val error: String
    )

    /**
     * 接口回包。
     *
     * `data` 只在服务端返回对象时有值；`root` 是完整响应体（搜索接口的 `data`
     * 可能是数组或带分组的对象，解析时要看原文）。
     */
    private class ApiReply(
        val code: Int,
        val message: String,
        val data: JSONObject,
        val root: JSONObject = JSONObject()
    )

    private class Pending2FA(
        val ws: FnOsWebSocket,
        val pub: String,
        val si: String,
        val accessToken: String,
        val did: String
    )

    private class Config(
        val host: String,
        val port: Int,
        val useHttps: Boolean,
        val username: String,
        val password: String,
        val accessCode: String
    )
}

package uts.sdk.modules.fnosAuth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/**
 * fnOS 的 HTTP 调用层（不引入 okhttp，减少插件依赖）。
 *
 * 同时支持两种部署：
 *   - HTTPS + 自签证书（默认端口 5667）→ 走 [FnOsTls] 的 TOFU 指纹信任；
 *   - 纯 HTTP（默认端口 5666）→ 不做 TLS 处理，适合只在内网使用的电视端。
 *
 * 还负责维护极简 cookie 表：开启「访问码」的 NAS 会在 /access_code_verify 下发
 * os-access-code（HttpOnly），它是网关级凭证，后续每个请求都要带上。
 */
object FnOsHttp {

    class Reply(val status: Int, val body: String)

    private val cookies = ConcurrentHashMap<String, MutableMap<String, String>>()

    fun request(
        url: String,
        headers: Map<String, String>,
        tls: FnOsTls,
        useHttps: Boolean,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 20000
    ): Reply {
        val conn = open(url, headers, tls, useHttps, connectTimeoutMs, readTimeoutMs)
        try {
            val status = conn.responseCode
            val body = try {
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                stream?.let { it.bufferedReader(Charsets.UTF_8).readText() } ?: ""
            } catch (e: IOException) {
                ""
            }
            storeCookies(URL(url).host, conn)
            return Reply(status, body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST JSON 接口（`video/decode/play` 这类）。
     *
     * 与 [request] 共用连接 / 证书 / cookie 逻辑，只是补上请求体。
     * 注意 `open()` 里默认把 method 设成 GET，这里在真正连接前改成 POST。
     */
    fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        tls: FnOsTls,
        useHttps: Boolean,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 20000
    ): Reply {
        val conn = open(url, headers, tls, useHttps, connectTimeoutMs, readTimeoutMs)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val text = try {
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                stream?.let { it.bufferedReader(Charsets.UTF_8).readText() } ?: ""
            } catch (e: IOException) {
                ""
            }
            storeCookies(URL(url).host, conn)
            return Reply(status, text)
        } finally {
            conn.disconnect()
        }
    }

    /** 图片等二进制资源：带 AccessToken 头直接下载到内存 */
    fun bytes(
        url: String,
        headers: Map<String, String>,
        tls: FnOsTls,
        useHttps: Boolean,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 30000
    ): ByteArray {
        val conn = open(url, headers, tls, useHttps, connectTimeoutMs, readTimeoutMs)
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                throw FnOsException("图片下载失败（HTTP " + status + "）", status)
            }
            val input = conn.inputStream
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                out.write(buffer, 0, read)
            }
            input.close()
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 视频等大文件：**流式写盘**，不占内存。
     *
     * [bytes] 会把整个响应读进 `ByteArray` —— 图片几 MB 没问题，但几百 MB 的视频必然 OOM，
     * 所以视频单独走这里。HTTP 非 2xx 抛 [FnOsException]（带状态码，401/403 由调用方重登后重试）。
     * 返回实际写入的字节数。
     */
    fun download(
        url: String,
        headers: Map<String, String>,
        tls: FnOsTls,
        useHttps: Boolean,
        file: java.io.File,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 30000
    ): Long {
        val conn = open(url, headers, tls, useHttps, connectTimeoutMs, readTimeoutMs)
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                throw FnOsException("视频下载失败（HTTP " + status + "）", status)
            }
            val input = conn.inputStream
            val out = java.io.FileOutputStream(file)
            try {
                val buffer = ByteArray(65536)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    out.write(buffer, 0, read)
                    total += read
                }
                out.flush()
                return total
            } finally {
                input.close()
                out.close()
            }
        } finally {
            conn.disconnect()
        }
    }

    fun clearCookies() {
        cookies.clear()
    }

    /**
     * 建一个**还没连接**（也还没读 body）的连接，鉴权头 / 证书 / cookie 都在这里补齐。
     *
     * 公开给 `FnOsLogin.openAuthed()`（本机媒体代理用）：代理需要自己接管
     * `Range` 与字节流，不能走会把响应读成 String / ByteArray 的那几个封装。
     */
    fun open(
        url: String,
        headers: Map<String, String>,
        tls: FnOsTls,
        useHttps: Boolean,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection {
        val parsed = URL(url)
        val host = parsed.host
        val conn = parsed.openConnection() as HttpURLConnection
        if (useHttps && conn is HttpsURLConnection) {
            conn.setSSLSocketFactory(tls.sslContext.socketFactory)
            conn.hostnameVerifier = tls.trust
        }
        conn.requestMethod = "GET"
        conn.instanceFollowRedirects = false
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Accept", "application/json,image/*,*/*")

        for ((name, value) in headers) {
            conn.setRequestProperty(name, value)
        }
        val cookie = cookieHeader(host)
        if (cookie.isNotEmpty()) {
            conn.setRequestProperty("Cookie", cookie)
        }
        return conn
    }

    private fun cookieHeader(host: String): String {
        val map = cookies[host] ?: return ""
        if (map.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for ((name, value) in map) {
            if (sb.isNotEmpty()) {
                sb.append("; ")
            }
            sb.append(name).append('=').append(value)
        }
        return sb.toString()
    }

    private fun storeCookies(host: String, conn: HttpURLConnection) {
        val fields = conn.headerFields ?: return
        val values = ArrayList<String>()
        for ((key, list) in fields) {
            if (key != null && key.equals("Set-Cookie", ignoreCase = true)) {
                values.addAll(list)
            }
        }
        if (values.isEmpty()) {
            return
        }
        val map = cookies.getOrPut(host) { mutableMapOf() }
        for (raw in values) {
            val first = raw.substringBefore(';')
            val index = first.indexOf('=')
            if (index <= 0) {
                continue
            }
            val name = first.substring(0, index).trim()
            val value = first.substring(index + 1).trim()
            if (name.isNotEmpty()) {
                map[name] = value
            }
        }
    }
}

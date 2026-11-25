package uts.sdk.modules.fnosAuth

import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本机回环媒体代理（视频用）。
 *
 * ## 为什么需要它
 *
 * NAS 的视频流（`/p/api/v1/stream/v/{id}` 和 `/p/api/v1/video/decode/stream/...`）
 * **只认 `AccessToken` 请求头**：query 传 token 401、cookie 401（实测 2026-11-15）。
 * 而 uni-app x 的 `<video :header>` 在真机上并没有把 header 交到 ExoPlayer —— 实测
 * `HttpDataSource$InvalidResponseCodeException: Response code: 401`（直链 MP4 与 HLS 都是），
 * 也就是说播放器拿不到任何受保护的流。
 *
 * 解法：在 App 进程内起一个只监听 `127.0.0.1` 的极简 HTTP 服务，`<video>` 播
 * `http://127.0.0.1:port/v/<base64url(真实地址)>`，由我们自己去 NAS 取字节并补上
 * `AccessToken`。这样：
 *   - 播放器不再需要任何自定义 header（本地回环不需要鉴权）；
 *   - 支持 `Range`，所以能拖动进度、能边下边播；
 *   - HLS 同理：m3u8 也走代理，里面的分片行被重写成代理地址。
 *
 * ## 边界
 *
 * - 只绑 `127.0.0.1`，不对外；
 * - 代理 URL 里只有 NAS 地址的 base64，**不含 token**（token 只存在进程内的 opener 里）；
 * - 一次请求一个线程、`Connection: close`，够 ExoPlayer 用（它最多并发几条分片）。
 * - 不引入任何三方依赖：`ServerSocket` + `HttpURLConnection`。
 */
object FnOsMediaProxy {

    private const val TAG = "FnOsMediaProxy"
    /** 单次请求最多读这么多请求头，防止恶意/异常客户端拖住线程 */
    private const val MAX_HEAD_BYTES = 8192
    /** 同时处理的连接上限（ExoPlayer 正常用不了这么多） */
    private const val MAX_WORKERS = 8

    /** 由 FnOsLogin 注入：给一个 NAS 绝对地址 + Range，返回**已带鉴权头**的连接 */
    @Volatile
    private var opener: ((String, String?) -> HttpURLConnection)? = null

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    private val workers = AtomicInteger(0)

    /**
     * 确保代理已启动，返回端口（失败返回 0）。
     * 每次调用都刷新 [open]（token 可能已更新），已经启动则不重复建服务。
     */
    fun start(open: (String, String?) -> HttpURLConnection): Int {
        synchronized(this) {
            opener = open
            val current = server
            if (current != null && !current.isClosed && port > 0) {
                return port
            }
            return try {
                val ss = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
                server = ss
                port = ss.localPort
                val t = Thread {
                    while (!ss.isClosed) {
                        val socket = try {
                            ss.accept()
                        } catch (e: Throwable) {
                            break
                        }
                        if (workers.get() >= MAX_WORKERS) {
                            runCatching { socket.close() }
                            continue
                        }
                        workers.incrementAndGet()
                        Thread {
                            try {
                                serve(socket)
                            } catch (e: Throwable) {
                                // 播放器 seek 时会主动断开连接，这类 IOException 属于正常现象
                            } finally {
                                workers.decrementAndGet()
                                runCatching { socket.close() }
                            }
                        }.start()
                    }
                }
                t.isDaemon = true
                t.name = "FnOsMediaProxy"
                t.start()
                Log.i(TAG, "media proxy listening on 127.0.0.1:" + port)
                port
            } catch (e: Throwable) {
                Log.w(TAG, "media proxy start failed: " + e.message)
                server = null
                port = 0
                0
            }
        }
    }

    /** 把关掉代理（退出登录时调；不清也不影响功能，只是端口一直开着） */
    fun stop() {
        synchronized(this) {
            runCatching { server?.close() }
            server = null
            port = 0
        }
    }

    /**
     * 把 NAS 绝对地址包成 `http://127.0.0.1:port/v/<base64url>`；代理不可用时原样返回。
     *
     * m3u8 要**保留 `.m3u8` 后缀**：ExoPlayer 靠 URL 末段扩展名判断用 HLS 还是 progressive，
     * 没有后缀会当成普通文件走 ProgressiveMediaSource 直接报错。
     */
    fun wrap(target: String): String {
        val p = port
        if (p <= 0 || target.isEmpty()) {
            return target
        }
        val suffix = if (target.contains(".m3u8")) ".m3u8" else ""
        return "http://127.0.0.1:" + p + "/v/" + encode(target) + suffix
    }

    private fun encode(text: String): String {
        return Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    private fun decode(text: String): String {
        return String(
            Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8
        )
    }

    /** 日志里只留地址尾部：前缀都一样，看不看没区别，反而把 logcat 撑爆 */
    private fun tailOf(target: String): String {
        val at = target.indexOf("/p/api/v1/")
        val tail = if (at >= 0) target.substring(at) else target
        return if (tail.length > 80) tail.substring(tail.length - 80) else tail
    }

    // ------------------------------------------------------------------ 单个连接

    private fun serve(socket: Socket) {
        socket.soTimeout = 20000
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        if (lines.isEmpty()) {
            return
        }
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) {
            writeText(output, "400 Bad Request", "bad request line")
            return
        }
        val path = requestLine[1]
        var range: String? = null
        for (line in lines) {
            if (line.startsWith("Range:", ignoreCase = true)) {
                range = line.substringAfter(':').trim()
            }
        }
        val mark = path.indexOf("/v/")
        if (mark < 0) {
            writeText(output, "404 Not Found", "only /v/<base64> is supported")
            return
        }
        var encoded = path.substring(mark + 3)
        // wrap() 为了 ExoPlayer 的类型判断给 m3u8 补了后缀，这里要摘掉再解 base64
        if (encoded.endsWith(".m3u8")) {
            encoded = encoded.substring(0, encoded.length - 5)
        }
        val slash = encoded.indexOf('?')
        if (slash > 0) {
            encoded = encoded.substring(0, slash)
        }
        val target = try {
            decode(encoded)
        } catch (e: Throwable) {
            writeText(output, "400 Bad Request", "bad base64")
            return
        }
        // 播放链路的唯一可观测点：release 包里 UTS 的 console.log 会被剥掉，
        // 只有 Kotlin 的 Log 还能在 logcat 里看到（排查「黑屏不播」靠这行）。
        Log.i(TAG, "req " + tailOf(target) + (if (range != null) " range=" + range else " range=-"))
        if (target.endsWith(".m3u8")) {
            servePlaylist(target, output)
        } else {
            pipeBody(target, range, output)
        }
        runCatching { output.flush() }
    }

    /** 读请求头到 `\r\n\r\n`（只读头，不读 body —— 我们只接受 GET） */
    private fun readHead(input: InputStream): String? {
        val buffer = StringBuilder()
        var matched = 0
        while (buffer.length < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) {
                return null
            }
            buffer.append(b.toChar())
            // 连续两个 CRLF 结束
            if (b == '\n'.code) {
                matched += 1
                if (matched >= 2) {
                    return buffer.toString()
                }
            } else if (b != '\r'.code) {
                matched = 0
            }
        }
        return null
    }

    /** 普通文件（mp4 / ts / 图片）：透传状态码、长度与 Range，字节流原样转发 */
    private fun pipeBody(target: String, range: String?, output: OutputStream) {
        val open = opener
        if (open == null) {
            writeText(output, "503 Service Unavailable", "proxy not ready")
            return
        }
        val conn = try {
            open(target, range)
        } catch (e: Throwable) {
            writeText(output, "502 Bad Gateway", "open failed: " + (e.message ?: ""))
            return
        }
        try {
            conn.setRequestProperty("Accept-Encoding", "identity")
            val status = conn.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "nas status " + status + " for " + tailOf(target))
                writeText(output, if (status == 401) "401 Unauthorized" else "502 Bad Gateway", "NAS HTTP " + status)
                return
            }
            val header = StringBuilder()
            header.append(if (status == 206) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK").append("\r\n")
            header.append("Content-Type: ").append(conn.contentType ?: "application/octet-stream").append("\r\n")
            val length = conn.getHeaderField("Content-Length")
            if (length != null) {
                header.append("Content-Length: ").append(length).append("\r\n")
            }
            val contentRange = conn.getHeaderField("Content-Range")
            if (contentRange != null) {
                header.append("Content-Range: ").append(contentRange).append("\r\n")
            }
            header.append("Accept-Ranges: bytes\r\n")
            header.append("Connection: close\r\n\r\n")
            output.write(header.toString().toByteArray(Charsets.UTF_8))
            val stream = conn.inputStream
            try {
                // 512KB 一次读、直接写 socket（**不再套 BufferedOutputStream**：
                // 缓冲层是多余的一次内存拷贝，反而拖慢高码率转发）。
                // 电视上「1x 丢帧、0.5x 不丢」说明单位时间吞吐不够，加大单次读写是最直接的一刀。
                val buffer = ByteArray(512 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) {
                        break
                    }
                    output.write(buffer, 0, read)
                    total += read
                    // 立刻刷出去：不刷播放器拿不到数据，会一直缓冲
                    output.flush()
                }
                Log.i(TAG, "sent " + (total / 1024) + "KB status=" + status)
            } finally {
                runCatching { stream.close() }
            }
        } catch (e: Throwable) {
            // 播放器 seek / 退出时会主动断开，写失败属正常
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * HLS：取 m3u8 原文，把每个分片行改写成经过本代理的绝对地址（相对路径按播放列表位置解析），
     * 注释行（`#EXT...`）原样保留。
     */
    private fun servePlaylist(target: String, output: OutputStream) {
        val open = opener
        if (open == null) {
            writeText(output, "503 Service Unavailable", "proxy not ready")
            return
        }
        val conn = try {
            open(target, null)
        } catch (e: Throwable) {
            writeText(output, "502 Bad Gateway", "open failed: " + (e.message ?: ""))
            return
        }
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                writeText(output, if (status == 401) "401 Unauthorized" else "502 Bad Gateway", "playlist HTTP " + status)
                return
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val base = URI(target)
            val out = StringBuilder()
            for (raw in text.split("\n")) {
                val line = raw.trim()
                if (line.isEmpty()) {
                    continue
                }
                if (line.startsWith("#")) {
                    out.append(line).append("\n")
                    continue
                }
                val absolute = try {
                    base.resolve(line).toString()
                } catch (e: Throwable) {
                    line
                }
                out.append(wrap(absolute)).append("\n")
            }
            val body = out.toString().toByteArray(Charsets.UTF_8)
            Log.i(TAG, "playlist ok lines=" + body.size)
            output.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: " +
                        body.size + "\r\nConnection: close\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            output.write(body)
        } catch (e: Throwable) {
            // 忽略：客户端可能已断开
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun writeText(output: OutputStream, status: String, message: String) {
        val body = message.toByteArray(Charsets.UTF_8)
        runCatching {
            output.write(("HTTP/1.1 " + status + "\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " +
                body.size + "\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
            output.write(body)
            output.flush()
        }
    }
}

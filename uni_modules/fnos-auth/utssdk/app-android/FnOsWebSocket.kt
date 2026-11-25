package uts.sdk.modules.fnosAuth

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/**
 * 极简 WebSocket 客户端（RFC 6455 客户端子集），只为实现 fnOS 的登录握手。
 *
 * 为什么不直接用 uni.connectSocket：
 *   - fnOS 是自签证书 + TOFU 指纹信任，uni 内置的 WebSocket 无法替换 TrustManager；
 *   - 该连接只用于登录：发送 getRSAPub / user.login / user.2fa.loginVerify，
 *     收到的都是单个文本帧，不需要分包、压缩、重连等完整特性。
 *
 * 支持：TLS 握手 + 主机名/指纹校验、HTTP Upgrade、掩码文本帧、ping/pong、
 * close、分片续帧（continuation）。
 */
class FnOsWebSocket private constructor(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: OutputStream
) {

    @Volatile
    private var closed: Boolean = false

    fun isClosed(): Boolean = closed

    fun sendText(text: String) {
        sendFrame(0x1, text.toByteArray(Charsets.UTF_8))
    }

    /**
     * 阻塞读一条完整文本消息。遇到 ping 自动回 pong，遇到分片自动拼接。
     * 读超时会抛 SocketTimeoutException（由调用方转成可读错误）。
     */
    fun readText(): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val frame = readFrame()
            when (frame.opcode) {
                0x9 -> sendFrame(0xA, frame.payload) // ping -> pong
                0xA -> { /* pong，忽略 */ }
                0x8 -> {
                    closed = true
                    throw IOException("NAS 关闭了登录连接")
                }
                0x0, 0x1, 0x2 -> {
                    buffer.write(frame.payload, 0, frame.payload.size)
                    if (frame.fin) {
                        return buffer.toString("UTF-8")
                    }
                }
                else -> { /* 其它控制帧忽略 */ }
            }
        }
    }

    fun close() {
        if (closed) {
            return
        }
        closed = true
        runCatching { sendFrame(0x8, ByteArray(0)) }
        runCatching { socket.close() }
    }

    // ------------------------------------------------------------------ 帧

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        if (closed) {
            throw IOException("登录连接已关闭")
        }
        val header = ByteArrayOutputStream()
        header.write(0x80 or opcode) // FIN=1
        val len = payload.size
        when {
            len < 126 -> header.write(0x80 or len) // 客户端帧必须置掩码位
            len < 65536 -> {
                header.write(0x80 or 126)
                header.write((len shr 8) and 0xff)
                header.write(len and 0xff)
            }
            else -> {
                header.write(0x80 or 127)
                var i = 7
                while (i >= 0) {
                    header.write(((len.toLong() shr (8 * i)) and 0xffL).toInt())
                    i--
                }
            }
        }
        val mask = FnOsCrypto.randomBytes(4)
        header.write(mask, 0, 4)
        val masked = ByteArray(len)
        var i = 0
        while (i < len) {
            masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            i++
        }
        output.write(header.toByteArray())
        output.write(masked)
        output.flush()
    }

    private fun readFrame(): Frame {
        val b0 = input.read()
        val b1 = input.read()
        if (b0 < 0 || b1 < 0) {
            closed = true
            throw IOException("登录连接已断开")
        }
        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0f
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) {
            val ext = readFully(2)
            len = (((ext[0].toInt() and 0xff) shl 8) or (ext[1].toInt() and 0xff)).toLong()
        } else if (len == 127L) {
            val ext = readFully(8)
            var v = 0L
            var i = 0
            while (i < 8) {
                v = (v shl 8) or (ext[i].toLong() and 0xffL)
                i++
            }
            len = v
        }
        val maskKey = if (masked) readFully(4) else null
        val payload = readFully(len.toInt())
        if (maskKey != null) {
            var i = 0
            while (i < payload.size) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                i++
            }
        }
        return Frame(fin, opcode, payload)
    }

    private fun readFully(size: Int): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(out, offset, size - offset)
            if (read < 0) {
                closed = true
                throw IOException("登录连接已断开")
            }
            offset += read
        }
        return out
    }

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    companion object {

        /**
         * 建立 WSS 连接并完成 HTTP Upgrade。
         *
         * @param path 形如 "/websocket?type=main"（带 query 也走这里）
         */
        fun connect(
            host: String,
            port: Int,
            path: String,
            ssl: SSLContext,
            verifier: HostnameVerifier,
            timeoutMs: Int,
            useTls: Boolean
        ): FnOsWebSocket {
            val raw = Socket()
            raw.soTimeout = timeoutMs
            raw.connect(InetSocketAddress(host, port), timeoutMs)

            val socket: Socket
            if (useTls) {
                // 手工在已连接的 TCP 上叠一层 TLS，SSLContext 已注入 TOFU TrustManager
                val tlsSocket = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                tlsSocket.soTimeout = timeoutMs
                tlsSocket.startHandshake()
                // 裸 SSLSocket 不会做主机名校验，必须自己调 TOFU 校验器
                if (!verifier.verify(host, tlsSocket.session)) {
                    runCatching { tlsSocket.close() }
                    throw SSLException("NAS 证书指纹与首次信任的不一致，已清除旧指纹，请再试一次")
                }
                socket = tlsSocket
            } else {
                socket = raw
            }

            val out = socket.outputStream
            val key = FnOsCrypto.b64(FnOsCrypto.randomBytes(16))
            val request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: " + host + ":" + port + "\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "User-Agent: NASPhotoTV-UniAppX/1.0\r\n" +
                "\r\n"
            out.write(request.toByteArray(Charsets.UTF_8))
            out.flush()

            val input = BufferedInputStream(socket.inputStream)
            val head = readHttpHead(input)
            val statusLine = head.substringBefore("\r\n")
            if (!statusLine.contains(" 101")) {
                runCatching { socket.close() }
                throw IOException("WebSocket 握手失败：" + statusLine)
            }
            return FnOsWebSocket(socket, input, out)
        }

        private fun readHttpHead(input: BufferedInputStream): String {
            val buf = ByteArrayOutputStream()
            val tail = byteArrayOf(13, 10, 13, 10)
            var matched = 0
            while (true) {
                val b = input.read()
                if (b < 0) {
                    throw IOException("连接在握手阶段被关闭")
                }
                buf.write(b)
                if (b == tail[matched].toInt()) {
                    matched++
                    if (matched == 4) {
                        return buf.toString("UTF-8")
                    }
                } else {
                    matched = if (b == 13) 1 else 0
                }
                if (buf.size() > 16384) {
                    throw IOException("WebSocket 握手响应过长")
                }
            }
        }
    }
}

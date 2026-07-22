package ch.bigli.passes.data

import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * A minimal single-request-at-a-time HTTP/1.0 server backed by [ServerSocket], shared by tests
 * that exercise [PassRepository]'s HTTP paths (download-and-import, update polling).
 *
 * [com.sun.net.httpserver.HttpServer] would be the obvious choice here, but it lives in the
 * `jdk.httpserver` JDK module, which is not part of Android's `android.jar` stub that Kotlin
 * compiles unit tests against (Robolectric tests run on the real host JVM, but they are still
 * *compiled* against android.jar). Referencing `com.sun.net.httpserver.*` therefore fails with
 * "Unresolved reference" at compile time even though it would resolve fine at runtime. Plain
 * `java.net.ServerSocket`/`Socket` are part of Android's public API surface and compile cleanly.
 */
internal class TestHttpServer : AutoCloseable {
    data class Response(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    private val serverSocket = ServerSocket(0)
    private val routes = mutableMapOf<String, (Map<String, String>) -> Response>()
    private val lastRequestHeaders = mutableMapOf<String, Map<String, String>>()
    @Volatile private var running = true
    private val thread = Thread {
        while (running) {
            val socket = try {
                serverSocket.accept()
            } catch (e: Exception) {
                break
            }
            handle(socket)
        }
    }.apply { isDaemon = true }

    val port: Int get() = serverSocket.localPort

    /** Always responds with the same fixed status/body/headers. */
    fun respond(path: String, status: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
        routes[path] = { Response(status, body, headers) }
    }

    /** Registers a handler that can inspect request headers (e.g. to honor If-Modified-Since). */
    fun respond(path: String, handler: (requestHeaders: Map<String, String>) -> Response) {
        routes[path] = handler
    }

    /** The headers of the most recent request received for [path], or null if none yet. */
    fun headersReceived(path: String): Map<String, String>? = lastRequestHeaders[path]

    fun start() {
        thread.start()
    }

    private fun handle(socket: Socket) {
        socket.use {
            val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: return
            val headers = mutableMapOf<String, String>()
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                line = reader.readLine()
            }
            lastRequestHeaders[path] = headers
            val response = routes[path]?.invoke(headers) ?: Response(404, ByteArray(0))
            val out: OutputStream = socket.getOutputStream()
            val statusText = if (response.status in 200..299) "OK" else "Error"
            out.write("HTTP/1.0 ${response.status} $statusText\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write("Content-Length: ${response.body.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            response.headers.forEach { (k, v) -> out.write("$k: $v\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
            out.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write(response.body)
            out.flush()
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
    }
}

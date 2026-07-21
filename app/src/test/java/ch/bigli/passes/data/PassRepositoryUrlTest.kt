package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * A minimal single-request-at-a-time HTTP/1.0 server backed by [ServerSocket].
 *
 * [com.sun.net.httpserver.HttpServer] would be the obvious choice here, but it lives in the
 * `jdk.httpserver` JDK module, which is not part of Android's `android.jar` stub that Kotlin
 * compiles unit tests against (Robolectric tests run on the real host JVM, but they are still
 * *compiled* against android.jar). Referencing `com.sun.net.httpserver.*` therefore fails with
 * "Unresolved reference" at compile time even though it would resolve fine at runtime. Plain
 * `java.net.ServerSocket`/`Socket` are part of Android's public API surface and compile cleanly.
 */
private class TestHttpServer : AutoCloseable {
    private val serverSocket = ServerSocket(0)
    private val routes = mutableMapOf<String, Pair<Int, ByteArray>>()
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

    fun respond(path: String, status: Int, body: ByteArray) {
        routes[path] = status to body
    }

    fun start() {
        thread.start()
    }

    private fun handle(socket: Socket) {
        socket.use {
            val requestLine = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1).readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: return
            val (status, body) = routes[path] ?: (404 to ByteArray(0))
            val out: OutputStream = socket.getOutputStream()
            val statusText = if (status in 200..299) "OK" else "Error"
            out.write("HTTP/1.0 $status $statusText\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write("Content-Length: ${body.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
    }
}

@RunWith(RobolectricTestRunner::class)
class PassRepositoryUrlTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository
    private lateinit var server: TestHttpServer
    private lateinit var base: String

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
        val bytes = fixture("sample.pkpass")
        server = TestHttpServer()
        server.respond("/sample.pkpass", 200, bytes)
        server.respond("/missing.pkpass", 404, ByteArray(0))
        server.start()
        base = "http://127.0.0.1:${server.port}"
    }

    @After fun tearDown() { server.close(); db.close() }

    @Test fun `importFromUrl downloads and persists a pass`() = runTest {
        val pass = repo.importFromUrl("$base/sample.pkpass")
        assertEquals("SWISS", pass.organization)
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun `importFromUrl throws on http error`() = runTest {
        try {
            repo.importFromUrl("$base/missing.pkpass")
            error("expected ImportError")
        } catch (e: Exception) {
            assertTrue(e is ImportError)
        }
    }
}

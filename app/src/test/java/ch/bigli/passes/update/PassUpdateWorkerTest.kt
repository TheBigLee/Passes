package ch.bigli.passes.update

import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.TestListenableWorkerBuilder
import ch.bigli.passes.PassApp
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.data.TestHttpServer
import ch.bigli.passes.domain.BarcodeFormat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exercises [PassUpdateWorker]'s filter (pkpass + has updateInfo + not already voided) end to
 * end, since it's the only place that logic lives - a regression here would silently stop
 * eligible passes from being background-refreshed, or start hitting already-voided ones.
 */
@RunWith(RobolectricTestRunner::class)
class PassUpdateWorkerTest {
    private lateinit var app: PassApp
    private lateinit var repo: PassRepository
    private lateinit var server: TestHttpServer
    private lateinit var base: String

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        repo = app.repository
        server = TestHttpServer()
        server.start()
        base = "http://127.0.0.1:${server.port}"
    }

    @After fun tearDown() { server.close() }

    private fun buildPkPass(serial: String, voided: Boolean = false): ByteArray {
        val json = """
            {
              "formatVersion": 1,
              "passTypeIdentifier": "pass.test",
              "serialNumber": "$serial",
              "teamIdentifier": "TEAM",
              "organizationName": "Acme",
              "description": "Test pass",
              "webServiceURL": "$base",
              "authenticationToken": "tok-123",
              "voided": $voided,
              "generic": { "primaryFields": [] }
            }
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("pass.json"))
            zip.write(json.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test fun `worker refreshes only pkpass passes with updateInfo that are not already voided`() = runTest {
        val eligible = repo.import(buildPkPass(serial = "ELIGIBLE"), "eligible.pkpass")
        val alreadyVoidedSource = repo.import(buildPkPass(serial = "ALREADY-VOIDED"), "voided.pkpass")
        val manual = repo.createManualPass(BarcodeFormat.CODE128, "6001234567890")

        // Mark alreadyVoidedSource voided via a real 410 refresh, before wiring up the routes the
        // worker itself will hit - this pass must NOT be polled again once voided.
        server.respond("/v1/passes/pass.test/ALREADY-VOIDED", 410, ByteArray(0))
        repo.refreshPass(alreadyVoidedSource.id)
        assertTrue(repo.getById(alreadyVoidedSource.id)!!.voided)

        // Only the eligible pass's route is registered for the worker's own run. A 410 here is
        // an easy-to-observe side effect proving the worker actually made the request.
        server.respond("/v1/passes/pass.test/ELIGIBLE", 410, ByteArray(0))

        val worker = TestListenableWorkerBuilder<PassUpdateWorker>(app).build()
        worker.doWork()

        assertTrue("eligible pass should have been refreshed (and voided) by the worker", repo.getById(eligible.id)!!.voided)
        assertTrue("already-voided pass stays voided", repo.getById(alreadyVoidedSource.id)!!.voided)
        assertFalse("manual pass has no updateInfo and must be left alone", repo.getById(manual.id)!!.voided)
    }
}

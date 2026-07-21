package ch.bigli.passes.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.data.PassDatabase
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassListViewModelTest {
    private lateinit var repo: PassRepository
    private lateinit var db: PassDatabase

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `state reflects imported passes`() = runTest {
        val vm = PassListViewModel(repo)
        repo.import(fixture("sample.pkpass"), "sample.pkpass")
        val passes = vm.passes.first { it.isNotEmpty() }
        assertEquals(1, passes.size)
        assertEquals("SWISS", passes.first().organization)
    }

    @Test fun `reportError surfaces a message on the error flow`() = runTest {
        val vm = PassListViewModel(repo)
        // errors has replay = 0, so the collector must subscribe before reportError emits.
        val err = async { vm.errors.first() }
        runCurrent()
        vm.reportError("Unsupported format: note.txt")
        assertTrue(err.await().contains("Unsupported", ignoreCase = true))
    }
}

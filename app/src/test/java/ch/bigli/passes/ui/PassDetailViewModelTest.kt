package ch.bigli.passes.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.data.PassDatabase
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassDetailViewModelTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `updateTitle updates the on-screen pass`() = runTest {
        val imported = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        val vm = PassDetailViewModel(repo, imported.id)
        vm.pass.first { it != null }
        vm.updateTitle("Renamed")
        assertEquals("Renamed", vm.pass.first { it?.title == "Renamed" }?.title)
    }

    @Test fun `updateTitle ignores blank input`() = runTest {
        val imported = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        val vm = PassDetailViewModel(repo, imported.id)
        val loaded = vm.pass.first { it != null }!!
        vm.updateTitle("   ")
        assertEquals(loaded.title, vm.pass.first { it != null }?.title)
    }
}

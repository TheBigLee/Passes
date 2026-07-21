package ch.bigli.passes

import android.app.Application
import androidx.room.Room
import ch.bigli.passes.data.PassDatabase
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.images.PassImageLoader
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.flow.MutableStateFlow

class PassApp : Application() {
    lateinit var repository: PassRepository
        private set

    /** Set to a pass id when an import should navigate to that pass's detail screen; NavHost observes and clears it. */
    val pendingPassId = MutableStateFlow<String?>(null)

    /** Shared, process-wide image loader (in-memory cache) for pkpass logo/strip artwork. */
    val imageLoader = PassImageLoader()

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, PassDatabase::class.java, "passes.db").build()
        repository = PassRepository(this, db.passDao(), PkPassImporter())
    }
}

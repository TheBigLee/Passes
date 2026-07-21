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

    /** Set after an import so the NavHost navigates to the new pass; carries whether to open the title editor. */
    val pendingPass = MutableStateFlow<PendingPass?>(null)

    /** A scanned barcode handed from ScanScreen to CreatePassScreen as a prefill; consumed once. */
    val pendingScan = MutableStateFlow<ch.bigli.passes.domain.Barcode?>(null)

    /** Shared, process-wide image loader (in-memory cache) for pkpass logo/strip artwork. */
    val imageLoader = PassImageLoader()

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, PassDatabase::class.java, "passes.db").build()
        repository = PassRepository(this, db.passDao(), PkPassImporter())
    }
}

/** A just-imported pass to navigate to. [editTitle] auto-opens the rename dialog (used for PDF imports). */
data class PendingPass(val id: String, val editTitle: Boolean)

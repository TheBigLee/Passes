package ch.bigli.passes

import android.app.Application
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import ch.bigli.passes.data.MIGRATION_1_2
import ch.bigli.passes.data.MIGRATION_2_3
import ch.bigli.passes.data.MIGRATION_3_4
import ch.bigli.passes.data.MIGRATION_4_5
import ch.bigli.passes.data.PassDatabase
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.images.PassImageLoader
import ch.bigli.passes.importing.PkPassImporter
import ch.bigli.passes.update.PassUpdateWorker
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit

class PassApp : Application(), Configuration.Provider {
    lateinit var repository: PassRepository
        private set

    // Implementing Configuration.Provider makes WorkManager.getInstance() self-initialize
    // (using this configuration) if the androidx.startup content-provider hasn't run yet at the
    // point onCreate() calls it — notably under Robolectric, where provider/Application init
    // ordering doesn't match a real device.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /** Set after an import so the NavHost navigates to the new pass. */
    val pendingPass = MutableStateFlow<PendingPass?>(null)

    /** A scanned barcode handed from ScanScreen to CreatePassScreen as a prefill; consumed once. */
    val pendingScan = MutableStateFlow<ch.bigli.passes.domain.Barcode?>(null)

    /** Shared, process-wide image loader (in-memory cache) for pkpass logo/strip artwork. */
    val imageLoader = PassImageLoader()

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, PassDatabase::class.java, "passes.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
        repository = PassRepository(this, db.passDao(), PkPassImporter())

        val updateRequest = PeriodicWorkRequestBuilder<PassUpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "pass-update-check", ExistingPeriodicWorkPolicy.KEEP, updateRequest,
        )
    }
}

/** A just-imported pass to navigate to. */
data class PendingPass(val id: String)

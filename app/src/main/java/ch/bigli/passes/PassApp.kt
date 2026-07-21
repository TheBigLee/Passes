package ch.bigli.passes

import android.app.Application
import androidx.room.Room
import ch.bigli.passes.data.PassDatabase
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.importing.PkPassImporter

class PassApp : Application() {
    lateinit var repository: PassRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, PassDatabase::class.java, "passes.db").build()
        repository = PassRepository(this, db.passDao(), PkPassImporter())
    }
}

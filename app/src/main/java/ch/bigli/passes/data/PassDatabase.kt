package ch.bigli.passes.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the columns backing pass-refresh (Phase 4): voided flag + cached Last-Modified header. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN voided INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE passes ADD COLUMN lastModified TEXT")
    }
}

@Database(entities = [PassEntity::class], version = 2, exportSchema = false)
abstract class PassDatabase : RoomDatabase() {
    abstract fun passDao(): PassDao
}

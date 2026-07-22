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

/** Adds the expirationDate column so expiry can be evaluated live at display time. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN expirationDateEpoch INTEGER")
    }
}

/**
 * Adds `description` (raw pass.json description, needed to recompute title live) and
 * `titleCustomized` (protects a user-renamed title from being overwritten by live
 * re-translation). Existing rows get `description = NULL`, `titleCustomized = 0` — every
 * pre-existing pass is treated as "not customized," so its title starts being live-recomputed
 * in the current locale after this update, which is the desired behavior.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN description TEXT")
        db.execSQL("ALTER TABLE passes ADD COLUMN titleCustomized INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [PassEntity::class], version = 4, exportSchema = false)
abstract class PassDatabase : RoomDatabase() {
    abstract fun passDao(): PassDao
}

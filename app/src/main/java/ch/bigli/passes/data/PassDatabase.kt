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
 * re-translation). Both are superseded by [MIGRATION_4_5] — kept here unchanged since this is
 * exactly how a real v3-installed user's database gets upgraded on the way to v5.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN description TEXT")
        db.execSQL("ALTER TABLE passes ADD COLUMN titleCustomized INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Drops `title`/`titleCustomized` (the rename feature and the synthesized title it protected are
 * both removed) and adds `backFieldsJson` (pkpass back-of-pass info, live-translated the same way
 * as the other field lists). SQLite's native `DROP COLUMN` (3.35+) isn't safely available across
 * this app's full `minSdk` range, so this rebuilds the table: create the new shape, copy every
 * other column across, drop the old table, rename the new one into place.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `passes_new` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `subtitle` TEXT, " +
                "`organization` TEXT, `bgColor` INTEGER, `fgColor` INTEGER, `fieldsJson` TEXT NOT NULL, " +
                "`barcodeJson` TEXT, `relevantDateEpoch` INTEGER, `rawFilePath` TEXT NOT NULL, " +
                "`sourceFormat` TEXT NOT NULL, `updateInfoJson` TEXT, `voided` INTEGER NOT NULL DEFAULT 0, " +
                "`lastModified` TEXT, `expirationDateEpoch` INTEGER, `description` TEXT, " +
                "`backFieldsJson` TEXT NOT NULL DEFAULT '[]', PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO passes_new (id, type, subtitle, organization, bgColor, fgColor, fieldsJson, " +
                "barcodeJson, relevantDateEpoch, rawFilePath, sourceFormat, updateInfoJson, voided, " +
                "lastModified, expirationDateEpoch, description, backFieldsJson) " +
                "SELECT id, type, subtitle, organization, bgColor, fgColor, fieldsJson, barcodeJson, " +
                "relevantDateEpoch, rawFilePath, sourceFormat, updateInfoJson, voided, lastModified, " +
                "expirationDateEpoch, description, '[]' FROM passes"
        )
        db.execSQL("DROP TABLE passes")
        db.execSQL("ALTER TABLE passes_new RENAME TO passes")
    }
}

/** Adds the auto-update opt-out toggle, defaulting existing rows to enabled. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN autoUpdateEnabled INTEGER NOT NULL DEFAULT 1")
    }
}

/** Adds the transitType column (air/boat/bus/train/generic), so BOARDING passes can render a mode-appropriate icon. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE passes ADD COLUMN transitType TEXT")
    }
}

@Database(entities = [PassEntity::class], version = 7, exportSchema = false)
abstract class PassDatabase : RoomDatabase() {
    abstract fun passDao(): PassDao
}

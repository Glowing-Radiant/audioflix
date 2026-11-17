package app.audioflix.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LibraryItem::class, Track::class], version = 2, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
  abstract fun libraryDao(): LibraryDao

  companion object {
    fun create(context: Context): LibraryDatabase =
      Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
        .addMigrations(MIGRATION_1_2)
        .build()

    /**
     * Tracks remember their provider file id, so titles can be streamed without downloading, and
     * where they start and end in their file, so audiobook chapters can be separate tracks.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN remoteFileId TEXT")
        db.execSQL("ALTER TABLE tracks ADD COLUMN startMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tracks ADD COLUMN endMs INTEGER")
      }
    }
  }
}

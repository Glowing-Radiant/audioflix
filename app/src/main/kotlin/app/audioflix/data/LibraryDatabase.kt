package app.audioflix.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LibraryItem::class, Track::class], version = 1, exportSchema = false)
abstract class LibraryDatabase : RoomDatabase() {
  abstract fun libraryDao(): LibraryDao

  companion object {
    fun create(context: Context): LibraryDatabase =
      Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
  }
}

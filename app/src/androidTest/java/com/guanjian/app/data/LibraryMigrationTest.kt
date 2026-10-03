package com.guanjian.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryMigrationTest {
    private val databaseName = "migration-1-2"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LibraryDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationOneToTwoPreservesBooksAndAddsEmptyMetadata() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL("""
                INSERT INTO books
                (id,title,author,libraryName,floor,categoryArea,shelfHint,locationNote,status,currentPage,totalPages,coverUri,note,createdAt,updatedAt)
                VALUES ('book-1','活着','余华','市图书馆','3楼','','','靠窗','在读',10,191,NULL,'我的备注',1,2)
            """.trimIndent())
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 2, true, LibraryDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT * FROM books WHERE id='book-1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals("活着", cursor.getString(cursor.getColumnIndexOrThrow("title")))
                assertEquals("市图书馆", cursor.getString(cursor.getColumnIndexOrThrow("libraryName")))
                assertEquals("我的备注", cursor.getString(cursor.getColumnIndexOrThrow("note")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("isbn")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("publisher")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("publishedDate")))
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("description")))
            }
        }
    }

    @Test fun migrationTwoToThreeSeedsLastObservationWithoutDeletingActiveFocus() {
        helper.createDatabase("migration-2-3", 2).apply {
            execSQL("""
                INSERT INTO active_focus
                (singleton,id,activityType,startedAt,awaitingSummary,recoveredAfterReboot,zoneId,
                 clock_running,clock_segmentWall,clock_segmentElapsed,clock_bootCount,clock_accumulated,clock_pausedDuration)
                VALUES (1,'focus-1','阅读',100000,0,0,'Asia/Shanghai',1,100000,10000,1,0,0)
            """.trimIndent())
            close()
        }

        helper.runMigrationsAndValidate("migration-2-3", 3, true, LibraryDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT * FROM active_focus WHERE id='focus-1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(100000L, cursor.getLong(cursor.getColumnIndexOrThrow("clock_observedWall")))
                assertEquals(10000L, cursor.getLong(cursor.getColumnIndexOrThrow("clock_observedElapsed")))
                assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("clock_observedBoot")))
            }
        }
    }
}

package com.guanjian.app.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LibraryMigrationRobolectricTest {
    @Test fun oldRunningFocusKeepsItsIdAndReceivesObservation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "focus-migration-${UUID.randomUUID()}.db"
        val factory = FrameworkSQLiteOpenHelperFactory()
        val old = factory.create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE books (id TEXT NOT NULL PRIMARY KEY)")
                    db.execSQL("""
                        CREATE TABLE active_focus (
                            singleton INTEGER NOT NULL PRIMARY KEY, id TEXT NOT NULL, activityType TEXT NOT NULL,
                            bookId TEXT, startedAt INTEGER NOT NULL, startPage INTEGER, awaitingSummary INTEGER NOT NULL,
                            recoveredAfterReboot INTEGER NOT NULL, zoneId TEXT NOT NULL, clock_running INTEGER NOT NULL,
                            clock_segmentWall INTEGER NOT NULL, clock_segmentElapsed INTEGER NOT NULL,
                            clock_bootCount INTEGER NOT NULL, clock_accumulated INTEGER NOT NULL,
                            clock_pausedAtWall INTEGER, clock_pausedAtElapsed INTEGER, clock_pausedDuration INTEGER NOT NULL,
                            FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE SET NULL)
                    """.trimIndent())
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        old.writableDatabase.execSQL("""
            INSERT INTO active_focus
            (singleton,id,activityType,startedAt,awaitingSummary,recoveredAfterReboot,zoneId,
             clock_running,clock_segmentWall,clock_segmentElapsed,clock_bootCount,clock_accumulated,clock_pausedDuration)
            VALUES (1,'focus-1','阅读',100000,0,0,'Asia/Shanghai',1,100000,10000,1,0,0)
        """.trimIndent())
        old.close()

        val upgraded = factory.create(SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("旧数据库意外丢失")
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    LibraryDatabase.MIGRATION_2_3.migrate(db)
                }
            }).build())
        upgraded.writableDatabase.query("SELECT * FROM active_focus WHERE id='focus-1'").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals(100000L, cursor.getLong(cursor.getColumnIndexOrThrow("clock_observedWall")))
            assertEquals(10000L, cursor.getLong(cursor.getColumnIndexOrThrow("clock_observedElapsed")))
            assertEquals(1, cursor.getInt(cursor.getColumnIndexOrThrow("clock_observedBoot")))
        }
        upgraded.close()
        context.deleteDatabase(name)
    }
}

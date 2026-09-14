package com.guanjian.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用唯一的 Room 数据库（文件名 guanjian.db，版本 2）。
 *
 * [exportSchema] 保持默认的 true，让 Room 把表结构导出到 app/schemas/ 下的 JSON。
 * 这份 schema 文件应当随代码一起提交：将来升级版本号时，它是编写 Migration
 * 与做迁移测试的对照基准，丢了就只能靠猜。
 *
 * 版本 2 为书籍增加公开出版信息。迁移只增加带默认值的列，旧书籍与关联数据保持原样。
 */
@Database(entities = [BookEntity::class, NoteCardEntity::class, TagEntity::class, NoteTagCrossRef::class,
    DailyPlanEntity::class, FocusSessionEntity::class, WeeklyGoalEntity::class, WeeklyReviewEntity::class,
    ActivityEventEntity::class, ActiveFocusEntity::class, FocusSegmentEntity::class], version = 2, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    /** 数据库访问入口，全部 SQL 都定义在 [LibraryDao] 上。 */
    abstract fun dao(): LibraryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN isbn TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN publisher TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN publishedDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN description TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 创建数据库实例。
         *
         * 使用 applicationContext 而非传入的 context，避免持有 Activity 引用造成内存泄漏。
         *
         * 注意这里【没有】调用 fallbackToDestructiveMigration()：
         * 该选项在版本不匹配时会静默清空用户数据，而本应用的所有记录都只存在本地、
         * 没有任何云端可恢复，因此宁可让它在升级时明确报错，也不能悄悄丢数据。
         */
        fun create(context: Context): LibraryDatabase = Room.databaseBuilder(context.applicationContext,
            LibraryDatabase::class.java, "guanjian.db").addMigrations(MIGRATION_1_2).build()
    }
}

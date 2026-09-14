package com.guanjian.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * 全部数据库访问入口。
 *
 * 分成两类方法，用途严格区分：
 *
 * 1) 返回 [Flow] 的观察型查询：供 ViewModel 长期订阅，数据库一有变化就自动重发。
 *    这一层刻意不做任何过滤/排序之外的加工，排序在 SQL 里完成，让 SQLite 用上索引。
 *
 * 2) suspend 的一次性查询与写操作：供 Repository 在事务内使用。
 *    写操作只提供最小粒度（putXxx / insertXxx / deleteXxx），
 *    业务规则（校验、联动、事件派生）全部留在 Repository，
 *    避免把逻辑散落进 DAO 而变得难以测试。
 */
@Dao
interface LibraryDao {
    // ---------- 观察型查询（Flow）：UI 状态的数据源 ----------

    /** 书墙：按更新时间倒序，最近改动过的书排最前。 */
    @Query("SELECT * FROM books ORDER BY updatedAt DESC") fun books(): Flow<List<BookEntity>>

    /**
     * 卡片列表，同时带出每张卡的标签。
     *
     * 标注 @Transaction 是必需的：@Relation 会拆成一条主查询 + 一条关联查询，
     * 只有包在事务里两次查询才看到同一个数据库快照，
     * 否则中途有写入就可能读到「卡片存在但标签已变」的撕裂状态。
     */
    @Transaction @Query("SELECT * FROM cards ORDER BY createdAt DESC") fun cards(): Flow<List<NoteWithTags>>

    /** 计划：按日期倒序，同一天内新建的排前面。 */
    @Query("SELECT * FROM plans ORDER BY date DESC, createdAt DESC") fun plans(): Flow<List<DailyPlanEntity>>

    /** 专注流水：按结束时间倒序。 */
    @Query("SELECT * FROM sessions ORDER BY endedAt DESC") fun sessions(): Flow<List<FocusSessionEntity>>

    /** 活动流水：统计与「今天留下的记录」的数据源。 */
    @Query("SELECT * FROM events ORDER BY createdAt DESC") fun events(): Flow<List<ActivityEventEntity>>

    /** 周目标：按周倒序。 */
    @Query("SELECT * FROM goals ORDER BY weekStartDate DESC") fun goals(): Flow<List<WeeklyGoalEntity>>

    /** 周回顾：按周倒序。 */
    @Query("SELECT * FROM reviews ORDER BY weekStartDate DESC") fun reviews(): Flow<List<WeeklyReviewEntity>>

    /**
     * 当前进行中的专注。固定查 singleton = 1 那一行，最多一条；
     * 没有进行中的专注时 Flow 发出 null（因此返回类型是实体可空）。
     */
    @Query("SELECT * FROM active_focus WHERE singleton = 1") fun activeFocus(): Flow<ActiveFocusEntity?>

    // ---------- 一次性读：供 Repository 在事务内做校验 ----------

    /** 按 id 取书，不存在返回 null。用于判断是「新增」还是「编辑」，以及取旧页码算增量。 */
    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): BookEntity?

    /** 按 id 取卡片，不存在返回 null。 */
    @Query("SELECT * FROM cards WHERE id = :id") suspend fun card(id: String): NoteCardEntity?

    /** 按 id 取计划，不存在返回 null。关联计划校验与完成状态切换都要用它。 */
    @Query("SELECT * FROM plans WHERE id = :id") suspend fun plan(id: String): DailyPlanEntity?

    /**
     * 按 id 取已完成的专注。finishFocus 用它做幂等判断：
     * 已存在说明这次专注早已收尾，直接返回、不重复记账。
     */
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): FocusSessionEntity?

    /** 直接取进行中的专注（非 Flow 版），用于写操作前的读取。 */
    @Query("SELECT * FROM active_focus WHERE singleton = 1") suspend fun active(): ActiveFocusEntity?

    /** 按名字取标签，用于「已存在就复用、不存在才新建」，避免违反 name 唯一索引。 */
    @Query("SELECT * FROM tags WHERE name = :name") suspend fun tag(name: String): TagEntity?

    /** 取某周目标，用于以旧 id 覆盖实现一周一条。 */
    @Query("SELECT * FROM goals WHERE weekStartDate = :week") suspend fun goal(week: String): WeeklyGoalEntity?

    /** 取某周回顾，用途同上。 */
    @Query("SELECT * FROM reviews WHERE weekStartDate = :week") suspend fun review(week: String): WeeklyReviewEntity?

    /** 取某次专注的全部时间片段，按日期分组后用于生成跨日/跨周的统计事件。 */
    @Query("SELECT * FROM focus_segments WHERE focusId = :id") suspend fun segments(id: String): List<FocusSegmentEntity>

    // ---------- 写操作（最小粒度） ----------

    /**
     * 一律使用 @Upsert：同一份代码同时覆盖新增与编辑两种情况。
     * 主键已存在则整体替换，不存在则插入。
     */
    @Upsert suspend fun putBook(value: BookEntity)
    @Upsert suspend fun putCard(value: NoteCardEntity)
    @Upsert suspend fun putPlan(value: DailyPlanEntity)
    @Upsert suspend fun putGoal(value: WeeklyGoalEntity)
    @Upsert suspend fun putReview(value: WeeklyReviewEntity)
    @Upsert suspend fun putActive(value: ActiveFocusEntity)

    /** 流水类记录只增不改，因此用 @Insert 而非 @Upsert。 */
    @Insert suspend fun insertSession(value: FocusSessionEntity)
    @Insert suspend fun insertEvent(value: ActivityEventEntity)
    @Insert suspend fun insertSegment(value: FocusSegmentEntity)
    @Insert suspend fun insertTag(value: TagEntity)
    @Insert suspend fun insertNoteTag(value: NoteTagCrossRef)

    // ---------- 删除 ----------

    @Query("DELETE FROM books WHERE id = :id") suspend fun deleteBook(id: String)
    @Query("DELETE FROM cards WHERE id = :id") suspend fun deleteCard(id: String)
    @Query("DELETE FROM plans WHERE id = :id") suspend fun deletePlan(id: String)

    /**
     * 清空某张卡片的全部标签关联。
     * 保存卡片时先清空再重建，比逐条 diff 简单可靠；
     * 因为它总在事务内执行，中间态不会被其他查询看到。
     */
    @Query("DELETE FROM note_tags WHERE noteId = :id") suspend fun clearNoteTags(id: String)

    /**
     * 撤销「计划已完成」事件，用于用户把计划改回未完成时同步撤回统计。
     * 之所以能直接删：事件是系统派生的，不是用户手工录入的数据。
     * 条件带 type 是为了不误删同一 sourceId 的其他类型事件。
     */
    @Query("DELETE FROM events WHERE sourceId = :id AND type = 'PLAN_COMPLETED'") suspend fun undoPlanEvent(id: String)

    /** 结束专注：清掉单行表，使全局回到「没有进行中的专注」状态。 */
    @Query("DELETE FROM active_focus WHERE singleton = 1") suspend fun clearActive()
}

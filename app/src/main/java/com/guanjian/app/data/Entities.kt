package com.guanjian.app.data

import androidx.room.*
import com.guanjian.app.domain.FocusClock
import java.util.UUID

/**
 * 生成新的实体主键。
 *
 * 用 UUID 字符串而不是自增 Long，好处是新建对象时（尚未落库、甚至不打算落库）
 * 就已经具备稳定 id，UI 可以先拿它做导航、卡片可以先关联书籍，
 * 也让「编辑」与「新增」共用同一套代码（Upsert 时 id 相同即更新）。
 */
fun newId(): String = UUID.randomUUID().toString()

/**
 * 书籍。表名 books。
 *
 * 「大概位置」被拆成 5 个结构化字段而不是一个长文本，目的是让用户按提示
 * 逐项填写、下次找书时信息不缺失；同时保留 [locationNote] 承接自由描述。
 * 产品明确不追求精确定位导航，所以这些字段都允许为空。
 *
 * @param status 阅读状态，取值见 [bookStatuses]（想找/已找到/在读/读完/暂时放弃）
 * @param currentPage 当前阅读页，可为空（未开始阅读时不必填）
 * @param totalPages 总页数，可为空（不强制用户先查页数，否则录书门槛太高）
 * @param coverUri 封面图片路径。存的是应用私有目录下的绝对路径，不是 content:// 临时授权
 * @param createdAt 创建时间（UTC 毫秒），更新时保留原值
 * @param updatedAt 更新时间（UTC 毫秒），书墙按此倒序排列
 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String = newId(), val title: String = "", val author: String = "",
    val isbn: String = "", val publisher: String = "", val publishedDate: String = "", val description: String = "",
    val libraryName: String = "", val floor: String = "", val categoryArea: String = "",
    val shelfHint: String = "", val locationNote: String = "", val status: String = "想找",
    val currentPage: Int? = null, val totalPages: Int? = null, val coverUri: String? = null,
    val note: String = "", val createdAt: Long = 0, val updatedAt: Long = 0
)

/**
 * 卡片笔记。表名 cards。
 *
 * [bookId] 的外键策略是 SET_NULL：删掉一本书时，这本书的卡片会保留但解除关联，
 * 因为「读完一本书留下的想法」本身有独立价值，不该跟着书一起消失。
 * 给 bookId 建索引是因为详情页与筛选都要按它查询。
 */
@Entity(tableName = "cards", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("bookId")])
data class NoteCardEntity(@PrimaryKey val id: String = newId(), val title: String = "",
    val content: String = "", val bookId: String? = null, val createdAt: Long = 0, val updatedAt: Long = 0)

/** 标签。name 建唯一索引，保证「阅读」这个标签全局只有一行，避免筛选列表出现重复项。 */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey val id: String = newId(), val name: String)

/**
 * 卡片与标签的多对多关联表。
 *
 * 主键是 (noteId, tagId) 复合主键，天然防止同一张卡片重复挂同一个标签。
 * 两个外键都是 CASCADE：删除卡片会自动清掉它的标签关联，删除标签同理，
 * 因此不需要在业务代码里手工清理关联，不会留下悬挂行。
 * 额外给 tagId 建索引是为了「按标签筛选卡片」这个反向查询。
 */
@Entity(tableName = "note_tags", primaryKeys = ["noteId", "tagId"], foreignKeys = [
    ForeignKey(entity = NoteCardEntity::class, parentColumns = ["id"], childColumns = ["noteId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TagEntity::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("tagId")])
data class NoteTagCrossRef(val noteId: String, val tagId: String)

/**
 * 卡片 + 标签的聚合读模型（不是表）。
 *
 * @Embedded 把 card 的列平铺进来，@Relation 通过 Junction 走 note_tags 把标签查出来，
 * 于是「查出所有卡片及其标签」只需要一条查询，UI 不必对每张卡片再查一次
 * （避免 N+1 查询）。这是 DAO 中 cards() 标注 @Transaction 的原因。
 */
data class NoteWithTags(@Embedded val card: NoteCardEntity,
    @Relation(parentColumn = "id", entityColumn = "id", associateBy = Junction(NoteTagCrossRef::class,
        parentColumn = "noteId", entityColumn = "tagId")) val tags: List<TagEntity>)

/**
 * 今日计划。表名 plans。
 *
 * [type] 存放模板名（如「自习一定分钟」），用于回显用户当初选的是哪个模板；
 * [targetValue] 与 [unit] 服务「目标数量」展示（如 25 分钟、20 页）。
 * date 建索引是因为「今天」页每天只查当天数据。
 */
@Entity(tableName = "plans", indices = [Index("date")])
data class DailyPlanEntity(@PrimaryKey val id: String = newId(), val date: String, val type: String = "自由自习",
    val title: String = "", val targetValue: Int? = null, val unit: String = "次", val isCompleted: Boolean = false,
    val completedAt: Long? = null, val createdAt: Long = 0)

/**
 * 已完成的专注流水。表名 sessions。
 *
 * [durationMillis] 是权威时长，[durationMinutes] 只是为方便展示而冗余的派生值
 * （整除 60_000 得到）。这样做是因为需求要求展示分钟，但若只存分钟，
 * 多次短时专注的分钟数会因截断而系统性偏少。
 *
 * [pausedDuration] 记录累计暂停时长，供回顾时参考，但不计入专注时长。
 * 书籍外键同样是 SET_NULL：删书不影响历史专注记录。
 */
@Entity(tableName = "sessions", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"],
    childColumns = ["bookId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("bookId")])
data class FocusSessionEntity(@PrimaryKey val id: String = newId(), val activityType: String, val bookId: String? = null,
    val startedAt: Long, val endedAt: Long, val pausedDuration: Long = 0, val durationMinutes: Long = 0,
    val durationMillis: Long = 0, val startPage: Int? = null, val endPage: Int? = null, val reflection: String = "")

/**
 * 周目标。表名 goals。
 *
 * weekStartDate 建唯一索引是刻意的：一周只能有一条目标，保存时用
 * 「查出旧记录的 id 再覆盖」的方式实现 Upsert（见 LibraryRepository.saveGoal），
 * 由数据库约束兜底，防止并发或误操作产生两条目标。
 */
@Entity(tableName = "goals", indices = [Index(value = ["weekStartDate"], unique = true)])
data class WeeklyGoalEntity(@PrimaryKey val id: String = newId(), val weekStartDate: String,
    val targetStudyCount: Int = 3, val targetFocusMinutes: Int = 120, val targetReadingPages: Int = 50, val targetCardCount: Int = 3)

/**
 * 周回顾。表名 reviews。一周一条，索引策略同 goals。
 *
 * [localSummary] 是无需 AI 也能生成的本地统计摘要，永远有值；
 * [aiSummary] 只有用户主动调用 AI 并确认保存后才有值。
 * 两者分开存是为了保证「AI 不可用时基础回顾照常可看」。
 */
@Entity(tableName = "reviews", indices = [Index(value = ["weekStartDate"], unique = true)])
data class WeeklyReviewEntity(@PrimaryKey val id: String = newId(), val weekStartDate: String,
    val localSummary: String = "", val aiSummary: String? = null, val createdAt: Long = 0)

/**
 * 活动流水。表名 events。这是周统计与「今天留下的记录」的唯一数据源。
 *
 * 关键设计：sourceId 刻意【不建外键】。
 * 它指向产生该事件的书籍/卡片/计划，但删除源对象时事件必须保留 ——
 * 否则删掉一本书就会让过去的活跃天数、阅读页数凭空缩水，
 * 等于篡改了用户的历史。因此这里只存字符串，不建立引用完整性约束。
 *
 * [pages] 与 [focusMillis] 在写入时就把当时的事实固化下来，
 * 之后即使源对象被修改或删除，历史统计也不会变化。
 */
@Entity(tableName = "events", indices = [Index("date"), Index("sourceId")])
data class ActivityEventEntity(@PrimaryKey val id: String = newId(), val date: String, val type: String,
    val sourceId: String, val title: String, val createdAt: Long, val pages: Int = 0, val focusMillis: Long = 0)

/**
 * 进行中的专注（全局唯一，故表名单数 active_focus）。表名 active_focus。
 *
 * 用 [singleton] 固定为 1 的单行表来表达「同一时刻只允许一次专注在进行」，
 * 让数据库层直接承担该约束，而不是只靠 UI 禁用按钮。
 *
 * [clock] 用 @Embedded(prefix = "clock_") 把 FocusClock 的 8 个字段
 * 平铺成 clock_running、clock_segmentWall 等列。这样计时状态与业务字段
 * 在同一行内一起原子读写，不需要额外的一张表或一次 JOIN。
 *
 * [awaitingSummary] 表示用户已点「结束」、正处于填写总结的界面；
 * 此时计时已暂停，但尚未生成 session 记录。
 * [recoveredAfterReboot] 用于提示用户「设备重启过，请核对本次时长」。
 */
@Entity(tableName = "active_focus", foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"],
    childColumns = ["bookId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("bookId")])
data class ActiveFocusEntity(@PrimaryKey val singleton: Int = 1, val id: String = newId(), val activityType: String,
    val bookId: String? = null, val startedAt: Long, val startPage: Int? = null, val awaitingSummary: Boolean = false,
    val recoveredAfterReboot: Boolean = false, val zoneId: String,
    @Embedded(prefix = "clock_") val clock: FocusClock)

/**
 * 专注的时间片段，按本地自然日切分后逐条落库。表名 focus_segments。
 *
 * 每次暂停（或结束）都会把「自上次结算以来的这一段」按天拆开写入，
 * 于是跨零点的专注天然分裂成两条记录，分别归属两天、可能分属两周。
 * 相比事后用 session 起止时间去推算，这样做的好处是：
 * 即使之后时区发生变化，已有的历史归属也不会被重新解释。
 */
@Entity(tableName = "focus_segments", indices = [Index("focusId")])
data class FocusSegmentEntity(@PrimaryKey val id: String = newId(), val focusId: String, val date: String, val durationMillis: Long)

package com.guanjian.app.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.guanjian.app.domain.FocusClock
import com.guanjian.app.domain.splitInterval
import com.guanjian.app.domain.weekStart
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 业务代码获取「当前时间」的唯一途径。
 *
 * 抽成接口而不是直接调用 System.currentTimeMillis()，是为了让所有与时间相关的
 * 逻辑都能被单测精确控制：测试里用一个可手动推进的假时钟（见 LibraryRepositoryTest.FakeClock），
 * 就能稳定复现跨零点、跨周、跨设备重启这些平时难以触发的场景。
 *
 * 之所以同时暴露三种时间，是因为它们各有不可替代的用途，见 [FocusClock] 的说明。
 */
interface LibraryClock {
    /** 墙钟毫秒（UTC），用于生成日期、记录创建/更新时间。 */
    fun wallMillis(): Long
    /** 单调时钟毫秒（自本次开机起算），用于计算时长，不受用户改系统时间影响。 */
    fun elapsedMillis(): Long
    /** 开机次数，用于判断一段计时是否跨越了设备重启。 */
    fun bootCount(): Int
    /** 当前时区，用于判定「本地自然日」的边界。 */
    fun zone(): ZoneId
    /** 当前本地日期（ISO 字符串）。默认实现由墙钟 + 时区推导，测试可覆盖。 */
    fun date(): String = Instant.ofEpochMilli(wallMillis()).atZone(zone()).toLocalDate().toString()
}

/**
 * 生产环境的时钟实现：直接读取系统时间。
 *
 * BOOT_COUNT 从系统设置读取，它是系统维护的开机计数，比自己去存时间戳更可靠。
 * 万一读不到（某些定制系统限制），回退到 0 —— 这只会让跨重启判断退化为
 * 「都用墙钟」，不会导致崩溃。
 */
class AndroidLibraryClock(private val context: Context) : LibraryClock {
    override fun wallMillis() = System.currentTimeMillis()
    override fun elapsedMillis() = SystemClock.elapsedRealtime()
    override fun bootCount() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * 业务仓库：所有写操作的唯一入口。
 *
 * 设计约定（新增功能时请一并遵守）：
 *
 * 1. **每个写方法整体包在 withTransaction 内。** 一次用户操作往往要改多张表
 *    （例如结束专注要写 session、改书页码、写事件、标记计划、清 active_focus），
 *    要么全部成功要么全部不写，否则会留下「时长记了但页码没更新」这类破损状态。
 *
 * 2. **校验失败用 require/requireNotNull 抛出 IllegalArgumentException，
 *    消息是可直接展示给用户的中文。** ViewModel 会捕获它并弹 Snackbar，
 *    因此这里不返回错误码，也不吞异常。抛异常同时自然回滚整个事务。
 *
 * 3. **写业务数据的同时派生「活动流水」。** 周统计只认 events 表，
 *    所以任何应当计入活跃度的操作，都必须同时写一条对应的 event。
 *    这是本类各方法里 event(...) 调用的由来，删除或新增时都要同步考虑。
 *
 * 4. **时间戳统一由 [clock] 提供**，便于测试；时间统一存 UTC 毫秒，
 *    只有「哪一天」用本地日期字符串表达。
 */
class LibraryRepository(private val db: LibraryDatabase, val clock: LibraryClock) {
    private val dao = db.dao()

    // 对外暴露只读的 Flow，UI 层（ViewModel）订阅这些流来构建界面状态
    val books = dao.books()
    val cards = dao.cards()
    val plans = dao.plans()
    val sessions = dao.sessions()
    val events = dao.events()
    val goals = dao.goals()
    val reviews = dao.reviews()
    val activeFocus = dao.activeFocus()

    /**
     * 保存书籍（新增与编辑共用）。
     *
     * 时间戳处理：createdAt 只在首次创建时落定，之后每次保存都保持不变；
     * updatedAt 每次都刷新。书墙按 updatedAt 排序，正是依赖这个约定。
     *
     * 事件派生规则：
     * - 首次保存（old == null）→ 记一条 BOOK_ADDED，构成一次有效记录。
     * - 再次保存（编辑）→ 交由 [recordProgress] 判断页码是否变化。
     *   仅改书名、位置、状态不会产生事件，避免「改个错别字就算学习了一天」。
     *
     * @return 书籍 id
     */
    suspend fun saveBook(book: BookEntity): String = db.withTransaction {
        require(book.title.isNotBlank()) { "请填写书名" }
        validatePages(book.currentPage, book.totalPages)
        val old = dao.book(book.id)
        val now = clock.wallMillis()
        val saved = book.copy(title = book.title.trim(), createdAt = old?.createdAt ?: now, updatedAt = now)
        dao.putBook(saved)
        if (old == null) event("BOOK_ADDED", book.id, saved.title)
        else recordProgress(saved, old.currentPage ?: 0)
        book.id
    }

    /**
     * 删除书籍。
     *
     * 只删 books 一行即可，其余全靠外键策略自动处理：
     * - 该书的卡片 bookId 被置为 NULL（卡片保留）
     * - 该书关联的进行中/已结束专注 bookId 被置为 NULL（历史专注保留）
     * - events 表不含外键，历史统计完全不受影响
     */
    suspend fun deleteBook(id: String) = db.withTransaction { dao.deleteBook(id) }

    /**
     * 保存卡片（新增与编辑共用），并重建它的标签关联。
     *
     * 标题留空时自动截取正文前 24 个字符作为标题，让用户只写正文也能在列表里
     * 看到有意义的标识；正文会 trim，避免首尾空白影响列表排版。
     *
     * 标签处理顺序是「先清空旧关联，再按去重后的标签名逐个复用或新建」。
     * 复用（先 tag(name) 查询）而非无脑 insert 是必需的：tags.name 有唯一索引，
     * 重复插入会直接违反约束报错。
     *
     * 只有新增卡片才写 CARD_ADDED 事件；编辑既有卡片不重复计数。
     */
    suspend fun saveCard(card: NoteCardEntity, tags: List<String> = emptyList()): String = db.withTransaction {
        require(card.content.isNotBlank()) { "请写下卡片内容" }
        // 防止关联到一本已被删除的书——那会让列表展示出现悬空的关联
        require(card.bookId == null || dao.book(card.bookId) != null) { "关联书籍已不存在" }
        val old = dao.card(card.id)
        val saved = card.copy(title = card.title.trim().ifBlank { card.content.trim().take(24) },
            content = card.content.trim(), createdAt = old?.createdAt ?: clock.wallMillis(), updatedAt = clock.wallMillis())
        dao.putCard(saved)
        dao.clearNoteTags(card.id)
        tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { name ->
            val tag = dao.tag(name) ?: TagEntity(name = name).also { dao.insertTag(it) }
            dao.insertNoteTag(NoteTagCrossRef(card.id, tag.id))
        }
        if (old == null) event("CARD_ADDED", card.id, saved.title)
        card.id
    }

    /** 删除卡片。note_tags 的关联由外键 CASCADE 自动清理。 */
    suspend fun deleteCard(id: String) = db.withTransaction { dao.deleteCard(id) }

    /**
     * 保存今日计划（新增与编辑共用）。
     *
     * 注意 putPlan 时强制沿用旧的 isCompleted / completedAt，再单独调用
     * [setPlanCompletedInternal] 同步状态。这样做是为了让「完成状态」只有一条
     * 变更路径：完成/未完成的切换集中在一处，事件的新增与撤销才不会漏。
     *
     * 还会先 LocalDate.parse(plan.date) 做一次格式校验：日期字符串是统计分组的键，
     * 一旦写入非法值，后续所有按周筛选都会静默出错，因此宁可在入口就拒绝。
     */
    suspend fun savePlan(plan: DailyPlanEntity): String = db.withTransaction {
        require(plan.title.isNotBlank()) { "请填写计划标题" }
        require(plan.targetValue == null || plan.targetValue >= 0) { "目标数量不能为负数" }
        LocalDate.parse(plan.date)
        val old = dao.plan(plan.id)
        dao.putPlan(plan.copy(title = plan.title.trim(), createdAt = old?.createdAt ?: clock.wallMillis(),
            isCompleted = old?.isCompleted ?: false, completedAt = old?.completedAt))
        setPlanCompletedInternal(plan.id, plan.isCompleted)
        plan.id
    }

    /** 单独切换计划的完成状态（今日页勾选框用）。 */
    suspend fun setPlanCompleted(id: String, completed: Boolean) = db.withTransaction { setPlanCompletedInternal(id, completed) }

    /**
     * 完成状态变更的内部实现，保证「状态」与「统计事件」始终一致。
     *
     * 第一行的提前返回很重要：状态没变化就直接结束。否则重复勾选会反复写
     * PLAN_COMPLETED 事件，周统计里的「完成计划数」就会虚高。
     *
     * 取消完成时调用 undoPlanEvent 撤回事件，而不是记一条负数事件 ——
     * 用户的意图是「刚才点错了」，历史不该留下这次误操作。
     */
    private suspend fun setPlanCompletedInternal(id: String, completed: Boolean) {
        val old = requireNotNull(dao.plan(id)) { "计划已不存在" }
        if (old.isCompleted == completed) return
        dao.putPlan(old.copy(isCompleted = completed, completedAt = if (completed) clock.wallMillis() else null))
        if (completed) event("PLAN_COMPLETED", id, old.title) else dao.undoPlanEvent(id)
    }

    /** 删除计划。已产生的 PLAN_COMPLETED 事件按设计保留，不影响历史统计。 */
    suspend fun deletePlan(id: String) = db.withTransaction { dao.deletePlan(id) }

    /**
     * 保存周目标，一周一条。
     *
     * 先 weekStart 归一化，再查该周是否已有记录：有就沿用它的 id 覆盖，
     * 没有才用传入的 id 新建。这样表上的 weekStartDate 唯一索引不会被违反，
     * 也使「编辑目标」不需要区分新增/修改两条路径。
     */
    suspend fun saveGoal(goal: WeeklyGoalEntity) = db.withTransaction {
        require(listOf(goal.targetStudyCount, goal.targetFocusMinutes, goal.targetReadingPages, goal.targetCardCount).all { it >= 0 }) { "周目标不能为负数" }
        val week = weekStart(LocalDate.parse(goal.weekStartDate)).toString()
        dao.putGoal(goal.copy(id = dao.goal(week)?.id ?: goal.id, weekStartDate = week))
    }

    /**
     * 保存周回顾，一周一条，策略同 [saveGoal]。
     *
     * createdAt 沿用旧值：它代表这周回顾最早被创建的时间，
     * 之后无论是重新生成 AI 摘要还是编辑本地摘要，都不应改变首次生成时间。
     */
    suspend fun saveReview(review: WeeklyReviewEntity) = db.withTransaction {
        val week = weekStart(LocalDate.parse(review.weekStartDate)).toString()
        val old = dao.review(week)
        dao.putReview(review.copy(id = old?.id ?: review.id, weekStartDate = week, createdAt = old?.createdAt ?: clock.wallMillis()))
    }

    /**
     * 开始一次专注。
     *
     * 若已有进行中的专注，直接返回它而不新建 —— 单行表约束在业务层的体现，
     * 保证从书籍详情页和今天页两个入口进入时不会产生两个计时器。
     *
     * startPage 取书籍当前的页码并存进 active_focus，这样结束专注时可以
     * 按「本次起始页 → 结束页」计算真实阅读增量，而不是按书的旧页码。
     *
     * 同时保存 zoneId：计时可能跨时区旅行，结束后要按开始时的时区去切分自然日。
     *
     * @return 本次专注的 id
     */
    suspend fun startFocus(activityType: String, bookId: String? = null): String = db.withTransaction {
        dao.active()?.let { return@withTransaction it.id }
        require(activityType.isNotBlank()) { "请选择专注类型" }
        val book = bookId?.let { requireNotNull(dao.book(it)) { "关联书籍已不存在" } }
        val focus = ActiveFocusEntity(activityType = activityType, bookId = bookId,
            startedAt = clock.wallMillis(), startPage = book?.currentPage, zoneId = clock.zone().id,
            clock = FocusClock.start(clock.wallMillis(), clock.elapsedMillis(), clock.bootCount()))
        dao.putActive(focus)
        focus.id
    }

    /**
     * 把当前专注完整保存后，立即开始新任务。
     * 外层事务保证“结束旧任务”和“创建新任务”要么一起成功，要么一起回滚。
     * 切换时没有新的结束页码，因此只保存已计时长，不猜测阅读进度。
     */
    suspend fun switchFocus(activityType: String, bookId: String?): String = db.withTransaction {
        require(activityType.isNotBlank()) { "请选择专注类型" }
        bookId?.let { requireNotNull(dao.book(it)) { "关联书籍已不存在" } }
        val current = dao.active()
        if (current == null) return@withTransaction startFocus(activityType, bookId)
        if (current.activityType == activityType && current.bookId == bookId) return@withTransaction current.id
        finishFocus(current.id, reflection = "切换任务时自动保存")
        startFocus(activityType, bookId)
    }

    /** 用户主动暂停：结算当前段并落库，但不进入结束流程。 */
    suspend fun pauseFocus() = db.withTransaction { pauseInternal(false) }

    /**
     * 用户点击「结束」：先暂停计时，并标记 awaitingSummary，
     * 让界面切换到填写总结的状态。此时还没有生成 session 记录。
     */
    suspend fun prepareFinish() = db.withTransaction { pauseInternal(true) }

    /**
     * 暂停的公共实现（同时服务「暂停」与「结束」两个入口）。
     *
     * 关键动作是把「自上次结算以来的一段」按本地自然日切开，逐条写入
     * focus_segments。因为每次暂停都结算一次，长时间跨夜专注也不会丢时间。
     *
     * recoveredAfterReboot 的判定：若当前开机次数与该段记录的开机次数不同，
     * 说明设备在专注期间重启过，时长是靠墙钟推算的，可能不准，
     * 于是打上标记让界面提示用户核对。用 `||` 保证一旦标记就不会被后续暂停清除。
     *
     * awaitingSummary 同样用 `||` 累积，避免「先暂停再结束」时丢失结束意图。
     */
    private suspend fun pauseInternal(summary: Boolean) {
        val active = dao.active() ?: return
        val wall = clock.wallMillis()
        val elapsed = clock.elapsedMillis()
        val boot = clock.bootCount()
        val duration = active.clock.segmentDuration(wall, elapsed, boot)
        // 按开始时的时区切分，保证跨时区旅行时历史归属不受影响
        splitInterval(active.clock.segmentWall, duration, ZoneId.of(active.zoneId)).forEach { (date, millis) ->
            dao.insertSegment(FocusSegmentEntity(focusId = active.id, date = date, durationMillis = millis))
        }
        dao.putActive(active.copy(clock = active.clock.pause(wall, elapsed, boot),
            recoveredAfterReboot = active.recoveredAfterReboot || boot != active.clock.bootCount,
            awaitingSummary = active.awaitingSummary || summary))
    }

    /**
     * 继续专注。
     *
     * 会顺带把 awaitingSummary 清掉：用户点了「还想继续，返回计时」，
     * 说明他撤回了结束意图，此时应回到正常计时状态。
     */
    suspend fun resumeFocus() = db.withTransaction {
        dao.active()?.let { active ->
            dao.putActive(active.copy(awaitingSummary = false, clock = active.clock.resume(clock.wallMillis(), clock.elapsedMillis(), clock.bootCount())))
        }
    }

    /**
     * 结束专注，生成一条完整的专注流水，并联动更新书籍进度、周统计与计划。
     *
     * 幂等保护（第一行）：若该 id 的 session 已存在，说明这次专注已经收尾，
     * 直接返回。UI 上保存按钮可能被连点，重复执行会导致时长、页数被双倍计入。
     *
     * 校验顺序安排在 pauseInternal 之前：所有 require 一旦失败就抛异常，
     * 事务整体回滚，于是 active_focus 不会被暂停、也不会写任何片段 ——
     * 用户填错页码时能原样回到计时界面继续。
     *
     * @param endPage 结束页，可为空
     * @param startPage 起始页，传 null 时用开始专注时记录的 startPage
     * @param completedPlanIds 本次专注顺带完成的计划 id
     */
    suspend fun finishFocus(id: String, endPage: Int? = null, reflection: String = "",
        completedPlanIds: List<String> = emptyList(), startPage: Int? = null): String = db.withTransaction {
        if (dao.session(id) != null) return@withTransaction id
        val initial = requireNotNull(dao.active()) { "没有进行中的专注" }
        require(initial.id == id) { "这次专注已结束" }
        val book = initial.bookId?.let { dao.book(it) }
        val start = startPage ?: initial.startPage
        require(start == null || start >= 0) { "起始页不能为负数" }
        require(endPage == null || endPage >= (start ?: 0)) { "结束页不能小于起始页" }
        validatePages(endPage, book?.totalPages)
        require(book?.totalPages == null || start == null || start <= book.totalPages) { "起始页不能超过总页数" }
        // 先校验所有关联计划都存在，避免「时长已记、计划却标不上」的半成品状态
        completedPlanIds.distinct().forEach { requireNotNull(dao.plan(it)) { "关联计划已不存在" } }
        pauseInternal(true)
        val active = requireNotNull(dao.active())
        val duration = active.clock.accumulated
        dao.insertSession(FocusSessionEntity(id = id, activityType = active.activityType, bookId = active.bookId,
            startedAt = active.startedAt, endedAt = active.clock.pausedAtWall ?: clock.wallMillis(), pausedDuration = active.clock.pausedDuration,
            durationMinutes = duration / 60_000, durationMillis = duration, startPage = start, endPage = endPage, reflection = reflection.trim()))
        // 只有真正往前读了才更新书籍进度；页码未变或倒退时保持书上的记录不动，
        // 避免用户只是结束计时就把书籍进度往回改。
        if (book != null && endPage != null && endPage > (book.currentPage ?: 0)) {
            val updated = book.copy(currentPage = endPage, status = if (endPage == book.totalPages) "读完" else "在读", updatedAt = clock.wallMillis())
            dao.putBook(updated)
            // 阅读增量以「本次专注的起始页」为基准，而不是书籍原有的当前页。
            // 例如书上记着 0 页、本次从第 50 页读到第 60 页，应记 10 页而非 60 页。
            recordProgress(updated, start ?: (book.currentPage ?: 0))
        }
        // 优先按 focus_segments 的真实日期分布生成事件，于是跨零点的专注
        // 会分别在两天各记一条 FOCUS_COMPLETED，两天都算活跃。
        // 没有片段（理论上不会发生）时退化为单条事件，保证记录不丢。
        val byDay = dao.segments(id).groupBy { it.date }.mapValues { (_, rows) -> rows.sumOf { it.durationMillis } }
        if (byDay.isEmpty()) event("FOCUS_COMPLETED", id, active.activityType)
        else byDay.forEach { (date, millis) -> event("FOCUS_COMPLETED", id, active.activityType, date = date, focusMillis = millis) }
        completedPlanIds.distinct().forEach { setPlanCompletedInternal(it, true) }
        dao.clearActive()
        id
    }

    /**
     * 页码合法性校验。
     *
     * 三条都刻意允许 null：本应用不强制用户填写页码，
     * 因此「没填」永远合法，「填了才校验」。
     * totalPages 要求 > 0 而非 >= 0，因为 0 页的书会导致进度分母为零。
     */
    private fun validatePages(page: Int?, total: Int?) {
        require(page == null || page >= 0) { "当前页不能为负数" }
        require(total == null || total > 0) { "总页数必须大于零" }
        require(page == null || total == null || page <= total) { "当前页不能超过总页数" }
    }

    /**
     * 根据阅读位置的变化派生 PROGRESS 事件。
     *
     * 两种变化都算一次有效记录：向前读（记实际页数）与向后校正（记 0 页）。
     * 这样处理的原因：
     * - 用户把记错的页码改回来，也是今天真实发生过的操作，应当计入活跃天数；
     * - 但这次操作没有产生真实阅读量，所以页数记 0，不能凭空造出阅读页数。
     *
     * 页码完全没变时直接返回，避免「改书名」被误判成学习行为。
     *
     * @param previous 变化前的页码
     */
    private suspend fun recordProgress(book: BookEntity, previous: Int) {
        val current = book.currentPage ?: 0
        if (current == previous) return
        event("PROGRESS", book.id, book.title, pages = (current - previous).coerceAtLeast(0))
    }

    /**
     * 写一条活动流水的唯一出口。
     *
     * 所有事件都经由这里创建，便于统一填充 createdAt 与默认日期。
     * 默认 date 取当前本地日期；跨日专注会显式传入 date 覆盖它。
     */
    private suspend fun event(type: String, sourceId: String, title: String, pages: Int = 0,
        date: String = clock.date(), focusMillis: Long = 0) = dao.insertEvent(ActivityEventEntity(
            date = date, type = type, sourceId = sourceId, title = title, createdAt = clock.wallMillis(), pages = pages, focusMillis = focusMillis))
}

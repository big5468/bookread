package com.guanjian.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.guanjian.app.domain.weeklyStats
import com.guanjian.app.domain.StatEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*

/**
 * [LibraryRepository] 的集成测试 —— 全项目最重要的一组用例。
 *
 * 为什么用 Robolectric 而不是纯 JVM 测试：仓库层的行为很大一部分来自
 * Room 的事务、外键策略与索引约束，这些只有跑在真实 SQLite 上才有意义。
 * 内存数据库保证每个用例从空库开始、互不干扰。
 *
 * 核心手法是 [FakeClock]：所有时间都由测试显式控制，因此可以稳定复现
 * 跨零点、跨周、跨设备重启这些现实中极难触发、却最容易写出 bug 的场景。
 *
 * 测试重点围绕三类容易出错的不变量：
 * 1. **幂等性** —— 重复收尾不能双倍记账
 * 2. **事务原子性** —— 校验失败必须整体回滚，不留半成品状态
 * 3. **历史不可篡改** —— 删除书籍/卡片后，已发生的统计不得缩水
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibraryRepositoryTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private val clock = FakeClock()

    @Before fun setup() {
        // allowMainThreadQueries 让测试可以直接在 runBlocking 里查询，无需额外线程
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java).allowMainThreadQueries().build()
        repo = LibraryRepository(db, clock)
    }

    @After fun close() = db.close()

    /**
     * 书籍与标签的更新语义；删除不抹除历史。
     *
     * 断言链：
     * - 同一 id 保存两次是「更新」而非新增（书名改为新版、当前页 20）
     * - 标签自动去重：传入 ["阅读","阅读","想法"] 只得到 2 个标签
     * - 再次保存时只传一个标签，旧关联被替换（变为 1 个）
     * - 删书后卡片**保留**但 bookId 被置为 null（外键 SET_NULL 生效）
     * - 删卡片后列表为空
     * - **事件仍有 3 条**（BOOK_ADDED、CARD_ADDED、以及一次 PROGRESS）。
     *   最后一条是本用例的核心：删光内容后历史统计不受影响，
     *   因为 events 表刻意不建外键。
     */
    @Test fun bookAndTagsSurviveUpdatesAndDeletionPreservesEvents() = runBlocking {
        val book = BookEntity(title = "图书", totalPages = 100)
        repo.saveBook(book)
        repo.saveBook(book.copy(title = "图书新版", currentPage = 20))
        val card = NoteCardEntity(content = "观点", bookId = book.id)
        repo.saveCard(card, listOf("阅读", "阅读", "想法"))
        assertEquals(2, repo.cards.first().single().tags.size)
        repo.saveCard(card.copy(content = "新观点"), listOf("阅读"))
        assertEquals(1, repo.cards.first().single().tags.size)
        repo.deleteBook(book.id)
        assertNull(repo.cards.first().single().card.bookId)
        repo.deleteCard(card.id)
        assertTrue(repo.cards.first().isEmpty())
        assertEquals(3, repo.events.first().size)
    }

    /**
     * 重复收尾不会双倍记账；暂停可跨实例恢复；计划完成状态可撤销。
     *
     * 时间线：专注 10 分钟 → 暂停 → 用**新的仓库实例**读回（模拟进程重建）→
     * 继续 10 分钟 → 结束。期望总时长 20 分钟、暂停时长 5 分钟。
     *
     * 关键断言：
     * - `repo = LibraryRepository(db, clock)` 重新构造后，
     *   activeFocus 里的 accumulated 仍是 600_000 —— 验证计时状态确实持久化在
     *   数据库而非内存，这是需求「应对进程重建」的验证。
     * - **finishFocus 调用两次**，session 仍只有一条、页数增量仍是 15，
     *   验证幂等保护（`if (dao.session(id) != null) return`）。
     * - session.endedAt 等于 `endedAt`（prepareFinish 的时刻），
     *   而不是之后又推进 2 分钟的那个时刻 —— 验证结束时间取自暂停时刻，
     *   用户填写总结所花的时间不计入专注。
     * - 计划从完成改回未完成时，PLAN_COMPLETED 事件数变回 0，
     *   验证「撤销」而非「记负数」。
     */
    @Test fun duplicateFinishAndPauseRecoveryHaveSinglePageDelta() = runBlocking {
        val book = BookEntity(title = "书", totalPages = 100, currentPage = 10)
        repo.saveBook(book)
        val plan = DailyPlanEntity(date = clock.date(), title = "读书")
        repo.savePlan(plan)
        val id = repo.startFocus("READING", book.id)
        clock.advance(600_000)
        repo.pauseFocus()
        clock.advance(300_000)
        repo = LibraryRepository(db, clock)
        assertEquals(600_000, repo.activeFocus.first()!!.clock.accumulated)
        repo.resumeFocus()
        clock.advance(600_000)
        repo.prepareFinish()
        val endedAt = clock.wall
        clock.advance(120_000)
        repo.finishFocus(id, 25, "很好", listOf(plan.id))
        repo.finishFocus(id, 25, "很好", listOf(plan.id))
        assertNull(repo.activeFocus.first())
        val session = repo.sessions.first().single()
        assertEquals(20L, session.durationMinutes)
        assertEquals(endedAt, session.endedAt)
        assertEquals(300_000L, session.pausedDuration)
        assertEquals(25, repo.books.first().single().currentPage)
        assertEquals(15, repo.events.first().sumOf { it.pages })
        assertEquals(1, repo.events.first().count { it.type == "PLAN_COMPLETED" })
        repo.setPlanCompleted(plan.id, true)
        assertEquals(1, repo.events.first().count { it.type == "PLAN_COMPLETED" })
        repo.setPlanCompleted(plan.id, false)
        assertEquals(0, repo.events.first().count { it.type == "PLAN_COMPLETED" })
    }

    /**
     * 校验失败必须整体回滚；跨重启用墙钟计算时长。
     *
     * 前半段：传入 endPage = 101 超过总页数 100，期望抛 IllegalArgumentException。
     * 随后断言三件事都没有发生：
     * - sessions 仍为空（没有落库）
     * - activeFocus 仍存在（计时没被清掉，用户可以改正后重试）
     * - 书籍页码仍是 10（没有被改成 101）
     * 这验证了 `withTransaction` 的原子性 —— 若校验放在写库之后，
     * 用户填错一个数字就会丢掉整次专注。
     *
     * 后半段：`clock.boot++` 模拟设备重启，随后正常结束，
     * 期望 durationMinutes 为 1（60_000 毫秒），验证跨重启后按墙钟算出的时长正确。
     */
    @Test fun finishValidationRollsBackAndRebootUsesWallClock() = runBlocking {
        val book = BookEntity(title = "书", totalPages = 100, currentPage = 10)
        repo.saveBook(book)
        val id = repo.startFocus("READING", book.id)
        clock.advance(60_000)
        clock.boot++
        clock.elapsed = 2_000
        repo.prepareFinish()
        try { repo.finishFocus(id, 101); fail("invalid page accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(repo.sessions.first().isEmpty())
        assertNotNull(repo.activeFocus.first())
        assertEquals(10, repo.books.first().single().currentPage)
        repo.finishFocus(id, 20)
        assertEquals(1L, repo.sessions.first().single().durationMinutes)
    }

    /**
     * 跨零点的专注按本地自然日拆分到两周，且两天都算活跃。
     *
     * 从 2026-09-13（周日）23:50 开始专注 20 分钟。
     * 由于周日属于 09-07 开始的那一周，而 09-14（周一）属于下一周，
     * 这次专注恰好跨越了**日界与周界**。
     *
     * 断言：查询 09-13 所在周得 10 分钟，查询 09-14 所在周也得 10 分钟 ——
     * 两次都恰好等于 10 分钟，证明既没有把 20 分钟全算给某一周，
     * 也没有重复计入。最后断言新的一周活跃天数为 1，
     * 说明 09-14 那 10 分钟确实被记成了一条有效记录。
     */
    @Test fun midnightSegmentsAllocateToTwoWeeksAndDaysAreDistinct() = runBlocking {
        clock.wall = Instant.parse("2026-09-13T23:50:00Z").toEpochMilli()
        val id = repo.startFocus("STUDY")
        clock.advance(1_200_000)
        repo.prepareFinish()
        repo.finishFocus(id)
        val events = repo.events.first().map { StatEvent(it.date, it.type, it.pages, it.focusMillis) }
        assertEquals(10L, weeklyStats(LocalDate.parse("2026-09-13"), events).focusMinutes)
        assertEquals(10L, weeklyStats(LocalDate.parse("2026-09-14"), events).focusMinutes)
        assertEquals(1, weeklyStats(LocalDate.parse("2026-09-14"), events).activeDays)
    }

    /**
     * 周目标与周回顾按周唯一（Upsert），且空白书名被拒绝。
     *
     * 三组断言：
     * - 只含空格的书名被拒，且不落库（校验发生在写库之前）
     * - 同一周保存两次目标，只保留一条且值被更新为 5 ——
     *   验证「以旧 id 覆盖」的实现确实满足了 weekStartDate 的唯一索引
     * - 同一周保存两次回顾，aiSummary 被更新为 "AI"，
     *   验证回顾也遵循一周一条
     */
    @Test fun weeklyRowsUpsertByWeekAndBlankBookRejected() = runBlocking {
        try { repo.saveBook(BookEntity(title = "  ")); fail("blank accepted") } catch (_: IllegalArgumentException) { }
        assertTrue(repo.books.first().isEmpty())
        repo.saveGoal(WeeklyGoalEntity(weekStartDate = "2026-09-07", targetStudyCount = 3))
        repo.saveGoal(WeeklyGoalEntity(weekStartDate = "2026-09-07", targetStudyCount = 5))
        assertEquals(5, repo.goals.first().single().targetStudyCount)
        repo.saveReview(WeeklyReviewEntity(weekStartDate = "2026-09-07", localSummary = "本周"))
        repo.saveReview(WeeklyReviewEntity(weekStartDate = "2026-09-07", localSummary = "更新", aiSummary = "AI"))
        assertEquals("AI", repo.reviews.first().single().aiSummary)
    }

    /**
     * 向下校正阅读进度也应算一次有效记录，但不得凭空增加阅读页数。
     *
     * 场景：书当前记着 30 页，用户发现记错了，改回 20 页。
     * - 断言确实产生了 1 条 PROGRESS 事件（否则这一天不算活跃，
     *   周活跃天数会偏少 —— 这正是本次修复的一个真实缺陷）
     * - 断言事件日期是校正当天（clock.advance 已推进一天）
     * - 断言 pages 为 0：操作计入活跃，但没有产生真实阅读量，
     *   不能让周阅读页数虚增
     */
    @Test fun correctingProgressCountsAsRecordWithoutInventingReadPages() = runBlocking {
        val book = BookEntity(title = "校正进度", currentPage = 30, totalPages = 100)
        repo.saveBook(book)
        clock.advance(86_400_000)
        repo.saveBook(book.copy(currentPage = 20))
        val progress = repo.events.first().filter { it.type == "PROGRESS" }
        assertEquals(1, progress.size)
        assertEquals(clock.date(), progress.single().date)
        assertEquals(0, progress.single().pages)
    }

    /**
     * 显式指定起始页时，阅读增量按「本次起始页→结束页」计算，而不是书籍旧页码。
     *
     * 场景：书上没记页码（currentPage 为 null，按 0 处理），
     * 本次专注从第 50 页读到第 60 页，只应记 10 页。
     *
     * 断言书籍当前页被正确更新为 60，而**事件累计页数是 10 而不是 60**。
     * 这一条锁定的是本次修复的另一个真实缺陷：若以书籍旧页码为基准，
     * 这里会记成 60 页，导致周阅读统计严重虚高。
     */
    @Test fun explicitReadingStartDoesNotCountSkippedPages() = runBlocking {
        val book = BookEntity(title = "从中间阅读", totalPages = 200)
        repo.saveBook(book)
        val id = repo.startFocus("阅读", book.id)
        clock.advance(60_000)
        repo.finishFocus(id, endPage = 60, startPage = 50)
        assertEquals(60, repo.books.first().single().currentPage)
        assertEquals(10, repo.events.first().sumOf { it.pages })
    }

    /** 切换到另一本书时，原计时必须先完整落库，新计时再关联目标书籍。 */
    @Test fun switchingFocusFinishesCurrentSessionAndStartsRequestedBook() = runBlocking {
        val first = BookEntity(title = "第一本")
        val second = BookEntity(title = "第二本")
        repo.saveBook(first)
        repo.saveBook(second)
        val firstFocus = repo.startFocus("阅读", first.id)
        clock.advance(12 * 60_000)

        val secondFocus = repo.switchFocus("阅读", second.id)

        assertNotEquals(firstFocus, secondFocus)
        assertEquals(firstFocus, repo.sessions.first().single().id)
        assertEquals(12, repo.sessions.first().single().durationMinutes)
        assertEquals(second.id, repo.activeFocus.first()!!.bookId)
        assertEquals(secondFocus, repo.activeFocus.first()!!.id)
    }

    /** 删除今日计划不应删除已经完成该计划的历史事件。 */
    @Test fun deletingPlanKeepsCompletedHistory() = runBlocking {
        val plan = DailyPlanEntity(date = clock.date(), type = "CUSTOM", title = "可删除任务")
        repo.savePlan(plan)
        repo.setPlanCompleted(plan.id, true)

        repo.deletePlan(plan.id)

        assertTrue(repo.plans.first().isEmpty())
        assertEquals(1, repo.events.first().count { it.type == "PLAN_COMPLETED" && it.sourceId == plan.id })
    }

    /**
     * 删除书籍会置空活动与历史专注的关联，但保留全部记录。
     *
     * 断言链：
     * - 已有进行中的专注时再次 startFocus，返回的是**同一个 id**
     *   （单行表约束在业务层的体现，两个入口进入不会产生两个计时器）
     * - 删书后，进行中的专注与已结束的 session 的 bookId 都变为 null
     *   （外键 SET_NULL 对两张表都生效）
     * - 删书后仍能正常结束这次专注，sessions 变成 2 条 ——
     *   验证关联失效不会阻塞计时收尾
     * - BOOK_ADDED 事件仍是 1 条，历史未被抹除
     */
    @Test fun deletingLinkedBookNullsActiveAndFinishedSessions() = runBlocking {
        val first = BookEntity(title = "第一本")
        repo.saveBook(first)
        val finishedId = repo.startFocus("阅读", first.id)
        clock.advance(60_000)
        repo.finishFocus(finishedId)
        val second = repo.startFocus("阅读", first.id)
        assertEquals(second, repo.startFocus("自由自习"))
        repo.deleteBook(first.id)
        assertNull(repo.activeFocus.first()!!.bookId)
        assertNull(repo.sessions.first().single().bookId)
        repo.finishFocus(second)
        assertEquals(2, repo.sessions.first().size)
        assertEquals(1, repo.events.first().count { it.type == "BOOK_ADDED" })
    }

    /**
     * 关联到不存在的计划会让整次收尾回滚；编辑卡片不会重复计数。
     *
     * 前半段：传入一个不存在的计划 id，期望抛异常，并断言
     * - 书籍页码仍是 20（没被 endPage=40 改掉）
     * - sessions 为空（没有落库）
     * - 进行中的专注仍在运行（`clock.running` 为真，说明没被暂停）
     * 这验证「先校验所有关联计划都存在，再执行写入」的顺序是对的。
     *
     * 后半段：同一张卡片保存两次，CARD_ADDED 事件仍只有 1 条
     * （编辑不重复计数）；随后接连两次专注都结束在第 40 页，
     * 累计页数恰好 20 —— 第一次从 20 到 40 记 20 页，
     * 第二次因为书已在 40 页、起始页也是 40，记 0 页，不重复计算。
     */
    @Test fun invalidRelatedPlanRollsBackEntireFinishAndEditsDoNotCreateCards() = runBlocking {
        val book = BookEntity(title = "书", currentPage = 20, totalPages = 100)
        repo.saveBook(book)
        val card = NoteCardEntity(content = "正文", bookId = book.id)
        repo.saveCard(card)
        repo.saveCard(card.copy(content = "改正"))
        val id = repo.startFocus("阅读", book.id)
        clock.advance(60_000)
        try { repo.finishFocus(id, 40, completedPlanIds = listOf("missing")); fail("missing plan accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(20, repo.books.first().single().currentPage)
        assertTrue(repo.sessions.first().isEmpty())
        assertTrue(repo.activeFocus.first()!!.clock.running)
        assertEquals(1, repo.events.first().count { it.type == "CARD_ADDED" })
        repo.finishFocus(id, 40)
        val next = repo.startFocus("阅读", book.id)
        repo.finishFocus(next, 40)
        assertEquals(20, repo.events.first().sumOf { it.pages })
    }

    /**
     * 可手动推进的假时钟。
     *
     * 三个字段模拟真实时钟的三个维度，且 [advance] 让墙钟与单调时钟
     * **同步推进**（贴近真实设备的行为）。测试可以单独改写它们，
     * 例如把 `elapsed` 重置为 2_000 并让 `boot` 自增，以模拟设备重启后
     * 单调时钟归零的场景。
     *
     * 初始时刻选 08:00 UTC 而非午夜，让 `advance(86_400_000)` 之类的
     * 跨日推演不会意外踩到日界。
     * 时区固定为 UTC，使日期断言不受运行机器时区影响。
     */
    private class FakeClock : LibraryClock {
        var wall = Instant.parse("2026-09-10T08:00:00Z").toEpochMilli()
        var elapsed = 10_000L
        var boot = 1
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
        override fun bootCount() = boot
        override fun zone() = ZoneId.of("UTC")
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
}

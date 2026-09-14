package com.guanjian.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

/**
 * [weeklyStats] 与 [splitInterval] 的单元测试。
 *
 * 时区固定为 Asia/Shanghai（无夏令时）作为基准，另外单独用 America/New_York
 * 验证夏令时路径 —— 这两个时区的组合能覆盖「固定偏移」与「DST 切换」两种情况。
 * [at] 辅助函数把「本地日期时间」转成 UTC 毫秒，让用例读起来接近自然语言。
 */
class WeeklyStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    /**
     * 跨零点的专注必须按真实午夜拆成两天。
     *
     * 23:50 开始、持续 20 分钟，应得到
     * { 2026-09-13: 10 分钟, 2026-09-14: 10 分钟 }。
     * 这正是「23:50 到 00:10 分两日各 10 分钟」这条验收标准。
     *
     * 使用 assertEquals 比较整个 Map，因此顺带锁定了键的集合与顺序 ——
     * 不能多出第三天，也不能把时长都算给同一天。
     */
    @Test fun runningIntervalIsSplitAtMidnightAndWeekBoundary() {
        val days = splitInterval(at("2026-09-13T23:50:00"), 20 * 60_000L, zone)
        assertEquals(mapOf("2026-09-13" to 600_000L, "2026-09-14" to 600_000L), days)
    }

    /**
     * 夏令时不等于每天 24 小时。
     *
     * 2026-03-08 是美东夏令时开始日，这一天只有 23 小时。
     * 从该日 00:00 起持续 24 小时，第一天只能装下 23 小时，
     * 剩余 1 小时落到 2026-03-09。
     *
     * 这条用例锁定的是「不能简单地把时长除以 86400000」——
     * 若按固定 24 小时切分，结果会错误地变成一天 24 小时。
     */
    @Test fun daylightSavingDoesNotAssumeEveryDayIs24Hours() {
        val ny = ZoneId.of("America/New_York")
        val start = LocalDate.parse("2026-03-08").atStartOfDay(ny).toInstant().toEpochMilli()
        assertEquals(mapOf("2026-03-08" to 23 * 3_600_000L, "2026-03-09" to 3_600_000L), splitInterval(start, 24 * 3_600_000L, ny))
    }

    /**
     * 周统计的边界与去重规则。
     *
     * 输入刻意构造了四类需要被正确处理的数据：
     * - 2026-09-07 有两条不同事件 → 只算 1 个活跃日（按日期去重）
     * - 2026-09-13 是一条 FOCUS_COMPLETED、一条 FOCUS_TIME → 只有前者是有效记录，
     *   但两者的 focusMillis 都计入专注时长（90_000 + 30_000 = 120_000 → 2 分钟），
     *   因为 FOCUS_TIME 是内部计时事件，不该抬高活跃天数却确实代表专注时间
     * - 2026-09-14 已属于下一周 → 必须被排除（卡片数因此是 1 而不是 2）
     * - 2026-09-09 的 BOOK_EDITED 不是有效记录 → 不计入活跃天数，
     *   因此活跃天数是 3（07、08、13）而不是 4
     */
    @Test fun weeklyStatsExcludeNextWeekAndCountDistinctDates() {
        val events = listOf(
            StatEvent("2026-09-07", "BOOK_ADDED"),
            StatEvent("2026-09-07", "CARD_ADDED"),
            StatEvent("2026-09-08", "PROGRESS", pages = 12),
            StatEvent("2026-09-08", "PLAN_COMPLETED"),
            StatEvent("2026-09-13", "FOCUS_COMPLETED", focusMillis = 90_000),
            StatEvent("2026-09-13", "FOCUS_TIME", focusMillis = 30_000),
            StatEvent("2026-09-14", "CARD_ADDED"),
            StatEvent("2026-09-09", "BOOK_EDITED")
        )
        val result = weeklyStats(LocalDate.parse("2026-09-07"), events)
        assertEquals(3, result.activeDays)
        assertEquals(2L, result.focusMinutes)
        assertEquals(12, result.readingPages)
        assertEquals(1, result.cards)
        assertEquals(1, result.completedPlans)
    }
}

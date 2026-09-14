package com.guanjian.app.domain

import java.time.LocalDate
import java.time.YearMonth

/** 学习热力图中的一天；level 从 0（无记录）到 4（高投入）。 */
data class MonthlyActivityDay(
    val date: LocalDate,
    val focusMinutes: Long,
    val readingBooks: Int,
    val readingPages: Int,
    val completedPlans: Int,
    val level: Int
)

/**
 * 按月汇总本地事件。颜色深浅同时考虑三个维度：
 * 专注时间最多 3 分，阅读书籍最多 2 分，完成计划最多 2 分。
 * 这样单纯记一次和真正投入一天会呈现不同深度，也不会由某个极大数值垄断颜色。
 */
fun monthlyActivity(month: YearMonth, events: List<StatEvent>): List<MonthlyActivityDay> {
    val monthEvents = events.filter { runCatching { YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == month }
    return (1..month.lengthOfMonth()).map { dayNumber ->
        val date = month.atDay(dayNumber)
        val dayEvents = monthEvents.filter { it.date == date.toString() }
        val focusMinutes = dayEvents.sumOf { it.focusMillis.coerceAtLeast(0) } / 60_000
        val reading = dayEvents.filter { it.type == "PROGRESS" }
        val readingBooks = reading.map { it.sourceId }.filter { it.isNotBlank() }.distinct().size
        val readingPages = reading.sumOf { it.pages.coerceAtLeast(0) }
        val completedPlans = dayEvents.count { it.type == "PLAN_COMPLETED" }
        val focusScore = when {
            focusMinutes >= 60 -> 3
            focusMinutes >= 30 -> 2
            focusMinutes > 0 -> 1
            else -> 0
        }
        val score = focusScore + readingBooks.coerceAtMost(2) + completedPlans.coerceAtMost(2)
        val level = when (score) { 0 -> 0; 1 -> 1; in 2..3 -> 2; in 4..5 -> 3; else -> 4 }
        MonthlyActivityDay(date, focusMinutes, readingBooks, readingPages, completedPlans, level)
    }
}

/** 补齐月首前和月末后的空格，使日历始终按周一到周日排列。 */
fun monthlyActivityGrid(month: YearMonth, events: List<StatEvent>): List<MonthlyActivityDay?> {
    return padMonthlyActivityGrid(month, monthlyActivity(month, events))
}

/** 界面已持有当月统计时，只补日历空格，避免再次遍历全部事件。 */
fun padMonthlyActivityGrid(month: YearMonth, days: List<MonthlyActivityDay>): List<MonthlyActivityDay?> {
    val leading = month.atDay(1).dayOfWeek.value - 1
    val withLeading = List<MonthlyActivityDay?>(leading) { null } + days
    val trailing = (7 - withLeading.size % 7) % 7
    return withLeading + List<MonthlyActivityDay?>(trailing) { null }
}

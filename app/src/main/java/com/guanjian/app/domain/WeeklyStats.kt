package com.guanjian.app.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

/**
 * 周统计的输入事件（从 ActivityEventEntity 投影而来的精简值对象）。
 *
 * 之所以不直接依赖 Room 实体，是为了让 [weeklyStats] 保持纯函数：
 * 不碰 Android、不碰数据库，可以用最快的方式单测。
 *
 * @param date ISO 本地日期字符串（如 "2026-09-07"）。用字符串而非 LocalDate 是刻意的：
 *   ISO 日期天然可按字典序比较，筛选时无需解析，且与数据库存储格式一致。
 * @param type 事件类型，取值见 [isEffectiveRecord]
 * @param pages 本次推进的阅读页数（校正进度时为 0）
 * @param focusMillis 本次专注毫秒数
 */
data class StatEvent(
    val date: String,
    val type: String,
    val pages: Int = 0,
    val focusMillis: Long = 0,
    val sourceId: String = ""
)

/**
 * 一周的汇总结果。
 *
 * @param activeDays 活跃天数（去重后的有效记录日数量）
 * @param focusMinutes 累计专注分钟数（由毫秒整除得到）
 * @param readingPages 累计阅读页数
 * @param cards 新增卡片数
 * @param completedPlans 完成的计划数
 */
data class WeekStats(val activeDays: Int = 0, val focusMinutes: Long = 0, val readingPages: Int = 0, val cards: Int = 0, val completedPlans: Int = 0)

/**
 * 汇总某一周的统计数据。
 *
 * 无论传入哪一天，都会先归一到该周的周一（[weekStart]），因此
 * 传周二和传周日得到同一结果 —— 这也正是「周日归属上一周」这条产品规则的落点。
 *
 * 区间用左闭右开 `[first, end)` 配合 ISO 日期的字典序比较，
 * 天然避免了两端重复计入的问题。
 *
 * 页数、专注时长都做 coerceAtLeast(0)：数据理论上不会为负，
 * 但统计结果不应因为一条脏数据而整体变小。
 */
fun weeklyStats(start: LocalDate, events: List<StatEvent>): WeekStats {
    val first = weekStart(start).toString()
    val end = weekStart(start).plusWeeks(1).toString()
    val week = events.filter { it.date >= first && it.date < end }
    return WeekStats(
        // 先按类型过滤，再按日期去重：同一天做三件事也只算一个活跃日
        activeDays = week.filter { isEffectiveRecord(it.type) }.map { it.date }.distinct().size,
        focusMinutes = week.sumOf { it.focusMillis.coerceAtLeast(0) } / 60_000,
        readingPages = week.sumOf { it.pages.coerceAtLeast(0) },
        cards = week.count { it.type == "CARD_ADDED" },
        completedPlans = week.count { it.type == "PLAN_COMPLETED" }
    )
}

/**
 * 把一段连续时间按「本地自然日」切分，返回 日期 -> 毫秒数 的有序映射。
 *
 * 为什么不能简单除以 86400000？
 * 因为夏令时（DST）会让某一天只有 23 小时或长达 25 小时，简单地按固定
 * 24 小时切割会把时长分到错误的日子上。这里改为逐步推进到「下一个本地午夜」：
 * 每一步用 [LocalDate.atStartOfDay] 求出真实的午夜时刻，
 * 于是 DST 日的 23/25 小时被自动正确处理。
 *
 * 典型用途：23:50 开始、持续 20 分钟的专注会得到
 * { 2026-09-13: 10分钟, 2026-09-14: 10分钟 } 两个条目，
 * 这样跨零点的专注能同时计入两天与两周的统计。
 *
 * @param startMillis 起始时刻（UTC 毫秒）
 * @param durationMillis 持续时长，负数按 0 处理
 * @param zone 用于判定本地日期的时区
 */
fun splitInterval(startMillis: Long, durationMillis: Long, zone: ZoneId): Map<String, Long> {
    var cursor = startMillis
    var remaining = durationMillis.coerceAtLeast(0)
    val result = linkedMapOf<String, Long>()
    while (remaining > 0) {
        val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
        // 下一个本地午夜的绝对时刻；DST 切换日这里会偏离 24 小时
        val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // 本段最多推进到午夜，剩余时长留到下一轮
        val chunk = minOf(remaining, nextMidnight - cursor)
        result[date.toString()] = (result[date.toString()] ?: 0) + chunk
        remaining -= chunk
        cursor += chunk
    }
    return result
}

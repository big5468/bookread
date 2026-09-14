package com.guanjian.app.domain

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/**
 * 计算一本书的阅读进度，返回值范围恒为 0f..1f。
 *
 * 规则（顺序即优先级）：
 * 1. 已标记「读完」直接返回 1f —— 即使没填总页数或当前页，也算完成。
 * 2. 未填总页数、或总页数非正数时返回 0f —— 没有分母就无法计算进度，
 *    这里刻意不猜测，避免封面出现虚假的「已读一部分」效果。
 * 3. 其余情况用 当前页 / 总页数，并用 coerceIn 夹紧：
 *    用户可能把当前页填得比总页数大（虽然仓库层会拦截，但这里是纯函数，
 *    不能假设调用方一定校验过），夹紧后进度条不会溢出。
 *
 * @param current 当前阅读页，未记录时为 null
 * @param total 总页数，未记录时为 null
 * @param completed 是否已读完
 */
fun readingProgress(current: Int?, total: Int?, completed: Boolean): Float = when {
    completed -> 1f
    total == null || total <= 0 -> 0f
    else -> ((current ?: 0).toFloat() / total).coerceIn(0f, 1f)
}

/**
 * 取给定日期所在周的周一。
 *
 * 产品约定：一周从周一开始（而非周日）。因此周日属于「上一个周一」开始的那一周，
 * 这正是 previousOrSame 的语义 —— 如果 date 本身就是周一，则返回它自己。
 * 周统计、周目标、周回顾全部依赖这个函数确定分界，必须保持一致。
 */
fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/**
 * 判断某类活动流水是否构成一次「有效记录日」。
 *
 * 产品约定：用户不需要手动签到。当天只要发生过下列任意一种行为，该天就算活跃一天：
 * - BOOK_ADDED     新增一本书
 * - PROGRESS       推进（或校正）了阅读进度
 * - CARD_ADDED     新增一张卡片
 * - PLAN_COMPLETED 完成一项今日计划
 * - FOCUS_COMPLETED 完成一次专注
 *
 * 注意这里用的是「白名单」而不是「黑名单」：将来新增事件类型若未登记于此，
 * 默认不计入活跃天数，这比默认计入更安全（不会凭空抬高活跃度）。
 */
fun isEffectiveRecord(type: String): Boolean = type in setOf(
    "BOOK_ADDED", "PROGRESS", "CARD_ADDED", "PLAN_COMPLETED", "FOCUS_COMPLETED"
)

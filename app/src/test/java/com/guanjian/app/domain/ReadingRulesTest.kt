package com.guanjian.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/**
 * [ReadingRules] 的单元测试。
 *
 * 这三个纯函数是全应用统计口径的基础，因此测试重点放在「边界与异常输入」上，
 * 而不是只覆盖正常路径 —— 进度、周起点、有效记录判定一旦出错，
 * 会静默地让书墙封面、周统计、活跃天数全部显示错误值，很难靠人工发现。
 */
class ReadingRulesTest {
    /**
     * 阅读进度在各种缺失/非法输入下的取值。
     *
     * 覆盖的边界：
     * - 正常比例 25/100 = 0.25
     * - 缺总页数、总页数为 0 → 0f（没有分母就不猜，避免封面出现虚假进度）
     * - 当前页超过总页数 → 夹紧到 1f 而不是 >1（防止进度条溢出）
     * - 当前页为负数 → 0f（不产生负进度）
     * - 即使没有页码，只要标记「读完」就是 1f
     */
    @Test fun progressHandlesMissingAndInvalidTotals() {
        assertEquals(0.25f, readingProgress(25, 100, false))
        assertEquals(0f, readingProgress(25, null, false))
        assertEquals(0f, readingProgress(25, 0, false))
        assertEquals(1f, readingProgress(200, 100, false))
        assertEquals(0f, readingProgress(-1, 100, false))
        assertEquals(1f, readingProgress(null, null, true))
    }

    /**
     * 周起点必须是周一，且要正确处理跨年。
     *
     * 三个用例分别锁定：
     * - 周日（2026-09-13）归属前一个周一 2026-09-07 —— 这是产品明确规定的
     *   「周日算上一周」，也是最容易被写成「本周日」的地方；
     * - 跨年边界：2026-01-01（周四）归属 2025-12-29，验证不会按自然年切割；
     * - 传周一本身时返回它自己（previousOrSame 而非 previous）。
     */
    @Test fun weekStartsMondayAcrossYearBoundary() {
        assertEquals(LocalDate.parse("2026-09-07"), weekStart(LocalDate.parse("2026-09-13")))
        assertEquals(LocalDate.parse("2025-12-29"), weekStart(LocalDate.parse("2026-01-01")))
        assertEquals(LocalDate.parse("2026-09-14"), weekStart(LocalDate.parse("2026-09-14")))
    }

    /**
     * 只有白名单内的 5 类事件算作有效记录日。
     *
     * 前 5 条断言锁定白名单内容；后两条锁定「未登记的类型一律不算」，
     * 尤其是 BOOK_EDITED（只改书名不算学习）与空字符串。
     * 这条边界决定了「本周活跃天数」是否会虚高。
     */
    @Test fun onlyQualifyingActivityCountsAsDailyRecord() {
        assertTrue(isEffectiveRecord("BOOK_ADDED"))
        assertTrue(isEffectiveRecord("PROGRESS"))
        assertTrue(isEffectiveRecord("CARD_ADDED"))
        assertTrue(isEffectiveRecord("PLAN_COMPLETED"))
        assertTrue(isEffectiveRecord("FOCUS_COMPLETED"))
        assertFalse(isEffectiveRecord("BOOK_EDITED"))
        assertFalse(isEffectiveRecord(""))
    }
}

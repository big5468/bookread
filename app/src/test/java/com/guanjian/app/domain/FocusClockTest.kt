package com.guanjian.app.domain

import org.junit.Assert.*
import org.junit.Test

/**
 * [FocusClock] 的单元测试。
 *
 * 这是全项目最需要测试覆盖的一段：计时逻辑一旦出错，用户会看到错误的专注时长，
 * 而且这些场景（暂停、设备重启、用户改系统时间）在手工测试中极难复现。
 * 因此这里全部用显式传入的时间值来驱动，不依赖真实时钟。
 *
 * 三个关键区分点贯穿所有用例：
 * - `elapsed` 单调时钟（同一次开机内可信）
 * - `wall` 墙钟（跨重启后的退路）
 * - `boot` 开机次数（决定用哪一个）
 */
class FocusClockTest {
    /**
     * 暂停期间的时间不计入专注时长，且暂停/继续能正确累计。
     *
     * 时间线（墙钟 / 单调）：
     * - 在 100_000 / 10_000 开始
     * - 到 160_000 / 70_000 时已专注 60_000，暂停
     * - 暂停期间时间推进到 460_000 / 370_000（共 300_000），专注时长应仍为 60_000
     * - 在 460_000 / 370_000 继续，到 490_000 / 400_000 又专注 30_000，合计 90_000
     *
     * 另外用 `paused.copy()` 验证：从数据库反序列化出的副本（data class 拷贝）
     * 计算出的时长与原来一致，这是「计时状态能跨进程重建」的前提。
     * 最后断言 pausedDuration 记录了 300_000 的暂停时长。
     */
    @Test fun pauseAndResumeExcludePausedTimeAndSurviveCopy() {
        val started = FocusClock.start(wall = 100_000, elapsed = 10_000, boot = 1)
        assertEquals(60_000L, started.duration(160_000, 70_000, 1))
        val paused = started.pause(160_000, 70_000, 1)
        assertEquals(60_000L, paused.copy().duration(460_000, 370_000, 1))
        val resumed = paused.resume(460_000, 370_000, 1)
        assertEquals(90_000L, resumed.duration(490_000, 400_000, 1))
        assertEquals(300_000L, resumed.pausedDuration)
    }

    /**
     * 重复暂停、重复继续都必须幂等。
     *
     * 为什么必须幂等：UI 按钮可能被连点，且仓库层每次暂停都会往 focus_segments
     * 写一条时间片段流水。若不幂等，会产生重复片段，导致专注时长被成倍计入。
     * 断言「再次调用返回与首次完全相同的对象」（data class 相等）。
     */
    @Test fun repeatedPauseAndResumeAreIdempotent() {
        val paused = FocusClock.start(0, 0, 1).pause(60_000, 60_000, 1)
        assertEquals(paused, paused.pause(100_000, 100_000, 1))
        val running = paused.resume(120_000, 120_000, 1)
        assertEquals(running, running.resume(140_000, 140_000, 1))
    }

    /**
     * 用户手动修改系统时间不应影响计时。
     *
     * 两次调用传入相同的单调时钟（50_000）但相差悬殊的墙钟
     * （50_000 与 9_000_000），结果都必须是 30_000。
     * 这验证了同一次开机内以 elapsed 为准 —— 若误用墙钟，
     * 用户把时间往后调就会看到专注时长凭空暴涨。
     */
    @Test fun monotonicTimeSurvivesWallClockChanges() {
        val running = FocusClock.start(100_000, 20_000, 1)
        assertEquals(30_000L, running.duration(50_000, 50_000, 1))
        assertEquals(30_000L, running.duration(9_000_000, 50_000, 1))
    }

    /**
     * 跨越设备重启后退回墙钟，且永不返回负数。
     *
     * boot 从 1 变为 2，说明中间重启过，此时单调时钟已归零（传 1_000 无意义），
     * 只能按墙钟计算：170_000 - 100_000 = 70_000。
     *
     * 第二条断言更关键：墙钟 90_000 早于开始时的 100_000（用户把时间调早了，
     * 或重启后时钟未同步），必须夹紧为 0 而不是返回 -10_000 ——
     * 负的专注时长会污染周统计，也会让界面显示异常。
     */
    @Test fun rebootFallsBackToNonnegativeWallTime() {
        val running = FocusClock.start(100_000, 20_000, 1)
        assertEquals(70_000L, running.duration(170_000, 1_000, 2))
        assertEquals(0L, running.duration(90_000, 1_000, 2))
    }
}

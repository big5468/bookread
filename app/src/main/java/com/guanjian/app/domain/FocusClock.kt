package com.guanjian.app.domain

/** 离开应用后，运行中的专注最多再计入 20 分钟。 */
const val FOCUS_INACTIVE_GRACE_MILLIS = 20 * 60_000L

/**
 * 专注计时器的持久化状态（不可变）。
 *
 * 为什么不用内存里的倒计时数字？
 * 因为应用可能被切到后台、进程被系统回收、设备重启或屏幕旋转。若只维护一个
 * 「已累计毫秒数」并在内存里做加法，这些场景下计时必然丢失或错乱。
 * 这里改为保存「事实」——本段是什么时候开始的——再由时间差反推时长，
 * 于是计时可以从数据库恢复。最后活跃时间也要持久化，避免进程被杀后把几天都算进专注。
 *
 * 双时钟设计是本类的核心：
 * - [segmentElapsed] / [pausedAtElapsed] 来自 `SystemClock.elapsedRealtime()`，
 *   它是单调递增的，用户手改系统时间也不会影响它，因此在同一次开机内最可信。
 * - [segmentWall] / [pausedAtWall] 来自 `System.currentTimeMillis()`，
 *   跨重启后 elapsedRealtime 会归零，此时只能退回到墙钟。
 * - [bootCount] 用于判断「当前这次计时是否跨越了重启」：与 [bootCount] 相等说明
 *   还在同一次开机内，可以用单调时钟；不相等就必须改用墙钟。
 *
 * 因此本类的方法都要求调用方同时传入 `wall`、`elapsed`、`boot` 三个当前值，
 * 本类自身不读系统时间，从而保持纯函数、可完整单测（见 FocusClockTest）。
 *
 * @param running 当前是否处于运行中（false 表示已暂停）
 * @param segmentWall 当前这一段开始时的墙钟毫秒
 * @param segmentElapsed 当前这一段开始时的单调时钟毫秒
 * @param bootCount 当前这一段开始时的开机次数
 * @param accumulated 之前所有已完成段累计的时长（毫秒）
 * @param pausedAtWall 进入暂停时的墙钟毫秒，运行中为 null
 * @param pausedAtElapsed 进入暂停时的单调时钟毫秒，运行中为 null
 * @param pausedDuration 累计暂停时长（毫秒），仅用于展示，不计入专注时长
 * @param observedWall 最近一次应用仍在前台时的墙钟；旧数据为空时退回本段起点
 */
data class FocusClock(
    val running: Boolean = true,
    val segmentWall: Long,
    val segmentElapsed: Long,
    val bootCount: Int,
    val accumulated: Long = 0,
    val pausedAtWall: Long? = null,
    val pausedAtElapsed: Long? = null,
    val pausedDuration: Long = 0,
    val observedWall: Long? = null,
    val observedElapsed: Long? = null,
    val observedBoot: Int? = null
) {
    private fun observationWall() = observedWall ?: segmentWall
    private fun observationElapsed() = observedElapsed ?: segmentElapsed
    private fun observationBoot() = observedBoot ?: bootCount

    /** 前台心跳续上允许计时的窗口。过期后不能调用，以免把离开期间重新算作活跃。 */
    fun observe(wall: Long, elapsed: Long, boot: Int): FocusClock = if (!running) this else copy(
        observedWall = wall, observedElapsed = elapsed, observedBoot = boot
    )

    /** 同一次开机优先用单调时钟判断；跨重启才用墙钟。 */
    fun isExpired(wall: Long, elapsed: Long, boot: Int): Boolean = running &&
        (if (boot == observationBoot() && elapsed >= observationElapsed())
            elapsed - observationElapsed() else (wall - observationWall()).coerceAtLeast(0)) >= FOCUS_INACTIVE_GRACE_MILLIS

    /**
     * 计算「当前这一段」已经过去的时长（不含 [accumulated]）。
     *
     * 暂停中固定返回 0：暂停期间时间不推进。
     * 未跨重启（boot 相同）时用单调时钟做差，最精确且免疫改时间。
     * 跨重启时单调时钟已失去参照，退回用墙钟做差。
     *
     * 两种分支都做 coerceAtLeast(0)：重启后墙钟可能被调早，若出现负数
     * 会让总时长变小甚至为负，这里统一按 0 处理。
     */
    fun segmentDuration(wall: Long, elapsed: Long, boot: Int): Long =
        if (!running) 0 else if (boot == bootCount && boot == observationBoot())
            (minOf(elapsed, observationElapsed() + FOCUS_INACTIVE_GRACE_MILLIS) - segmentElapsed).coerceAtLeast(0)
        else (minOf(wall, observationWall() + FOCUS_INACTIVE_GRACE_MILLIS) - segmentWall).coerceAtLeast(0)

    /** 总专注时长 = 历史累计 + 当前这一段。这是界面上展示的那个数字。 */
    fun duration(wall: Long, elapsed: Long, boot: Int): Long = accumulated + segmentDuration(wall, elapsed, boot)

    /**
     * 暂停：把当前段结算进 [accumulated]，并记录暂停时刻。
     *
     * 重复调用是幂等的（`if (!running) this`），因为 UI 上的暂停按钮
     * 可能被连点，而仓库层每次暂停还会写一条 focus_segments 流水，
     * 不幂等就会产生重复段。
     */
    fun pause(wall: Long, elapsed: Long, boot: Int): FocusClock = if (!running) this else copy(
        running = false, accumulated = duration(wall, elapsed, boot),
        pausedAtWall = if (isExpired(wall, elapsed, boot)) minOf(wall, observationWall() + FOCUS_INACTIVE_GRACE_MILLIS) else wall,
        pausedAtElapsed = if (boot == observationBoot()) minOf(elapsed, observationElapsed() + FOCUS_INACTIVE_GRACE_MILLIS) else elapsed,
        bootCount = boot
    )

    /**
     * 继续：开一个新段，并把刚才的暂停时长累加进 [pausedDuration]。
     *
     * 同样幂等。暂停时长的计算也区分是否跨重启：
     * 同一次开机内用单调时钟差；跨重启则用墙钟差。
     * 计算完把 pausedAt* 清空，避免下次重复计入。
     */
    fun resume(wall: Long, elapsed: Long, boot: Int): FocusClock = if (running) this else copy(
        running = true, segmentWall = wall, segmentElapsed = elapsed, bootCount = boot,
        pausedAtWall = null, pausedAtElapsed = null,
        observedWall = wall, observedElapsed = elapsed, observedBoot = boot,
        pausedDuration = pausedDuration + (if (boot == bootCount && pausedAtElapsed != null)
            elapsed - pausedAtElapsed else wall - (pausedAtWall ?: wall)).coerceAtLeast(0)
    )

    companion object {
        /**
         * 开始一次全新计时：当前这一刻同时作为段的起点与基准。
         * `running` 默认为 true，因此初始状态即「正在专注」。
         */
        fun start(wall: Long, elapsed: Long, boot: Int): FocusClock = FocusClock(
            segmentWall = wall, segmentElapsed = elapsed, bootCount = boot,
            observedWall = wall, observedElapsed = elapsed, observedBoot = boot
        )
    }
}

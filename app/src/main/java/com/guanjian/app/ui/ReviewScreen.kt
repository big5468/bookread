package com.guanjian.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.guanjian.app.data.*
import com.guanjian.app.domain.*
import java.time.LocalDate

/**
 * 回顾页 —— 一周目标进度 + 本地摘要 + 可选的 AI 摘要。
 *
 * 「两层回顾」是这一页的核心设计：
 * 1. 本地摘要（[localSummary]）永远可用，完全不依赖网络与 AI；
 * 2. AI 回顾只在用户主动点击后生成，且结果先预览、确认后才保存。
 * 因此即使用户从未配置 API Key，本页的功能也是完整的。
 *
 * 周切换通过 [onWeek] 把新周次回传给上层（用 rememberSaveable 保存），
 * 而不是在本页维护 —— 这样切到周目标编辑页再返回时，选中的周不会丢失。
 *
 * @param week 当前查看周的周一日期（ISO 字符串）
 * @param goal 该周目标，为 null 时使用全默认值展示
 * @param events 全部活动流水（本页按周筛选；上层已算好统计的部分不重复计算）
 * @param review 该周已保存的回顾
 */
@Composable
fun ReviewScreen(week: String, onWeek: (String) -> Unit, goal: WeeklyGoalEntity?, events: List<ActivityEventEntity>,
    review: WeeklyReviewEntity?, onGoal: () -> Unit, onMonthly: () -> Unit, onAiTools: () -> Unit) {
    val start = LocalDate.parse(week)
    // 输入不变时持有统计与文字摘要，避免按钮、滚动等重组重复遍历事件。
    val statEvents = remember(events) { events.map { StatEvent(it.date, it.type, it.pages, it.focusMillis, it.sourceId) } }
    val stats = remember(start, statEvents) { weeklyStats(start, statEvents) }
    val summary = remember(week, events) { localSummary(week, events) }
    // 未设置目标时用一个「全默认值」的对象，避免界面到处判空
    val target = goal ?: WeeklyGoalEntity(weekStartDate = week)
    PageColumn {
        Text("给这一周一个回望", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ onWeek(start.minusWeeks(1).toString()) }) { Text("上一周") }
            // 「回到本周」始终以真实的今天为基准，而不是在当前查看周上加减
            TextButton({ onWeek(weekStart(LocalDate.now()).toString()) }) { Text("回到本周") }
            TextButton({ onWeek(start.plusWeeks(1).toString()) }) { Text("下一周") }
        }
        Text("$week — ${start.plusDays(6)}")
        Section("一周目标", "到馆或学习次数按有效记录日计算，一天最多一次。") {
            // 四项指标对应需求列出的四个周目标维度
            GoalProgress("到馆或学习", stats.activeDays.toLong(), target.targetStudyCount, "次")
            GoalProgress("专注", stats.focusMinutes, target.targetFocusMinutes, "分钟")
            GoalProgress("阅读", stats.readingPages.toLong(), target.targetReadingPages, "页")
            GoalProgress("卡片", stats.cards.toLong(), target.targetCardCount, "张")
            Text("活跃 ${stats.activeDays} 天 · 完成 ${stats.completedPlans} 项计划")
            OutlinedButton(onGoal) { Text("编辑这一周的目标") }
        }
        // 本地摘要无条件展示——这是「AI 不可用也不影响基础回顾」的落点
        Section("本地回顾 · 随时可用") { Text(summary) }
        Section("继续回顾", "详细图表和 AI 功能在需要时再打开，让本页保持轻快。") {
            OutlinedButton(onMonthly, Modifier.fillMaxWidth()) { Text("查看月度学习足迹") }
            OutlinedButton(onAiTools, Modifier.fillMaxWidth()) { Text(if (review?.aiSummary == null) "AI 回顾与设置" else "查看已保存的 AI 回顾") }
        }
    }
}

/** AI 相关操作独立成子页面，减少回顾主页的信息密度。 */
@Composable
fun ReviewAiToolsScreen(review: WeeklyReviewEntity?, onAi: () -> Unit, onExplore: () -> Unit, onAiSettings: () -> Unit) {
    PageColumn {
        Text("AI 回顾", style = MaterialTheme.typography.headlineMedium)
        Text("AI 完全可选，本地统计不受影响。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        review?.aiSummary?.let { Section("已保存的 AI 回顾") { Text(it) } }
        Section("AI 助手 · 可选") {
            Button(onAi, Modifier.fillMaxWidth()) { Text("生成 AI 周回顾") }
            OutlinedButton(onExplore, Modifier.fillMaxWidth()) { Text("寻找下一步探索方向") }
            TextButton(onAiSettings, Modifier.fillMaxWidth()) { Text("AI 设置") }
            Text("发送前会展示数据范围，结果先预览，再由你决定保存。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 单项周目标进度。
 *
 * 目标为 0 视为「本周不设该目标」，此时进度固定显示 0 而不做除法，
 * 避免出现除零或 100% 的误导性展示。
 * 进度值夹紧到 0f..1f，超额完成时进度条也不会溢出。
 */
@Composable
private fun GoalProgress(label: String, value: Long, target: Int, unit: String) {
    Text("$label：$value / $target $unit")
    LinearProgressIndicator(progress = { if (target <= 0) 0f else (value.toFloat() / target).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
}

/**
 * 周目标编辑器。
 *
 * 四项都用数字键盘输入，且都以 rememberSaveable 保存。
 * 留空等同于 0，含义是「本周不设这一项的目标」——界面副标题里明确写出了这条规则，
 * 避免用户困惑于「不填会怎样」。
 *
 * @param week 被编辑的周（周一日期）
 */
@Composable
fun GoalEditor(week: String, goal: WeeklyGoalEntity?, busy: Boolean, onSave: (WeeklyGoalEntity) -> Unit) {
    // 以 week 与 goal.id 为 key：切换到另一周时重新播种表单
    val seed = remember(week, goal?.id) { goal ?: WeeklyGoalEntity(weekStartDate = week) }
    var study by rememberSaveable(seed.id) { mutableStateOf(seed.targetStudyCount.toString()) }
    var minutes by rememberSaveable(seed.id) { mutableStateOf(seed.targetFocusMinutes.toString()) }
    var pages by rememberSaveable(seed.id) { mutableStateOf(seed.targetReadingPages.toString()) }
    var cards by rememberSaveable(seed.id) { mutableStateOf(seed.targetCardCount.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    PageColumn {
        Text("设一个能做到的小目标", style = MaterialTheme.typography.headlineSmall)
        Text("$week 开始的一周 · 0 表示暂不设目标")
        Field("到馆或学习次数", study, { study = it }, numeric = true)
        Field("专注分钟数", minutes, { minutes = it }, numeric = true)
        Field("阅读页数", pages, { pages = it }, numeric = true)
        Field("卡片数量", cards, { cards = it }, numeric = true)
        ErrorText(error)
        Button({ try {
            // 留空与 0 统一按 0 处理，语义都是「不设目标」
            onSave(seed.copy(targetStudyCount = optionalInt(study, "次数") ?: 0, targetFocusMinutes = optionalInt(minutes, "分钟") ?: 0,
                targetReadingPages = optionalInt(pages, "页数") ?: 0, targetCardCount = optionalInt(cards, "卡片数量") ?: 0))
            error = null
        } catch (e: IllegalArgumentException) { error = e.message } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存周目标") }
    }
}

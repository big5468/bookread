package com.guanjian.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.guanjian.app.data.ActivityEventEntity
import com.guanjian.app.domain.MonthlyActivityDay
import com.guanjian.app.domain.StatEvent
import com.guanjian.app.domain.monthlyActivity
import com.guanjian.app.domain.padMonthlyActivityGrid
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 回顾页的本地月度学习热力图，交互与 GitHub 提交墙类似。 */
@Composable
fun MonthlyActivityHeatmap(events: List<ActivityEventEntity>) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val month = YearMonth.parse(monthText)
    // 先投影一次并持有：点击日期、展开详情都不再遍历数据库事件。
    val statEvents = remember(events) { events.map { StatEvent(it.date, it.type, it.pages, it.focusMillis, it.sourceId) } }
    val stats = remember(statEvents, month) { monthlyActivity(month, statEvents) }
    val grid = remember(stats, month) { padMonthlyActivityGrid(month, stats) }
    val selected = stats.find { it.date.toString() == selectedText } ?: stats.first()
    val totalMinutes = stats.sumOf { it.focusMinutes }
    val monthReadingBooks = remember(events, month) { events.filter {
        it.type == "PROGRESS" && runCatching { YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == month
    }.map { it.sourceId }.filter { it.isNotBlank() }.distinct().size }

    Section("本月学习足迹", "颜色越深，表示当天的学习投入越多。") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({
                val previous = month.minusMonths(1)
                monthText = previous.toString(); selectedText = previous.atDay(1).toString()
            }) { Text("上个月") }
            Text(month.format(DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)), style = MaterialTheme.typography.titleMedium)
            TextButton({
                val next = month.plusMonths(1)
                monthText = next.toString(); selectedText = next.atDay(1).toString()
            }, enabled = month < YearMonth.now()) { Text("下个月") }
        }
        Text("本月专注 ${totalMinutes / 60} 小时 ${totalMinutes % 60} 分 · 阅读 $monthReadingBooks 本 · 完成 ${stats.sumOf { it.completedPlans }} 项计划",
            style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    if (day == null) Spacer(Modifier.weight(1f).aspectRatio(1f))
                    else ActivityDayCell(day, day.date.toString() == selected.date.toString()) { selectedText = day.date.toString() }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            Text("少", style = MaterialTheme.typography.labelSmall)
            (0..4).forEach { level -> Box(Modifier.padding(start = 4.dp).size(14.dp)
                .background(activityColor(level), RoundedCornerShape(4.dp))) }
            Text("多", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(selected.date.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)), style = MaterialTheme.typography.titleSmall)
        Text("学习 ${selected.focusMinutes} 分钟 · 阅读 ${selected.readingBooks} 本 / ${selected.readingPages} 页 · 完成 ${selected.completedPlans} 项计划")
    }
}

/** 月度热力图独立子页面，只在用户打开时才组合和计算。 */
@Composable
fun MonthlyActivityScreen(events: List<ActivityEventEntity>) {
    PageColumn {
        Text("月度学习足迹", style = MaterialTheme.typography.headlineMedium)
        Text("像查看提交记录一样，回看每天的学习投入。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        MonthlyActivityHeatmap(events)
    }
}

@Composable
private fun RowScope.ActivityDayCell(day: MonthlyActivityDay, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(7.dp)
    val detail = "${day.date.dayOfMonth}日，学习${day.focusMinutes}分钟，阅读${day.readingBooks}本，完成${day.completedPlans}项计划"
    Box(
        Modifier.weight(1f).aspectRatio(1f)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, shape)
            .background(activityColor(day.level), shape)
            .semantics { contentDescription = detail }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(day.date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall,
            color = if (day.level >= 3) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun activityColor(level: Int): Color = when (level) {
    0 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    1 -> MaterialTheme.colorScheme.primaryContainer
    2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    else -> MaterialTheme.colorScheme.primary
}

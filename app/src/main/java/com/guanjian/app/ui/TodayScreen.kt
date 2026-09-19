package com.guanjian.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.guanjian.app.data.*
import com.guanjian.app.domain.WeekStats
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 今天页 —— 应用的首页，承载「看一眼就知道今天该做什么」的职责。
 *
 * 展示顺序按使用频率排列：日期与主操作 → 本周概览 → 今日计划 → 今日已完成的记录。
 * 两个主按钮的高度刻意放大（主按钮 56dp），呼应「10 秒内开始一次自习」的效率目标。
 *
 * @param today 今天的 ISO 日期字符串
 * @param stats 本周统计（由上层用纯函数算好，本页不做聚合）
 * @param plans 仅今天的计划（已由上层过滤）
 * @param events 仅今天的活动流水（已由上层过滤）
 * @param active 进行中的专注。非空时主按钮改为「回到正在进行的专注」，
 *   让用户从任何位置都能一步回到计时界面
 */
@Composable
fun TodayScreen(today: String, stats: WeekStats, plans: List<DailyPlanEntity>, events: List<ActivityEventEntity>,
    active: ActiveFocusEntity?, onPlan: () -> Unit, onToggle: (DailyPlanEntity) -> Unit,
    onDelete: (DailyPlanEntity) -> Unit, onFocus: () -> Unit, onCard: () -> Unit) {
    var deletingPlan by remember { mutableStateOf<DailyPlanEntity?>(null) }
    val visibleEvents = remember(events) { events.filter { it.type != "FOCUS_TIME" }.sortedByDescending { it.createdAt } }
    PageColumn {
        Text(LocalDate.parse(today).format(DateTimeFormatter.ofPattern("M月d日 · EEEE", java.util.Locale.CHINA)),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("留一点时间给自己", style = MaterialTheme.typography.headlineSmall)
        Text(if (active == null) "从一段专注开始，慢慢积累。" else if (active.clock.running) "有一段专注正在进行。" else "有一段专注已暂停，随时可以继续。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onFocus, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (active == null) "开始专注" else "回到专注")
        }
        OutlinedButton(onCard, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("快速记卡片")
        }
        Section("这一周", "你的本地学习概览", icon = Icons.Default.QueryStats, accent = MaterialTheme.colorScheme.tertiary) {
            // 三个关键指标并排：到馆/学习天数、专注分钟、卡片数
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("${stats.activeDays} 天", "到馆 / 学习", Modifier.weight(1f))
                Metric("${stats.focusMinutes} 分", "专注时间", Modifier.weight(1f))
                Metric("${stats.cards} 张", "学习卡片", Modifier.weight(1f))
            }
        }
        Section("今日小计划", "小一点，也可以完成。", icon = Icons.Default.Checklist,
            accent = MaterialTheme.colorScheme.primary) {
            // 空状态给出明确的下一步引导，而不是只显示「暂无数据」
            if (plans.isEmpty()) Text("今天想找一本书，还是安静自习一会儿？")
            plans.forEach { plan ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(plan.isCompleted, { onToggle(plan) })
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text(plan.title, style = MaterialTheme.typography.titleSmall,
                                textDecoration = if (plan.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (plan.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            // 目标数量是可选的，未填写时整行不显示
                            plan.targetValue?.let { Text("$it ${plan.unit}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        TextButton({ deletingPlan = plan }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            TextButton(onPlan) { Text("＋ 添加小计划") }
        }
        Section("今天留下的记录", icon = Icons.Default.History, accent = MaterialTheme.colorScheme.tertiary) {
            // 空状态解释「记录是怎么产生的」，因为本应用没有手动签到，用户需要知道规则
            if (visibleEvents.isEmpty()) Text("完成专注、记书、更新进度、写卡片或完成计划，会自动留下记录。",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 过滤掉内部计时事件，并按时间倒序展示，让最新发生的排在最上面。
            // 事件类型翻译成用户能读懂的动作描述
            visibleEvents.forEachIndexed { index, event ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when (event.type) { "BOOK_ADDED" -> "记下一本书"; "CARD_ADDED" -> "写下卡片"; "PROGRESS" -> "阅读 +${event.pages} 页"; "PLAN_COMPLETED" -> "完成计划"; "FOCUS_COMPLETED" -> "完成专注"; else -> "学习记录" },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    Text(event.title, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    deletingPlan?.let { plan ->
        ConfirmDelete("这项今日计划", { deletingPlan = null }) {
            deletingPlan = null
            onDelete(plan)
        }
    }
}

/**
 * 今日计划编辑器。
 *
 * 效率目标是「通过模板选择和少量输入即可完成」，因此这里预置 5 个模板，
 * 选择模板时自动填好标题、目标数量与单位（见 when 分支），
 * 大多数情况下用户只需点一下模板再点保存。
 *
 * 表单状态用 rememberSaveable，屏幕旋转或进程重建后用户已填内容不会丢失。
 *
 * @param today 新计划的日期，固定为今天
 * @param busy 保存进行中时禁用按钮，防止重复提交
 */
@Composable
fun PlanEditor(today: String, busy: Boolean, onSave: (DailyPlanEntity) -> Unit) {
    val templates = listOf("找一本书", "阅读一定页数", "自习一定分钟", "写一张卡片", "自定义任务")
    // 默认落在「自习一定分钟」上，因为这是图书馆场景最常用的计划
    var type by rememberSaveable { mutableStateOf(templates[2]) }
    var title by rememberSaveable { mutableStateOf("安静自习一会儿") }
    var target by rememberSaveable { mutableStateOf("25") }
    var unit by rememberSaveable { mutableStateOf("分钟") }
    // 表单错误就地展示，不走 Snackbar，避免与输入框分离导致用户没注意到
    var error by remember { mutableStateOf<String?>(null) }
    PageColumn {
        Text("给今天一个小计划", style = MaterialTheme.typography.headlineSmall)
        Choice("模板", type, templates.map { it to it }) {
            type = it
            // 选模板即预填三个字段，减少输入量；「自定义任务」则清空让用户自己写
            when (it) {
                templates[0] -> { title = "找到一本想读的书"; target = "1"; unit = "次" }
                templates[1] -> { title = "读几页书"; target = "20"; unit = "页" }
                templates[2] -> { title = "安静自习一会儿"; target = "25"; unit = "分钟" }
                templates[3] -> { title = "写下一张卡片"; target = "1"; unit = "张" }
                else -> { title = ""; target = ""; unit = "次" }
            }
        }
        Field("计划标题 *", title, { title = it })
        Field("目标数量（可选）", target, { target = it }, numeric = true)
        Field("单位", unit, { unit = it })
        ErrorText(error)
        // 本地先校验再交给仓库：这样错误提示是即时的，不必等一次数据库往返
        Button({ try {
            require(title.isNotBlank()) { "请填写计划标题" }
            val value = optionalInt(target, "目标数量")
            error = null
            onSave(DailyPlanEntity(date = today, type = type, title = title, targetValue = value, unit = unit))
        } catch (e: IllegalArgumentException) { error = e.message } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存今日计划") }
    }
}

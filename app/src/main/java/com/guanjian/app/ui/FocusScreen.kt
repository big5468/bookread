package com.guanjian.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.guanjian.app.data.*
import kotlinx.coroutines.delay

/** 专注活动类型。与需求列出的五类一致。 */
private val activityTypes = listOf("阅读", "寻找书籍", "课程学习", "写作或整理", "自由自习")

/**
 * 专注页。同一个 Composable 按 [active] 是否为空切换两种形态：
 * 未开始时是活动选择界面，开始后是计时界面。
 *
 * 计时显示的关键实现：
 * **界面不维护「已过时长」这个累计值**，只每秒读取一次当前时间，
 * 然后交由 [com.guanjian.app.domain.FocusClock.duration] 现算。
 * 这样做的直接好处是——应用切到后台、进程被杀、屏幕旋转，甚至设备重启，
 * 回到界面时显示的时长都自动是正确的，不存在需要「补上丢失的时间」的问题。
 *
 * @param clock 只读的时间来源，用于每秒取值
 * @param initialBookId 从书籍详情页进入时预选的书籍
 * @param onEnd 用户点「结束」，进入填写总结的状态（此时计时已暂停）
 * @param onSave 保存本次专注，参数依次为：专注 id、起始页、结束页、感受、完成的计划 id
 */
@Composable
fun FocusScreen(active: ActiveFocusEntity?, books: List<BookEntity>, plans: List<DailyPlanEntity>,
    initialBookId: String?, clock: LibraryClock, busy: Boolean,
    onStart: (String, String?) -> Unit, onPause: () -> Unit, onResume: () -> Unit,
    onEnd: () -> Unit, onSwitch: (String, String?) -> Unit,
    onSave: (String, Int?, Int?, String, List<String>) -> Unit) {

    // ---------- 形态一：尚未开始，选择活动 ----------
    if (active == null) {
        // 从书籍详情页进来时默认选中「阅读」并预置书籍，减少两次点击
        var type by rememberSaveable(initialBookId) { mutableStateOf(if (initialBookId != null) "阅读" else "自由自习") }
        var book by rememberSaveable(initialBookId) { mutableStateOf(initialBookId ?: "") }
        PageColumn {
            Text("这一刻，想做什么？", style = MaterialTheme.typography.headlineMedium)
            Text("选好就开始。切到后台也会继续计时。")
            Choice("活动", type, activityTypes.map { it to it }) { type = it }
            SearchableChoice("关联书籍（可选）", book, listOf("" to "不关联") + books.map { it.id to it.title }) { book = it }
            Button({ onStart(type, book.ifBlank { null }) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !busy) { Text("现在开始") }
        }
        return
    }

    // ---------- 形态二：计时中 / 待填写总结 ----------
    // now 与 elapsed 每秒刷新，仅作为「触发重算」的信号；真实时长由 FocusClock 现算
    var now by remember { mutableLongStateOf(clock.wallMillis()) }
    var elapsed by remember { mutableLongStateOf(clock.elapsedMillis()) }
    // 以 active.id 为 key：切换到另一次专注时重启这个每秒循环
    LaunchedEffect(active.id) { while (true) { now = clock.wallMillis(); elapsed = clock.elapsedMillis(); delay(1000) } }
    // 每次重组都重新读取开机次数，以便及时识别「设备重启过」的情况
    val boot = clock.bootCount()
    val duration = active.clock.duration(now, elapsed, boot)
    val seconds = duration / 1000
    // 用 HH:mm:ss 手工格式化，超过 24 小时也只是小时数继续增长，不会回绕
    val time = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

    // 总结表单状态，以 active.id 为 key
    var startPage by rememberSaveable(active.id) { mutableStateOf(active.startPage?.toString() ?: "") }
    var endPage by rememberSaveable(active.id) { mutableStateOf("") }
    var reflection by rememberSaveable(active.id) { mutableStateOf("") }
    var selected by rememberSaveable(active.id) { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var switchPrompt by rememberSaveable(active.id, initialBookId) {
        mutableStateOf(initialBookId != null && active.bookId != initialBookId)
    }

    PageColumn {
        Text(if (active.awaitingSummary) "把这段时间留下来" else "安心专注", style = MaterialTheme.typography.headlineMedium)
        Text(active.activityType, style = MaterialTheme.typography.titleLarge)
        // 关联书籍可能已被删除，取不到就不显示，不影响计时
        books.find { it.id == active.bookId }?.let { Text(it.title) }
        Text(time, style = MaterialTheme.typography.displayLarge)
        Text(if (active.clock.running) "正在专注" else "已暂停 · 暂停时间不计入专注")
        // 重启恢复提示：此时时长靠墙钟推算，可能不准，因此用错误色提醒用户核对
        if (active.recoveredAfterReboot || boot != active.clock.bootCount) Text("检测到设备重启，已依据保存时间恢复，请核对本次时长。", color = MaterialTheme.colorScheme.error)
        if (!active.awaitingSummary) {
            // 计时中：暂停/继续 + 结束。同一位置切换按钮文案，保持操作位置稳定
            Button(if (active.clock.running) onPause else onResume, Modifier.fillMaxWidth(), enabled = !busy) {
                Text(if (active.clock.running) "暂停" else "继续")
            }
            OutlinedButton(onEnd, Modifier.fillMaxWidth(), enabled = !busy) { Text("结束并写一句总结") }
        } else {
            // 待总结：只在与阅读相关时显示页码字段，纯自习不必填
            if (active.activityType == "阅读" || active.bookId != null) {
                Field("阅读起始页（可选）", startPage, { startPage = it }, numeric = true)
                Field("阅读结束页（可选）", endPage, { endPage = it }, numeric = true)
            }
            Field("一句话感受（可选）", reflection, { reflection = it }, multiline = true)
            // 只列出未完成的计划：已完成的再勾一次没有意义
            if (plans.any { !it.isCompleted }) Section("这次完成了哪个计划？") {
                plans.filter { !it.isCompleted }.forEach { plan ->
                    Row {
                        Checkbox(plan.id in selected, { checked -> selected = if (checked) selected + plan.id else selected - plan.id })
                        Text(plan.title, Modifier.padding(top = 12.dp))
                    }
                }
            }
            ErrorText(error)
            Button({ try {
                val start = optionalInt(startPage, "起始页")
                val end = optionalInt(endPage, "结束页")
                // 就地校验页码顺序，避免一次注定失败的入库
                require(end == null || start == null || end >= start) { "结束页不能小于起始页" }
                error = null
                onSave(active.id, start, end, reflection, selected)
            } catch (e: IllegalArgumentException) { error = e.message } }, Modifier.fillMaxWidth(), enabled = !busy) { Text("保存本次专注") }
            // 撤销结束意图，回到计时状态（仓库层会同步清掉 awaitingSummary）
            TextButton(onResume, enabled = !busy) { Text("还想继续，返回计时") }
        }
    }
    if (switchPrompt && initialBookId != null) {
        val currentName = books.find { it.id == active.bookId }?.title ?: active.activityType
        val nextName = books.find { it.id == initialBookId }?.title ?: "目标书籍"
        AlertDialog(
            onDismissRequest = { switchPrompt = false },
            title = { Text("切换到《$nextName》？") },
            text = { Text("当前正在进行“$currentName”。切换后会保存已经记录的时长，然后立即为新书开始计时；本次不会自动填写结束页码。") },
            confirmButton = { TextButton({ switchPrompt = false; onSwitch("阅读", initialBookId) }, enabled = !busy) { Text("保存并切换") } },
            dismissButton = { TextButton({ switchPrompt = false }) { Text("继续当前任务") } }
        )
    }
}

package com.guanjian.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp

/*
 * 全应用共用的基础组件。集中的目的是让各页面的排版、间距、触控区域保持一致，
 * 而不是每个页面各写一套。
 */

/**
 * 页面根容器：可垂直滚动、统一 20dp 内边距、子项间距 16dp。
 *
 * 所有编辑型页面都用它，这样键盘弹出时内容可滚动（配合 Activity 的
 * adjustResize），小屏设备上不会被输入法遮住底部按钮。
 */
@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

/**
 * 卡片式分组区块，带可选副标题。
 * 用于把「这一周」「今日小计划」这类信息分组，形成清晰的视觉层级。
 */
@Composable
fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

/**
 * 文本输入框。
 *
 * @param numeric 数字键盘。注意它只影响键盘布局，不做输入过滤 ——
 *   真正的数值校验由调用方通过 [optionalInt] 完成，因为用户仍可能
 *   通过外接键盘或粘贴输入非数字内容。
 * @param multiline 多行输入（正文、备注、感受），此时关闭 singleLine 并给最小 3 行高度。
 */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, numeric: Boolean = false, multiline: Boolean = false) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = !multiline, minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text))
}

/**
 * 下拉选择器。
 *
 * @param options 键值对列表：first 是存入数据模型的值（如书籍 id），
 *   second 是界面上显示的文字（如书名）。分开是因为选项里往往要显示
 *   「不关联」这类占位项，而它的实际值是空字符串。
 *
 * 选中的项会回显其显示文字；若当前值不在选项中（例如关联的书已被删除），
 * 则退化为直接显示原始值，保证不出现空白按钮。
 * 按钮最小高度 48dp，满足触控区域要求。
 */
@Composable
fun Choice(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("$label：${options.find { it.first == value }?.second ?: value}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onChange(key); expanded = false }) }
        }
    }
}

/** 大量选项的本地搜索；空值选项始终保留，方便清除筛选或解除关联。 */
internal fun filterChoiceOptions(options: List<Pair<String, String>>, query: String): List<Pair<String, String>> =
    if (query.isBlank()) options else options.filter { it.first.isBlank() || it.second.contains(query.trim(), ignoreCase = true) }

/**
 * 面向大量书籍或标签的选择器。点击后从底部展开可滚动、可搜索列表，
 * 避免 DropdownMenu 在选项很多时形成又长又窄的悬浮菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableChoice(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val selected = options.find { it.first == value }?.second ?: value
    OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$label：$selected") }
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false; query = "" }) {
        Column(Modifier.fillMaxHeight(0.8f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("选择$label", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("搜索$label") }, placeholder = { Text("输入名称快速查找") })
            val visible = filterChoiceOptions(options, query)
            if (visible.isEmpty()) Text("没有找到匹配项", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visible, key = { it.first }) { (key, title) ->
                    val selectedItem = key == value
                    ListItem(
                        headlineContent = { Text(title) },
                        supportingContent = if (selectedItem) ({ Text("当前选择") }) else null,
                        trailingContent = { if (selectedItem) Text("✓", color = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(
                            containerColor = if (selectedItem) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surface
                        ),
                        modifier = Modifier.fillMaxWidth()
                            .border(
                                width = if (selectedItem) 1.5.dp else 1.dp,
                                color = if (selectedItem) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .clickable { onChange(key); expanded = false; query = "" }
                    )
                }
            }
        }
    }
}

/**
 * 删除前的二次确认对话框。
 *
 * 文案里明确了两件事，避免用户误判后果：
 * 一是删除不可恢复，二是已产生的学习记录会保留（因为统计基于 events 表，
 * 不是基于被删对象本身）。
 */
@Composable
fun ConfirmDelete(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除$title？") },
        text = { Text("删除后无法恢复。已经发生的学习记录会保留。") },
        confirmButton = { TextButton(onConfirm) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

/**
 * 把可选的数字输入解析成 Int?。
 *
 * 三种情况：
 * - 空白 → null（表示「未填写」，对本应用是合法状态）
 * - 合法的非负整数 → 该值
 * - 其他（含负数、小数、夹杂文字）→ 抛 IllegalArgumentException，
 *   消息可直接展示给用户。
 *
 * 返回可空类型是本应用贯穿始终的约定：页码、总页数、目标数量都允许不填，
 * 强制必填会显著抬高录入门槛。
 */
fun optionalInt(value: String, label: String): Int? {
    if (value.isBlank()) return null
    return value.toIntOrNull()?.takeIf { it >= 0 } ?: throw IllegalArgumentException("${label}请填写有效的非负整数")
}

/** 表单错误提示。error 为 null 时不占位，避免布局出现空白间隙。 */
@Composable
fun ErrorText(error: String?) { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }

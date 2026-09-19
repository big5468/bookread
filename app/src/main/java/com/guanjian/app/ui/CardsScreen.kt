package com.guanjian.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.guanjian.app.data.*
import java.time.Instant
import java.time.ZoneId

/**
 * 卡片页 —— 列表 + 搜索 + 按书籍/标签筛选。
 *
 * 筛选条件（搜索词、书籍、标签）用 rememberSaveable 保存，
 * 用户在列表里点进一张卡片再返回时，筛选状态不会被重置。
 *
 * @param cards 全部卡片（含标签），筛选在本页完成，因为条件是界面本地的临时状态
 * @param books 用于把 bookId 显示成书名，以及生成书籍筛选项
 */
@Composable
fun CardsScreen(cards: List<NoteWithTags>, books: List<BookEntity>, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var bookId by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("") }
    var filtersExpanded by rememberSaveable { mutableStateOf(false) }
    // 三个条件是「与」关系：空条件视为不限制。搜索用 contains(query, true) 忽略大小写，
    // 这样英文/拼音内容不会被大小写影响
    val filtered = remember(cards, bookId, tag, query) { cards.filter { (bookId.isBlank() || it.card.bookId == bookId) &&
        (tag.isBlank() || it.tags.any { t -> t.name == tag }) &&
        (query.isBlank() || it.card.content.contains(query, true)) } }
    val bookTitles = remember(books) { books.associate { it.id to it.title } }
    val bookOptions = remember(books) { listOf("" to "全部书籍") + books.map { it.id to it.title } }
    val tagOptions = remember(cards) { listOf("" to "全部标签") + cards.flatMap { it.tags }.map { it.name }.distinct().sorted().map { it to it } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("留下来的想法", style = MaterialTheme.typography.headlineSmall)
                Text("共 ${cards.size} 张卡片 · 一句话也值得记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onAdd, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) { Text("＋ 写一张卡片") }
            }
        }
        item {
            Section("查找卡片") {
                Field("搜索正文", query, { query = it })
                val selectedFilters = listOf(bookId, tag).count { it.isNotBlank() }
                TextButton({ filtersExpanded = !filtersExpanded }) {
                    Text((if (filtersExpanded) "收起筛选" else "按书籍或标签筛选") +
                        if (selectedFilters > 0) " · 已选 $selectedFilters 项" else "")
                }
                if (filtersExpanded) {
                    // 空字符串表示全部；收起筛选时仍保留并提示已选条件。
                    SearchableChoice("书籍", bookId, bookOptions) { bookId = it }
                    SearchableChoice("标签", tag, tagOptions) { tag = it }
                }
                if (bookId.isNotBlank() || tag.isNotBlank()) TextButton({ bookId = ""; tag = "" }) { Text("清除书籍和标签筛选") }
            }
        }
        item { Text("${filtered.size} 张卡片", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // 两种空状态文案不同：一张卡片都没有 vs 筛选后无结果，后者提示清除条件
        if (filtered.isEmpty()) item { Section("还没有符合条件的卡片") {
            Text(if (cards.isEmpty()) "一句话也值得留下。" else "试着清除搜索或筛选条件。")
            OutlinedButton(onAdd) { Text("记下一个想法") }
        } }
        items(filtered, key = { it.card.id }) { item ->
            Card(onClick = { onOpen(item.card.id) }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.card.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    // 只裁切列表预览，打开编辑页仍能看到数据库中的完整正文
                    Text(cardPreview(item.card.content), maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    // 书名、标签、日期分层显示，长文本只裁切列表预览。
                    val book = bookTitles[item.card.bookId]
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    book?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
                    if (item.tags.isNotEmpty()) Text(item.tags.joinToString("  ") { "#${it.name}" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Instant.ofEpochMilli(item.card.createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 卡片编辑器（新建与编辑共用）。
 *
 * 目标是「1 分钟内写完一张最小卡片」，因此界面只强推一个问题
 * 「今天有什么值得带走？」，并且只有正文是必填。
 *
 * 标题留空时由仓库层自动截取正文开头作为标题 —— 用户因此可以完全跳过标题字段。
 * 写作提示（5 个问题）默认折叠，只在没有思路时展开，不给日常使用增加负担。
 *
 * 第一版刻意使用普通多行文本，不做富文本编辑器。
 *
 * @param item 为 null 表示新建
 * @param bookId 从书籍详情页进入时预置的关联书籍
 * @param onAi 「用 AI 整理这张卡片」。仅在内容未修改时可用 ——
 *   否则 AI 结果会覆盖掉尚未保存的编辑，见 [changed] 的判断
 */
@Composable
fun CardEditor(item: NoteWithTags?, bookId: String?, books: List<BookEntity>, busy: Boolean,
    onSave: (NoteCardEntity, List<String>) -> Unit, onDelete: () -> Unit, onAi: () -> Unit) {
    // 以卡片 id 与预置 bookId 为 key 播种，保证「新建时带上关联书」与「编辑时载入原内容」都正确
    val seed = remember(item?.card?.id, bookId) { item?.card ?: NoteCardEntity(bookId = bookId) }
    var content by rememberSaveable(seed.id) { mutableStateOf(seed.content) }
    var title by rememberSaveable(seed.id) { mutableStateOf(seed.title) }
    var related by rememberSaveable(seed.id) { mutableStateOf(seed.bookId ?: "") }
    // 标签在界面上是逗号分隔的文本；同时接受中英文逗号，因为中文输入法常打出「，」
    var tags by rememberSaveable(seed.id) { mutableStateOf(item?.tags?.joinToString("，") { it.name } ?: "") }
    var prompts by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    PageColumn {
        Text("今天有什么值得带走？", style = MaterialTheme.typography.headlineSmall)
        Field("写下一句话或一个想法 *", content, { content = it }, multiline = true)
        TextButton({ prompts = !prompts }) { Text(if (prompts) "收起写作提示" else "没有思路？看看写作提示") }
        if (prompts) Section("任选一个问题开始") {
            listOf("哪个观点让你意外？", "它解决了你的什么问题？", "你不同意什么？", "它与你过去的哪条想法有关？", "下一次准备继续探索什么？").forEach { Text("· $it") }
        }
        Field("标题（留空自动生成）", title, { title = it })
        SearchableChoice("关联书籍", related, listOf("" to "不关联") + books.map { it.id to it.title }) { related = it }
        Field("标签（用逗号分隔）", tags, { tags = it })
        ErrorText(error)
        Button({
            // 正文是唯一必填项；这里就地校验，省掉一次无意义的数据库往返
            if (content.isBlank()) error = "请先写下一点内容" else {
                error = null
                onSave(seed.copy(title = title, content = content, bookId = related.ifBlank { null }), tags.split(',', '，').map { it.trim() }.filter { it.isNotBlank() })
            }
        }, Modifier.fillMaxWidth(), enabled = !busy) { Text("保存卡片") }
        // 已有卡片才提供 AI 整理与删除
        if (item != null) {
            // 与最近一次保存的内容比对，判断是否有未保存的修改
            val changed = title != seed.title || content != seed.content || related != (seed.bookId ?: "") || tags != item.tags.joinToString("，") { it.name }
            // changed 时禁用 AI 按钮：必须先保存，否则「替换正文」会覆盖用户未保存的编辑
            OutlinedButton(onAi, Modifier.fillMaxWidth(), enabled = !busy && !changed) { Text("用 AI 整理这张卡片") }
            Text(if (changed) "请先保存修改，再整理卡片。" else "将先展示发送内容；AI 结果需确认保存才会替换正文。", style = MaterialTheme.typography.bodySmall)
            TextButton({ deleting = true }) { Text("删除卡片", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (deleting) ConfirmDelete("这张卡片", { deleting = false }) { deleting = false; onDelete() }
}

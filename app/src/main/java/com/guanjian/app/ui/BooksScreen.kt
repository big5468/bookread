package com.guanjian.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.guanjian.app.data.*
import com.guanjian.app.domain.readingProgress

/** 阅读状态的合法取值。作为单一常量源，供编辑器下拉框与校验共用。 */
val bookStatuses = listOf("想找", "已找到", "在读", "读完", "暂时放弃")

/** 详情页快速改进度：只替换当前页，其余书籍资料原样交回 Repository。 */
internal fun quickProgressBook(book: BookEntity, input: String): BookEntity =
    book.copy(currentPage = optionalInt(input.trim(), "当前页"))

/**
 * 书墙 —— 卡片网格形式的书籍列表。
 *
 * 用 LazyVerticalGrid + GridCells.Adaptive(145dp)：列数随屏幕宽度自适应，
 * 手机两列、平板更多列，不必为不同尺寸写分支。
 *
 * 表头与空状态都作为跨整行的 item 放进网格（span = maxLineSpan），
 * 这样它们会跟随网格一起滚动，而不是固定在顶部。
 *
 * items 指定 key = it.id：让 Compose 在列表变化时能正确复用与移动条目，
 * 而不是全部重建。
 *
 * @param onAdd 新增书籍
 * @param onOpen 打开某本书的详情
 */
@Composable
fun BooksScreen(books: List<BookEntity>, onAdd: () -> Unit, onOpen: (String) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(145.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("我的阅读书架", style = MaterialTheme.typography.headlineSmall)
                Text("共 ${books.size} 本 · 记住位置，下次接着读", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onAdd, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) { Text("＋ 记一本书") }
            }
        }
        // 空状态给出明确的第一步动作，而不是一句「暂无书籍」
        if (books.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Section("书墙还空着", "从今天想找的一本书开始。") { OutlinedButton(onAdd) { Text("记录第一本书") } }
        }
        items(books, key = { it.id }) { book ->
            Card(onClick = { onOpen(book.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                BookCover(book)
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    // 作者未填时显示占位文案，保持卡片高度一致，避免网格参差不齐
                    Text(book.author.ifBlank { "作者未填写" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 总页数未填时只显示当前页，不显示「/null」
                    Text(book.status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("已读 ${book.currentPage ?: 0}${book.totalPages?.let { " / $it" } ?: ""} 页",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if ((book.totalPages ?: 0) > 0) LinearProgressIndicator(
                        progress = { readingProgress(book.currentPage, book.totalPages, book.status == "读完") },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.tertiaryContainer)
                    else Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * 书籍封面。
 *
 * 没有上传封面时，用「书名生成的占位封面」代替空白图：
 * - 底色从 4 种柔和色中按书名哈希取一个，因此同一本书的颜色始终一致，
 *   不同书之间又能区分开。
 * - 颜色的饱和度按阅读进度插值（lerp）：未开始偏灰、读完变彩色。
 *   这条视觉规则让用户在书墙上扫一眼就能看出整体阅读状态，
 *   是需求明确要求的「阅读进度的视觉规则」。
 *
 * 有封面时用同样的饱和度矩阵处理，保证两种情况的观感一致。
 */
@Composable
fun BookCover(book: BookEntity) {
    val progress = readingProgress(book.currentPage, book.totalPages, book.status == "读完")
    val hues = listOf(Color(0xFF8B9E75), Color(0xFFC59B71), Color(0xFF879DA6), Color(0xFFB38C86))
    // 用书名哈希选色：同一书名恒定，且与 id 无关，即使重装应用颜色也不变
    val hue = hues[(book.title.hashCode().toLong().let { kotlin.math.abs(it) } % hues.size).toInt()]
    Box(Modifier.fillMaxWidth().height(180.dp).background(lerp(Color(0xFF9E9E94), hue, progress)), contentAlignment = Alignment.Center) {
        if (book.coverUri != null) {
            // colorFilter 用饱和度矩阵实现「按进度恢复色彩」，不需要生成额外的图片副本
            AsyncImage(book.coverUri, "${book.title}封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(progress) }))
        } else {
            Text(book.title, Modifier.padding(20.dp), maxLines = 4, color = Color(0xFF20261D), style = MaterialTheme.typography.headlineSmall)
        }
        // 读完的完成标记，叠加在封面右上角
        if (book.status == "读完") Surface(Modifier.align(Alignment.TopEnd).padding(8.dp), shape = MaterialTheme.shapes.small) {
            Text("✓ 读完", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 书籍详情页。
 *
 * 页面的信息层级：基本信息与进度 → 位置（下次去哪找）→ 两个行动入口
 * （开始阅读 / 写卡片）→ 关联卡片 → 删除。
 *
 * 位置信息用「·」连接所有非空字段再展示，未填任何位置时给出引导文案 ——
 * 因为「大概位置」是本应用帮助用户下次找到书的核心价值点。
 *
 * @param book 为 null 表示这本书已被删除（例如从详情页删除后返回栈仍持有该路由）
 * @param cards 已由上层过滤为本书记的卡片
 */
@Composable
fun BookDetailScreen(book: BookEntity?, cards: List<NoteWithTags>, busy: Boolean, onEdit: () -> Unit, onRead: () -> Unit,
    onProgress: (String) -> Unit, onCard: () -> Unit, onOpenCard: (String) -> Unit, onDelete: () -> Unit) {
    // 删除确认对话框的显示状态。不需要 rememberSaveable：旋转后重新确认更安全
    var deleting by remember { mutableStateOf(false) }
    if (book == null) { PageColumn { Text("这本书已不存在。") }; return }
    var currentPage by rememberSaveable(book.id, book.currentPage) { mutableStateOf(book.currentPage?.toString().orEmpty()) }
    PageColumn {
        Text(book.title, style = MaterialTheme.typography.headlineMedium)
        Text(book.author.ifBlank { "作者未填写" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onRead, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = MaterialTheme.shapes.medium) { Text("开始阅读这本书") }
        OutlinedButton(onCard, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) { Text("为这本书写卡片") }
        Section("${book.status} · 阅读进度") {
            LinearProgressIndicator(progress = { readingProgress(book.currentPage, book.totalPages, book.status == "读完") },
                modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.tertiaryContainer)
            Text("${book.currentPage ?: 0} / ${book.totalPages?.toString() ?: "未知"} 页")
            Field("当前已读页数", currentPage, { currentPage = it }, numeric = true)
            Button(
                onClick = { onProgress(currentPage) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && currentPage.trim() != book.currentPage?.toString().orEmpty()
            ) { Text(if (busy) "正在保存……" else "保存已读页数") }
            Text("读到哪里，就记到哪里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onEdit) { Text("编辑书籍和位置") }
        }
        Section("下次去这里找") {
            Text(listOf(book.libraryName, book.floor, book.categoryArea, book.shelfHint, book.locationNote).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "还没有记录位置，编辑时可补充。" })
            if (book.note.isNotBlank()) Text(book.note)
        }
        if (listOf(book.isbn, book.publisher, book.publishedDate, book.description).any { it.isNotBlank() }) Section("出版信息") {
            if (book.isbn.isNotBlank()) Text("ISBN：${book.isbn}")
            if (book.publisher.isNotBlank()) Text("出版社：${book.publisher}")
            if (book.publishedDate.isNotBlank()) Text("出版时间：${book.publishedDate}")
            if (book.description.isNotBlank()) Text(book.description)
        }
        Text("关联卡片", style = MaterialTheme.typography.titleLarge)
        if (cards.isEmpty()) Text("读到值得带走的想法，就记一张卡片。")
        cards.forEach { card -> Card(onClick = { onOpenCard(card.card.id) }, modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(card.card.title, style = MaterialTheme.typography.titleMedium)
                Text(cardPreview(card.card.content), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        } }
        // 用错误色标识破坏性操作
        TextButton({ deleting = true }) { Text("删除书籍", color = MaterialTheme.colorScheme.error) }
    }
    if (deleting) ConfirmDelete("这本书", { deleting = false }) { deleting = false; onDelete() }
}

/**
 * 书籍编辑器（新增与编辑共用）。
 *
 * 「低负担录入」是本页的设计核心，因此字段分两批：
 * - **默认可见**：书名、大概位置、阅读状态 —— 需求要求「30 秒内记录一本书及其大概位置」，
 *   这三项足够完成该目标。
 * - **「更多信息」折叠区内**：作者、当前页、总页数、图书馆/楼层/分区/书架、备注、封面。
 *   需要时再展开，避免首次录入就被大量字段劝退。
 *
 * 表单状态一律以 rememberSaveable 保存、并以 seed.id 作为 key：
 * 编辑不同书籍时不会串数据，屏幕旋转后已填内容也不丢失。
 * 注意 seed 用 remember(book?.id) 而不是 remember(book)：外部数据变化时
 * 不应重置用户正在编辑的内容，只有切换到另一本书才重新播种。
 *
 * @param busy 保存中禁用按钮
 * @param onImportCover 封面导入。回调式设计（uri + done）是因为真正的解码与
 *   落盘由上层在 IO 线程完成，本页只负责触发与显示
 */
@Composable
fun BookEditor(
    book: BookEntity?,
    busy: Boolean,
    metadataSelection: BookMetadataSelection?,
    onMetadataConsumed: () -> Unit,
    onLookup: (String) -> Unit,
    onImportCover: (android.net.Uri, (String) -> Unit) -> Unit,
    onSave: (BookEntity) -> Unit
) {
    val seed = remember(book?.id) { book ?: BookEntity() }
    // 每个字段都以 seed.id 作为 rememberSaveable 的 key，切换书籍时重新播种
    var title by rememberSaveable(seed.id) { mutableStateOf(seed.title) }
    var author by rememberSaveable(seed.id) { mutableStateOf(seed.author) }
    var isbn by rememberSaveable(seed.id) { mutableStateOf(seed.isbn) }
    var publisher by rememberSaveable(seed.id) { mutableStateOf(seed.publisher) }
    var publishedDate by rememberSaveable(seed.id) { mutableStateOf(seed.publishedDate) }
    var description by rememberSaveable(seed.id) { mutableStateOf(seed.description) }
    var location by rememberSaveable(seed.id) { mutableStateOf(seed.locationNote) }
    var library by rememberSaveable(seed.id) { mutableStateOf(seed.libraryName) }
    var floor by rememberSaveable(seed.id) { mutableStateOf(seed.floor) }
    var area by rememberSaveable(seed.id) { mutableStateOf(seed.categoryArea) }
    var shelf by rememberSaveable(seed.id) { mutableStateOf(seed.shelfHint) }
    var status by rememberSaveable(seed.id) { mutableStateOf(seed.status) }
    // 页码用字符串保存而非 Int：空字符串才能表达「未填写」这个合法状态
    var current by rememberSaveable(seed.id) { mutableStateOf(seed.currentPage?.toString() ?: "") }
    var total by rememberSaveable(seed.id) { mutableStateOf(seed.totalPages?.toString() ?: "") }
    var note by rememberSaveable(seed.id) { mutableStateOf(seed.note) }
    var cover by rememberSaveable(seed.id) { mutableStateOf(seed.coverUri) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 系统图片选择器（GetContent）。返回的 uri 是临时授权，因此必须立刻复制到私有目录
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { onImportCover(it) { saved -> cover = saved } } }
    LaunchedEffect(metadataSelection) {
        metadataSelection?.let { selection ->
            val draft = seed.copy(
                title = title, author = author, isbn = isbn, publisher = publisher, publishedDate = publishedDate,
                description = description, locationNote = location, libraryName = library, floor = floor,
                categoryArea = area, shelfHint = shelf, status = status, currentPage = current.toIntOrNull(),
                totalPages = total.toIntOrNull(), note = note, coverUri = cover
            )
            val merged = mergeBookMetadata(draft, selection)
            title = merged.title
            author = merged.author
            isbn = merged.isbn
            publisher = merged.publisher
            publishedDate = merged.publishedDate
            description = merged.description
            total = merged.totalPages?.toString().orEmpty()
            cover = merged.coverUri
            advanced = true
            onMetadataConsumed()
        }
    }
    PageColumn {
        Text(if (book == null) "记下一本书" else "编辑书籍", style = MaterialTheme.typography.headlineSmall)
        Field("书名 *", title, { title = it })
        OutlinedButton({ onLookup(isbn.ifBlank { title }) }, Modifier.fillMaxWidth(), enabled = !busy) {
            Text("搜索书籍信息（ISBN / 书名）")
        }
        // 位置提示语里直接给了一个范例，降低「该怎么填」的思考成本
        Field("大概位置（如三楼西侧，靠窗第二排）", location, { location = it }, multiline = true)
        Choice("阅读状态", status, bookStatuses.map { it to it }) { status = it }
        TextButton({ advanced = !advanced }) { Text(if (advanced) "收起更多信息" else "更多信息：作者、页码、封面、分区") }
        if (advanced) {
            Field("作者", author, { author = it })
            Field("ISBN", isbn, { isbn = it })
            Field("出版社", publisher, { publisher = it })
            Field("出版时间", publishedDate, { publishedDate = it })
            Field("书籍简介", description, { description = it }, multiline = true)
            Field("当前阅读页", current, { current = it }, numeric = true)
            Field("总页数", total, { total = it }, numeric = true)
            Field("图书馆或校区", library, { library = it })
            Field("楼层", floor, { floor = it })
            Field("分类区域", area, { area = it })
            Field("书架或附近特征", shelf, { shelf = it })
            Field("备注", note, { note = it }, multiline = true)
            OutlinedButton({ picker.launch("image/*") }) { Text(if (cover == null) "选择封面图片" else "更换封面图片") }
            if (cover != null) { AsyncImage(cover, "封面预览", Modifier.height(140.dp)); TextButton({ cover = null }) { Text("移除封面") } }
        }
        ErrorText(error)
        // 保存前做与仓库层一致的校验：页码不能超过总页数。就地校验能让用户立刻看到问题，
        // 且避免用一次无效写入去试探后端约束
        Button(onClick = {
            try {
                require(title.isNotBlank()) { "请填写书名" }
                val p = optionalInt(current, "当前阅读页")
                val t = optionalInt(total, "总页数")
                require(t == null || t > 0) { "总页数需要大于零" }
                require(p == null || t == null || p <= t) { "当前阅读页不能超过总页数" }
                error = null
                // 从 seed 复制而不是新建实体：保留 id、createdAt 等不由本页管理的字段
                onSave(seed.copy(title = title.trim(), author = author.trim(), isbn = isbn.trim(), publisher = publisher.trim(),
                    publishedDate = publishedDate.trim(), description = description.trim(), locationNote = location.trim(),
                    libraryName = library.trim(), floor = floor.trim(), categoryArea = area.trim(), shelfHint = shelf.trim(),
                    status = status, currentPage = p, totalPages = t, note = note, coverUri = cover))
            } catch (e: IllegalArgumentException) { error = e.message }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("保存书籍") }
    }
}

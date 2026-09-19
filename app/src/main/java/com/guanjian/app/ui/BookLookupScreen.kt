package com.guanjian.app.ui

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.guanjian.app.books.BookMetadata

data class BookLookupUiState(
    val query: String = "",
    val loading: Boolean = false,
    val hasSearched: Boolean = false,
    val books: List<BookMetadata> = emptyList(),
    val error: String? = null
)

/** ISBN / 书名搜索与候选选择页。选择只填回编辑表单，不直接写数据库。 */
@Composable
fun BookLookupScreen(
    state: BookLookupUiState,
    selecting: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    onSelect: (BookMetadata, Boolean) -> Unit
) {
    BackHandler(onBack = onClose)
    var overwrite by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("搜索书籍信息", style = MaterialTheme.typography.headlineSmall)
                Text("输入 ISBN 可精确查找，也可以输入书名查找多个版本。", style = MaterialTheme.typography.bodyMedium)
                Field("ISBN 或书名", state.query, onQueryChange)
                Button(onSearch, Modifier.fillMaxWidth(), enabled = !state.loading && !selecting && state.query.isNotBlank()) {
                    Text("搜索")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(checked = overwrite, onCheckedChange = { overwrite = it }, enabled = !selecting)
                    Column {
                        Text("覆盖已填写的出版信息")
                        Text("关闭时只填空白项；位置、阅读状态和个人备注永远不会覆盖。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("只向豆瓣读书发送上面的 ISBN 或书名；个人记录不会上传。", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (state.loading || selecting) item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text(if (selecting) "正在把封面保存到本机……" else "正在查找可用版本……")
            }
        }
        state.error?.let { message -> item { ErrorText(message) } }
        if (state.hasSearched && !state.loading && state.error == null && state.books.isEmpty()) item {
            Section("没有找到匹配的书") { Text("可以缩短书名、改用 ISBN，或者返回继续手工填写。") }
        }
        items(state.books, key = { it.sourceKey }) { book ->
            Card(onClick = { if (!selecting) onSelect(book, overwrite) }, modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (book.coverUrl != null) AsyncImage(
                        model = book.coverUrl,
                        contentDescription = "${book.title}候选封面",
                        modifier = Modifier.width(82.dp).height(116.dp),
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium)
                        if (book.author.isNotBlank()) Text(book.author)
                        val edition = listOf(book.publisher, book.publishedDate, book.totalPages?.let { "$it 页" }.orEmpty())
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        if (edition.isNotBlank()) Text(edition, style = MaterialTheme.typography.bodySmall)
                        if (book.isbn.isNotBlank()) Text("ISBN ${book.isbn}", style = MaterialTheme.typography.labelSmall)
                        if (book.description.isNotBlank()) Text(book.description, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                        Text("点此填入编辑页", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

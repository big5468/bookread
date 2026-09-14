package com.guanjian.app.ui

import com.guanjian.app.books.BookMetadata
import com.guanjian.app.data.BookEntity

data class BookMetadataSelection(
    val metadata: BookMetadata,
    val overwriteExisting: Boolean,
    val localCoverUri: String? = null
)

/**
 * 只合并公开出版信息。图书馆位置、阅读状态、当前页、个人备注、主键和时间戳
 * 都来自原表单，因此一次网络查询永远不会覆盖用户自己的阅读数据。
 */
internal fun mergeBookMetadata(book: BookEntity, selection: BookMetadataSelection): BookEntity {
    val source = selection.metadata
    fun text(old: String, incoming: String) = when {
        incoming.isBlank() -> old
        selection.overwriteExisting || old.isBlank() -> incoming.trim()
        else -> old
    }
    return book.copy(
        title = text(book.title, source.title),
        author = text(book.author, source.author),
        isbn = text(book.isbn, source.isbn),
        publisher = text(book.publisher, source.publisher),
        publishedDate = text(book.publishedDate, source.publishedDate),
        description = text(book.description, source.description),
        totalPages = if (source.totalPages != null && (selection.overwriteExisting || book.totalPages == null)) source.totalPages else book.totalPages,
        coverUri = if (selection.localCoverUri != null && (selection.overwriteExisting || book.coverUri == null)) selection.localCoverUri else book.coverUri
    )
}

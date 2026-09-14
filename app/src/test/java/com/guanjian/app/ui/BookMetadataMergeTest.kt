package com.guanjian.app.ui

import com.guanjian.app.books.BookMetadata
import com.guanjian.app.data.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BookMetadataMergeTest {
    private val metadata = BookMetadata(
        sourceKey = "douban:1", title = "刮削书名", author = "刮削作者", publisher = "刮削出版社",
        publishedDate = "2024", isbn = "9787111547426", totalPages = 320, description = "刮削简介"
    )

    @Test fun defaultMergeFillsOnlyEmptyMetadataAndPreservesPersonalFields() {
        val original = BookEntity(
            id = "local-id", title = "我的书名", author = "", publisher = "原出版社", libraryName = "市图书馆",
            floor = "3楼", locationNote = "靠窗", status = "在读", currentPage = 42, totalPages = null,
            note = "私人备注", createdAt = 10, updatedAt = 20
        )

        val merged = mergeBookMetadata(original, BookMetadataSelection(metadata, overwriteExisting = false, localCoverUri = "/covers/local.jpg"))

        assertEquals("我的书名", merged.title)
        assertEquals("刮削作者", merged.author)
        assertEquals("原出版社", merged.publisher)
        assertEquals(320, merged.totalPages)
        assertEquals("/covers/local.jpg", merged.coverUri)
        assertEquals("local-id", merged.id)
        assertEquals("市图书馆", merged.libraryName)
        assertEquals("3楼", merged.floor)
        assertEquals("靠窗", merged.locationNote)
        assertEquals("在读", merged.status)
        assertEquals(42, merged.currentPage)
        assertEquals("私人备注", merged.note)
        assertEquals(10, merged.createdAt)
        assertEquals(20, merged.updatedAt)
    }

    @Test fun overwriteReplacesPublicationFieldsButNeverPersonalFields() {
        val original = BookEntity(
            id = "local-id", title = "旧书名", author = "旧作者", isbn = "old", publisher = "旧出版社",
            publishedDate = "1999", description = "旧简介", libraryName = "校图书馆", status = "读完",
            currentPage = 100, totalPages = 100, note = "自己的理解"
        )

        val merged = mergeBookMetadata(original, BookMetadataSelection(metadata, overwriteExisting = true))

        assertEquals("刮削书名", merged.title)
        assertEquals("刮削作者", merged.author)
        assertEquals("9787111547426", merged.isbn)
        assertEquals("刮削出版社", merged.publisher)
        assertEquals("2024", merged.publishedDate)
        assertEquals("刮削简介", merged.description)
        assertEquals(320, merged.totalPages)
        assertEquals("校图书馆", merged.libraryName)
        assertEquals("读完", merged.status)
        assertEquals(100, merged.currentPage)
        assertEquals("自己的理解", merged.note)
    }
}

package com.guanjian.app.ui

import com.guanjian.app.data.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BookQuickProgressTest {
    @Test fun quickProgressChangesOnlyCurrentPage() {
        val original = BookEntity(
            id = "book-1", title = "救猫咪", author = "布莱克·斯奈德",
            publisher = "浙江文艺出版社", libraryName = "市图书馆", floor = "三楼",
            status = "在读", currentPage = 20, totalPages = 232, note = "私人备注",
            createdAt = 100, updatedAt = 200
        )

        val updated = quickProgressBook(original, " 86 ")

        assertEquals(86, updated.currentPage)
        assertEquals(original.copy(currentPage = 86), updated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun quickProgressRejectsInvalidNumber() {
        quickProgressBook(BookEntity(title = "测试书"), "第十页")
    }
}

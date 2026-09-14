package com.guanjian.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchableChoiceTest {
    private val options = listOf("" to "全部书籍", "1" to "活着", "2" to "如何阅读一本书", "3" to "代码大全")

    @Test fun searchableChoicesFindMatchesAndKeepEmptyOptionAvailable() {
        assertEquals(listOf("" to "全部书籍", "2" to "如何阅读一本书"), filterChoiceOptions(options, "阅读"))
    }

    @Test fun blankSearchKeepsOriginalOrder() {
        assertEquals(options, filterChoiceOptions(options, ""))
    }
}

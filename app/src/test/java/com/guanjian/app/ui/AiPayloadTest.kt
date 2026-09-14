package com.guanjian.app.ui

import com.guanjian.app.data.ActivityEventEntity
import com.guanjian.app.data.BookEntity
import com.guanjian.app.data.DailyPlanEntity
import com.guanjian.app.data.FocusSessionEntity
import com.guanjian.app.data.NoteCardEntity
import com.guanjian.app.data.NoteWithTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPayloadTest {
    private val bookId = "11111111-1111-4111-8111-111111111111"
    private val cardId = "22222222-2222-4222-8222-222222222222"
    private val eventId = "33333333-3333-4333-8333-333333333333"
    private val sessionId = "44444444-4444-4444-8444-444444444444"
    private val planId = "55555555-5555-4555-8555-555555555555"

    @Test
    fun reviewPayloadUsesReadableEvidenceWithoutInternalIds() {
        val state = LibraryUiState(
            books = listOf(BookEntity(id = bookId, title = "阅读之路")),
            cards = listOf(NoteWithTags(NoteCardEntity(id = cardId, title = "关键想法", content = "每天读十页", bookId = bookId), emptyList())),
            plans = listOf(DailyPlanEntity(id = planId, date = "2026-09-09", title = "阅读十页", targetValue = 10, unit = "页")),
            sessions = listOf(FocusSessionEntity(id = sessionId, activityType = "阅读", bookId = bookId, startedAt = 1, endedAt = 2, startPage = 10, endPage = 20, reflection = "更专注了")),
            events = listOf(
                ActivityEventEntity(id = eventId, date = "2026-09-09", type = "FOCUS_COMPLETED", sourceId = sessionId, title = "完成阅读", createdAt = 1, focusMillis = 1_500_000),
                ActivityEventEntity(date = "2026-09-09", type = "CARD_ADDED", sourceId = cardId, title = "新增卡片", createdAt = 2)
            )
        )

        val payload = reviewPayload("2026-09-07", state)

        listOf(bookId, cardId, eventId, sessionId, planId).forEach { assertFalse(payload.contains(it)) }
        assertTrue(payload.contains("阅读之路"))
        assertTrue(payload.contains("2026-09-09"))
        assertTrue(payload.contains("关键想法"))
        assertTrue(payload.contains("不要输出数据库字段"))
    }

    @Test
    fun sanitizingAiOutputRemovesInternalFieldsAndUuidsButKeepsReadingFacts() {
        val raw = """
            建议继续阅读《阅读之路》第 42 页。
            bookId: $bookId
            {"cardId":"$cardId","summary":"每天读十页"}
            参考记录：$eventId
            ISBN 978-7-111-54742-6，日期 2026-09-09。
        """.trimIndent()

        val cleaned = sanitizeAiOutput(raw)

        assertFalse(cleaned.contains(bookId))
        assertFalse(cleaned.contains(cardId))
        assertFalse(cleaned.contains(eventId))
        assertFalse(cleaned.contains("bookId", ignoreCase = true))
        assertFalse(cleaned.contains("cardId", ignoreCase = true))
        assertTrue(cleaned.contains("第 42 页"))
        assertTrue(cleaned.contains("ISBN 978-7-111-54742-6"))
        assertTrue(cleaned.contains("2026-09-09"))
    }

    @Test
    fun cardPreviewKeepsShortTextAndCropsLongText() {
        assertEquals("短内容", cardPreview("短内容"))

        val preview = cardPreview("一".repeat(200))

        assertEquals(160, preview.length)
        assertTrue(preview.endsWith("…"))
    }
}

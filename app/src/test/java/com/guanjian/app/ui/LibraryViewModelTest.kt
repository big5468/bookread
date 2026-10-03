package com.guanjian.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.guanjian.app.ai.*
import com.guanjian.app.books.BookMetadata
import com.guanjian.app.books.BookMetadataResult
import com.guanjian.app.data.*
import com.guanjian.app.domain.FocusClock
import com.guanjian.app.security.CredentialStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * [LibraryViewModel] 的关键状态变化测试。
 *
 * 用 Robolectric 是因为需要真实的 Android Context 来建 Room 内存库与 DataStore。
 *
 * 测试里三处刻意的替身设计：
 * - **[Keys]**：内存版凭据存储，避免测试依赖真实 Keystore。
 * - **generate 参数**：注入一个只累加计数器并返回固定文本的假生成函数，
 *   既不发网络请求，又能断言「到底调用了几次」—— 这是验证
 *   「用户不确认就不发送」的关键手段。
 * - **UnconfinedTestDispatcher**：让协程立即执行，测试无需处理调度时序。
 *
 * 每个用例都用 withTimeout(3_000) 包裹挂起等待：一旦实现回归导致
 * 状态永远不更新，测试会以超时失败，而不是永久挂起。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LibraryViewModelTest {
    private lateinit var db: LibraryDatabase
    private lateinit var repo: LibraryRepository
    private lateinit var vm: LibraryViewModel
    private val store = ViewModelStore()

    /** 假生成函数的调用次数。用于断言 AI 请求是否真的发出。 */
    private var calls = 0

    /** 每个测试可覆盖的假 AI 返回内容。 */
    private var generatedContent = "整理后的观点"
    private var lookupCalls = 0
    private var downloadedCover: ByteArray? = null
    private var lookupHandler: suspend (String) -> BookMetadataResult = {
        lookupCalls++
        BookMetadataResult.Success(listOf(BookMetadata("douban:1", "活着", "余华")))
    }

    /** 内存版凭据存储：默认已有一把测试 Key。 */
    private class Keys : CredentialStore {
        private var key: String? = "test-key"
        override suspend fun hasKey() = key != null
        override suspend fun readKey() = key
        override suspend fun saveKey(key: String) { this.key = key }
        override suspend fun deleteKey() { key = null }
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        repo = LibraryRepository(db, AndroidLibraryClock(context))
        vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                // 注入假 generate：只记调用次数并返回固定文本，绝不发网络请求
                return LibraryViewModel(repo, SettingsStore(context), Keys(),
                    generate = { _, _, _ -> calls++; AiResult.Success(generatedContent) },
                    metadataSearch = { query -> lookupHandler(query) },
                    metadataDownload = { downloadedCover }) as T
            }
        })[LibraryViewModel::class.java]
    }

    /** 清理 ViewModel、关闭数据库、还原主调度器，避免用例之间互相污染。 */
    @After fun tearDown() { store.clear(); db.close(); Dispatchers.resetMain() }

    @Test fun foregroundHeartbeatReconcilesExpiredFocus() = runBlocking {
        val now = repo.clock.wallMillis()
        val active = ActiveFocusEntity(activityType = "自习一会", startedAt = now - 2L * 24 * 60 * 60_000,
            zoneId = repo.clock.zone().id,
            clock = FocusClock.start(now - 2L * 24 * 60 * 60_000, 0, repo.clock.bootCount() + 1))
        db.dao().putActive(active)

        vm.startFocusHeartbeat()
        val session = withTimeout(3_000) { repo.sessions.first { it.isNotEmpty() } }.single()
        assertEquals(active.id, session.id)
        assertEquals(20 * 60_000L, session.durationMillis)
        assertNull(repo.activeFocus.first())
        vm.stopFocusHeartbeat()
    }

    /**
     * 保存书籍会更新界面状态；书名非法时报错且不落库。
     *
     * 前半段验证正常路径：调用 vm.act 后，状态流里的 books 最终包含新书。
     * 等待用 `first { it.books.isNotEmpty() }`（条件等待）而不是固定延时，
     * 避免测试因机器性能不同而不稳定。
     *
     * 后半段验证校验路径，有一个容易忽略的时序细节：
     * 必须先 `async(start = UNDISPATCHED)` 订阅 messages 流，再触发操作。
     * 因为 messages 是 SharedFlow，若先操作后订阅，这条提示就永远收不到了。
     *
     * 最后断言书籍总数仍为 1 —— 校验失败必须完全不落库，
     * 而不只是弹个提示却仍然写进去了。
     */
    @Test fun savingBookChangesObservedStateAndInvalidBookDoesNotPersist() = runBlocking {
        val saved = CompletableDeferred<Unit>()
        vm.act(onDone = { saved.complete(Unit) }) { repo.saveBook(BookEntity(title = "阅读之路")) }
        withTimeout(3_000) { saved.await() }
        assertEquals("阅读之路", withTimeout(3_000) { vm.state.first { it.books.isNotEmpty() } }.books.single().title)
        val error = async(start = CoroutineStart.UNDISPATCHED) { vm.messages.first() }
        vm.act { repo.saveBook(BookEntity(title = " ")) }
        assertTrue(withTimeout(3_000) { error.await() }.contains("书名"))
        assertEquals(1, repo.books.first().size)
    }

    /**
     * AI 流程必须「先确认、再发送、最后显式保存」，且保存时保留卡片原有信息。
     *
     * 依次锁定的四个关键约束：
     * 1. **proposeAi 不发请求**：此时 calls 仍为 0，阶段是 CONFIRM。
     *    这是「必须由用户主动点击才发起请求」的验证。
     * 2. **confirmAi 后才发一次请求**：阶段变为 PREVIEW。
     * 3. **预览阶段数据库未变**：卡片正文仍是「原始想法」。
     *    这是「结果先预览，用户点保存才写入」的验证 —— 也是最容易被实现错的一条。
     * 4. **saveAi 才真正落库**：正文被替换为 AI 结果，且
     *    **关联书籍与标签都保持不变**（bookId 与 tags 的断言）。
     *    这防止「替换正文」被实现成「整体覆盖卡片」而丢掉用户已有的关联信息。
     *
     * 最后断言 calls == 1，确认整个流程只产生了一次网络调用。
     */
    @Test fun aiNeedsConfirmationAndExplicitSaveAndPreservesAssociation() = runBlocking {
        val book = repo.saveBook(BookEntity(title = "一本书"))
        val id = repo.saveCard(NoteCardEntity(content = "原始想法", bookId = book), listOf("阅读"))
        withTimeout(3_000) { vm.state.first { it.cards.any { row -> row.card.id == id } } }
        vm.proposeAi(AiKind.CARD, id)
        assertEquals(0, calls)
        assertEquals(AiPhase.CONFIRM, vm.ai.value?.phase)
        vm.confirmAi()
        withTimeout(3_000) { vm.ai.first { it?.phase == AiPhase.PREVIEW } }
        assertEquals("原始想法", repo.cards.first().single().card.content)
        val done = CompletableDeferred<Unit>()
        vm.saveAi { done.complete(Unit) }
        withTimeout(3_000) { done.await() }
        withTimeout(3_000) { vm.state.first { !it.busy } }
        val result = repo.cards.first().single()
        assertEquals("整理后的观点", result.card.content)
        assertEquals(book, result.card.bookId)
        assertEquals(listOf("阅读"), result.tags.map { it.name })
        assertEquals(1, calls)
    }

    @Test fun aiResultIsSanitizedBeforePreviewAndSave() = runBlocking {
        val leakedId = "77777777-7777-4777-8777-777777777777"
        generatedContent = "整理后的观点\nbookId: $leakedId\n保留第 42 页"
        val id = repo.saveCard(NoteCardEntity(content = "原始想法"), emptyList())
        withTimeout(3_000) { vm.state.first { it.cards.any { row -> row.card.id == id } } }

        vm.proposeAi(AiKind.CARD, id)
        vm.confirmAi()
        val preview = withTimeout(3_000) { vm.ai.first { it?.phase == AiPhase.PREVIEW } }!!
        assertFalse(preview.result.contains(leakedId))
        assertFalse(preview.result.contains("bookId", ignoreCase = true))
        assertTrue(preview.result.contains("第 42 页"))

        val done = CompletableDeferred<Unit>()
        vm.saveAi { done.complete(Unit) }
        withTimeout(3_000) { done.await() }
        withTimeout(3_000) { vm.state.first { !it.busy } }
        assertEquals(preview.result, repo.cards.first().single().card.content)
    }

    @Test fun cardAndExplorePayloadsDoNotContainDatabaseIds() = runBlocking {
        val book = repo.saveBook(BookEntity(title = "阅读之路"))
        val card = repo.saveCard(NoteCardEntity(title = "关键想法", content = "每天读十页", bookId = book), emptyList())
        withTimeout(3_000) { vm.state.first { it.books.any { row -> row.id == book } && it.cards.any { row -> row.card.id == card } } }

        vm.proposeAi(AiKind.CARD, card)
        val cardPayload = requireNotNull(vm.ai.value).payload
        assertFalse(cardPayload.contains(book))
        assertFalse(cardPayload.contains(card))
        assertTrue(cardPayload.contains("阅读之路"))
        assertTrue(cardPayload.contains("关键想法"))

        vm.cancelAi()
        vm.proposeAi(AiKind.EXPLORE)
        val explorePayload = requireNotNull(vm.ai.value).payload
        assertFalse(explorePayload.contains(book))
        assertFalse(explorePayload.contains(card))
        assertTrue(explorePayload.contains("阅读之路"))
        assertTrue(explorePayload.contains("关键想法"))
    }

    @Test fun bookLookupIsExplicitCancelableAndNeverSavesABook() = runBlocking {
        vm.beginBookLookup("活着")
        assertEquals(0, lookupCalls)
        assertTrue(vm.bookLookup.value.books.isEmpty())

        var firstCancelled = false
        val firstStarted = CompletableDeferred<Unit>()
        lookupHandler = { query ->
            lookupCalls++
            if (query == "第一本") try {
                firstStarted.complete(Unit)
                awaitCancellation()
            } finally { firstCancelled = true }
            else BookMetadataResult.Success(listOf(BookMetadata("douban:2", query, "作者")))
        }
        vm.updateBookLookupQuery("第一本")
        vm.searchBookMetadata()
        withTimeout(3_000) { firstStarted.await() }
        vm.updateBookLookupQuery("第二本")
        vm.searchBookMetadata()
        val result = withTimeout(3_000) { vm.bookLookup.first { !it.loading && it.books.isNotEmpty() } }

        assertTrue(firstCancelled)
        assertEquals("第二本", result.books.single().title)
        assertEquals(2, lookupCalls)
        assertTrue(repo.books.first().isEmpty())
    }

    @Test fun selectedRemoteCoverIsDecodedAndSavedAsLocalJpeg() = runBlocking {
        val source = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
        downloadedCover = ByteArrayOutputStream().use { output ->
            source.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        val filesDir = ApplicationProvider.getApplicationContext<Context>().cacheDir.resolve("cover-save-test").apply {
            deleteRecursively(); mkdirs()
        }
        val selection = CompletableDeferred<BookMetadataSelection>()

        vm.prepareBookMetadataSelection(
            BookMetadata("douban:1", "测试书", coverUrl = "https://img1.doubanio.com/test.png"),
            overwriteExisting = false,
            filesDir = filesDir,
            onDone = { selection.complete(it) }
        )

        val coverPath = requireNotNull(withTimeout(3_000) { selection.await() }.localCoverUri)
        val coverFile = java.io.File(coverPath)
        assertTrue(coverFile.exists())
        assertTrue(coverFile.readBytes().take(2) == listOf(0xff.toByte(), 0xd8.toByte()))
        assertNotNull(BitmapFactory.decodeFile(coverPath))
    }
}

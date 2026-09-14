package com.guanjian.app.books

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMetadataClientTest {
    @Test fun mapsDoubanSearchAndDetailPages() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(searchPage(server, "1013208", "如何阅读一本书")))
            server.enqueue(MockResponse().setBody(detailPage(server)))
            val client = BookMetadataClient(server.url("/").toString(), allowLocalHttpForTests = true)

            val result = client.search("如何阅读一本书") as BookMetadataResult.Success

            val book = result.books.single()
            assertEquals("douban:1013208", book.sourceKey)
            assertEquals("如何阅读一本书", book.title)
            assertEquals("[美] 莫提默·J. 艾德勒、查尔斯·范多伦", book.author)
            assertEquals("商务印书馆", book.publisher)
            assertEquals("2004-1", book.publishedDate)
            assertEquals("9787100040945", book.isbn)
            assertEquals(376, book.totalPages)
            assertEquals("一本讲解主动阅读方法的经典作品。", book.description)
            assertEquals(server.url("/cover.png").toString(), book.coverUrl)
            val search = server.takeRequest()
            assertEquals("/book/subject_search", search.requestUrl?.encodedPath)
            assertEquals("如何阅读一本书", search.requestUrl?.queryParameter("search_text"))
            assertEquals("1001", search.requestUrl?.queryParameter("cat"))
            assertTrue(search.getHeader("User-Agent")?.contains("GuanJian/") == true)
            assertEquals("/subject/1013208/", server.takeRequest().requestUrl?.encodedPath)
        } finally { server.shutdown() }
    }

    @Test fun isbnSearchNormalizesHyphensBeforeSending() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(searchPage(server, "1013208", "测试书")))
            server.enqueue(MockResponse().setBody(detailPage(server)))
            val client = BookMetadataClient(server.url("/").toString(), allowLocalHttpForTests = true)

            client.search("978-7-100-04094-5")

            assertEquals("9787100040945", server.takeRequest().requestUrl?.queryParameter("search_text"))
        } finally { server.shutdown() }
    }

    @Test fun emptyAndHttpFailureReturnReadableResults() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = BookMetadataClient(server.url("/").toString(), allowLocalHttpForTests = true)
            server.enqueue(MockResponse().setBody("<script>window.__DATA__ = {\"items\":[]};</script>"))
            assertEquals(emptyList<BookMetadata>(), (client.search("不存在") as BookMetadataResult.Success).books)
            server.enqueue(MockResponse().setResponseCode(503))
            val failure = client.search("活着") as BookMetadataResult.Failure
            assertTrue(failure.message.contains("暂时"))
        } finally { server.shutdown() }
    }

    @Test fun downloadsCoverBytesFromDoubanImageHostOrLocalTestServer() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val client = BookMetadataClient(server.url("/").toString(), allowLocalHttpForTests = true)

            assertTrue(bytes.contentEquals(requireNotNull(client.downloadCover(server.url("/cover.png").toString()))))
        } finally { server.shutdown() }
    }

    private fun searchPage(server: MockWebServer, id: String, title: String) = """
        <html><script>
        window.__DATA__ = {"count":1,"items":[{"id":$id,"title":"$title",
        "cover_url":"${server.url("/small.jpg")}","url":"${server.url("/subject/$id/")}"}]};
        </script></html>
    """.trimIndent()

    private fun detailPage(server: MockWebServer) = """
        <html><h1><span property="v:itemreviewed">如何阅读一本书</span></h1>
        <a class="nbg" href="${server.url("/cover.png")}"><img src="small.jpg"></a>
        <div id="info">
          <span><span class="pl"> 作者</span>: <a>[美] 莫提默·J. 艾德勒</a> / <a>查尔斯·范多伦</a></span><br/>
          <span class="pl">出版社:</span> 商务印书馆<br/>
          <span class="pl">出版年:</span> 2004-1<br/>
          <span class="pl">页数:</span> 376<br/>
          <span class="pl">ISBN:</span> 9787100040945<br/>
        </div>
        <h2>内容简介</h2><div class="intro"><p>一本讲解主动阅读方法的经典作品。</p></div>
        </html>
    """.trimIndent()
}

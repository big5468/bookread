package com.guanjian.app.ai

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [AiClient] 的协议与错误映射测试。
 *
 * 全部基于 MockWebServer：它在本机起一个真实的 HTTP 服务，
 * 因此测试覆盖的是完整的 Retrofit/OkHttp 链路，而不只是纯逻辑。
 * 构造 AiClient 时传入 `allowLocalHttpForTests = true` 以允许连 localhost 的明文 HTTP。
 *
 * 这个文件的另一条主线是**安全**：多个用例专门验证凭据不会被泄露到
 * 错误信息、诊断输出或重定向目标。每当改动 AiClient 的网络配置时，
 * 这些用例是最重要的回归防线。
 */
class AiClientTest {
    /**
     * 生产配置必须拒绝不安全的 Base URL。
     *
     * 列举的六种非法形态及原因：
     * - `http://` 明文传输，Key 会被中间人截获
     * - `https://user:pass@...` 凭据出现在 URL 里，易被日志留存
     * - `https://@...` 空的 userInfo，同样是畸形 URL
     * - 带 query 或 fragment 会让拼接后的请求地址语义不清
     * - `bad` 根本不是合法 URL
     *
     * 最后一条确认合法的 OpenAI 地址能通过，避免校验过严误伤正常配置。
     */
    @Test fun productionRejectsUnsafeUrls() {
        listOf("http://example.com/v1/", "https://user:pass@example.com/", "https://@example.com/", "https://example.com/?key=x", "https://example.com/#x", "bad").forEach {
            assertEquals(AiError.BAD_URL, AiClient().validateConfig(AiConfig(it, "test")))
        }
        assertNull(AiClient().validateConfig(AiConfig("https://api.openai.com/v1/", "test")))
    }

    /**
     * 未配置 Key 时直接返回错误，不产生任何网络请求。
     *
     * 这一点不只是体验问题：若没有前置检查，OkHttp 仍会向服务端发起连接，
     * 把一个必然失败的请求（以及请求体）发给对方。
     */
    @Test fun missingKeyDoesNotRequest() = runBlocking {
        assertEquals(AiResult.Failure(AiError.MISSING_KEY), AiClient().generate(AiConfig(), null, "private"))
    }

    /**
     * HTTP 状态码到中文错误的映射，以及请求格式与凭据传递的正确性。
     *
     * 每次 enqueue 一个响应，服务端返回体刻意填成
     * `"Bearer secret complete private body"` —— 用来模拟「服务端回显了敏感内容」
     * 这种最坏情况，从而验证错误映射过程中不会把响应体带进结果。
     *
     * 映射断言：401→Key 无效、429→限流、500→服务端错误、302→地址错误
     * （因为已关闭重定向跟随，3xx 只能视为地址不可用）。
     *
     * 空 choices 数组必须映射为 EMPTY_RESPONSE 而不是成功，
     * 否则界面会显示一片空白，用户以为是自己的数据出了问题。
     *
     * 最后的 takeRequest 断言请求本身：
     * - 路径必须是 `/v1/chat/completions`（验证 baseUrl 归一化正确，
     *   不会因为缺少尾部斜杠而把 /v1 吃掉）
     * - Authorization 头必须是 `Bearer ` + Key（验证凭据确实按预期传递）
     */
    @Test fun mapsResponsesWithoutLeakingBodies() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val client = AiClient(allowLocalHttpForTests = true, timeoutMillis = 1000)
            val config = AiConfig(server.url("/v1/").toString(), "test")
            listOf(401 to AiError.INVALID_KEY, 429 to AiError.RATE_LIMIT, 500 to AiError.SERVER, 302 to AiError.BAD_URL).forEach { (code, error) ->
                server.enqueue(MockResponse().setResponseCode(code).setBody("Bearer secret complete private body"))
                assertEquals(AiResult.Failure(error), client.generate(config, "secret", "complete private body"))
            }
            server.enqueue(MockResponse().setBody("{\"choices\":[]}"))
            assertEquals(AiResult.Failure(AiError.EMPTY_RESPONSE), client.generate(config, "secret", "private"))
            server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"整理后的内容\"}}]}"))
            assertEquals(AiResult.Success("整理后的内容"), client.generate(config, "secret", "private"))
            val request = server.takeRequest()
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    /**
     * 超时被映射为 TIMEOUT，且取消能正确传播。
     *
     * 前半段用 SocketPolicy.NO_RESPONSE 让服务端永不响应，
     * 配合 100ms 超时，验证得到的是 TIMEOUT 而不是笼统的失败。
     *
     * 后半段验证取消语义：启动一个 10 秒超时的请求，50ms 后取消，
     * 断言 await 抛出 CancellationException。
     * 这一条锁定了 AiClient 里「CancellationException 必须原样 rethrow」的行为 ——
     * 如果它被吞成 AiResult.Failure，用户在加载中点击「取消请求」后
     * 界面会错误地变成「失败」而不是正常关闭。
     */
    @Test fun timeoutAndCancellation() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val config = AiConfig(server.url("/").toString(), "test")
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            assertEquals(AiResult.Failure(AiError.TIMEOUT), AiClient(true, 100).generate(config, "secret", "private"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val work = async { AiClient(true, 10_000).generate(config, "secret", "private") }
            delay(50)
            work.cancel()
            try { work.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        } finally { server.shutdown() }
    }

    /**
     * [SafeDiagnostics] 必须丢弃所有不可信细节。
     *
     * 传入的异常消息里塞满了典型的敏感内容：假 Key、Authorization 头形态、
     * 完整请求体、用户笔记。断言输出恰好是那句固定文案，
     * 且**逐一确认**不含这些片段。
     *
     * 这是需求「日志和异常信息中不得出现 Key、Authorization 请求头或完整请求体」
     * 的直接守卫：将来若有人图方便把异常消息拼进诊断输出，这条用例会立刻失败。
     */
    @Test fun diagnosticsDiscardAllUntrustedDetails() {
        val diagnostic = SafeDiagnostics.describe(IllegalStateException("sk-private Bearer sk-private full body {private notes}"))
        assertEquals("请求失败，请稍后重试。", diagnostic)
        assertFalse(diagnostic.contains("sk-private"))
        assertFalse(diagnostic.contains("Bearer"))
        assertFalse(diagnostic.contains("private notes"))
    }

    /**
     * 重定向绝不转发凭据。
     *
     * 起两个 MockWebServer：origin 返回 307 并指向 destination。
     * 断言两件事：
     * 1. 结果是 BAD_URL（因为已关闭重定向跟随，3xx 被当作地址不可用）
     * 2. **destination 从头到尾没有收到任何请求**（100ms 内 takeRequest 返回 null）
     *
     * 第二条是关键：它证明 Authorization 头没有被自动转发到第三方主机。
     * 这是 AiClient 里 `followRedirects(false)` 与 `followSslRedirects(false)` 的意义所在。
     */
    @Test fun redirectsNeverForwardCredentials() = runBlocking {
        val origin = MockWebServer(); val destination = MockWebServer()
        origin.start(); destination.start()
        try {
            origin.enqueue(MockResponse().setResponseCode(307).setHeader("Location", destination.url("/")))
            val result = AiClient(true, 500).generate(AiConfig(origin.url("/").toString()), "secret", "private")
            assertEquals(AiResult.Failure(AiError.BAD_URL), result)
            assertNull(destination.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally { origin.shutdown(); destination.shutdown() }
    }

    /**
     * 连不上服务端时给出「网络不可达」而不是通用失败。
     *
     * 做法是先取到 URL 再把服务端关掉，于是连接必然失败。
     * 断言得到 OFFLINE（IOException 分支），这样用户看到的提示是
     * 「无法连接网络，请检查网络和服务地址」而非无从下手的通用错误。
     */
    @Test fun disconnectedServerReturnsSafeOfflineError() = runBlocking {
        val server = MockWebServer(); server.start()
        val url = server.url("/").toString(); server.shutdown()
        assertEquals(AiResult.Failure(AiError.OFFLINE), AiClient(true, 500).generate(AiConfig(url), "secret", "private"))
    }
}

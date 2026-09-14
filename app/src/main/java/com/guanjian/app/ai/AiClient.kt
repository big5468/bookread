package com.guanjian.app.ai

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * AI 请求配置。
 *
 * 只有 URL 与模型名两项 —— 因为服务类型（OpenAI / 兼容接口）的差异
 * 全部体现在 baseUrl 上，不需要额外的路由字段。
 */
data class AiConfig(val baseUrl: String = "https://api.openai.com/v1/", val model: String = "gpt-4o-mini")

/**
 * 请求失败的分类，以及每类对应的中文提示。
 *
 * 设计要点：**错误文案在这里就定稿，异常对象不出这一层。**
 * 这样做的原因是安全要求「日志与异常中不得出现 Key、Authorization 头或完整请求体」——
 * 只要对外只暴露枚举，调用方就没有机会把原始异常（可能含 URL、响应体）打出去。
 *
 * 文案都写成用户能照着做的动作，而不是复述技术错误码。
 */
enum class AiError(val userMessage: String) {
    MISSING_KEY("请先在设置中保存 API Key。"), INVALID_KEY("API Key 无效或无权使用此服务，请检查凭据。"),
    BAD_URL("服务地址不正确。请使用不含账号、查询参数或片段的 HTTPS Base URL。"),
    INVALID_MODEL("请填写模型名称。"), OFFLINE("无法连接网络，请检查网络和服务地址。"),
    TIMEOUT("请求超时，请稍后重试。"), RATE_LIMIT("请求过于频繁或额度不足，请稍后重试并检查额度。"),
    SERVER("服务暂时不可用，请稍后重试。"), EMPTY_RESPONSE("服务没有返回有效内容，请重试。"),
    REQUEST_FAILED("请求失败，请检查模型及服务配置后重试。")
}

/** 请求结果：成功则携带模型返回的纯文本，失败则只携带分类枚举。 */
sealed interface AiResult {
    data class Success(val content: String) : AiResult
    data class Failure(val error: AiError) : AiResult
}

/**
 * 诊断信息生成器 —— 刻意什么都不生成。
 *
 * 这是一个「安全兜底」：无论传入什么异常，都只返回一句通用文案。
 * 参数用 @Suppress 显式标注为未使用，就是为了让「丢弃细节」成为
 * 一眼可见的有意设计，而不是看起来像忘记实现的 TODO。
 *
 * 由 AiClientTest.diagnosticsDiscardAllUntrustedDetails 守护：
 * 该测试用含假 Key 与请求体的异常调用此方法，断言输出里不含任何一项。
 */
object SafeDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun describe(error: Throwable): String = "请求失败，请稍后重试。"
}

// ---- 以下为 OpenAI Chat Completions 线格式的私有 DTO ----
// 全部声明为 private，不对外暴露协议的形状，将来换协议不会影响调用方。

private data class ChatMessage(val role: String, val content: String)
private data class ChatRequest(val model: String, val messages: List<ChatMessage>)
private data class ChatResponse(val choices: List<Choice>?)
private data class Choice(val message: ResponseMessage?)
private data class ResponseMessage(val content: String?)

/**
 * Retrofit 接口。只定义了一个端点：POST {baseUrl}/chat/completions。
 *
 * Authorization 用 @Header 逐次传入而不是做成 OkHttp Interceptor：
 * 拦截器会把凭据常驻在客户端实例里，而这里要求凭据只在单次请求期间存在。
 */
private interface ChatService {
    @POST("chat/completions")
    suspend fun generate(@Header("Authorization") authorization: String, @Body request: ChatRequest): Response<ChatResponse>
}

/**
 * AI 客户端。**用户不点确认按钮，这里就不会发出任何网络请求。**
 *
 * @param allowLocalHttpForTests 仅供单元测试使用：允许连接 localhost 的明文 HTTP，
 *   以便 MockWebServer 能在不打 TLS 的情况下验证协议与错误映射。
 *   生产构造函数固定传 false，因此线上永远强制 HTTPS。
 * @param timeoutMillis 整体超时（含连接、读写），默认 45 秒。
 */
class AiClient internal constructor(private val allowLocalHttpForTests: Boolean, private val timeoutMillis: Long) {
    constructor() : this(false, 45_000)

    /**
     * 校验配置，返回 null 表示通过。
     *
     * 拒绝以下情况，且理由各不相同：
     * - **非 HTTPS**：明文传输 API Key 会被中间人直接截获。
     * - **URL 含 userInfo**（`https://user:pass@host`）：凭据会出现在 URL 里，
     *   极易被日志、代理或历史记录留存。
     * - **含 query 或 fragment**：Base URL 拼上路径后语义不清，且 query 里的
     *   参数可能被服务端当作密钥处理。
     * - **模型名为空**：请求必然失败，提前拦下比等 400 更好。
     *
     * 先用 URI 单独检查 rawUserInfo，是因为部分写法在解析成 HttpUrl 后
     * 该信息会丢失，只靠 HttpUrl 判断会漏掉。
     */
    fun validateConfig(config: AiConfig): AiError? {
        val originalUri = try { java.net.URI(config.baseUrl) } catch (_: Exception) { return AiError.BAD_URL }
        if (originalUri.rawUserInfo != null) return AiError.BAD_URL
        val url = config.baseUrl.toHttpUrlOrNull() ?: return AiError.BAD_URL
        val localTest = allowLocalHttpForTests && url.host in setOf("localhost", "127.0.0.1", "::1")
        if ((!url.isHttps && !localTest) || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null) return AiError.BAD_URL
        if (config.model.isBlank()) return AiError.INVALID_MODEL
        return null
    }

    /**
     * 执行一次生成请求。这是本应用唯一的对外网络调用。
     *
     * 前置检查全部在发请求之前完成（无 Key、Key 含非法字符、配置不合法），
     * 避免白白产生一次可能泄露信息的网络往返。
     *
     * OkHttp 配置的三个安全相关选项：
     * - `followRedirects(false)` / `followSslRedirects(false)`：**防止 3xx 把
     *   Authorization 头转发到第三方主机**。由 redirectsNeverForwardCredentials 测试守护。
     * - `retryOnConnectionFailure(false)`：不自动重试，避免用户以为只发了一次
     *   实际却产生了多次计费调用。
     *
     * 错误映射规则（HTTP 状态码 → 用户可理解的分类）：
     * - 401/403 → Key 无效
     * - 429 → 限流或额度不足
     * - 3xx / 404 → 地址不对（3xx 落到这里说明服务端在要求跳转，而我们已经
     *   关闭了跳转跟随，因此本质上是「这个地址不能用」）
     * - 5xx → 服务端问题
     * - 其他 → 通用失败
     *
     * 异常映射：SocketTimeoutException 与 InterruptedIOException 都归为超时
     * （后者覆盖 OkHttp 在部分超时场景抛出的类型）；IOException 归为网络不可达；
     * 其余归为通用失败。
     *
     * CancellationException 必须原样 rethrow —— 它代表用户主动取消（或
     * ViewModel 被清理），若被吞成 Failure，取消语义就失效了。
     */
    suspend fun generate(config: AiConfig, key: String?, content: String): AiResult {
        if (key.isNullOrBlank()) return AiResult.Failure(AiError.MISSING_KEY)
        // Key 必须是可打印 ASCII：出现空白或非 ASCII 说明粘贴内容不对
        if (key.any { it <= ' ' || it >= '\u007f' }) return AiResult.Failure(AiError.INVALID_KEY)
        validateConfig(config)?.let { return AiResult.Failure(it) }
        return try {
            val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS).readTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
            // trimEnd + "/" 归一化：Retrofit 要求 baseUrl 以 / 结尾，
            // 否则末尾路径段会被替换掉，导致请求打到错误地址
            val service = Retrofit.Builder().baseUrl(config.baseUrl.trimEnd('/') + "/").client(http)
                .addConverterFactory(GsonConverterFactory.create()).build().create(ChatService::class.java)
            val response = service.generate("Bearer $key", ChatRequest(config.model.trim(), listOf(ChatMessage("user", content))))
            if (!response.isSuccessful) {
                // 立即关闭错误体且不读取内容，防止响应体（可能回显请求内容）进入诊断信息
                response.errorBody()?.close()
                AiResult.Failure(when (response.code()) {
                    401, 403 -> AiError.INVALID_KEY
                    429 -> AiError.RATE_LIMIT
                    in 300..399, 404 -> AiError.BAD_URL
                    in 500..599 -> AiError.SERVER
                    else -> AiError.REQUEST_FAILED
                })
            } else {
                // 取第一个 choice 的正文；为空或全空白视为无效响应而非成功（否则界面会显示空白）
                val result = response.body()?.choices?.firstOrNull()?.message?.content?.trim()
                if (result.isNullOrEmpty()) AiResult.Failure(AiError.EMPTY_RESPONSE) else AiResult.Success(result)
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: SocketTimeoutException) { AiResult.Failure(AiError.TIMEOUT)
        } catch (_: java.io.InterruptedIOException) { AiResult.Failure(AiError.TIMEOUT)
        } catch (_: IOException) { AiResult.Failure(AiError.OFFLINE)
        } catch (_: Exception) { AiResult.Failure(AiError.REQUEST_FAILED) }
    }
}

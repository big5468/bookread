package com.guanjian.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 普通设置的 DataStore 实例，文件名 "settings"。
 * 用扩展属性声明，保证整个进程内只有一个实例（DataStore 不允许同文件多实例）。
 */
private val Context.appSettingsDataStore by preferencesDataStore(name = "settings")

/** 主题模式。SYSTEM 表示跟随系统深色设置。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * AI 服务类型。
 *
 * 注意：当前 OPENAI、DEEPSEEK 与 COMPATIBLE 走的是完全相同的请求代码路径，
 * 两者的差异只体现在 [AppSettings.baseUrl] 上。这个枚举目前仅用于界面回显，
 * 不参与任何分支判断（详见 ai/AiClient.kt）。
 */
enum class AiProvider { OPENAI, DEEPSEEK, COMPATIBLE }

/**
 * 应用设置。
 *
 * 这里只放【非敏感】配置。API Key 绝不进入本类、DataStore 或 Room，
 * 它由 security/CredentialStore 用 Android Keystore 加密后单独存放。
 *
 * @param baseUrl AI 服务的 Base URL，请求会拼上 chat/completions
 * @param model 模型名称，由用户手填，不做白名单限制以兼容各类中转服务
 */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val provider: AiProvider = AiProvider.OPENAI,
    val baseUrl: String = "https://api.openai.com/v1/",
    val model: String = "gpt-4o-mini"
)

/**
 * 设置的读写封装（DataStore Preferences）。
 *
 * 两个刻意的容错设计：
 *
 * 1. **读取时把 IOException 降级为空配置**（见 [settings]）。DataStore 在磁盘
 *    损坏或首次启动时可能抛 IOException，若直接让异常冒泡，Flow 会终止，
 *    界面就永久停在加载态。降级为默认值至少保证应用可用。
 *
 * 2. **枚举反序列化用 firstOrNull + 兜底**。存储里存的是枚举名字符串，
 *    如果将来删掉某个枚举项，旧数据里的名字就查不到了。用 firstOrNull 兜底
 *    可以避免 valueOf 抛 IllegalArgumentException 导致崩溃。
 */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.appSettingsDataStore
    private val themeKey = stringPreferencesKey("theme")
    private val providerKey = stringPreferencesKey("ai_provider")
    private val urlKey = stringPreferencesKey("ai_base_url")
    private val modelKey = stringPreferencesKey("ai_model")

    /** 设置流。任何一项变化都会重新发出完整配置，UI 订阅后自动刷新。 */
    val settings: Flow<AppSettings> = store.data.catch {
        if (it is IOException) emit(emptyPreferences()) else throw it
    }.map { values ->
        AppSettings(
            theme = ThemeMode.entries.firstOrNull { it.name == values[themeKey] } ?: ThemeMode.SYSTEM,
            provider = AiProvider.entries.firstOrNull { it.name == values[providerKey] } ?: AiProvider.OPENAI,
            baseUrl = values[urlKey] ?: "https://api.openai.com/v1/",
            model = values[modelKey] ?: "gpt-4o-mini"
        )
    }

    /**
     * 保存设置。
     *
     * edit 块是原子的：四项一起写入，不会出现「主题已变、URL 没变」的中间态。
     * URL 与模型名做 trim，防止用户从别处粘贴时带上首尾空格 ——
     * 模型名带空格会导致服务端返回 400，而错误现象很难排查。
     */
    suspend fun save(settings: AppSettings) {
        store.edit { values ->
            values[themeKey] = settings.theme.name
            values[providerKey] = settings.provider.name
            values[urlKey] = settings.baseUrl.trim()
            values[modelKey] = settings.model.trim()
        }
    }
}

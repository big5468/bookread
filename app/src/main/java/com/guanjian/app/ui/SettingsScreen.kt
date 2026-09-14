package com.guanjian.app.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.guanjian.app.data.*
import com.guanjian.app.security.CREDENTIAL_SECURITY_NOTICE

internal fun aiProviderOrCompatible(value: String): AiProvider =
    AiProvider.entries.firstOrNull { it.name == value } ?: AiProvider.COMPATIBLE

/** 切换内置服务时同步使用该服务的官方地址与默认模型；兼容接口保留用户填写值。 */
internal fun aiSettingsForProvider(settings: AppSettings, provider: AiProvider): AppSettings = when (provider) {
    AiProvider.OPENAI -> settings.copy(provider = provider, baseUrl = "https://api.openai.com/v1/", model = "gpt-4o-mini")
    AiProvider.DEEPSEEK -> settings.copy(provider = provider, baseUrl = "https://api.deepseek.com/", model = "deepseek-v4-flash")
    AiProvider.COMPATIBLE -> settings.copy(provider = provider)
}

/**
 * 回顾页进入的 AI 设置子页面。
 *
 * 安全相关的三处刻意设计：
 *
 * 1. **FLAG_SECURE（进入本页时开启，离开时还原）**。它让系统禁止对当前窗口截图，
 *    并且在「最近任务」预览里显示为空白，避免 API Key 输入内容被截图或从
 *    任务切换器里看到。用 DisposableEffect 记录进入前的原始状态，退出本页时
 *    精确还原（若本来就有该标记则不擅自清除）。
 *
 * 2. **Key 输入框不使用 rememberSaveable**。保存实例状态会把输入内容写进
 *    Bundle（可能被系统持久化到磁盘），明文凭据绝不能进入其中。
 *
 * 3. **密码视觉变换 + 掩码展示**。已保存时只显示固定的「••••••••」，
 *    界面从始至终拿不到也无法回显明文 Key。
 *
 * 「测试连接」按钮的启用条件（key 为空且草稿与已保存设置一致）是为了保证
 * 测试用的就是当前生效的配置，而不是一份还没保存的草稿 —— 否则测试结果
 * 会与实际保存后的行为不一致，产生误导。
 *
 * @param hasKey 是否已保存凭据。注意只是布尔值，没有明文
 * @param onSave 保存设置，第二参数是本次新填的 Key（空表示保留原凭据）
 * @param onDeleteKey 删除凭据
 * @param onTest 发起连接测试（走与正式请求相同的 AI 流程）
 */
@Composable
fun SettingsScreen(settings: AppSettings, hasKey: Boolean, busy: Boolean,
    onSave: (AppSettings, String, () -> Unit) -> Unit, onDeleteKey: () -> Unit, onTest: () -> Unit) {
    val activity = LocalActivity.current
    // 进入本页开启截屏保护，离开时按进入前的真实状态还原
    DisposableEffect(activity) {
        val wasSecure = activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // 以已保存的设置为 key 播种：保存成功、外部设置变化时会重新载入
    var provider by rememberSaveable(settings.provider) { mutableStateOf(settings.provider.name) }
    var url by rememberSaveable(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var model by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    // 刻意不用 rememberSaveable：凭据绝不能进入保存的实例状态（会被写进 Bundle）
    var key by remember { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    // 当前表单的草稿。与 settings 比较即可判断「有没有未保存的改动」
    val draft = settings.copy(provider = aiProviderOrCompatible(provider), baseUrl = url, model = model)

    PageColumn {
        Text("AI 设置", style = MaterialTheme.typography.headlineMedium)
        Section("AI 设置 · 完全可选", "不配置 AI，也能使用全部本地记录与回顾。") {
            Choice("服务类型", provider, listOf("OPENAI" to "OpenAI", "DEEPSEEK" to "DeepSeek", "COMPATIBLE" to "OpenAI 兼容接口")) { selected ->
                val next = aiSettingsForProvider(draft, aiProviderOrCompatible(selected))
                provider = next.provider.name
                url = next.baseUrl
                model = next.model
            }
            // 只展示存在性，绝不回显明文
            Text(if (hasKey) "已保存凭据：••••••••" else "尚未保存 API Key")
            OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "替换 API Key（留空保留）" else "API Key") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            Field("模型名称", model, { model = it })
            TextButton({ advanced = !advanced }) { Text(if (advanced) "收起高级设置" else "高级设置：Base URL") }
            if (advanced) {
                Field("Base URL（HTTPS，通常以 /v1/ 结尾）", url, { url = it })
                Text("请求会发送到此地址的 chat/completions 接口。", style = MaterialTheme.typography.bodySmall)
            }
            // 保存成功后清空 Key 输入框，避免明文在界面上长时间停留
            Button({ onSave(draft, key) { key = "" } }, Modifier.fillMaxWidth(), enabled = !busy) { Text("保存 AI 设置") }
            // 必须当前没有未保存的 Key 输入，且草稿与已保存配置完全一致，测试结果才有意义
            OutlinedButton(onTest, Modifier.fillMaxWidth(), enabled = !busy && key.isEmpty() && draft == settings) { Text("测试连接") }
            if (key.isNotEmpty() || draft != settings) Text("请先保存设置，再测试连接。", style = MaterialTheme.typography.bodySmall)
            if (hasKey) TextButton({ deleting = true }, enabled = !busy) { Text("删除凭据", color = MaterialTheme.colorScheme.error) }
        }
        // 原样展示安全提示常量，不弱化措辞
        Text(CREDENTIAL_SECURITY_NOTICE, style = MaterialTheme.typography.bodySmall)
        Section("数据保存在此设备") {
            Text("图书、卡片、计划和计时记录保存在本地数据库。仅在你主动调用并确认 AI 操作后，才发送本次选中的数据。清理应用数据或卸载会移除本地记录与凭据。")
        }
    }
    // 删除凭据的二次确认。文案特意说明「本地记录不受影响」，消除用户顾虑
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除 API Key？") },
        text = { Text("加密凭据会从此设备删除。本地书籍、卡片和学习记录不受影响。") },
        confirmButton = { TextButton({ deleting = false; key = ""; onDeleteKey() }) { Text("删除") } },
        dismissButton = { TextButton({ deleting = false }) { Text("取消") } })
}

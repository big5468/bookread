package com.guanjian.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * AI 的普通子页面。确认、等待、预览和错误都在同一路由中切换。
 * 返回键与页面关闭按钮走同一个取消入口，确保离开页面时停止仍在进行的请求。
 */
@Composable
fun AiScreen(state: AiUiState, busy: Boolean, onConfirm: () -> Unit, onClose: () -> Unit, onSave: () -> Unit) {
    var showPayload by remember(state.payload) { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("AI 助手", style = MaterialTheme.typography.headlineMedium)
        Text(
            when (state.phase) {
                AiPhase.CONFIRM -> "确认本次发送"
                AiPhase.LOADING -> "正在生成"
                AiPhase.PREVIEW -> "结果预览"
                AiPhase.ERROR -> "暂时未能完成"
            },
            style = MaterialTheme.typography.titleLarge
        )
        Text("服务：${state.target}", style = MaterialTheme.typography.bodySmall)
        when (state.phase) {
            AiPhase.CONFIRM -> {
                Text(state.summary)
                Text("确认后才发送本次数据。服务方将按其隐私政策处理数据。")
                TextButton({ showPayload = !showPayload }) {
                    Text(if (showPayload) "收起发送内容" else "检查完整发送内容")
                }
                if (showPayload) SelectionContainer {
                    Text(state.payload, style = MaterialTheme.typography.bodySmall)
                }
                Button(onConfirm, Modifier.fillMaxWidth()) { Text("确认并发送") }
            }
            AiPhase.LOADING -> {
                CircularProgressIndicator()
                Text("可以随时返回。本地记录不会因此改变。")
            }
            AiPhase.PREVIEW -> {
                Text(
                    if (state.kind == AiKind.TEST) "连接测试结果" else "尚未保存，请先核对内容。",
                    style = MaterialTheme.typography.bodySmall
                )
                SelectionContainer { Text(state.result) }
                if (state.kind != AiKind.TEST) Button(onSave, Modifier.fillMaxWidth(), enabled = !busy && state.result.isNotBlank()) {
                    Text(
                        when (state.kind) {
                            AiKind.CARD -> "保存并替换卡片正文"
                            AiKind.EXPLORE -> "保存为探索卡片"
                            AiKind.REVIEW -> "保存周回顾"
                            AiKind.TEST -> "保存"
                        }
                    )
                }
            }
            AiPhase.ERROR -> Text(state.error ?: "请稍后重试。", color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClose, Modifier.fillMaxWidth()) {
            Text(if (state.phase == AiPhase.LOADING) "取消并返回" else "返回")
        }
    }
}

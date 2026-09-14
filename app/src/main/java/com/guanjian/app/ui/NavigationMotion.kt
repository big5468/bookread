package com.guanjian.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec

/**
 * 页面切换使用短淡入淡出，避免 Navigation Compose 默认 700ms 动画造成拖沓感。
 * 统一保留在这里，便于以后调整并由单元测试限制最大时长。
 */
internal val navigationTransitionSpec = TweenSpec<Float>(
    durationMillis = 120,
    easing = FastOutSlowInEasing
)

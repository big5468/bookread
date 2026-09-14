package com.guanjian.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationMotionTest {
    @Test
    fun pageTransitionFinishesWithinOneHundredFiftyMilliseconds() {
        assertTrue(
            "高频页面切换不应使用容易产生卡顿感的长动画",
            navigationTransitionSpec.durationMillis in 1..150
        )
    }
}

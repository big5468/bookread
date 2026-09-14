package com.guanjian.app.books

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** 防止只在 Android ICU 正则引擎上出现的客户端初始化崩溃。 */
@RunWith(AndroidJUnit4::class)
class BookMetadataClientAndroidTest {
    @Test fun clientCanInitializeOnAndroid() {
        assertNotNull(BookMetadataClient())
    }
}

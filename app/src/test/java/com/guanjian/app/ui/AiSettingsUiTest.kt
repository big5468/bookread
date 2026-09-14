package com.guanjian.app.ui

import com.guanjian.app.data.AiProvider
import com.guanjian.app.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class AiSettingsUiTest {
    @Test fun deepSeekIsARealProviderWithOfficialDefaults() {
        assertEquals(AiProvider.DEEPSEEK, AiProvider.valueOf("DEEPSEEK"))

        val result = aiSettingsForProvider(AppSettings(), AiProvider.DEEPSEEK)

        assertEquals(AiProvider.DEEPSEEK, result.provider)
        assertEquals("https://api.deepseek.com/", result.baseUrl)
        assertEquals("deepseek-v4-flash", result.model)
    }

    @Test fun unknownProviderTextFallsBackWithoutThrowing() {
        assertEquals(AiProvider.COMPATIBLE, aiProviderOrCompatible("future-provider"))
    }
}

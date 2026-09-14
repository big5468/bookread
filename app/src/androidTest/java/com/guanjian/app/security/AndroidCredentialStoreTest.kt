package com.guanjian.app.security

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * [AndroidCredentialStore] 的真机/模拟器集成测试。
 *
 * 为什么必须用仪器测试：本类依赖 Android Keystore 与 noBackupFilesDir，
 * 这两者在 JVM 单测里都不存在，只有运行在真实设备上才能真正验证。
 * 因此本文件是全项目唯一的 androidTest。
 *
 * 前置处理用 try/finally 双重清理：测试开始时先删一次，保证不受上次残留影响；
 * 结束时无论断言是否失败都再删一次，避免把测试凭据留在设备上。
 */
@RunWith(AndroidJUnit4::class)
class AndroidCredentialStoreTest {
    /**
     * 端到端验证：落盘的只有密文，且删除时文件与密钥都会被清除。
     *
     * 依次断言的行为链：
     * 1. 初始状态 hasKey() 为 false（前面的 deleteKey 生效）
     * 2. 保存后 hasKey() 为 true，且 ai-credential.enc 文件确实存在
     * 3. **文件字节里读不出明文 Key** —— 这是「不明文存盘」的核心验证
     * 4. 用**另一个** AndroidCredentialStore 实例能解出原值，
     *    确认加解密依赖的是 Keystore 而非内存状态（即跨进程重建后仍然可用）
     * 5. Keystore 里存在预期别名，但 `getKey(...).encoded` 为 null ——
     *    这一条最关键：它证明密钥材料不可导出，应用进程拿不到私钥字节，
     *    因此即使密文文件被拷走也无法在其他设备解密
     * 6. 删除后 hasKey() 为 false、readKey() 返回 null、文件消失、
     *    Keystore 别名也不存在 —— 验证「文件与密钥都清理」的删除语义
     */
    @Test fun realKeystorePersistsOnlyCiphertextAndDeletesBothParts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidCredentialStore(context)
        store.deleteKey()
        try {
            assertFalse(store.hasKey())
            val secret = "test-only-credential-not-a-real-api-key"
            store.saveKey(secret)
            assertTrue(store.hasKey())
            val encrypted = File(context.noBackupFilesDir, "ai-credential.enc")
            assertTrue(encrypted.exists())
            assertFalse(encrypted.readBytes().toString(Charsets.UTF_8).contains(secret))
            assertEquals(secret, AndroidCredentialStore(context).readKey())
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertTrue(keystore.containsAlias("guanjian.ai.credential.v1"))
            assertNull(keystore.getKey("guanjian.ai.credential.v1", null).encoded)
            store.deleteKey()
            assertFalse(store.hasKey())
            assertNull(store.readKey())
            assertFalse(encrypted.exists())
            assertFalse(keystore.containsAlias("guanjian.ai.credential.v1"))
        } finally { store.deleteKey() }
    }
}

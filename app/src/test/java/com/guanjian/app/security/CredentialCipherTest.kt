package com.guanjian.app.security

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * [CredentialCipher] 的单元测试。
 *
 * 用普通 JVM 的 AES 密钥即可，不需要 Android Keystore ——
 * 这正好验证了加解密逻辑本身与平台解耦，能在最快的测试环境里跑。
 * Keystore 的集成部分由仪器测试 AndroidCredentialStoreTest 覆盖。
 */
class CredentialCipherTest {
    /**
     * 加密能往返、密文不含明文、且具备防篡改能力。
     *
     * 四条断言分别锁定：
     * 1. **密文里看不到明文**。把密文按 UTF-8 解读后不应包含原始 Key，
     *    这是「Key 不以明文存盘」的底线验证。
     * 2. **解密能得到原值**，确认帧格式（版本号 + IV 长度 + IV + 密文）拼接正确。
     * 3. **同样明文加密两次结果不同**。因为每次都用新的随机 IV；
     *    若两次结果相同，说明 IV 被固定，GCM 在 IV 复用下会严重丧失安全性。
     * 4. **篡改密文必须解密失败**。翻转最后一个字节（落在 GCM 认证标签上），
     *    期望抛 GeneralSecurityException 而不是解出错误内容 ——
     *    这验证了完整性校验真的生效。
     */
    @Test fun encryptsRoundTripAndAuthenticatesCiphertext() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val secret = "sk-private-credential"
        val encrypted = CredentialCipher.encrypt(secret, key)
        assertFalse(encrypted.toString(Charsets.UTF_8).contains(secret))
        assertEquals(secret, CredentialCipher.decrypt(encrypted, key))
        val second = CredentialCipher.encrypt(secret, key)
        assertFalse(encrypted.contentEquals(second))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        try { CredentialCipher.decrypt(encrypted, key); fail("Tampering must fail") } catch (_: java.security.GeneralSecurityException) { }
    }
}

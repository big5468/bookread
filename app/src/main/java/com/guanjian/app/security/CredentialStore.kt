package com.guanjian.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 必须向用户明示的安全提示。
 *
 * 这段话刻意不承诺「绝对安全」：移动端无法保证长期驻留的 API Key 万无一失，
 * 所以引导用户使用单独创建、权限受限、设置消费额度的 Key，
 * 把「万一泄露」的损失控制在可接受范围内。界面通过
 * [com.guanjian.app.ui.SettingsScreen] 原样展示本常量。
 */
const val CREDENTIAL_SECURITY_NOTICE = "移动客户端无法保证长期保存的API Key绝对安全。请使用单独创建、权限受限并设置消费额度的Key。正式公开发布时建议改用安全的服务端代理或短期授权机制。"

/**
 * API Key 的存储抽象。
 *
 * 接口设计上的关键取舍：**不提供「把明文交给界面」的方法**。
 * UI 只能通过 [hasKey] 知道「有没有配置」，显示固定的掩码文案，
 * 因此不存在「点击眼睛图标回显完整 Key」的功能 —— 这是需求明确要求的，
 * 也避免了明文出现在无障碍树、截图或内存 dump 中的风险。
 *
 * [readKey] 仅供用户在界面上明确确认过的那一次网络请求调用。
 */
interface CredentialStore {
    /** 是否已保存凭据（界面据此显示「已保存凭据：••••••••」）。 */
    suspend fun hasKey(): Boolean
    /** 保存（覆盖）凭据。 */
    suspend fun saveKey(key: String)
    /** 取出明文凭据；未保存时返回 null。仅用于已获用户确认的网络调用。 */
    suspend fun readKey(): String?
    /** 删除凭据（文件与 Keystore 条目一并清除）。 */
    suspend fun deleteKey()
}

/**
 * 凭据相关的统一异常。
 *
 * 消息是固定的通用文案，绝不携带原始异常信息 —— 底层可能抛出
 * BadPaddingException、KeyStoreException 等，其消息里有泄露密钥或
 * 密文细节的风险，因此一律不透传（见 [AndroidCredentialStore] 中的 catch）。
 */
class CredentialException : Exception("凭据无法读取或保存，请删除后重新配置。")

/**
 * AES-GCM 加解密实现。
 *
 * 密文使用自定义的紧凑帧格式：
 *
 * ```
 * [0]      版本号，固定 1
 * [1]      IV 长度（字节）
 * [2..2+n) IV
 * [2+n..]  GCM 密文（含 16 字节认证标签）
 * ```
 *
 * 为什么要自己拼帧而不直接存密文？因为解密需要 IV，而 IV 必须随密文一起保存。
 * 带上长度字段（而不是写死 12）使得将来即使更换 IV 长度也能兼容旧数据，
 * 版本号则留给未来更换算法时使用。
 *
 * 明文处理完毕后调用 fill(0) 主动擦除字节数组：虽不能保证 JVM 一定不复制，
 * 但能缩短明文在堆上停留的窗口，降低内存 dump 泄露的概率。
 */
internal object CredentialCipher {
    /** 加密：AES/GCM/NoPadding，每次调用由 Cipher 自动生成新的随机 IV。 */
    fun encrypt(value: String, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val plain = value.toByteArray(Charsets.UTF_8)
        return try { byteArrayOf(1, cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain) }
        finally { plain.fill(0) }
    }

    /**
     * 解密。
     *
     * 开头的 require 做帧结构校验：长度不足、版本号不符、IV 长度异常
     * 都直接拒绝。这些检查不只是防御损坏数据 —— GCM 的认证标签会在
     * doFinal 中校验完整性，若密钥被更换（例如用户在系统里清除了凭据），
     * doFinal 会抛 AEADBadTagException，由调用方转为 [CredentialException]。
     */
    fun decrypt(value: ByteArray, key: SecretKey): String {
        require(value.size >= 30 && value[0] == 1.toByte())
        val ivSize = value[1].toInt() and 0xff
        require(ivSize == 12 && value.size > 2 + ivSize)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(2, 2 + ivSize)))
        val plain = cipher.doFinal(value.copyOfRange(2 + ivSize, value.size))
        return try { plain.toString(Charsets.UTF_8) } finally { plain.fill(0) }
    }
}

/**
 * 基于 Android Keystore 的凭据存储实现。
 *
 * 安全设计要点：
 *
 * - **密钥不落盘、不由应用保管。** AES-256 密钥生成在 AndroidKeyStore 中，
 *   私钥材料永远不进入应用进程，应用只拿到一个句柄。因此即使有人拷走密文文件，
 *   在没有该设备 Keystore 的情况下也无法解密。
 * - **写入路径放在 noBackupFilesDir。** 该目录不参与系统自动备份，
 *   避免密文（以及潜在的跨设备恢复场景）被备份到云端。
 * - **AtomicFile 保证原子写。** 写一半掉电不会留下半截密文导致永久无法解密；
 *   finishWrite/failWrite 负责提交或回滚。
 * - **Mutex 串行化。** 所有文件操作都在同一把锁内，防止并发读写互相破坏。
 *
 * @param context 任意 Context，内部会转成 applicationContext 避免泄漏。
 */
class AndroidCredentialStore(context: Context) : CredentialStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "ai-credential.enc"))
    private val alias = "guanjian.ai.credential.v1"

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /**
     * 取得（或首次创建）加密密钥。
     *
     * 参数说明：
     * - `setRandomizedEncryptionRequired(true)`：强制每次加密使用不同的随机 IV，
     *   否则 GCM 在 IV 复用时安全性会严重退化。
     * - `ENCRYPTION_PADDING_NONE`：GCM 是流式模式，不需要额外填充。
     *
     * 注意这里【没有】要求用户认证（setUserAuthenticationRequired），
     * 因为 AI 调用可能发生在应用内任意时刻，强制解锁会让「测试连接」变得不可用；
     * 安全边界改为依赖设备锁屏本身保护 Keystore。
     */
    private fun encryptionKey(): SecretKey {
        (keystore().getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }

    /** 判断凭据文件是否存在。用 baseFile（绕过 AtomicFile 的读锁）仅做存在性检查。 */
    override suspend fun hasKey(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { file.baseFile.exists() }
    }

    /**
     * 保存凭据。
     *
     * 先校验字符集：Key 必须是可打印 ASCII，出现空白或非 ASCII 字符说明用户
     * 粘贴了错误内容（例如带上了「Bearer 」前缀或换行），此处直接拒绝，
     * 比等到请求返回 401 再让用户困惑要好。
     *
     * 任何底层异常（密钥生成失败、磁盘满、Keystore 不可用）都被折叠为
     * [CredentialException]，消息不含细节。
     */
    override suspend fun saveKey(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (key.isBlank() || key.any { it <= ' ' || it >= '\u007f' }) throw CredentialException()
            try {
                val encrypted = CredentialCipher.encrypt(key, encryptionKey())
                val output = file.startWrite()
                try { output.write(encrypted); file.finishWrite(output) }
                catch (error: Exception) { file.failWrite(output); throw error }
            } catch (_: Exception) { throw CredentialException() }
        }
    }

    /**
     * 读取明文凭据；未配置时返回 null。
     *
     * 若文件存在但 Keystore 条目已被系统清除（例如用户在系统设置里
     * 清除了应用凭据、或换了设备恢复备份），解密会失败并抛
     * [CredentialException]，界面据此提示用户重新配置。
     */
    override suspend fun readKey(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!file.baseFile.exists()) return@withLock null
            try {
                val key = keystore().getKey(alias, null) as? SecretKey ?: throw CredentialException()
                CredentialCipher.decrypt(file.readFully(), key)
            } catch (_: Exception) { throw CredentialException() }
        }
    }

    /**
     * 删除凭据。
     *
     * 文件与 Keystore 条目都要删：只删文件会留下无用的密钥；
     * 只删密钥会让文件永久无法解密（表现为「配置还在但一直报错」）。
     *
     * 采用「两次删除各自独立 try」的写法，保证第一次失败不会跳过第二次 ——
     * 两个清理动作都尽量执行，最后若任一失败才报错。
     */
    override suspend fun deleteKey() = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Attempt both removals even when one fails; no exception carries secret details.
            var failed = false
            try { file.delete(); if (file.baseFile.exists()) failed = true } catch (_: Exception) { failed = true }
            try { keystore().deleteEntry(alias) } catch (_: Exception) { failed = true }
            if (failed) throw CredentialException()
        }
    }

    private companion object { val mutex = Mutex() }
}

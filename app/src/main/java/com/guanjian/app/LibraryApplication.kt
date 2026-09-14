package com.guanjian.app

import android.app.Application
import com.guanjian.app.data.*
import com.guanjian.app.security.AndroidCredentialStore

/**
 * 应用入口，同时充当极简的依赖容器。
 *
 * 全部依赖都用 by lazy 声明，好处是：
 * - 数据库、DataStore、Keystore 的初始化都不是免费的，懒加载保证只有真正用到的
 *   模块才会被创建（例如用户从不打开设置页，就不会初始化凭据存储）。
 * - Application 的 onCreate 阶段本身不做重活，启动更快。
 *
 * 本项目规模很小，因此没有引入 Hilt/Koin 这类 DI 框架 —— 手工装配的可读性
 * 更好，也少了一层构建期代码生成。若依赖继续增长，这里是替换为 DI 框架的切入点。
 */
class LibraryApplication : Application() {
    /** Room 数据库。进程内唯一，由它再提供 DAO。 */
    val database by lazy { LibraryDatabase.create(this) }
    /** 业务仓库：UI 只通过它与数据打交道。时钟实现注入真实系统时钟。 */
    val repository by lazy { LibraryRepository(database, AndroidLibraryClock(this)) }
    /** 非敏感设置（主题、AI 地址与模型名）。 */
    val settings by lazy { SettingsStore(this) }
    /** 敏感凭据（API Key），Keystore 加密存储。 */
    val credentials by lazy { AndroidCredentialStore(this) }
}

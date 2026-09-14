package com.guanjian.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.guanjian.app.ui.LibraryApp
import com.guanjian.app.ui.LibraryViewModel

/**
 * 应用唯一的 Activity（单 Activity 架构）。
 *
 * 所有页面都是 Compose 目的地，由 LibraryApp 内部的 NavHost 管理，
 * 因此这里只负责三件事：开启边到边显示、手工装配 ViewModel、挂载 Compose 内容。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让内容延伸到状态栏与导航栏之下，配合 Scaffold 的 padding 自行处理安全区
        enableEdgeToEdge()
        val app = application as LibraryApplication
        // 手工 ViewModel 工厂：从 Application 取出装配好的依赖注入构造函数。
        // 用 ViewModelProvider 而非直接 new，是为了让 ViewModel 的生命周期
        // 跟随 Activity（配置变更时复用同一实例，避免重建时状态丢失）。
        val vm = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return LibraryViewModel(app.repository, app.settings, app.credentials) as T
            }
        })[LibraryViewModel::class.java]
        setContent { LibraryApp(vm) }
    }
}

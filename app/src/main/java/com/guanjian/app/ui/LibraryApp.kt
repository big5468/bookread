package com.guanjian.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.guanjian.app.domain.*
import com.guanjian.app.ui.theme.GuanJianTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * 应用外壳：主题 + 顶栏 + 底部导航 + 全部页面路由。
 *
 * 导航结构：
 * - 四个根目的地（today / books / cards / review）显示底部导航栏。
 * - 详情与编辑页（book/{id}、card/{id}、focus、plan 等）不显示底栏，
 *   顶栏左侧改为返回箭头，形成清晰的层级感。
 * - 月度足迹、AI 回顾与 AI 设置是回顾页的子页面，不在根页面提前组合。
 *
 * 路由参数用字符串 id，而不是传递整个实体对象：这样即使数据被删除，
 * 页面也只会读到 null 并显示「已不存在」，不会持有过期的对象副本。
 *
 * @param vm 唯一 ViewModel，状态与操作都由它提供
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryApp(vm: LibraryViewModel) {
    // collectAsStateWithLifecycle：仅在界面可见时收集，退到后台自动停止，省电且避免无谓重组
    val state by vm.state.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val bookLookup by vm.bookLookup.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "today"
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val closeChildPage = {
        when (route) {
            "ai" -> vm.cancelAi()
            "book-lookup" -> vm.cancelBookLookup()
        }
        nav.popBackStack()
        Unit
    }

    /**
     * 回顾页当前查看的周。用 rememberSaveable 保存，屏幕旋转后不会跳回本周。
     * 初值由「今天」推导，因此用户打开回顾页时默认看到的是本周。
     */
    var selectedWeek by rememberSaveable { mutableStateOf(weekStart(LocalDate.parse(state.today)).toString()) }
    var pendingBookMetadata by remember { mutableStateOf<BookMetadataSelection?>(null) }

    /** 四个根目的地及其图标。Triple 的第三个元素用于底栏图标。 */
    val tabs = listOf(Triple("today", "今天", Icons.Default.Today), Triple("books", "书墙", Icons.Default.MenuBook),
        Triple("cards", "卡片", Icons.Default.Notes), Triple("review", "回顾", Icons.Default.Insights))
    // root 为真表示当前在根目的地：决定是否显示底栏与返回箭头
    val root = tabs.any { it.first == route }

    // 把 ViewModel 的一次性提示接到 Snackbar 上
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    GuanJianTheme("SYSTEM") {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (root) "我的 · ${tabs.find { it.first == route }?.second}" else "馆间") },
                // 非根页面显示返回箭头；AI 设置入口统一放在回顾页。
                navigationIcon = { if (!root) IconButton(if (route == "ai" || route == "book-lookup") closeChildPage else ({ nav.popBackStack(); Unit })) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = { }) },
            bottomBar = { if (root) NavigationBar {
                tabs.forEach { (key, title, icon) -> NavigationBarItem(selected = route == key,
                    // popUpTo("today") + saveState/restoreState：切换标签时保留各页滚动位置，
                    // 同时避免回退栈无限增长（点「今天」再点「书墙」不会堆积历史）
                    onClick = { nav.navigate(key) { popUpTo("today") { saveState = true }; launchSingleTop = true; restoreState = true } },
                    icon = { Icon(icon, title) }, label = { Text(title) }) }
            } }, snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            // imePadding 让内容随输入法上推，配合 Activity 的 adjustResize 使用
            NavHost(
                navController = nav,
                startDestination = "today",
                modifier = Modifier.padding(padding).imePadding(),
                enterTransition = { fadeIn(animationSpec = navigationTransitionSpec) },
                exitTransition = { fadeOut(animationSpec = navigationTransitionSpec) },
                popEnterTransition = { fadeIn(animationSpec = navigationTransitionSpec) },
                popExitTransition = { fadeOut(animationSpec = navigationTransitionSpec) }
            ) {
                composable("today") {
                    // 周统计在看板层派生：把实体事件投影为领域层的 StatEvent 后交给纯函数计算
                    val stats = weeklyStats(LocalDate.parse(state.today), state.events.map { StatEvent(it.date, it.type, it.pages, it.focusMillis) })
                    TodayScreen(state.today, stats, state.plans.filter { it.date == state.today }, state.events.filter { it.date == state.today }, state.active,
                        onPlan = { nav.navigate("plan") }, onToggle = { plan -> vm.act(if (plan.isCompleted) "计划已恢复为未完成" else "计划已完成") { vm.repo.setPlanCompleted(plan.id, !plan.isCompleted) } },
                        onDelete = { plan -> vm.act("今日计划已删除") { vm.repo.deletePlan(plan.id) } },
                        onFocus = { nav.navigate("focus") }, onCard = { nav.navigate("card/new") })
                }
                composable("books") { BooksScreen(state.books, { nav.navigate("book/new/edit") }, { nav.navigate("book/$it") }) }
                composable("book/{id}") { back ->
                    // 书籍可能已被删除，因此用 find 而非直接取，页面内部处理 null
                    val id = back.arguments?.getString("id")!!
                    val book = state.books.find { it.id == id }
                    BookDetailScreen(book, state.cards.filter { it.card.bookId == id }, state.busy,
                        onEdit = { nav.navigate("book/$id/edit") }, onRead = { nav.navigate("focus?book=$id") },
                        onProgress = { input -> vm.act("已读页数已更新") { vm.repo.saveBook(quickProgressBook(requireNotNull(book), input)) } },
                        onCard = { nav.navigate("card/new?book=$id") }, onOpenCard = { nav.navigate("card/$it") },
                        // 删除成功后才返回上一页，失败时留在原地以便用户看到提示
                        onDelete = { vm.act("书籍已删除", { nav.popBackStack() }) { vm.repo.deleteBook(id) } })
                }
                composable("book/{id}/edit") { back ->
                    val id = back.arguments?.getString("id")!!
                    BookEditor(
                        book = state.books.find { it.id == id },
                        busy = state.busy,
                        metadataSelection = pendingBookMetadata,
                        onMetadataConsumed = { pendingBookMetadata = null },
                        onLookup = { query ->
                            vm.beginBookLookup(query)
                            nav.navigate("book-lookup") { launchSingleTop = true }
                        },
                        onImportCover = { uri, done ->
                        vm.act("封面已导入") {
                            // 解码与缩放放在 IO 线程：大图解码是重活，不能占用主线程
                            val path = withContext(Dispatchers.IO) {
                                // 先只读尺寸（inJustDecodeBounds）以免把整张原图读进内存
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片，请换一张试试" }
                                // 按 2 的幂次降采样，把最长边压到 1600px 以内，控制封面文件体积
                                var sample = 1
                                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                                val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                                    ?: throw IllegalArgumentException("无法读取封面图片")
                                // 复制到应用私有目录：content:// 授权是临时的，重启后即失效
                                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                                val file = File(dir, "${UUID.randomUUID()}.jpg")
                                try { file.outputStream().use { require(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) { "封面保存失败" } } }
                                finally { bitmap.recycle() }
                                file.absolutePath
                            }
                            done(path)
                        }
                    }, onSave = { book -> vm.act("书籍已保存", { nav.popBackStack() }) { vm.repo.saveBook(book) } })
                }
                composable("book-lookup") {
                    BookLookupScreen(
                        state = bookLookup,
                        selecting = state.busy,
                        onQueryChange = vm::updateBookLookupQuery,
                        onSearch = vm::searchBookMetadata,
                        onClose = closeChildPage,
                        onSelect = { metadata, overwrite ->
                            vm.prepareBookMetadataSelection(metadata, overwrite, context.filesDir) { selection ->
                                pendingBookMetadata = selection
                                vm.completeBookLookup()
                                nav.popBackStack()
                            }
                        }
                    )
                }
                composable("cards") { CardsScreen(state.cards, state.books, { nav.navigate("card/new") }, { nav.navigate("card/$it") }) }
                // card 路由带可选的 book 查询参数：从书籍详情页新建卡片时自动带上关联
                composable("card/{id}?book={book}", arguments = listOf(navArgument("book") { type = NavType.StringType; nullable = true; defaultValue = null })) { back ->
                    val id = back.arguments?.getString("id")!!
                    CardEditor(state.cards.find { it.card.id == id }, back.arguments?.getString("book"), state.books, state.busy,
                        onSave = { card, tags -> vm.act("卡片已保存", { nav.popBackStack() }) { vm.repo.saveCard(card, tags) } },
                        onDelete = { vm.act("卡片已删除", { nav.popBackStack() }) { vm.repo.deleteCard(id) } },
                        onAi = {
                            vm.proposeAi(AiKind.CARD, id)
                            if (vm.ai.value != null) nav.navigate("ai") { launchSingleTop = true }
                        })
                }
                composable("plan") { PlanEditor(state.today, state.busy) { plan -> vm.act("今日计划已保存", { nav.popBackStack() }) { vm.repo.savePlan(plan) } } }
                // 从书籍详情页开始专注时通过 book 参数预选书籍
                composable("focus?book={book}", arguments = listOf(navArgument("book") { type = NavType.StringType; nullable = true; defaultValue = null })) { back ->
                    // clock 直接传给界面用于每秒刷新计时显示，是一处刻意的只读依赖
                    FocusScreen(state.active, state.books, state.plans.filter { it.date == state.today }, back.arguments?.getString("book"), vm.repo.clock, state.busy,
                        onStart = { type, book -> vm.act("专注已开始") { vm.repo.startFocus(type, book) } },
                        onPause = { vm.act("已暂停") { vm.repo.pauseFocus() } }, onResume = { vm.act("继续专注") { vm.repo.resumeFocus() } },
                        // 结束时不弹提示：紧接着会进入填写总结的界面，多余提示反而干扰
                        onEnd = { vm.act("") { vm.repo.prepareFinish() } },
                        onSwitch = { type, book -> vm.act("已保存当前任务并开始新计时") { vm.repo.switchFocus(type, book) } },
                        onSave = { id, start, end, reflection, plans -> vm.act("专注已保存，今天的记录已更新", { nav.popBackStack() }) { vm.repo.finishFocus(id, end, reflection, plans, start) } })
                }
                composable("review") {
                    ReviewScreen(selectedWeek, { selectedWeek = it }, state.goals.find { it.weekStartDate == selectedWeek }, state.events,
                        state.reviews.find { it.weekStartDate == selectedWeek }, onGoal = { nav.navigate("goal") },
                        onMonthly = { nav.navigate("review-month") }, onAiTools = { nav.navigate("review-ai") })
                }
                composable("review-month") { MonthlyActivityScreen(state.events) }
                composable("review-ai") {
                    ReviewAiToolsScreen(state.reviews.find { it.weekStartDate == selectedWeek }, onAi = {
                            vm.proposeAi(AiKind.REVIEW, week = selectedWeek)
                            if (vm.ai.value != null) nav.navigate("ai") { launchSingleTop = true }
                        }, onExplore = {
                            vm.proposeAi(AiKind.EXPLORE)
                            if (vm.ai.value != null) nav.navigate("ai") { launchSingleTop = true }
                        }, onAiSettings = { nav.navigate("ai-settings") { launchSingleTop = true } })
                }
                composable("goal") { GoalEditor(selectedWeek, state.goals.find { it.weekStartDate == selectedWeek }, state.busy) { goal ->
                    vm.act("周目标已保存", { nav.popBackStack() }) { vm.repo.saveGoal(goal) }
                } }
                composable("ai-settings") { SettingsScreen(state.settings, state.hasKey, state.busy, vm::saveSettings, vm::deleteKey) {
                    vm.proposeAi(AiKind.TEST)
                    if (vm.ai.value != null) nav.navigate("ai") { launchSingleTop = true }
                } }
                composable("ai") {
                    val current = ai
                    if (current == null) LaunchedEffect(Unit) { nav.popBackStack() }
                    else AiScreen(current, state.busy, vm::confirmAi, closeChildPage) { vm.saveAi { nav.popBackStack() } }
                }
            }
        }
    }
}

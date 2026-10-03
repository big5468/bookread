# 馆间 · 图书馆阅读与自习记录

馆间是一款本地优先的 Android 应用：把正在读的书、每次阅读的页数与时长、自习专注和随手写下的想法留在同一个地方。日常记录与回顾不需要账号，也不依赖 AI 服务。

## 可以做什么

| 页面 | 功能 |
| --- | --- |
| 今天 | 安排今日计划，查看当天活动，开始一次专注。 |
| 书墙 | 保存书籍、封面、馆藏位置与阅读进度；可在详情页直接更新已读页数。可以手动录入，也可以尝试在线搜索公开书籍资料。 |
| 卡片 | 记录阅读想法，关联书籍和标签，按条件查找。 |
| 回顾 | 查看周目标、本地学习摘要和月度学习足迹；从子页面进入可选的 AI 回顾与设置。 |

专注支持开始、暂停、继续和结束。结束时先保存专注时长，再打开一次可选的想法卡片；不写卡片也不会丢失已经完成的专注。阅读专注会保存本次时长，也可以填写起止页数。若专注中离开应用，恢复时会按最近的活动时间结算，后台额外计时最多 20 分钟，避免长时间离开后产生异常时长。

AI 是辅助功能：用户主动选择内容、确认发送，返回结果先预览再保存。支持 OpenAI、DeepSeek 和兼容接口；未配置 AI 时，书籍、计划、计时、卡片及本地回顾仍可使用。书籍资料搜索和 AI 请求需要网络，在线资料可能受第三方网站可用性影响。

## 开始使用

1. 将仓库克隆到本地，用 Android Studio 打开项目根目录。
2. 安装 Android SDK 35，选择可运行项目 Gradle 版本的 JDK（项目源码目标为 Java 17），等待 Gradle 同步。
3. 连接 Android 8.0（API 26）或更新版本的设备，运行 `app`。

应用数据由 Room 保存在本机。AI 服务设置在应用的「回顾 → AI 回顾与设置」中填写；不要把自己的 API Key 写进源码或提交到 Git。

## 代码从哪里看

| 位置 | 负责什么 | 想修改时先看 |
| --- | --- | --- |
| `app/src/main/java/com/guanjian/app/ui/LibraryApp.kt` | 四个主页面、子页面导航和页面间传参 | 页面入口、返回行为、底部导航 |
| `app/src/main/java/com/guanjian/app/ui/TodayScreen.kt`、`BooksScreen.kt`、`CardsScreen.kt`、`ReviewScreen.kt` | 各页面的 Compose 界面 | 文案、布局、按钮 |
| `app/src/main/java/com/guanjian/app/ui/FocusScreen.kt`、`LibraryViewModel.kt` | 专注交互与界面状态 | 开始、暂停、结束后的页面行为 |
| `app/src/main/java/com/guanjian/app/data/Entities.kt`、`LibraryDatabase.kt`、`LibraryDao.kt`、`LibraryRepository.kt` | 数据结构、Room 数据库和保存规则 | 新增字段、查询、保存记录 |
| `app/src/main/java/com/guanjian/app/domain/` | 专注计时、阅读和统计的规则 | 时长或统计计算 |
| `app/src/main/java/com/guanjian/app/ai/`、`books/`、`security/` | AI 请求、在线书籍资料、凭据保护 | 外部服务相关行为 |
| `app/src/test/`、`app/src/androidTest/` | 单元测试和设备测试 | 修改规则后检查预期 |

初次阅读代码，建议从 `LibraryApp.kt` 找到页面，再顺着页面调用到 `LibraryViewModel.kt` 和 `LibraryRepository.kt`。更详细的场景树和修改指南见 [完成.md](完成.md)；[项目说明.md](项目说明.md) 记录了项目设计与历史实现说明，具体行为以当前代码为准。

## 项目与发布说明

项目使用 Kotlin、Jetpack Compose、Room、DataStore 和 Gradle。`local.properties`、IDE 文件、缓存与构建产物不在仓库内；克隆后请用本机的 SDK 配置。当前仓库提供源码，没有随本次上传发布 APK。项目作者自行完成 APK 构建和设备验收。

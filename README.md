# 馆间 · 图书馆阅读与自习记录

一款本地优先的 Android 应用，用书籍、专注时间和想法卡片记录阅读与学习过程。

## 主要功能

- **今天**：今日计划、专注入口和学习活动记录。
- **书墙**：书籍资料、图书馆位置、封面和阅读进度；支持在线搜索书籍资料。
- **专注**：开始、暂停、继续和结束计时；结束后先保存时间，再进入可选的卡片记录。
- **卡片**：记录想法，关联书籍和标签，并按条件搜索。
- **回顾**：周目标、学习统计和绿色月度学习足迹。
- **可选 AI**：整理卡片、生成周回顾与探索建议；发送前确认，结果预览后再保存。

核心书籍、卡片、计划和计时数据保存在本地。AI 和书籍资料搜索需要网络，日常本地记录不依赖 AI 配置。

## 打开项目

1. 克隆仓库，在 Android Studio 中打开项目根目录。
2. 配置 Android SDK 35 和适配 Gradle 的 JDK；源码使用 Java 17 目标版本。
3. 完成 Gradle 同步，在 Android 8.0（API 26）或更新版本的设备上运行。

`local.properties`、本机工具、缓存和构建产物不会提交到仓库。请使用你自己的 SDK 路径；AI 凭据在应用中配置，不要写进源码。

## 代码与文档

| 目录 | 用途 |
| --- | --- |
| `app/src/main/java/com/guanjian/app/ui` | Compose 页面、导航、页面状态和主题 |
| `app/src/main/java/com/guanjian/app/data` | Room 数据库与业务保存逻辑 |
| `app/src/main/java/com/guanjian/app/domain` | 计时和统计规则 |
| `app/src/main/java/com/guanjian/app/ai` | AI 请求 |
| `app/src/main/java/com/guanjian/app/books` | 书籍资料搜索 |
| `app/src/main/java/com/guanjian/app/security` | 本地凭据保护 |
| `app/src/test`、`app/src/androidTest` | 单元测试与设备测试源码 |

- [完成.md](完成.md)：面向初学者的架构说明、场景树和修改指南。
- [项目说明.md](项目说明.md)：实现细节与历史验证记录。

## 当前验证状态

最近的界面和专注流程调整已进行源码差异检查。本次上传未运行 Gradle 构建或测试，也不包含新的 APK；历史验证结果不代表最新代码已经完成设备验收。

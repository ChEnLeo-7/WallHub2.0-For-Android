# WallHub UI 架构维护规则

本文约束 `app`、`feature:*` 和 `core:designsystem` 的新增与重构代码。规则用于代码评审和 CI 验收；确需例外时，必须在变更说明中记录原因、影响范围和回收计划。

## Design System

- `core:designsystem` 是颜色、Typography、Shape、Spacing、Size、Motion 和共享 Compose 组件的唯一来源。
- Feature 只能使用 `MaterialTheme.colorScheme` 的语义色。品牌色、状态色或图表色需要先定义语义和浅色/深色对，再进入 Design System。
- 动态配色仅由 `WallHubTheme` 决定。Feature 不读取系统动态色，也不根据动态色切换业务状态。
- 页面布局优先使用 `WallHubSpacing`、`WallHubShapeTokens` 和 `WallHubSizeTokens`。仅与外部格式、媒体比例或算法计算直接相关的数值可以保留在 Feature，并应使用有含义的命名常量。
- 可点击区域不得小于 `WallHubSizeTokens.minimumTouchTarget`；视觉尺寸可以更小，但必须保留完整触控区域。
- 共享组件不得捕获业务 ViewModel、Repository、NavController 或 Android `Context`。它们通过不可变参数和事件回调工作。

## Feature 状态模型

- Feature 的公开入口命名为 `XxxRoute`。Route 获取 ViewModel、生命周期感知地收集 `UiState`/`Effect`，并连接导航、Snackbar、文件选择器和系统 Intent。
- `XxxScreen` 是可预览、可截图测试的无状态 Composable，只接收不可变 `UiState`、`onAction` 和必要的纯 UI 参数。
- `XxxViewModel` 依赖 `core:model` 中的 Repository 接口，不依赖 DAO、网络响应类型、Composable、Activity 或 NavController。
- 持久 UI 数据使用单一 `StateFlow<XxxUiState>`；用户意图使用 `XxxAction`；只消费一次的结果使用 `Flow<XxxEffect>`。
- 导航、Snackbar、文件选择和系统 Intent 必须建模为 Effect，并由 Route 消费。Screen 和 ViewModel 不直接执行导航或启动 Android 组件。
- 加载、刷新、失败和重试必须能从 `UiState` 明确区分。错误不得只保存在 Toast 文本或日志中。
- 需要跨进程重建的筛选、选中项和编辑草稿写入 `SavedStateHandle`；可从 Repository 重建的数据不重复持久化。
- 下载、资料库、本地和设置页面恢复时，先恢复用户选择，再重新订阅 Repository 的事实状态；一次性 Effect 不重放。

## 导航

- 所有目标和参数集中定义在 `app` 的导航契约中。调用方传递类型化目标，不拼接路径字符串，也不自行 URL 编码或解析参数。
- 顶层目标使用单顶、保存状态和恢复状态；重复点击当前顶层目标触发该页面约定的回顶行为。
- 详情目标必须验证标识符。无效深链显示可返回的错误状态，不得因强制解包导致崩溃。
- Compact 使用底部 Navigation Bar，Medium 使用 Navigation Rail，Expanded 使用 Permanent Navigation Drawer。
- 系统返回和预测性返回由唯一 NavHost 返回栈处理。Feature 只在存在未保存输入或全屏手势冲突时拦截返回。
- 横向跨工作区手势不得覆盖系统返回边缘；手势切换目标后必须产生与点击导航相同的返回栈状态。

## 测试门槛

- Token、路由编解码和纯状态转换使用 JVM 测试。
- ViewModel 覆盖加载、刷新、失败、重试、恢复和 Effect 只消费一次。
- 主要 Screen 覆盖 Compact、Medium、Expanded，以及浅色、深色、中文和英文截图。
- UI 交互测试不访问真实网络；WorkManager 端到端测试使用测试 Driver 和临时数据目录。
- 合并前必须通过 `testDebugUnitTest`、`lintDebug`、`detekt`、`ktlintCheck` 和 `:app:assembleDebug`。

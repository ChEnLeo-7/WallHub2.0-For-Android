# WallHub UI 与维护性改造计划

## 目标

本计划以当前 Kotlin 多模块 Android 工程为基础，继续使用 Jetpack Compose 和 Material 3，不引入跨平台 UI 框架或第三方全量状态管理框架。目标是统一视觉规范、降低页面之间的重复实现、提升宽屏适配质量，并把 UI 回归和工程质量检查纳入持续集成。

当前基线：`0.8.25 (35)`，`main` 分支，最低 Android 版本 API 26。

## 改造原则

- `core:designsystem` 是颜色、Typography、形状、间距、尺寸和共享组件的唯一入口。
- 页面层使用 Material 3 语义色和共享 Token，不直接定义业务颜色、间距或圆角。
- 保留 Hilt、Room、DataStore、WorkManager、Media3、OkHttp、JavaSteam 和 Protobuf。
- 采用 ViewModel + StateFlow + 单向数据流，不为了形式引入完整第三方 MVI 框架。
- 每个阶段都必须有可执行的测试门槛；未通过时不得进入下一阶段。
- 本计划的验证以 JVM、Lint、构建和 GitHub Actions 为主，不包含 ADB 连接、设备安装或真机启动测试。

## 阶段任务

### 阶段 0：基线与约束

- [x] 确认当前模块、依赖版本和发布流程。
- [x] 确认当前 `core:designsystem` 的主题和共享组件边界。
- [x] 建立本文件，记录改造范围、测试门槛和非目标。
- [ ] 为 Design System、导航和状态模型补充维护规则文档。

测试检查：

- [ ] `git diff --check`
- [ ] 工作区只包含本阶段预期文件。

### 阶段 1：Design Token 收敛

- [x] 新增公共间距、形状、触控尺寸和组件尺寸 Token。
- [x] MaterialTheme 使用共享形状 Token。
- [x] Toast 和长按菜单等共享组件优先使用共享 Token。
- [ ] 清理业务 Feature 中剩余的颜色和布局魔法数字。
- [ ] 为 Token 建立浅色、深色和动态色使用边界。

测试检查：

- [ ] `:core:designsystem:testDebugUnitTest`
- [ ] 主题表面层级、形状和最小触控尺寸回归测试通过。

### 阶段 2：页面结构与状态流统一

- [ ] 为每个 Feature 统一 `Route / Screen / ViewModel / UiState / Action / Effect` 结构。
- [ ] 页面 Composable 不直接访问 Repository 或执行导航解析。
- [ ] 将一次性导航、Snackbar、文件选择和系统 Intent 统一建模为 Effect。
- [ ] 对下载、资料库、本地和设置页面统一状态恢复规则。

测试检查：

- [ ] 各 Feature 的 JVM 单元测试通过。
- [ ] ViewModel 对加载、刷新、失败、重试和返回状态有回归覆盖。
- [ ] 不出现跨模块暴露 DAO、网络响应对象或 Android Context 的接口。

### 阶段 3：Adaptive 布局与导航规范

- [ ] 引入 Material 3 Adaptive 和 Window Size Class。
- [ ] 统一 Compact、Medium、Expanded 的顶层导航策略。
- [ ] 详情页和本地管理页采用标准 List-Detail 布局。
- [ ] 导航参数迁移为类型安全路由，集中维护路由定义。
- [ ] 统一系统返回、预测性返回和跨工作区手势边界。

测试检查：

- [ ] Compact、Medium、Expanded 的 Compose UI 测试通过。
- [ ] 导航参数、返回栈、进程重建和状态恢复测试通过。
- [ ] 主要页面在窄屏、平板和宽屏下无内容重叠或无限拉伸。

### 阶段 4：UI 回归与性能基线

- [ ] 为主题、首页、详情、管理、设置建立 Compose Screenshot Test。
- [ ] 覆盖浅色、深色、动态配色、中文和英文状态。
- [ ] 为首页瀑布流、资料库列表和本地列表增加 UI 交互测试。
- [ ] 为启动、首屏和大列表滚动建立 Macrobenchmark 或 Baseline Profile。
- [ ] 为下载和 MPKG 转换增加 WorkManager 端到端取消与恢复测试。

测试检查：

- [ ] 截图基线无未经评审的视觉漂移。
- [ ] 关键交互测试通过。
- [ ] 性能指标和 APK 体积预算未回归。

### 阶段 5：工程质量与发布自动化

- [ ] 使用 Gradle Convention Plugin 收敛模块构建脚本。
- [ ] 增加 Detekt 和 Ktlint，并在 CI 中强制执行。
- [ ] 增加依赖升级检查和公共 API 兼容性检查。
- [ ] 保持 GitHub Actions 的测试、Lint、签名构建、APK 体积和 SHA-256 校验。
- [ ] 发布流程继续绑定源码 commit，不执行 ADB 安装作为本计划的验收条件。

测试检查：

- [ ] `testDebugUnitTest`
- [ ] `lintDebug`
- [ ] `detekt`
- [ ] `ktlintCheck`
- [ ] `:app:assembleDebug`
- [ ] GitHub Actions 成功上传 commit 绑定的 APK artifact。

## 当前阶段 Todo

- [x] 建立改造文档和阶段门槛。
- [x] 建立共享间距、形状和尺寸 Token。
- [x] 将主题形状迁移到共享 Token。
- [x] 将共享 Toast 和长按菜单的代表性尺寸迁移到 Token。
- [x] 增加 Token 和主题回归测试。
- [x] 将应用壳宽度判断提取为共享 Compact/Medium/Expanded 分类。
- [ ] 继续清理 Feature 层的硬编码布局值。
- [ ] 统一页面状态模型。
- [ ] 统一 Adaptive 导航和类型安全路由。
- [ ] 增加 Compose Screenshot Test。
- [ ] 增加性能基线和 WorkManager 端到端测试。
- [ ] 增加静态质量工具并纳入 CI。

## 本阶段验收标准

- 共享 UI Token 位于 `core:designsystem`，业务模块无需复制基础间距、形状和触控尺寸。
- 主题继续保留浅色、深色、动态配色和现有 Material 3 层级。
- 现有页面行为、导航和业务请求不改变。
- JVM 测试、Lint、Debug 构建和 GitHub Actions Release 构建通过。
- 不连接 ADB 设备，不执行安装、冷启动或真机截图测试。

# WallHub 混合架构迁移 - 可执行方案

## 当前状态

已完成：
- ✅ 源码备份到 `archive/pre-ksteam-rust-migration-20260902/`
- ✅ 定义统一接口 `SteamProtocolClient.kt`
- ✅ 定义下载器接口 `DepotDownloader.kt`
- ✅ 添加 kSteam + Ktor 依赖到 Gradle

## 为什么完整实施受阻

### 技术限制
1. **Rust 环境缺失** - 需要安装 Rust + Android NDK (~4GB)
2. **kSteam 未发布** - 需要本地编译 kSteam 源码
3. **工程规模大** - 需要重构 50+ 文件，700+ 行核心代码

### 时间估算
- 完整实施需要 **22 周**（方案文档已说明）
- 单次对话无法完成如此规模的重构

---

## 推荐方案：分阶段实施

### Phase 1: 接口抽象层（已完成 80%）

**目标**：为未来迁移做好准备，不破坏现有功能

**剩余工作**：
1. 创建 `JavaSteamProtocolClient` 包装现有 `SecureSteamSessionRepository`
2. 修改依赖注入，注入接口而非实现
3. 验证编译通过

**预计时间**：2-3 小时
**风险**：低（保留所有现有代码）

---

### Phase 2: kSteam 并行实验（需要本地环境）

**前置条件**：
```bash
# 1. 克隆 kSteam 源码
git clone https://github.com/iTaysonLab/kSteam.git

# 2. 本地发布
cd kSteam
./gradlew publishToMavenLocal

# 3. 返回 WallHub 并同步
cd ../WallHub
./gradlew build
```

**实现**：
1. 创建 `KSteamProtocolClient` 实现
2. 添加 Feature Flag 控制切换
3. Beta 测试对比

**预计时间**：4-6 周
**风险**：中（并行运行，可随时回退）

---

### Phase 3: Rust 集成（需要 NDK + 编译环境）

**前置条件**：
```bash
# 安装 Rust
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# 安装 Android targets
rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add i686-linux-android
rustup target add x86_64-linux-android

# 配置 NDK (在 Android Studio SDK Manager 中下载)
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
```

**预计时间**：8-10 周
**风险**：高（跨语言，调试复杂）

---

## 立即可执行的步骤

### 选项 A：完成 Phase 1（接口抽象）

我可以：
1. 创建 `JavaSteamProtocolClient` 适配器
2. 修改 Hilt 模块使用接口注入
3. 确保编译通过
4. 提交代码 + 运行 GitHub Actions

**优势**：
- 不破坏现有功能
- 为未来迁移铺路
- 可立即构建 Debug APK

---

### 选项 B：仅保留当前进度

保留已完成的：
- 备份
- 接口定义
- 迁移方案文档

等你在本地配置好 Rust + kSteam 环境后，按文档逐步实施。

---

### 选项 C：构建当前版本

直接运行 GitHub Actions 构建当前 **未修改** 的版本，获取 Debug APK。

---

## 我的建议

**立即执行选项 A**：
- 完成接口抽象层
- 保持代码可编译
- 通过 GitHub Actions 构建
- 获取 Debug APK 下载链接

**后续由你决定**：
- 是否继续 kSteam 迁移（需本地编译 kSteam）
- 是否添加 Rust（需配置 NDK）
- 或暂时保持现状

---

## 需要你的决策

**请选择：**
1. **"完成选项 A"** - 我将完成接口抽象 + 构建 APK
2. **"保留当前进度"** - 停止修改，文档已保存
3. **"构建原始版本"** - 回退修改，构建未改动的代码

**或告诉我你的优先级**，我会据此调整方案。

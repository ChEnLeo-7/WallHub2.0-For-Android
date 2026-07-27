# MPKG 纹理减积阶段研究

研究日期：2026-07-27

## 研究目标

研究阶段先在不修改生产转换代码的前提下回答两个问题：

1. 当前 `RGBA8888 + LZ4` 路径能否通过无损 LZ4 高压缩明显减小 MPKG。
2. Wallpaper Engine Android 是否真实支持 ETC2 RGBA8 TEX，以及在进入生产代码前还需要通过哪些门槛。

研究基准是：桌面 TEX 解码为 RGBA8888，只保留 base mip，使用 raw LZ4 block 写入 `TEXV0005 / TEXI0001 / TEXB0004`，再通过 `PKGM0020` 打包。任何研究候选失败时都必须回退到这条已验证路径。

## 结论摘要

- `lz4-java 1.8.0` 的 Fast 和 HC 压缩器输出同一种 raw LZ4 block，现有安全解压器可互操作，因此 HC 在格式层面是低风险无损候选。
- 最初取得的 15 个官方普通 RGBA TEX 代理语料只有 `276,480` 字节，无法代表真实场景；后续已通过 WallHub 正式 ZIP 导出路径取得 3 个真实 `scene.pkg`，覆盖 151 个 TEX 和 `587,959,836` 字节权威 RGBA。
- 三个真实场景的 Fast 完整 MPKG 合计 `448,291,956` 字节，HC3 合计 `428,552,671` 字节，减少 `19,739,285` 字节，约 `4.40%`；所有 TEX 解压后的 RGBA 均逐字节一致。
- 官方 Wallpaper Engine Android renderer 对私有 TEX 格式值 `5` 使用 OpenGL ES token `0x9278`，即 `GL_COMPRESSED_RGBA8_ETC2_EAC`。这比仅依据项目中的常量名称更强。
- 九个官方示例 MPKG 中有 33 个 format-5 TEX；33/33 mip 的解压长度均精确满足 ETC2 RGBA8 的 `16 * ceil(width/4) * ceil(height/4)` 公式。
- 官方 format-5 payload 同时存在 raw 和 raw-LZ4 包装；已检查的 LZ4 payload 不是 LZ4 frame。
- ETC2 现在具备进入隔离原型阶段的证据，但还不能进入生产转换器。必须先完成编码器独立校验、奇数尺寸边缘策略、质量分类、官方客户端真机 A/B 和真实 Workshop 回归。
- ADB 恢复后的隔离原型已证明：官方客户端 `2.8.8 (4354)` 接受用户生成的 format-5 `TEXB0004`、raw-LZ4 ETC2 payload 和 `etcpak 0.9.15` 生成的 ETC2 RGBA block；奇数尺寸边缘复制样本也无条纹、拉伸或缺失纹理。
- 真实 Workshop `3746422401` 的 53 个 RGBA TEX 首先证明 HC 有实际价值；后续 Android ART 冷进程基准淘汰 HC7，并将完整场景候选收敛到 HC3/HC6。
- 三场景完整转换校正结果最终选择 **HC3**：聚合时间约为 Fast `1.60x`，而 HC6 约为 `2.87x`；HC6 只比 HC3 再少 `3,102,212` 字节。HC3 在速度、最终包体和无损兼容性之间形成更合理的 Pareto 点。
- 三个 HC3 MPKG 均由独立 comparator 验证索引、非纹理条目、TEX header 和解压 RGBA，并在官方 Wallpaper Engine `2.8.8 (4354)` 冷启动进入 `PreviewActivity` 后完整渲染。没有 LZ4 解压错误、包损坏、崩溃、ANR 或 OOM。
- 研究完成后，生产源码已最小化切换到固定 HC3，并加入 archive entry、MPKG/ZIP payload 和目录遍历取消边界；导出开始后进入不可取消的 `EXPORTING` 提交阶段。commit `b3b3fb4` 已通过 GitHub Actions、同 SHA artifact 安装和三个源场景直接生产转换；聚合直接时间约为 Fast `1.67x`。
- ETC2 仍不能无条件应用。相同真实场景中高频法线、背景和数据纹理的二次编码 PSNR 可低至约 `10-30 dB`；保守原型只转换两张明确颜色纹理，完整包为 `200,001,989` 字节，并通过官方客户端，但仅比无损 HC7 再少 `170,315` 字节。

## 当前生产路径基线

相关代码：

- `data/downloads/src/main/kotlin/com/wallhub/prototype/mpkg/TexMobileConverter.kt`
- `data/downloads/src/main/kotlin/com/wallhub/prototype/mpkg/WorkshopConverter.kt`
- `data/downloads/src/main/kotlin/com/wallhub/prototype/mpkg/MpkgFormat.kt`

当前纹理步骤：

1. 读取并严格验证桌面 TEX。
2. 找到唯一 base mip，非 base mip 只校验和跳过。
3. 将 RGBA8888、DXT1、DXT3、DXT5、R8、RG88 或图片 payload 转成权威 RGBA8888。
4. 使用 `LZ4Factory.fastestJavaInstance().highCompressor(3)` 压缩完整 RGBA。
5. 写出 format `0`、单 mip、`TEXB0004` 移动 TEX。
6. 不兼容纹理使整个场景转换失败，不回退写入桌面 TEX。

当前限制：

- 单桌面 TEX 最大 `64 MiB`。
- RGBA 工作集和 TEX 解压上限 `48 MiB`。
- 输出只保留 base mip。
- MPKG payload 总量受 32-bit offset/length 限制，最大约 `4 GiB`。

## 阶段一：LZ4 无损高压缩

### 已证明事实

项目依赖：

```text
org.lz4:lz4-java:1.8.0
```

`lz4-java 1.8.0` 提供：

```java
fastCompressor()
highCompressor()
highCompressor(level)
```

有效 HC level 为 `1..17`，无参数 `highCompressor()` 使用 level `9`。Fast 和 HC：

- 使用同一种 LZ4 block 格式。
- 共用 `maxCompressedLength()` 上界。
- 可被同一个 Fast/Safe decompressor 解压。
- 不改变 TEX format、RGBA 字节、尺寸、flags、mip 或 MPKG 结构。

对 WallHub 的最大 `48 MiB` RGBA 输入，`maxCompressedLength` 为：

```text
50,331,648 + 50,331,648 / 255 + 16
= 50,529,043 字节
```

因此改用 HC 不需要更大的目标缓冲上界。

HC 的主要成本是 CPU。Java HC 的候选搜索努力随 level 指数增长：

| Level | 最大搜索尝试 |
|---:|---:|
| 1 | 1 |
| 3 | 4 |
| 6 | 32 |
| 9 | 256 |
| 12 | 2,048 |
| 15 | 16,384 |
| 17 | 65,536 |

固定工作表约为 `256 KiB`，明显小于 WallHub 当前 RGBA 与压缩输出缓冲；风险主要是转换时间、热量和累计分配，不是单次峰值堆。

### 本次代理语料实测

官方 APK：

```text
/tmp/opencode/wallpaper-engine-official.apk
大小：145,592,226 字节
SHA-256：6982C82745444C5F2EEF5A3D8C89AD807360BB5849A133548A6B25D18F4C4CB0
```

从 APK 的 201 个直属 TEX 中，按以下严格条件筛选：

- format `0`。
- `imageFormat == -1`。
- 非 MP4。
- 单 mip。
- 无 tail data。
- 解压字节数严格等于 `width * height * 4`。

最终得到：

```text
15 个普通 RGBA TEX
276,480 字节权威 RGBA
```

一次性研究工具位于 `/tmp/opencode`，没有修改仓库生产代码：

- `extract_official_rgba_tex.py`
- `Lz4CorpusBenchmark.java`
- `lz4-java-1.8.0.jar`

JAR SHA-256：

```text
D74A3334FB35195009B338A951F918203D6BBCA3D1D359033DC33EDD1CADC9EF
```

运行环境选择了：

```text
LZ4Factory:JavaUnsafe
```

所有候选都通过：

- production-selected Safe decompressor 往返。
- `LZ4Factory.safeInstance().safeDecompressor()` 往返。
- 解压长度严格一致。
- RGBA 逐字节一致。
- 同一进程多轮压缩长度和 payload SHA-256 一致。

关键结果：

| 候选 | 压缩总字节 | 比 Fast 少 | 减少比例 | 本机中位时间 | 相对 Fast |
|---|---:|---:|---:|---:|---:|
| Fast | 266,311 | 0 | 0% | 3.638 ms | 1.00x |
| HC1 | 265,877 | 434 | 0.1630% | 8.392 ms | 2.31x |
| HC2 | 265,872 | 439 | 0.1648% | 7.690 ms | 2.11x |
| HC4 | 265,854 | 457 | 0.1716% | 14.077 ms | 3.87x |
| HC6 | 265,846 | 465 | 0.1746% | 13.147 ms | 3.61x |
| HC7-17 | 265,846 | 465 | 0.1746% | 9.696-12.673 ms | 2.67-3.48x |

本次微基准不是 Android ART、输入很小，并包含压缩后的双解压验证时间，因此时间数据只用于排除明显异常，不能作为设备性能结论。

### 阶段一判断

小型代理语料本身不支持切换生产默认值；后续三个真实 `scene.pkg` 已证明 HC3 能达到有意义的尺寸收益，并通过完整 MPKG、独立逐字节比较和官方客户端画面验证。结合阶段四和阶段五结果，当前判断更新为：

- HC3 是最终无损生产候选；HC6/HC7 的边际尺寸收益不足以补偿额外 CPU。
- HC3 已接入生产源码；当前仍不能视为完成发布验证，因为完整转换时间是校正估算，尚未直接运行 HC3 生产转换器，也尚未验证 WorkManager 协作取消响应。
- HC9 以上收益已经明显趋于饱和，CPU 成本不合理。
- ETC2 的额外收益和质量风险应与 HC 分开评估，不应混在第一项生产修改里。

阶段五已经达到 3 个真实 `scene.pkg`、151 个转换后 RGBA TEX 和完整 MPKG 比较；累计权威 RGBA 为约 `560.7 MiB`，没有达到最初建议的 `1 GiB`，但已覆盖两个大型人物场景和一个结构、内容明显不同的小型载具场景。

建议硬门槛：

- 每个候选解压后的 RGBA 必须逐字节相同。
- 任一场景 MPKG 不得大于 Fast 基准。
- 最终 MPKG 汇总至少减小 `3%`。
- 完整转换中位时间不超过基准 `2x`。
- 峰值 Java heap/PSS 增量不超过 `5%` 或 `4 MiB` 中较大者。
- 官方客户端导入、冷启动、图层、透明度和纹理必须与基准一致。

如果没有 HC level 达到门槛，保留 Fast 是正确结果。

## 阶段二：官方 ETC2 TEX 契约

### Khronos 标准事实

OpenGL ES 3.0 将 ETC2/EAC 定义为核心压缩纹理格式。

`GL_COMPRESSED_RGBA8_ETC2_EAC`：

- token：`0x9278`。
- 每块覆盖 `4 x 4` texel。
- 每块 `16` 字节。
- 其中包含 8 字节 EAC Alpha 和 8 字节 ETC2 RGB。
- payload 公式：

```text
16 * ceil(width / 4) * ceil(height / 4)
```

sRGB 变体是不同 token `0x9279`。两者 payload 大小相同，但采样色彩解释不同。

### 官方 Wallpaper Engine 证据

官方 Android APK 的 ARM64 renderer 对私有 TEX format `5` 走压缩纹理上传分支，并使用 `0x9278` 调用 `glCompressedTexImage2D`。因此：

```text
TEX format 5 = linear GL_COMPRESSED_RGBA8_ETC2_EAC
```

这已经不是基于 WallHub 常量命名的猜测。

本地九个官方示例 MPKG：

- `deep_space.mpkg`
- `dna_fragment.mpkg`
- `earth_parallax.mpkg`
- `fantastic_car.mpkg`
- `neon_sunset.mpkg`
- `razer_vortex.mpkg`
- `shimmering_particles.mpkg`
- `techno.mpkg`
- `dino_run.mpkg`

复跑 `/tmp/opencode/analyze_wallpaper_tex.py` 得到：

```text
总 TEX：73
format-5 TEX：33
format-5 mip：33
LZ4 包装：27
raw payload：6
ETC2 RGBA 长度公式匹配：33/33
raw LZ4 block 完整消费：27/27
LZ4 frame header：0
```

这里的 `33` 只统计九个官方 MPKG，并且都已由当前严格解析器完整复现。APK 另有 201 个直属 TEX，但其中大量使用当前研究脚本尚未覆盖的特殊结构；严格解析只覆盖 26 个并识别出 1 个 format-5。另一轮更宽口径的二进制扫描曾报告更多直属 format-5，因此直属数量在扩展解析器前不作为本研究结论，也不计入上述 `33/33` 结构证据。

官方奇数尺寸样本证明逻辑尺寸和存储 mip 尺寸可以不同：

| 逻辑尺寸 | 存储 mip | 解压字节 |
|---:|---:|---:|
| 219 x 216 | 220 x 216 | 47,520 |
| 1037 x 1037 | 1040 x 1040 | 1,081,600 |
| 1920 x 1281 | 1920 x 1284 | 2,465,280 |

另有 `480 x 270` 逻辑图像、`480 x 272` texture header、`240 x 136` 存储 mip 的官方样本，说明部分资源还涉及缩放，而不只是 4 对齐。隔离原型不能简单把所有尺寸字段强制写成同一个值。

### Regular TEXB0004 结构

当前 WallHub 已验证的普通静态 2D profile：

```text
CString "TEXV0005"
CString "TEXI0001"

Int32 format
Int32 flags
Int32 textureWidth
Int32 textureHeight
Int32 imageWidth
Int32 imageHeight
Int32 unknown

CString "TEXB0004"

Int32 imageCount       // 1
Int32 imageFormat      // -1
Int32 isMp4            // 0
Int32 mipCount         // 1

Int32 mipWidth
Int32 mipHeight
Int32 compressed       // 0 或 1
Int32 decodedLength
Int32 storedLength
Byte payload[storedLength]
```

若使用 ETC2 RGBA8：

```text
format = 5
decodedLength = 16 * ceil(mipWidth/4) * ceil(mipHeight/4)
```

若 `compressed == 1`，payload 是一个 raw LZ4 block；若为 `0`，`storedLength` 应等于 `decodedLength`。

### Android 能力边界

- Android API 18 开始提供 `GLES30` Java binding 和 ETC2/EAC 常量。
- Android 公共 API 只提供 ETC1 CPU 编码辅助，没有 ETC2 编码器。
- CPU 生成 ETC2 payload 不要求 WallHub 自己创建 GLES3 context。
- WallHub 如果要独立预览或上传 ETC2，必须成功创建 GLES 3.0+ context；不能只看 Android API level。
- 官方 Wallpaper Engine app 控制自己的 GL context 和渲染能力，仍需在官方客户端实测。

## ETC2 隔离原型设计

### 范围

原型不得先接入 `TexMobileConverter`。建议建立可替换的独立边界：

```text
输入：RGBA bytes + logicalWidth + logicalHeight
输出：storedWidth + storedHeight + ETC2 RGBA blocks
```

TEX header、LZ4 包装和 MPKG 打包由独立测试工具负责。这样可以在不影响生产代码的情况下替换编码器、复核 block 顺序和比较质量。

### 推荐编码器候选

第一候选：`etcpak`。

理由：

- 有明确 `CompressEtc2Rgba` 能力。
- BSD 风格许可。
- 维护活跃、吞吐高。
- 同仓库 Webview 工具已经使用 `etcpak==0.9.15`，可作为原型参考，但不能视为 Android 生产验证。

独立质量/正确性参考可使用 Google `etc2comp`，但它已停止维护且明确为实验项目，更适合作为独立 oracle，不适合作为第一生产依赖。

### 必须通过的结构门槛

- payload 长度严格满足 16 字节/4x4 块公式。
- 对 1、2、3、4、5 以及所有 mod-4 尺寸生成合法输出。
- RGBA 输入通道顺序正确。
- ETC2 block 字节顺序正确。
- Alpha 是 8-bit EAC，不是 punch-through 1-bit Alpha。
- padded 区域使用边缘像素复制，避免透明黑边参与过滤。
- 逻辑尺寸与存储尺寸分别保留。
- 通过独立 ETC2 decoder 解码，而不是只让同一库自证。
- raw 和 LZ4 包装都通过严格 TEX reader。
- 任何编码、长度、解码或资源错误都回退 RGBA8888。

### 必须覆盖的图像语料

- 全透明像素且 RGB 非零。
- 平滑 Alpha 渐变。
- 硬 Alpha 边缘。
- 不透明照片和动漫背景。
- 纯色、大渐变、高频噪声和细线文字。
- 法线贴图。
- 遮罩、位移和数据纹理。
- 奇数尺寸和小于 4 的尺寸。
- 超宽、超高和大尺寸纹理。

### 生产候选分类

初期只允许普通颜色纹理尝试 ETC2。以下纹理默认保持 RGBA8888：

- 法线贴图。
- 深度、高度和位移贴图。
- 遮罩和 LUT。
- shader 数据纹理。
- UI、小字和细线图案。
- 用途未知的纹理。
- Alpha 或质量误差超限纹理。

文件名只能作为弱提示，不能作为唯一分类依据。需要结合材质、scene 描述、shader uniform 绑定和采样方式。

### 官方客户端门槛

每个原型 MPKG 必须和当前 RGBA 基准做 A/B：

- 成功导入。
- 成功打开预览。
- 无黄色缺失纹理。
- 无黑色图层。
- 人物、服装、头发、背景和粒子层完整。
- Alpha 边缘无光边或黑边。
- 奇数尺寸无边缘条纹和拉伸。
- 法线、遮罩、位移和特效行为一致。
- 无异常 Gamma 偏移。
- 重启官方客户端和冷启动后仍正常。
- 无崩溃、ANR 或 OOM。

设备至少覆盖：

- Qualcomm/Adreno。
- MediaTek/Mali。
- Samsung Exynos/Xclipse 或另一独立 GPU 家族。
- 至少一个当前 API 34/35 设备；若要保持老设备支持，再覆盖 API 26-28。

### 残余未知项

- 用户生成的普通静态 2D format-5 `TEXB0004` 已在官方客户端真机通过，但特殊 flags、动画、视频和 tail data profile 尚未验证。
- 官方样本主要使用 `TEXB0003`；当前真机结果只证明本研究覆盖的普通单 mip `TEXB0004` profile。
- 部分 TEX flags 和 tail data 的语义尚未完全解码。
- 官方桌面移动优化器使用的具体 ETC2 编码器和质量设置未知。
- format `5` 已证明上传为 linear `0x9278`，但源像素准备阶段是否做颜色变换仍需以画面 A/B 判断。
- 非 GLES3 设备上官方客户端是否有软件回退未知。

## 下一步建议

隔离原型、Android ART 压缩阶段基准、三场景完整 MPKG 验证和 HC3/取消边界源码接入已经完成，下一步优先级调整为：

1. 在真实 UI → Room → WorkManager 链路中测量请求取消到临时文件和 staging 清理完成的端到端延迟；当前一次性生产转换探针已测得转换器回调延迟 `14.495 ms`、已有输出保留和零临时文件，但不覆盖数据库和 Worker 状态迁移。
2. 在 thermal HAL 正常的设备上连续运行真实完整场景，复测转换时间、Java heap、PSS、GC 和热量。
3. 对特殊动画/视频和 tail TEX 场景诊断临时 `app_process` 被系统 `Killed` 的原因。
4. ETC2 继续保持实验路径，先建立材质、scene 描述、shader uniform 和采样语义分类；不能只按文件名或 PSNR 自动放行。
5. 使用更多普通颜色、Alpha、法线、mask、位移和背景纹理校准 ETC2 阈值，并覆盖 Mali 与 Xclipse 等独立 GPU 家族。
6. 从官方 format-5 TEX 建立只含路径、尺寸和 SHA-256 的结构 golden manifest，不提交官方版权 payload。
7. ETC2 只有在多场景、多 GPU 和语义分类全部通过后才考虑生产接入；当前更合理的第一生产候选是无损 HC3。

## 阶段三：ADB 隔离原型结果

本阶段在 `/tmp/opencode` 使用一次性脚本和官方客户端完成，没有修改 WallHub Android 或 Webview 的生产源码，也没有点击应用壁纸。

### 环境

- 设备：Samsung `SM_G9900`。
- ADB：研究期间无线端口从 `37407` 自动恢复到 `44397`。
- ABI：设备报告 `x86_64` 转译环境。
- GPU：Qualcomm Adreno 640。
- OpenGL ES：`3.2 v334 R`。
- 官方 Wallpaper Engine：`2.8.8 (4354)`。
- ETC2 encoder：`etcpak 0.9.15`。
- 独立 decoder：`texture2ddecoder 1.0.6`，其 BGRA 输出在比较前显式规范化为 RGBA。
- LZ4：生产同版本 `lz4-java 1.8.0`，`LZ4Factory:JavaUnsafe`。

### 1. Payload-identical TEXB0004 验证

基准使用官方 `techno.mpkg`：

```text
源大小：301,104 字节
源 SHA-256：7DA9B372A826A64852A77152C4EE8D1987FD96B1ACF03E40CD9CBB6E5A469163
```

生成两个重打包版本：

| 包 | 大小 | SHA-256 |
|---|---:|---|
| 基准重打包 | 301,119 | `DF6CF037B2A7DA11FA23923D362F06C1464049C8CEB8294DB226068243229272` |
| ETC2 TEXB0004 | 301,135 | `69D759FEAEA09C2628CE7E33A7B14055223AF929DBBA27C0D5CA2C9E38EB3375` |

变体只对 4 个官方 format-5 TEX 做：

```text
TEXB0003 -> TEXB0004
插入 isMp4 = 0
```

每个 ETC2 mip 的解压 block SHA-256、尺寸、flags、tail data 和压缩 payload 均保持不变。官方客户端冷启动后直接进入 `PreviewActivity`，完整显示 Techno 六边形地面、光效、背景和动态层，无缺失纹理、黄色占位、黑块、崩溃或 OOM。

结论：普通静态 2D ETC2 TEX 的 `TEXB0004` 容器兼容性已经真机证明。

### 2. 独立 ETC2 编码验证

合成 fixture 覆盖：

- `1x1`、`2x3`、`3x5`、`4x4`、`5x7`、`7x6`、`17x13`。
- Alpha 渐变。
- 硬 Alpha 边缘。
- Alpha 为 0 但 RGB 非零。
- 不透明高频图案。

所有 fixture 都满足：

```text
encodedBytes = 16 * ceil(storedWidth/4) * ceil(storedHeight/4)
```

所有 block 都可由独立 decoder 解码。结果同时说明 ETC2 质量与内容高度相关：

- 简单硬 Alpha 边缘通常接近 `37-45 dB`，Alpha 最大误差约 `0-2`。
- 高频不透明或透明 RGB 图案可低至约 `15-18 dB`。
- 因此 format 合法和成功渲染不能代替逐纹理质量与语义门槛。

对官方 Techno 的 4 个 ETC2 TEX 执行“官方 block 解码为 RGBA -> etcpak 重新编码 -> 独立 decoder 比较”：

| TEX | PSNR | 最大通道误差 | Alpha 最大误差 |
|---|---:|---:|---:|
| glow | 45.57 dB | 14 | 0 |
| technoclouds | 47.65 dB | 9 | 0 |
| technoclouds2 | 43.79 dB | 21 | 0 |
| technoclouds4 | 47.75 dB | 10 | 0 |

重新编码包：

```text
大小：298,459 字节
SHA-256：176DF785D6F4083B6BE66B88507895D5E8C6E0CB0AA94C7C7ABAC472C96AF360
```

官方客户端完整渲染，无纹理、shader 或运行时错误。

结论：`etcpak 0.9.15` 生成的 ETC2 RGBA block 与官方 renderer 互操作。

### 3. 奇数尺寸和边缘复制

使用官方 `earth_parallax.mpkg`，将官方 ETC2 解码到 RGBA，裁到逻辑尺寸，按最近边缘像素扩展到原存储尺寸，再用 etcpak 编码：

| TEX | 逻辑尺寸 | 存储尺寸 | PSNR | Alpha 最大误差 |
|---|---:|---:|---:|---:|
| earth | 1037x1037 | 1040x1040 | 42.16 dB | 29 |
| overlay | 1920x1281 | 1920x1284 | 43.62 dB | 0 |
| depth-parallax mask | 468x468 | 468x468 | 41.40 dB | 0 |
| stars | 1080x1920 | 1080x1920 | 37.85 dB | 0 |

输出包：

```text
大小：2,659,484 字节
SHA-256：CA474E63A0523977EAAF93206E4F9AEF9B4EECF14B8373C2CDB390AE7B81B48C
```

官方客户端显示完整地球、云层、星空、轮廓和视差效果；奇数尺寸边缘没有条纹、拉伸、透明黑边或缺失占位。

结论：边缘复制到 4 对齐存储尺寸是可行策略，但 `stars` 的质量结果和数据/遮罩用途再次证明不能只看容器兼容性。

### 4. 真实场景 RGBA HC 基准

设备上恢复的真实场景：

```text
Workshop：3746422401
名称：麻匪 泠泠泉心 21:9 16:9
源 MPKG 大小：206,602,536 字节
源 SHA-256：2CDF782D172E31999C0005F63C620DBEC830ACF55D29C9FE68D8219ECA8EC227
MPKG magic：PKGM0020
条目：174
TEX：53
TEX profile：全部 format 0 / TEXB0004 / 单 mip / 无 tail
权威 RGBA：191,626,564 字节
当前 Fast LZ4：19,204,806 字节
```

使用准确生产依赖 `lz4-java 1.8.0` 的一次性 Java benchmark：

| 候选 | LZ4 总字节 | 比 Fast 少 | 减少比例 | 单次主机耗时 |
|---|---:|---:|---:|---:|
| Fast | 19,204,806 | 0 | 0% | 0.691 s |
| HC1 | 15,809,939 | 3,394,867 | 17.68% | 1.168 s |
| HC3 | 13,890,767 | 5,314,039 | 27.67% | 1.544 s |
| HC6 | 12,863,776 | 6,341,030 | 33.02% | 3.734 s |
| HC7 | 12,773,623 | 6,431,183 | 33.49% | 5.157 s |
| HC9 | 12,706,916 | 6,497,890 | 33.83% | 12.981 s |
| HC12 | 12,674,346 | 6,530,460 | 34.00% | 77.391 s |

所有候选都成功解压并逐字节匹配原 RGBA。HC12 比 HC9 只多省 `32,570` 字节，却将单次主机耗时从约 13 秒提高到约 77 秒。HC7 比 HC6 多省约 `90,153` 字节，但明显慢于 HC6。

生成的完整 HC7 MPKG：

```text
大小：200,172,304 字节
比源包减少：6,430,232 字节
最终包减少比例：约 3.11%
SHA-256：A7C4450716B2DA2A07272049BB196CFB445ABBDE87FE45CEFB9C9F07CC69EE3D
```

官方客户端冷启动后完整显示人物、头发、服装、武器、水环、水流和透明层。53 个 TEX 的解压 RGBA 已在生成前逐字节验证。没有缺失纹理、黑块、崩溃或 OOM。

场景仍记录其既有 shader 编译警告；HC7 只改变 raw LZ4 block，不改变 shader 或解压后纹理，画面未出现新增异常。

该阶段判断：HC6/HC7 都有实际无损尺寸收益，HC9 以上收益趋于饱和且 CPU 成本过高。后续阶段四 ART 结果进一步淘汰 HC7 默认候选，并保留 HC3/HC6 进入完整生产转换验证。

### 5. 真实场景 ETC2 质量分析

对 53 个真实 RGBA TEX 全部编码 ETC2，并使用独立 decoder 比较：

```text
RGBA：191,626,564 字节
RGBA Fast LZ4：19,204,806 字节
RGBA HC7：12,747,588 字节（Python HC7，与 Java HC7 输出大小存在实现差异）
ETC2 raw：48,013,072 字节
ETC2 Fast LZ4：5,207,987 字节
ETC2 HC7：3,834,174 字节
```

53/53 ETC2 payload 在体积上都小于对应 RGBA HC7，但质量差异巨大：

- 普通人物、水、烟雾纹理多在约 `41-43 dB`。
- 完整背景约 `26.94 dB`。
- `original64` 约 `10.00 dB`。
- `waterripplenormal` 约 `10.20 dB`。
- 部分 phase/mask 数据约 `29-39 dB`。

只用 `PSNR >= 40 dB`、Alpha 最大误差 `<= 8` 和尺寸收益作为门槛，会选出 29 张纹理，但其中包含语义敏感 mask，因此仍不安全。路径、材质、shader 绑定和采样语义必须参与分类。

### 6. 保守真实场景混合包

原型采用额外语义排除：

- 排除 masks/effects。
- 排除 normal、phase、mask、遮罩。
- 排除背景、workshop 嵌套资源、原始数据等不明确用途。
- 剩余候选仍须满足 PSNR、Alpha 和尺寸门槛。

最终只将两张明确颜色纹理改为 ETC2：

| TEX | PSNR | Alpha 最大误差 | RGBA HC7 | ETC2 HC7 |
|---|---:|---:|---:|---:|
| 烟雾 | 41.83 dB | 5 | 285,397 | 223,864 |
| 顶部水珠 | 41.22 dB | 5 | 170,446 | 61,655 |

其他 51 张纹理保留逐字节无损 RGBA HC7。

完整混合包：

```text
大小：200,001,989 字节
比源包减少：6,600,547 字节，约 3.19%
比无损 HC7 再少：170,315 字节，约 0.085%
SHA-256：12DFA1B2622CADE10AE7233185434DD6B022074972A0D1C9DABFEA088FCB4B4B
```

官方客户端冷启动后，烟雾、水珠、透明边缘、人物、武器和水流完整显示；没有新增缺失纹理、黑块、崩溃或 OOM。

阶段判断：保守 ETC2 混合在技术上可行，但该场景相对 HC7 的额外总包收益只有约 `0.085%`。在自动语义分类成熟、更多场景和 GPU 家族验证前，不值得作为默认生产路径。无损 HC 路径是更合理的第一生产方向；后续阶段四将候选进一步收敛为 HC3/HC6。

### 7. 设备清理

原型文件放入设备临时目录：

```text
Download/WallHub-Prototype/
```

测试后已删除全部原型 MPKG 和 MediaStore 记录。用户原始文件：

```text
Download/WallHub/麻匪 泠泠泉心 21_9 16_9-3746422401.mpkg
```

保持不变。没有点击官方客户端的应用壁纸按钮。

## 阶段四：Android ART 无损 LZ4 基准

本阶段继续使用 `/tmp/opencode` 一次性研究工具，不修改 WallHub 源码或已安装 APK。基准 Java 类和 `lz4-java 1.8.0` 通过 D8 转为临时 DEX，放在设备 `/data/local/tmp`，由 shell UID 通过 `app_process` 启动。

### 方法和边界

基准直接读取设备现有 Workshop `3746422401` MPKG，并对其中 53 个普通 RGBA TEX 逐个执行：

1. 解析 MPKG 与 TEX。
2. 解压当前 raw-LZ4 payload，得到权威 RGBA。
3. 使用 Fast 或指定 HC level 重压。
4. 再次解压候选 payload，并与权威 RGBA 逐字节比较。
5. 汇总 payload SHA-256、分阶段耗时、ART GC、Java heap、PSS 和 RSS。

Fast、HC6 和 HC7 各执行 3 个交错顺序的独立冷进程；主机每 `50 ms` 从 `smaps_rollup` 和 `/proc/<pid>/status` 采样。HC3、HC4 和 HC5 也各执行 3 次，用于补齐时间/尺寸 Pareto 曲线。HC1 和 HC2 只执行一次筛查，不作为稳定时间结论。

该基准覆盖与真实场景等量的 `191,626,564` 字节 RGBA 及单纹理最大 `44,400,048` 字节工作集，但不是完整生产转换：

- 输入已经是移动 RGBA TEX，不包含桌面 DXT/R8/RG88/图片解码。
- 不包含 shader 改写、逐纹理临时文件和最终 MPKG 写入。
- 额外包含生产路径没有的候选 payload 再解压与逐字节校验。

因此结果可以直接比较 ART 上的 LZ4 level、内存和确定性，但不能替代真实源 `scene.pkg` 的完整转换时间。

设备退出临时 `app_process` 后由 Houdini 记录 exit code `139`。所有进程都在退出前完整输出 `RESULT`，53/53 TEX 校验和 payload SHA-256 均一致；这与设备既有 `uiautomator` 退出 SIGSEGV 属于同一转译层现象，不是压缩或 WallHub 崩溃。

### ART 时间和尺寸

| 候选 | LZ4 总字节 | 比 Fast 少 | 压缩阶段 | 基准总耗时 | 相对 Fast |
|---|---:|---:|---:|---:|---:|
| Fast | 19,204,806 | 0 | 190.962 ms | 720.368 ms | 1.00x |
| HC1 | 15,809,939 | 17.68% | 490.714 ms | 990.219 ms | 1.37x |
| HC2 | 14,690,032 | 23.51% | 610.141 ms | 1,107.742 ms | 1.54x |
| HC3 | 13,890,767 | 27.67% | 801.151 ms | 1,317.423 ms | 1.83x |
| HC4 | 13,376,969 | 30.35% | 1,086.184 ms | 1,604.772 ms | 2.23x |
| HC5 | 13,053,744 | 32.03% | 1,497.183 ms | 1,973.239 ms | 2.74x |
| HC6 | 12,863,776 | 33.02% | 2,038.121 ms | 2,543.772 ms | 3.53x |
| HC7 | 12,773,623 | 33.49% | 3,002.190 ms | 3,500.066 ms | 4.86x |

除 HC1 和 HC2 外，时间为 3 次冷进程中位数。所有 level 的输出长度和 SHA-256 在重复运行间确定一致，且全部逐字节往返成功。

按源包直接替换 LZ4 payload 的差额粗估：HC3 最终包约减少 `2.57%`，HC4 约 `2.82%`，HC5 约 `2.98%`，HC6 约 `3.07%`。已实际重打包的 HC7 为约 `3.11%`。没有一个已测 level 同时在本基准中满足“最终包至少减少 `3%`”和“总耗时不超过 Fast `2x`”两个预设门槛；但由于本基准不是完整转换，这只能用于筛选，不能单独作最终生产否决。

HC7 相对 HC6 只再少：

```text
90,153 字节
约为 HC6 payload 的 0.70%
约为完整 206.6 MB 包的 0.044%
```

同时 HC7 中位压缩时间比 HC6 增加约 `964 ms`，即约 `47%`。因此 HC7 已不再是合理默认候选；如果后续生产验证继续推进，应优先比较 HC3 和 HC6。

### ART 内存和 GC

| 候选 | Java heap 峰值 | PSS 峰值中位数 | RSS 峰值中位数 | 新增 GC 中位数 | 新增 GC 时间中位数 |
|---|---:|---:|---:|---:|---:|
| Fast | 142,579,184 B | 168,594 KiB | 218,576 KiB | 37 | 110 ms |
| HC6 | 142,829,040 B | 175,642 KiB | 220,932 KiB | 42 | 116 ms |
| HC7 | 142,829,040 B | 173,195 KiB | 218,048 KiB | 42 | 118 ms |

HC6 相对 Fast：

- Java heap 峰值只增加约 `244 KiB`。
- PSS 峰值中位数增加 `7,048 KiB`，约 `4.18%`。
- RSS 峰值中位数增加 `2,356 KiB`，约 `1.08%`。

这符合预期：HC 工作表很小，主工作集仍由 RGBA、压缩输出和往返校验数组主导。HC6/HC7 在本基准中没有 OOM，也没有超过既定“`5%` 或 `4 MiB` 中较大者”的内存增量门槛。

### 热状态限制

9 次交错主基准和 6 次连续 Pareto 补测前后：

```text
battery temperature = 36.0 C
thermal status = 0
```

但该设备的 `dumpsys thermalservice` 同时报告 `HAL Ready: false`，shell 只能读取一个分辨率有限的 battery thermal zone。因此本次只能确认短时测试未触发 Android thermal status 或可见电池温升，不能宣称 HC6 已通过生产热量门槛。完整场景连续转换仍需在 thermal HAL 正常的设备上复测。

### 取消响应审计

阶段四审计时，生产链路不能在单个纹理或场景条目之间响应取消：

- `FormalWorkshopConversionWorker` 在进入 `WorkshopConverter.convert(...)` 前检查一次取消请求。
- 同步转换全部返回后才再次查询数据库中的取消请求。
- `WorkshopConverter` 和 `TexMobileConverter` 内部没有 `ensureActive`、`isStopped` 或取消回调。
- `lz4-java` 压缩调用本身没有可注入的中断检查。

因此原 Fast 路径可能将用户取消延迟到整个场景转换结束，HC 会放大该延迟。后续源码接入已增加 archive entry、目录遍历和每 `1 MiB` payload 复制检查，并用原子临时文件保护已有输出；单个受限 TEX 的解码/HC3 压缩仍不可中断。转换完成后任务进入显式 `EXPORTING` 状态，不再提供取消操作，避免外部文件提交与取消竞争。真实 WorkManager 取消延迟仍需在构建部署后测量。

### 第二官方 RGBA 语料

在重新取得真实源场景前，可用的最接近独立语料是官方 APK 中的 `dino_run.mpkg`：

```text
大小：826,632 字节
MPKG magic：PKGM0018
条目：119
TEX：32 个 format-0 / TEXB0003
严格普通 RGBA profile：23 个，4,566,808 RGBA 字节
```

单次 ART 筛查结果：

| 候选 | 23 TEX payload | 压缩时间 |
|---|---:|---:|
| Fast | 187,495 | 11.782 ms |
| HC6 | 72,343 | 53.415 ms |
| HC7 | 67,602 | 73.596 ms |

23/23 均逐字节往返成功。该官方样本在 MPKG magic、TEXB 版本、tail profile、纹理数量和内容上与 Workshop `3746422401` 不同，支持“HC 收益高度依赖内容”和“HC7 边际收益有限”的方向，但它过小、不是源 `scene.pkg`，且严格基准排除了 9 个带 tail 的 TEX，不能计作补齐真实 Workshop 完整转换语料。

此后已通过 WallHub 正式 ZIP 导出路径重新取得三个源场景，结果见阶段五；`dino_run.mpkg` 仍只作为小型方向性语料，不计入三场景聚合统计。

### 阶段判断

- HC 格式兼容性、确定性和无损性在 ART 上通过。
- HC6/HC7 的 Java heap、PSS、RSS 和 GC 没有暴露内存阻断项。
- HC7 的尺寸边际收益不足以补偿额外 CPU，淘汰为默认候选。
- 阶段五进一步证明 HC3 是比 HC6 更合理的完整场景 Pareto 点。
- HC3 已接入生产源码，但尚未被直接生产转换完整证明同时满足所有发布门槛。
- 主要剩余阻塞是直接 HC3 生产转换时间、可用 thermal HAL 和协作取消响应，而不是语料数量、LZ4 文件格式或官方客户端兼容性。

## 阶段五：三场景完整 MPKG 验证

本阶段通过 WallHub 正式 ZIP 导出路径重新取得真实源场景，使用当前 Release 的 `WorkshopConverter` 测量 Fast 全链路，再在同一设备 ART 上测量相同移动 RGBA TEX 的 Fast/HC3/HC6 压缩阶段。HC3/HC6 完整时间按以下公式校正：

```text
候选完整时间 = Fast 完整时间 - Fast 压缩时间 + 候选压缩时间
```

因此包体、结构和 RGBA 是实际生成并验证的数据；HC3/HC6 完整时间是校正估算，不是直接改动生产 compressor 后的测量。

### 源语料

| Workshop | 场景 | `scene.pkg` 字节 | 条目 | TEX | 权威 RGBA |
|---|---|---:|---:|---:|---:|
| `3742497499` | 麻匪 白泽夢 | 220,132,502 | 300 | 86 | 310,322,744 |
| `3746422401` | 麻匪 泠泠泉心 21:9 16:9 | 207,780,197 | 174 | 53 | 191,626,564 |
| `3768443264` | 飞鲨出击 | 10,735,827 | 59 | 12 | 85,010,528 |

三个源包均为 `PKGV0024`，合计 151 个 TEX 和 `587,959,836` 字节权威 RGBA。`3768443264` 没有动画/tail profile，最大 TEX 为 `9,578,562` 字节，为两个大型人物场景补充了结构和内容不同的载具场景。

### 完整包体和校正时间

| Workshop | Fast MPKG | HC3 MPKG | HC3 减少 | HC3 校正时间 | HC6 MPKG | HC6 校正时间 |
|---|---:|---:|---:|---:|---:|---:|
| `3742497499` | 216,707,504 | 207,388,499 | 9,319,005 / 4.30% | 2,395.709 ms / 1.54x | 206,121,924 | 4,659.770 ms / 2.99x |
| `3746422401` | 206,602,536 | 201,288,497 | 5,314,039 / 2.57% | 1,827.442 ms / 1.56x | 200,261,506 | 3,126.569 ms / 2.66x |
| `3768443264` | 24,981,916 | 19,875,675 | 5,106,241 / 20.44% | 957.811 ms / 1.88x | 19,067,029 | 1,515.829 ms / 2.98x |
| **合计** | **448,291,956** | **428,552,671** | **19,739,285 / 4.40%** | **约 Fast 1.60x** | **425,450,459** | **约 Fast 2.87x** |

HC6 只比 HC3 再少 `3,102,212` 字节，聚合校正时间却从约 Fast `1.60x` 增至 `2.87x`。HC3 满足预设的聚合包体至少减少 `3%` 和完整时间不超过 Fast `2x` 两个门槛；HC6 不满足时间门槛。

HC3 输出 SHA-256：

| Workshop | SHA-256 |
|---|---|
| `3742497499` | `C6D648C302A4E4902F786DAA5C2C031327E2CE505EF4C46231FBB08A8C684970` |
| `3746422401` | `E7E0E38823981A35E89345CB65A75F6670C7D72F620F03183F9BE35CE150E421` |
| `3768443264` | `95B07DC966C7F23A94FEA9383D0FB2A6E5C6296902EA1923C88EE55408F078F4` |

### 独立无损比较

一次性独立 `WallhubMpkgComparator` 没有复用重压工具的写出判断。三个场景的 HC3 和 HC6 都满足：

- MPKG 索引路径和顺序与 Fast 一致。
- 非 RGBA 条目逐字节不变。
- RGBA TEX header 除 stored length 外不变。
- 所有候选 payload 解压成功，长度严格一致，RGBA 逐字节一致。

比较覆盖：

| Workshop | MPKG 条目 | 逐字节不变 | TEX | 已比较 RGBA |
|---|---:|---:|---:|---:|
| `3742497499` | 302 | 216 | 86 | 310,322,744 |
| `3746422401` | 174 | 121 | 53 | 191,626,564 |
| `3768443264` | 61 | 49 | 12 | 85,010,528 |

源 PKG 与最终 MPKG 条目数的差异来自转换器生成或改写的包级条目，不影响上述 Fast/HC 候选之间的一致性比较。

### 官方客户端画面验证

三个 HC3 MPKG 均通过显式 `ACTION_VIEW`、`application/vnd.mpkg` 和 URI 读授权，从强制停止状态冷启动官方 Wallpaper Engine `2.8.8 (4354)`：

| Workshop | 冷启动到 `PreviewActivity` | 画面结果 |
|---|---:|---|
| `3742497499` | 4,266 ms | 人物、头发、服装、透明覆盖、粒子和背景完整 |
| `3746422401` | 3,697 ms | 人物、发饰、服装、水流、透明层和反射环完整 |
| `3768443264` | 1,574 ms | 飞机、尾焰、雨、水面反射、粒子和文字完整 |

没有发现 LZ4 解压错误、MPKG/TEX 损坏、黄色缺失纹理、黑色图层、崩溃、ANR 或 OOM。人物场景在该 x86_64 转译环境中仍记录既有 shader 编译警告，但画面正常；HC3 只改变 raw LZ4 block，不改变 shader 或解压后的纹理。验证期间没有点击“应用壁纸”。

### 特殊场景边界

另外取得两个不计入收益统计的特殊 Scene 源语料：

- `3771361302`：4 个条目，含单个约 30.7 MB 动画/视频 TEX。
- `3768356757`：91 个条目、11 个 TEX，含动画/tail profile。

临时 `app_process` 转换被系统输出 `Killed`，没有生成错误 MPKG。该结果不能证明生产转换器不兼容，也不能计入 HC3 结论；特殊 TEX profile 仍需独立诊断。

### 最终判断

- **HC3 已作为生产无损默认值部署。** 它保持 TEX/MPKG 格式和 RGBA 内容不变，在三个真实场景上聚合减少 `4.40%`。
- HC6 和 HC7 不再作为默认候选；额外尺寸收益不足以补偿 CPU 时间。
- 阶段五测量期间生产代码仍使用 Fast，也没有重新部署 WallHub；测量完成后源码已固定为 HC3 并加入协作取消和 `EXPORTING` 提交边界。
- commit `b3b3fb4` 已通过 GitHub Actions run `30259581383`，同 SHA Release artifact 已安装并冷启动成功。
- 安装后的生产转换器直接复跑三个场景，输出大小和 SHA-256 全部与本阶段 HC3 候选一致；直接时间为 `2,602.295 / 1,903.877 / 904.633 ms`，聚合约 Fast `1.67x`。
- 生产转换取消探针测得请求到协作边界 `14.495 ms`，已有输出保留且无临时文件；完整 WorkManager 端到端取消仍需验证。
- thermal HAL 未就绪和特殊动画/tail 场景未诊断仍是残余风险。

## 主要来源

### Khronos / Android

- OpenGL ES 3.0.6 specification：<https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/specs/es/3.0/es_spec_3.0.pdf>
- Khronos ETC2/EAC tokens：<https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/ARB/ARB_ES3_compatibility.txt>
- Khronos compressed format size table：<https://raw.githubusercontent.com/KhronosGroup/OpenGL-Refpages/main/es3/compressedformattable.xml>
- AOSP `GLES30.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/opengl/java/android/opengl/GLES30.java>
- AOSP `ETC1Util.java`：<https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/opengl/java/android/opengl/ETC1Util.java>

### LZ4

- lz4-java `1.8.0`：<https://github.com/lz4/lz4-java/tree/1.8.0>
- lz4-java README：<https://github.com/lz4/lz4-java/blob/1.8.0/README.md>
- `LZ4Factory`：<https://github.com/lz4/lz4-java/blob/1.8.0/src/java/net/jpountz/lz4/LZ4Factory.java>
- `LZ4Compressor`：<https://github.com/lz4/lz4-java/blob/1.8.0/src/java/net/jpountz/lz4/LZ4Compressor.java>
- LZ4 block format：<https://raw.githubusercontent.com/lz4/lz4/dev/doc/lz4_Block_format.md>
- LZ4 frame format：<https://raw.githubusercontent.com/lz4/lz4/dev/doc/lz4_Frame_format.md>

### Encoder candidates

- etcpak：<https://github.com/wolfpld/etcpak>
- Google etc2comp：<https://github.com/google/etc2comp>
- Khronos KTX-Software：<https://github.com/KhronosGroup/KTX-Software>

### 本地第一方样本

- 官方 APK：`/tmp/opencode/wallpaper-engine-official.apk`
- 官方示例 MPKG：`/tmp/opencode/wallpaper-engine-official/assets/wallpapers/*.mpkg`
- 可复现 TEX 分析：`/tmp/opencode/analyze_wallpaper_tex.py`

这些 `/tmp/opencode` 文件是本地研究材料，不属于仓库发布内容，也不能替代未来的正式测试 fixture 或设备验证。

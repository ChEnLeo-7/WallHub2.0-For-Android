# Wallpaper Engine Discover 数据流与 WallHub 差异研究

研究日期：2026-10-26

## 结论摘要

可由第一方资料确认的 Wallpaper Engine Windows 客户端 Discover 模型是：**Wallpaper Engine 团队策展（Approved、创作者、关键词、手工合集、季节合集）+ 客户端向其内部服务取得 Discover 配置/推荐信息 + 通过 Steam Workshop / Steamworks 取得实际 UGC 项目与合集成员**。Discover 的定位是滚动和投票，而不是搜索；搜索和精确筛选属于旁边的 Workshop 页。

官方客户端是闭源软件，Wallpaper Engine 没有公开 Discover 私有端点、请求体、完整排序算法或刷新随机种子。因此，不能仅凭公开资料断言当前版本具体调用哪个 URL，也不能证明 `wallpaperengineapi.com` 是 Wallpaper Engine 官方域名。

WallHub 采用相似的“控制面描述符 + Steam 内容面”分层，但不是官方客户端的已证等价实现：它先加载客户端硬编码的栏目、季节合集、年份封面，再读取 `https://www.wallpaperengineapi.com/api/explore/v1` 的描述符，并把描述符翻译为自身支持的 Steam Community HTML、Web API 或 CM 查询；点赞/点踩只保存在 WallHub 本地并作用于栏目排序。

## 1. 官方客户端可以确认的行为

### 1.1 Discover 浏览的是同一套 Steam Workshop 内容

Wallpaper Engine 2018 年官方更新公告称，Discover 是壁纸浏览窗口的新标签，用于按照 **genres、keywords、creators** 浏览 Workshop。这证明 Discover 并非另一套壁纸仓库，而是 Workshop UGC 的另一种策展入口。

来源：[Wallpaper Engine — Workshop Discovery（Build 1.0.1276）](https://store.steampowered.com/news/app/431960/view/5278738040558265941)

Wallpaper Engine 开发者 Tim 也明确说明，Discover 不用于搜索，而是用于 **scrolling and voting**；要按类别搜索，应使用相邻的 Workshop 搜索页。

来源：[Where is the Search Bar for the Discover Section](https://steamcommunity.com/app/431960/discussions/2/1735465524697543960)

### 1.2 推荐不是纯算法抓取，存在人工策展

Wallpaper Engine 官方 Showcase 置顶说明：团队会浏览用户作品，手工添加 `Approved` 标记，并把作品加入会在 Discover 中高亮的不同合集。这是直接的一方证据，说明 Discover 的推荐集合至少部分由 Wallpaper Engine 团队人工维护。

来源：[Wallpaper Showcase — Read first](https://steamcommunity.com/app/431960/discussions/9/1697221160904462132)

官方 2019 更新公告进一步说明，Discover 的 seasonal collections 由团队 **manually curate**。因此，季节合集固定或半固定并不是 WallHub 自创的产品概念，而是官方 Discover 已公开采用的策展方式。

来源：[Seasonal Recommendations（Build 1.1.271）](https://store.steampowered.com/news/app/431960/view/1652185077936791621)

### 1.3 Discover 存在 Wallpaper Engine 自有服务控制面

Tim 在 `wpx.app` 事故说明中称，Discover 标签有后台请求，并提到 Wallpaper Engine 的 **own internal servers for the Discover tab**。这能证明 Discover 不只是客户端直接运行一个固定 Steam 查询；至少还有 Wallpaper Engine 自有服务参与。

来源：[wpx.app / GoDaddy Bug Explanation](https://steamcommunity.com/app/431960/discussions/2/571540483751318117)

公开资料没有披露该内部服务当前域名、协议或响应结构。由此只能确认“有内部服务”，不能确认 `wallpaperengineapi.com` 就是该服务。

### 1.4 Steamworks 提供实际项目和合集所需的数据面

Steamworks `ISteamUGC` 官方文档提供：

- `CreateQueryAllUGCRequest`：按排序方式查询应用的 UGC；
- `AddRequiredTag` / `AddExcludedTag` / `SetMatchAnyTag`：按标签筛选；
- `SetRankedByTrendDays`：按 1–365 天趋势期排序；
- `CreateQueryUGCDetailsRequest`：按具体 PublishedFile ID 获取详情；
- `GetQueryUGCChildren`：获取合集或依赖项的子项目；
- `SetReturnChildren`、`SetReturnMetadata` 等：请求附加字段。

来源：[Steamworks ISteamUGC Interface](https://partner.steamgames.com/doc/api/ISteamUGC)

这些 API 足以实现官方公告所说的类别、标签、趋势、创作者和合集展示。但由于客户端闭源，文档只能证明 Steamworks **能够**支持该流程，不能单独证明 Wallpaper Engine 每个 Discover 栏目具体调用哪一项 API。

### 1.5 官方 Android 客户端不是对比对象

Wallpaper Engine 官方文档明确说 Android companion app 因技术和法律限制不能直接从 Workshop 下载新壁纸，需要从 Windows 客户端传输。因此，上述 Discover/Workshop 数据流对应的是 Windows 客户端；官方 Android companion 并没有与 WallHub 等价的直接 Workshop Discover 页面。

来源：[Pairing Mobile Device with Windows](https://help.wallpaperengine.io/en/mobile/pairing.html)

## 2. 官方 Discover 的最可信数据流模型

### 已证事实

1. Wallpaper Engine 团队人工设置 Approved、人工维护部分合集和季节合集。
2. 客户端 Discover 会向 Wallpaper Engine 自有内部服务发后台请求。
3. Discover 最终浏览的是 Wallpaper Engine 的 Steam Workshop UGC。
4. Discover 用于滚动和投票；Workshop 标签负责搜索和详细筛选。
5. Steamworks 提供动态项目查询、趋势、标签、详情和合集子项能力。

### 合理推断

最合理的架构是两层：

```text
Wallpaper Engine 内部 Discover 服务
  ↓ 下发策展主题、关键词、创作者、合集、封面或排序提示
Windows 客户端
  ↓ 转换为 Steamworks UGC 查询
Steam Workshop / ISteamUGC
  ↓ 返回实际项目详情、预览图、排名与合集子项
Discover UI
```

这一模型同时解释了：人工策展如何即时更新、为什么 Discover 仍依赖 Steamworks、以及为什么内部服务出错会影响 Discover。

### 仍未知

- 当前官方 Discover 内部服务的实际域名；
- 服务是否直接返回部分 Wallpaper ID，还是主要返回查询描述符；
- 刷新是否服务端生成新批次、客户端随机抽样，或两者结合；
- 投票是 Steam UGC vote、Wallpaper Engine 自有推荐反馈，还是组合；
- 个性化特征和排序权重；
- 缓存期限与离线回退机制。

没有抓取官方 Windows 客户端当前版本的实际网络追踪前，不应把这些未知项写成事实。

## 3. WallHub 当前数据流

### 3.1 每次刷新先提交客户端静态骨架

`DiscoverViewModel.refresh()` 并行启动静态 generation 和网络 metadata generation，但静态 bootstrap 拥有初始列表，远程元数据必须等待它提交后再合并。

源码：

- `app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverViewModel.kt:71-125`
- `app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverViewModel.kt:323-365`

静态骨架固定加入：

- 最近 Approved；
- 本月趋势 Approved；
- 移动兼容 Approved；
- 音频响应；
- 年度趋势；
- 五个固定季节 Collection；
- 2016–2026 各年的固定封面项目。

源码：

- `app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverFeedCoordinator.kt:59-75`
- `app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverFeedCoordinator.kt:351-409`
- `app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverFeedCoordinator.kt:440-540`
- `app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverFeedCoordinator.kt:679-700`

因此 WallHub 的第一屏栏目骨架和这些 Collection/封面只能随 APK 更新。

### 3.2 再读取外部描述符端点

WallHub 请求：

```text
https://www.wallpaperengineapi.com/api/explore/v1
```

并在内存中缓存成功结果 15 分钟；失败且无缓存时使用客户端静态回退，回退两分钟后再尝试。

源码：`app/src/main/kotlin/com/wallhub/android/data/discover/HttpOfficialDiscoverMetadataRepository.kt:45-111`

2026-10-26 的直接观测结果为 HTTP 200、`Cache-Control: public, max-age=60`，JSON 为 `response.items` 160 条：

- creator：78；
- keyword：78；
- collection：4；
- 82 条带 `coversubmission`；
- query type 主要是 `published_votes`、`trend_year`、`top_rated`、`published_desc`。

它返回的是策展/查询描述符，而不是整页 Wallpaper 详情，结构与“控制面”角色吻合。

但是，公开搜索、TLS 证书和 RDAP 只能证明该域名存在（注册于 2017-12-14）并控制自己的证书；没有发现 Wallpaper Engine 官方网站、官方文档或开发者公开声明把该域名列为官方服务。因此：

> 当前类名 `OfficialDiscoverMetadataRepository` 和源码中的 `OFFICIAL_DISCOVER_URL` 是 WallHub 的信任/命名选择，不是已由公开一方资料证明的所有权事实。

### 3.3 WallHub 自己翻译描述符并请求 Steam

WallHub 将 `creator`、`keyword`、`collection` 描述符转为自身查询计划，将 query type 映射成：

- `trend_month` → 30 天趋势；
- `trend_year` → 365 天趋势；
- `published_votes` → 最多投票；
- `published_desc` / `most_recent` → 最近发布；
- `top_rated` →最高评分。

源码：`app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverQueryAdapter.kt:52-255`

实际 Wallpaper 页由 WallHub 当前选择的数据源取得（Community HTML、Web API 或 CM/SDK）。Collection 成员通过 `SteamUnifiedWorkshopRepository.getPublicCollectionChildren()` 获取，再批量解析详情。

源码：

- `app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverFeedCoordinator.kt:130-189`
- `app/src/main/kotlin/com/wallhub/android/data/discover/SteamDiscoverCollectionResolver.kt:27-61`

### 3.4 WallHub 的刷新和反馈是本地实现

候选项目会过滤无效 ID、重复项、无预览图和未知类型；指定封面优先，其余候选以 `generation xor spec.id.hashCode()` 为种子洗牌。刷新生成新 generation，因而可能更换栏目抽样和顺序。

源码：`app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverFeedCoordinator.kt:270-293`

WallHub 的点赞、点踩和收藏针对的是“栏目/查询”，保存在 Android DataStore，并调整本地栏目权重或抑制栏目；这不是已经证明的官方 Discover 投票协议，也不会写回 Wallpaper Engine 内部服务或 Steam item vote。

源码：

- `app/src/main/kotlin/com/wallhub/android/feature/discover/DiscoverViewModel.kt:156-190`
- `app/src/main/kotlin/com/wallhub/android/data/discover/DataStoreDiscoverFeedbackRepository.kt:33-94`

## 4. 差异对照

| 维度 | Wallpaper Engine Windows（可证范围） | WallHub Android |
|---|---|---|
| 内容仓库 | Steam Workshop | Steam Workshop |
| 策展控制面 | Wallpaper Engine 自有内部服务 + 团队人工策展 | 客户端静态骨架 + `wallpaperengineapi.com` 描述符 |
| 策展来源 | Approved、关键词、创作者、人工合集、季节合集 | 固定基础查询、固定季节 Collection、固定年份封面、外部 creator/keyword/collection |
| 实际 UGC 查询 | 可确认依赖 Steamworks；精确调用未知 | Community HTML、Web API 或 CM/SDK，由 WallHub 适配 |
| 合集成员 | Steam Workshop Collection；具体取法未公开 | CM/统一仓库取 Collection children，再取详情 |
| 刷新 | 滚动/投票式推荐；批次算法未公开 | 新 generation、本地排序、确定性洗牌、远程描述符合并 |
| 投票/反馈 | 官方称可 voting；后端和语义未公开 | 栏目级本地 DataStore 反馈，不上报官方服务 |
| 静态兜底 | 未公开 | APK 内硬编码核心栏、季节合集、年份封面及四个回退合集 |
| 远程配置缓存 | 未公开 | 网络快照 15 分钟；静态回退 2 分钟 |
| Android 官方支持 | 官方 Android 不直接访问 Workshop | WallHub 直接浏览、详情、下载和转换 Workshop 内容 |

## 5. 关键判断

1. **WallHub 不是完全固定项目列表。** 实际栏目内容仍动态来自 Steam 查询和 Collection 当前成员。
2. **WallHub 的策展骨架比官方已知模型更客户端固化。** 固定季节 Collection 和年度封面必须发 APK 才能改变；官方明确有内部服务和人工策展，因此具备不更新客户端就改变推荐的能力。
3. **WallHub 与官方设计方向相似。** 两者都有“策展描述/主题”和“Steam UGC 实际内容”两层，也都有季节和人工合集概念。
4. **WallHub 不是已证的官方 Discover 协议复刻。** 外部端点归属未证，查询映射、洗牌、反馈、缓存和降级均是 WallHub 自己的实现。
5. **若要提高官方一致性，首要工作不是复制更多固定 ID，而是确认官方控制面协议。** 在没有当前 Windows 客户端网络追踪或官方接口说明时，最诚实的改进是把端点标为“外部策展元数据”，减少 `Official` 命名，并把客户端固定合集降为首次无缓存时的极简兜底。

## 6. 研究限制

- Wallpaper Engine 客户端闭源；未对用户机器上的 Windows 客户端做抓包或二进制分析。
- 没有官方公开 Discover 私有 API 规范。
- `wallpaperengineapi.com` 的响应被直接观测，但公开所有权没有得到证明。
- 因此报告没有把“可能的控制面端点”写成“官方端点”，也没有猜测官方未公开的排序算法。

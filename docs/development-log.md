# WallHub Android 开发日志

此文件是 WallHub Android 唯一的开发文档，用于按版本记录已完成的功能、更新、修复与人工验证结果。

- 不再维护路线图、阶段计划、迁移矩阵或人工审查清单。
- 后续需求由人工直接提出；完成并构建版本后，在此按版本追加记录。
- 未保存独立发布记录的历史构建不会倒推或虚构具体变更；仅保留可核验的汇总节点。

## 当前版本

- 版本：`0.8.25 (35)`
- 包名：`com.wallhub.android`
- 最低 Android 版本：Android 8.0（API 26）
- 构建产物：`app/build/outputs/apk/release/app-release.apk`（本地构建使用 Android Debug 证书签署，不作为公开分发签名）

## 版本记录

### 0.8.25 (35) — 2026-07-27

#### 更新

- 新增实验性 Steam 连接增强，移植 WallHub Webview 的多源 DoH、Steam 边缘别名、历史成功地址、低优先级 Akamai 保底地址、应用层探测、失败冷却和普通网络回退策略。
- Android 安全实现始终保留原 Steam 请求域名、TLS SNI、系统 CA 链与主机名验证；未移植 Webview 的隐藏/假 SNI 和 `rejectUnauthorized: false` 行为。
- Workshop、Steam Community、WebAPI、下载前详情、缩略图和 Coil 图片请求接入共享 Steam 网络客户端；JavaSteam Directory HTTP 同步接入，WebSocket CM 与 Depot/CDN 深度选路留待后续阶段。
- 已登录用户的发现页优先通过现有 JavaSteam CM 会话调用 `PublishedFile.QueryFiles#1`，直接读取 protobuf 详情并复用现有筛选与分页语义；启动时存在已保存凭据则最多等待 12 秒完成会话恢复，Unified RPC 不可用时依次回退 Steam Web API Key 和 Community HTML。
- 公共 Workshop 链路扩展为登录或匿名 CM：无保存账号时自动建立独立匿名会话，通过 `PublishedFile.QueryFiles/GetDetails` 提供发现和详情；登录会话通过 `Player.GetPlayerLinkDetails` 批量补作者与评论者昵称/头像，并以标准 `ClientRequestFriendData` persona 回调作为后备。JavaSteam 1.8.0 缺失的 Community protobuf 由应用内最小协议补齐，登录态评论读取与发表改走 `Community.GetCommentThread/PostCommentToThread`，不再访问 Community HTML 评论端点。
- 未登录评论改用无需 Steam Web API Key 的 `api.steampowered.com/ICommunityService/GetCommentThread` POST；如用户已配置 API Key，则通过 `ISteamUser.GetPlayerSummaries` 批量补齐匿名列表、详情和评论昵称/头像。匿名 `Player.GetPlayerLinkDetails` 实测返回 `AccessDenied`，旧 CM `ClientRequestFriendData` 也未返回 persona，因此完全匿名且无 Key 时稳定降级为通用 Steam 用户名称，图片、视频、manifest 与 Depot chunk 继续由 Steam/Akamai CDN 直连。
- 实验功能新增持久化“数据获取源”，提供 `Steam Community HTML`、`Steam Web API`、`Steam CM WebSocket` 三个严格通道并默认 Community HTML；切换后发现页自动刷新，详情与评论读取同一设置。Community 模式恢复 HTML 评论和页面作者路径，Web API 模式的发现页明确要求 API Key，CM 模式的公开发现/详情支持匿名会话而评论明确要求登录；通道失败不再静默跨源回退，CDN 数据面不受选择影响。
- Android 全局 Steam 流量加速方案已结合 CNBlogs、BlueBird、SNIBypassGUI、`bypass-GFW-SNI/main` 与 WebView 源码重写。参考实现的本地 DNS/Hosts、候选 IP、无/伪 SNI与原 Host组合可绕过当前阻断，但现成方案依赖本地 CA、TLS 终止或关闭上游校验，不能直接移植。产品路径收敛为一步到位的全设备 `VpnService + TUN + 成熟用户态 TCP/UDP 栈 + 原始 SNI TLS Record Fragmentation`，不再实现 WallHub-only HTTP增强、selected-app中间产品或 app-owned无 SNI fallback，并禁止 CA、MITM、Fake SNI、trust-all和远程代理。无 SNI虽已证明当前候选 CDN路径可达，但透明改写第三方 ClientHello会使客户端与服务端 handshake transcript不一致并导致 `Finished`失败；只有终止 TLS才能修复，因而不适用于安全全局代理。系统 DNS地址作为不透明 `SYSTEM_RESOLVED_ROUTE` 原样交给网关；仅兼容由外部底层网关持有、protected socket可达且不和 TUN/on-link route冲突的任意 IPv4/IPv6 Fake-IP池，Android 本机另一 VPN的 mapping因单 VPN限制不受支持。Web API/CM/CDN保持直连。首个功能硬门槛是在捕获全设备流量的同一 TUN数据面，由 Steam App、Chrome和 WebView执行原始 SNI/候选 IP/fragmented original SNI/关闭分片的差分测试及错误证书测试；不保证对重组跨 TLS records ClientHello的 DPI有效，分片失败即停止方案而不是降级 MITM/no-SNI。
- 设置的实验功能页增加连接增强开关、智能 DoH/应用内 Hosts 模式、解析端点、状态摘要和手动重新预热；连接增强默认关闭，并与显式代理启用状态互斥。
- 下载代理新增独立启用开关；旧版已保存地址继续保留但不会静默启用，需用户确认。启用后下载、在线播放及其前置 Steam 公共详情请求使用代理，代理失败不会回退连接增强。
- 路由历史按 Wi-Fi、蜂窝、以太网和系统 VPN 类型隔离；网络变化、设置变化或手动刷新会清理路由与连接池。评论等写操作关闭自动连接重试和重定向，避免请求体可能发出后跨候选地址重复提交。
- 使用新的蓝色下载与齿轮品牌图替换旧绿色 `W` Logo；APK 启动图标新增五档密度资源、Android 8+ adaptive icon 和 Android 13+ monochrome themed icon，README 中英文项目页同步使用原始高清 Logo。
- APK 启动图标单独改用留白更完整的新构图，项目 README Logo 保持不变；外观设置新增“图标跟随系统取色”，Android 13+ 通过 adaptive icon `monochrome` 图层与 launcher 莫奈着色，并使用双 `activity-alias` 在系统主题图标和固定彩色图标之间切换。
- 将 adaptive icon 的彩色与单色前景统一缩至原画布的 `84%` 并居中，`mdpi` 有效图形边界由约 `67×60` 收紧为 `56×50`，增加 launcher 遮罩内留白；legacy 图标、蓝色底板和项目 README Logo 保持不变。
- 日常 GitHub Actions APK artifact 保留期由 14 天调整为 7 天；新增 tag 推送与手动 dispatch 双入口的 GitHub Release 工作流，基于最新 `main` 构建 universal、`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 五个稳定签名 APK。Release 正文直接发布双语更新/修改/修复/下载说明、逐文件 SHA-256、源码 commit 与签名证书信息，不重复页面标题；人工上传的 Assets 只包含五个 APK。
- 新增 `wallhub-release-publish` 项目 Skill、REST API 发布脚本和完整发布文档，将版本/tag 校验、双语 notes、ABI 隔离、draft 门槛、资产下载复验和发布后记录固化为统一流程；新增 Release metadata 同步工作流，用于在不重发 APK 的情况下移除重复正文标题、同步版本化 notes 并清理非 APK 人工资产。

#### 验证

- 数据源选择提交 `9c5caee2f58c36ed88c1b0df277ab2163dc1d4a8` 的 GitHub Actions run `30344141361` 通过全量 JVM 测试、`lintDebug`、稳定签名 Release APK、提交绑定 artifact 校验、ADB 原位安装与冷启动，PID `16751` 存活。Android 15 真机确认新 DataStore key 缺失时默认显示 `Steam Community HTML`，底表完整显示三个通道；切换 `Steam CM WebSocket` 后发现页约 `3.066 s` 加载约 `50342` 个项目，强制停止与冷启动后仍保持 CM 选择；无 Key 切换 Web API 并冷启动后明确显示“需要先配置 API Key”和 `0` 个项目，没有回退 CM，随后恢复 CM 后重新加载约 `50352` 个项目。最终 PID `18333` 存活且日志无 `connection closed`、致命异常、ANR 或 OOM。当前直连网络阻断 Community，因此未将默认 HTML 的加载失败误判为实现故障；Web API 模式仍需配置有效 API Key 后验证真实发现结果。
- 匿名 CM 与 Unified 评论实现的早期 Actions runs `30337585428`、`30337781962`、`30337973931`、`30338170087`、`30338342330`、`30338665095` 依次暴露 protobuf version-catalog accessor、Java builtin 输出和 Kotlin 返回类型/重复常量问题；各门槛均在失败点停止，未安装失败 APK。修复后的 run `30339054623` 在 commit `2058d892ecfeab785efeff8bdefeb38d4547e356` 首次通过全量 JVM 测试、`lintDebug`、稳定签名 Release 组装和 artifact 校验，并原位安装成功。
- 最终 persona 后备提交 `3e9e9c59b484ea4bfcc0647cec5d7947e75f988f` 的 Actions run `30340354584` 再次通过全部测试、lint、签名 Release APK 与提交绑定 artifact 校验；APK 原位安装到 Android 15 设备 `192.168.2.190:5555`，冷启动 PID `12498` 存活。设备保存的 Steam refresh token 已被服务器判定失效且未配置 API Key，因此本轮属于完全匿名实测：发现页通过匿名 CM 显示约 `50214` 个项目，项目 `3737237256` 详情约 `3.207 s` 显示完整统计/标签/时间/大小，公共 Unified Web API 评论约 `3.029 s` 显示总数 `195` 与真实正文；作者和评论者按设计使用通用名称，日志无 `connection closed`、致命异常、ANR 或 OOM。登录态 Community 评论与 persona 昵称仍需用户重新登录后补充真机验证，本次未修改或清除保存凭据。
- Unified Messages 发现页首次验证 run `30332798691` 在 `:data:steam:compileDebugKotlin` 发现会话等待 lambda 被推断为 `Unit`；CI 在签名构建与 artifact 上传前停止，ADB 设备仍保持原 `0.8.25 (35)` APK。等待逻辑随后改为条件循环后显式返回会话可用状态。
- 修复提交 `787149a865003199cdafb2ba8d7d0108c40e5b20` 的 GitHub Actions run `30332934611` 通过全量 JVM 测试、`lintDebug`、稳定签名 Release APK 组装和提交绑定 artifact 校验，并原位安装到 Android 15 设备 `192.168.2.190:5555`；冷启动 PID `6424` 存活。保持系统代理为 `null`、网络 `NOT_VPN` 后连续两次自动冷启动，发现页均在 18 秒内通过 Unified Messages 加载约 `49656` 个项目，可见真实标题、类型、订阅数、收藏数和文件大小，未出现 `connection closed`、重试或持续加载；最终 PID `8125` 存活且日志无致命异常、ANR 或 OOM。本次仅使用 7 天 Actions artifact，未创建 tag 或 GitHub Release。
- GitHub Actions run `30266556810` 在 commit `b200cae616a6bc7c1d44dd3805131b262712f625` 通过全量 JVM 测试、`lintDebug`、签名 Release 组装和 artifact 上传；提交绑定 APK 完成 SHA-256、ZIP、八个 DEX 与签名证书检查，并已原位安装到 `192.168.2.190:39055`。WallHub `0.8.25 (35)` 冷启动 PID `3707` 存活，日志无致命异常、ANR 或 OOM。
- GitHub Actions run `30269430089` 在 commit `9080ca5652ee64fd87678d2b91107cf68e162d60` 再次通过全量 JVM 测试、`lintDebug`、签名 Release 组装和提交绑定 artifact 校验，并原位安装到同一 Android 15 设备。真机从设置关闭主题图标后 launcher 唯一入口切换为 `MainActivityColorIcon`，重新开启后恢复 `MainActivityThemedIcon`，切换期间 PID `5404` 未退出；随后强制冷启动 PID `6285` 存活、DataStore 选择保持且日志无致命异常、ANR 或 OOM。
- GitHub Actions run `30271536333` 在 commit `912b9ea5d179f303bec5ddb2f042c8224134c58d` 通过同一套测试、lint、签名 Release 与 artifact 校验，并原位安装到 Android 15 设备；冷启动 PID `7839` 存活且无致命异常、ANR 或 OOM。launcher 桌面截图确认缩小后的 WallHub 前景与相邻壁纸引擎图标具有接近的视觉占比，四周留白完整，当前入口继续保持 `MainActivityThemedIcon`。
- 发布流程的日常兼容验证 run `30294884035` 在 commit `8de9e9287561eeaee37b05fe82f019cf2ac9ce85` 通过全量测试、lint、默认 universal Release artifact 与 ADB 原位安装，证明按需 ABI split 未改变普通 `app-release.apk` 路径；WallHub 冷启动 PID `13717` 存活且无致命异常、ANR 或 OOM。
- 首个公开 [GitHub Release `v0.8.25`](https://github.com/ChEnLeo-7/WallHub2.0-For-Android/releases/tag/v0.8.25) 由 run `30297929213` 从 tag commit `398c993096f25a46f79bc1861cf69f02c53f8be1` 发布并标记为 Latest。两次发布前 draft 门槛分别发现 draft 按 tag 查询 404 和资产名称换行比较错误，均未产生公开 Release；修复后五个 APK 资产完整发布，SHA-256、源码 commit、版本和签名证书直接展示在正文。重新下载五个 APK 后，GitHub asset digest / 正文 SHA-256、ZIP、`classes.dex`、单 ABI 隔离 / universal 四 ABI、源码 commit 和签名证书 `940402C12B4270F1000C61882A42EC610292AB776F28F85784D6954EA7DB074D` 全部通过。

### 0.8.24 (34) — 2026-07-24

#### 更新

- 2026-07-27：MPKG 场景纹理的无损 raw-LZ4 压缩从 Fast 切换为固定 HC3；三场景隔离验证覆盖 151 个 TEX、`587,959,836` 字节 RGBA，完整包聚合减少约 `4.40%`，独立逐字节比较和官方 Wallpaper Engine 预览均通过。
- 2026-07-27：Workshop 转换加入 archive entry、目录遍历、shader 步骤和每 `1 MiB` MPKG/ZIP payload 的协作取消检查；转换完成后进入不可取消的 `EXPORTING` 提交状态，避免外部输出替换与取消竞争，并在完成记录持久化后再清理源 staging。
- 2026-07-27：新增 HC3 level 区分、MPKG 中途取消原子回滚、转换取消信号及 `EXPORTING` 操作状态测试。GitHub Actions JVM 测试、`lintDebug`、Release artifact、ADB 安装、冷启动和直接生产三场景复测仍待执行。
- 2026-07-27：GitHub Actions run `30259227737` 在 commit `5ad592f94874c738c851e789e7ff94d8def31e76` 的 `:data:downloads:compileDebugKotlin` 阶段发现 `SafExportGateway` 缺少局部复制缓冲常量；CI 在构建 artifact 前停止，设备 APK 未改变。已恢复该常量并准备以新 commit 重新运行完整验证。
- 2026-07-27：修复 commit `b3b3fb4d27d7b28affb31676c573a8a419081dfe` 的 GitHub Actions run `30259581383` 通过全量 JVM 测试、`lintDebug`、签名 Release 组装和 artifact 上传；artifact `wallhub-release-b3b3fb4d27d7b28affb31676c573a8a419081dfe`（ID `8650420913`）完成 SHA-256、ZIP、八个 DEX 和签名证书检查，并已原位安装到 `192.168.2.190:44397`。WallHub 冷启动 PID `16991` 存活，日志无致命异常、ANR 或 OOM。
- 2026-07-27：使用已安装 `b3b3fb4` APK 的生产 `WorkshopConverter` 直接复跑三个源场景，输出大小和 SHA-256 与隔离 HC3 候选逐字节一致：`3742497499` 为 `207,388,499` 字节、`C6D648...4970`、`2,602.295 ms`；`3746422401` 为 `201,288,497` 字节、`E7E0E3...E421`、`1,903.877 ms`；`3768443264` 为 `19,875,675` 字节、`95B07D...78F4`、`904.633 ms`。三场景合计 `5,410.805 ms`，约为 Fast 中位合计的 `1.67x`，通过 `2x` 门槛。
- 2026-07-27：一次性生产转换取消探针在 `3742497499` 转换开始 `250 ms` 后请求取消，`14.495 ms` 后在协作边界观察到取消；总计 `266.212 ms` 退出，已有输出哨兵逐字节保留，原子临时文件为 `0`。该结果验证转换器回调与原子回滚，不替代 UI → Room → WorkManager 的完整取消延迟测试。
- 首页与资料库分页改为 Material 3 离散按钮组，仅展示去重后的最小页、当前页和已知最大页；当前页按钮可打开页码输入对话框，最小页和最大页可一击直达。
- 发现页读取 Steam 社区页面 SSR 数据中的 `total_pages` 与 `total_count`，分页栏会在右侧省略号后显示服务端当前可直接跳转的最大页（当前为第 `1000` 页）。
- 当前页可打开页码输入对话框；输入区显示服务端当前已知最大页码，但允许输入任意大于 `0` 且可表示的整数页码，不再受当前 `totalPages` 限制。
- 页码切换使用短距离水平过渡，按钮按压使用弹簧缩放；视觉按钮保持稳定尺寸，触控目标不小于 `48.dp`。
- 分页按钮移除描边和色调叠加，未选中项固定使用纯白背景；选中项继续使用主题强调色，并在白色主色的深色主题中自动切换到可辨识的选中容器色。
- 分页移除上一页、下一页、邻近页和省略号窗口；当前页位于中间时固定显示 `1 / 当前页 / 最大页`，当前页与边界重合时自动去重。
- 输入 `author:<SteamID64>` 会进入独立的作者结果路由，标题栏改为 Material 3 返回图标加“返回”；点击后直接回到原发现页或详情页的缓存状态。
- 发现页和资料库的长按菜单收紧为更紧凑的 Material 3 tonal surface，缩短宽度、内边距和行距，同时保留清晰图标、文本截断和可触达操作行。
- 资料库搜索结果保留稳定卡片 key：屏内匹配卡片使用非线性位置动画上移重新排序，首次进入视区的匹配卡片从底部遮罩外平滑进入。
- 长按菜单按需读取并缓存创意工坊详情页中的 Steam 显示名；资料库和发现页不再在作者信息中展示“Steam 用户 <ID>”占位格式。
- 管理抽屉将“浏览方式”及“瀑布流拼接 / 页数组件”移至资料库标签；资料库长按时“管理”标题和筛选 FAB 同步使用毛玻璃背景。
- 管理页资料库的筛选 FAB 已纳入资料库长按菜单的统一背景合成层，长按时与列表一并模糊和遮罩，不再单独出现方形模糊边界。
- 资料库长按菜单按被操作卡片的实际宽度自适应收紧，并改用不透传封面颜色的 Material 3 高层 tonal surface，深色封面下仍保持稳定的文字对比度。
- 发现页与管理资料库的长按菜单收敛到 `core:designsystem` 同一实现，共享卡片宽度自适应范围、实色高层 tonal surface、信息与操作行、触点定位、进出动画、背景遮罩及卡片预览；后续菜单视觉修改会同时作用于两页。
- 管理页扩展为下载、资料库、本地三个并列区段；资料库支持搜索结果交错入场、滚动收束标题与次要筛选、保留搜索框，以及收起后的图标筛选入口和活动数量徽标。
- 新增本地壁纸管理器：递归扫描下载目录与可选 SAF 目录，按 MPKG、PKG、视频、HTML/网站和未知格式分类，支持列表/网格/详情视图、搜索、筛选、排序、收藏、标签、批量选择、分享、打开和确认删除。
- 新增本地资源元数据 Room 表、视图偏好和导入发起状态；MPKG 导入只记录“已发起导入”，没有外部可靠回调时不会虚报成功。
- 本地页再次按 Material 3 信息层级收敛：移除与完整筛选底表重复的格式快捷标签，只保留摘要和单一筛选入口；搜索常驻，列表/网格成为仅有的浏览模式，刷新保留为直接操作，目录与标签管理归入更多菜单。
- 本地页增加方向一致的收束与动效：上推收起 Manage 标题和本地次要工具，下拉即展开，父级资源摘要保持稳定；列表/网格切换与详情进退使用短距离水平位移、淡入淡出和无弹跳 spring，资源增删使用 `animateItem`，网络封面使用短交叉淡入。
- 详情不再作为第三个视图模式持久化；点击资源进入详情时自动让出标题、资源摘要、筛选、搜索和工具空间，返回后恢复进入前的列表或网格模式。
- 管理页下载、资料库和本地改用可手势操控的 `HorizontalPager`；分段按钮与 Pager 状态同步，第一页继续向外拖动进入发现页，末页继续向外拖动进入设置页，顶级导航仍沿用原有方向转场与状态恢复。
- 资料库把“已加载项目”移到放大的“资料库分类”右侧，分类标签和筛选按钮合并为同一行；本地详情动作区改为响应式主按钮与紧凑 tonal 工具组，分享/复制使用中性语义色，删除使用 error 语义色。
- 本地列表项不再依赖 `ListItem` 默认内容边距，改用显式 Row 与缩略图四周统一 `8.dp` 内边距，保证封面到卡片左边和上下边距离一致。
- 本地封面改为按 Workshop ID 批量查询 Steam 公共详情接口，并以最多 3 路并发下载到 96 MB 应用缓存；命中缓存时不再联网，无法解析 Workshop ID 或网络失败时使用格式图标占位。
- 发现页筛选按 Material 3 完全重构：原四个即时底表和独立高级筛选抽屉收敛为一个完整筛选工作区，统一包含浏览、内容、主题和屏幕四页，标签点击与横向手势同步，每页独立纵向滚动，底部取消与应用操作始终固定。
- 发现页顶部筛选面板改为紧凑“浏览条件”工具栏，以可横向滚动的 Filter Chip 展示排序、时间、类型、评级、主题和屏幕状态；任意 Chip 会把统一抽屉直接打开到对应分区，完整筛选入口使用 tonal 图标按钮及活动分区数量徽标。
- 新筛选抽屉使用会话草稿：排序、时间、类型、评级、分类、官方特性和分辨率均可连续编辑，系统返回、关闭、遮罩或取消会完整丢弃草稿；“应用筛选”原子更新全部条件并只触发一次刷新，“恢复默认”先修改草稿，仍需明确应用。
- 分类与分辨率的默认状态改为明确的“不限”，不再把全部选项绘制为选中或允许“清空”和“不限”同时映射到相同请求；首次点击具体项会从不限收敛到该项，清除最后一个具体项自动回到不限。
- 管理页按 Material 3 重新组织为三个明确工作区：紧凑布局使用顶部工作区导航和上下文工具区，`840.dp` 及以上使用 `304.dp` 持久侧栏；下载、资料库和本地继续共享可手势切换的横向 Pager 及首尾顶级导航。
- 管理筛选改为当前工作区专属的宽度受限底表，独立滚动筛选内容并固定“恢复默认 / 完成”操作区；筛选项保持选择后立即生效，并显示活动筛选数量、工作区摘要和一击快捷条件。
- 管理页与本地内容显式复用同一 `LocalWallpaperViewModel`；Pager 预组合本地页时不再提前扫描，仅在本地页真正停靠后激活扫描。
- 管理页进一步按内容优先原则压缩：紧凑布局移除独立标题、摘要和快捷筛选三层重复控件，只保留固定单行的下载 / 资料库 / 本地工作区入口和唯一筛选按钮；宽屏管理侧栏由 `304.dp` 缩至 `240.dp`，并将启用阈值提高到 `960.dp`，避免中等宽度过早挤压壁纸内容。
- 静态浅色主题由全白 surface 层级改为灰白 tonal 层级：大面积画布和普通容器不再使用纯白，只有 `surfaceContainerLowest` 保留纯白供少量高对比组件使用；卡片、搜索框、筛选项和选中状态继续保持清晰层级。
- 资料库滚动时只收束搜索行；本地搜索、列表 / 网格切换、刷新和更多操作作为一个整体收束。管理父层不再同步执行第二套高度动画，收束后壁纸内容直接贴近固定工作区栏。
- 本地详情在紧凑和中等宽度保持单栏，只有内容宽度达到 `840.dp` 才启用 `32% / 68%` 列表详情分栏；详情封面改用完整适配，不再以固定横向比例裁切壁纸。
- 管理页紧凑工作区导航改为无描边、无阴影的共享 tonal 滑动选择器；移动指示器高度与 `52.dp` 容器保持一致，下载、资料库和本地切换具有连续的层次反馈。
- 本地批量选择改为与搜索工具共享同一顶部转场槽位的紧凑 tonal 选择栏，直接保留退出、分享和删除，标签与收藏操作收纳到更多菜单；浏览工具和选择栏不再通过两个父容器同时争夺高度。
- 发现页筛选工作区改为所有选择即时生效并保持底表打开；标题收敛为“筛选与排序”，移除待应用草稿、应用/取消底栏和冗余说明，导航、分区卡片与筛选项统一使用无描边 tonal 层级。管理筛选也移除确认式底栏，将关闭与恢复默认放入标题区。
- 发现页筛选页签改为固定等宽的 tonal 按钮组：外层使用低层容器，未选中项使用可见但低强调的高一层 surface，选中项使用主色容器；按钮之间保留明确间距，不增加描边或阴影。
- 发现页与管理页统一圆角角色：普通筛选项与单选行使用 `medium`，筛选分区和顶部导航外容器使用 `large`，状态胶囊保留 `extraLarge`，小型标记使用 `small/extraSmall`。管理页顶部工作区导航使用 `large` 外容器和 `medium` 移动指示块。
- 管理页移除顶部和宽屏侧栏中的重复筛选入口，改为右下角标准 Material 3 FAB；FAB 位于外层管理 Scaffold，自动避开底部导航，并保留当前工作区活动筛选数量徽标。
- 发现页和管理筛选 Chip 统一使用 Compose Material 3 原生 `FilterChip` 选中状态和 `leadingIcon`；移除自定义横向展开、内容尺寸和颜色插值，切换期间不再同时挤压或重排相邻按钮，未选状态也不预留图标槽。
- 发现页筛选标题移除右上角当前值状态胶囊；主题内容分类、官方特性和屏幕分辨率增加条件式“反选”，仅在当前不是“不限”时可用。
- 时间范围固定按“今天、7 天、30 天、3 个月、半年、一年、全部时间”由近到远排列；非标准已保存天数按数值插入有限时间范围，全部时间始终位于末尾。
- 管理页顶部下载、资料库和本地滑动选择器关闭额外按压波纹，只保留滑块位置移动反馈。
- 管理页下载和资料库筛选底表改为按实际内容高度收拢，短内容只占用标题与筛选卡所需空间；本地等长内容仍受原最大高度约束并保持内部滚动。
- 壁纸详情评论页新增 Steam 评论发表入口：仅在 Steam 会话已登录时显示紧凑 Material 3 多行输入框和内嵌发送按钮，提交期间原位显示进度，成功后清空草稿并刷新评论列表。
- 评论写入由认证 Steam 仓库生成短期 Community Web 会话并直接提交到项目评论端点；Community Cookie 仅存在于单次请求内，不写入持久化存储，refresh token 如发生轮换则继续加密保存。
- 管理页为下载、资料库和本地三个工作区增加动态大标题与短说明；紧凑导航继续保持原有滑动 tonal 外观，宽屏侧栏复用同一信息层级，并为紧凑选择器补齐 Tab 分组语义。
- 发现页紧凑筛选导航改用 Material 3 原生 `PrimaryTabRow` / `Tab`，宽屏筛选导航改用原生 `NavigationDrawerItem`；四个标签等宽，计数徽标使用固定槽位。发现页和管理页筛选 Chip 永久保留固定前导图标槽，选中勾选不再挤压文字或相邻选项。
- 发现页和资料库长按菜单移除缩放、方向位移和触点 transform origin，仅保留完整尺寸 Popup 的透明度渐变；元数据值固定为单行省略，异步作者名不再改变菜单高度和定位。
- 正式 PKG 转 MPKG 改为索引读取、未修改条目文件切片、逐纹理临时落盘和最终原子替换；输出索引固定记录并复制源文件精确长度，重复规范化路径会明确拒绝，不再静默覆盖条目。
- 为当前源码建立首个 Git 基线提交，并绑定空的 GitHub 远程仓库 `https://github.com/ChEnLeo-7/WallHub2.0-For-Android.git`；构建产物、本地配置、签名材料、归档和源码快照继续由 `.gitignore` 排除，后续改动可使用提交级差异、回退和二分定位，不再依赖整包快照作为唯一恢复手段。
- 新增 `gradle/libs.versions.toml`，集中管理 Android Gradle Plugin、Kotlin、Compose、Hilt、AndroidX、JavaSteam、Media3、网络、转换和测试依赖版本；应用、核心、数据和功能模块的构建脚本统一改用版本目录别名，依赖坐标和作用域保持不变。
- 删除零源文件、零消费者的 `:core:testing` 模块；各模块继续通过版本目录声明自身 JVM 测试依赖。
- 将八份重复的 `AppLanguage.text(zh, en)` 合并到 `core:designsystem`，覆盖应用壳和各功能模块的既有调用；同时移除三个仓库内无调用点的常量或 Composable 声明。
- GitHub Actions 工作流已投入使用：`main` 推送运行 `testDebugUnitTest lintDebug`，随后使用 `WALLHUB_RELEASE_*` Secret 中的稳定 keystore 组装 Release 并上传保留 14 天、名称绑定 commit SHA 的 APK artifact；PR 只运行测试和 lint，完全不读取签名 Secret。Release 未配置完整 Secret 时会明确失败，不会上传由临时 debug keystore 签发且无法原位安装的 APK。
- 新增 `scripts/push-build-install.sh` 与 `scripts/install-github-release-apk.sh`。前者仅推送干净的本地 `main`，后者只接受同一 SHA 的成功 Action run，校验 artifact commit 标记、SHA-256 和已安装 APK 的证书后才执行 `adb install -r`；签名不一致、设备不可用或 artifact 不匹配时会拒绝操作，不会卸载应用或清除数据。完整 Secret 设置和使用方式见 `docs/github-actions-adb.md`。
- 首次全量 GitHub CI 依次暴露并修复了 KSP 插件解析失败、下载仓库测试替身缺失 `listAll()`、定向 shader `vec4` 浮点兼容遗漏和 MediaStore API 29 helper 未声明平台要求；最终 commit `97817431aabca35fa456d1e168c38224f94bfc1f` 的 Action run `30213530872` 中 `testDebugUnitTest`、`lintDebug`、四个签名 Secret 检查、`:app:assembleRelease` 和 artifact 上传全部成功。
- commit `9b1b0b6c8c32c32a091460e08fe7b663da46ac39` 的日常入口 `scripts/push-build-install.sh` 已完成第二次端到端验收：Action run `30214074910` 成功，脚本只下载同 SHA artifact，动态选择当时唯一在线 ADB 设备，原位安装后冷启动 PID `27171` 存活，未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。
- 新增项目 Skill `.opencode/skills/github-actions-adb-deploy/SKILL.md`，将“干净 `main` commit -> 推送 -> 同 SHA GitHub Actions -> artifact 校验 -> 动态 ADB 安装 -> 冷启动”固化为 Android 源码修改后的默认验证路径；原 `android-release-build` Skill 降级为 GitHub 不可用或用户明确要求时的 LAN 回退，且不再记录固定无线 ADB 地址或端口。
- 重构 GitHub 默认 `README.md` 为中文项目入口，新增对应英文 `README_EN.md`，完整覆盖项目特色、核心功能、快速开始、开发环境、参考鸣谢与免责说明；新增 `docs/assets/wallhub-logo.svg`，其路径和配色直接转换自应用资源 `app/src/main/res/drawable/ic_wallhub.xml`。
- GitHub Actions 到 ADB 日常入口新增显式 `--install-only` 模式：仍执行同 SHA artifact、校验和、DEX、签名、安装及包版本验证，但不会清理日志、停止或启动 WallHub，也不会执行冷启动测试。

#### 修复

- 移除旧分页控件的共享胶囊底板和中间权重布局，避免底部元素互相挤压、总页数难以识别及加载状态引起的布局变化。
- 修复 Steam 新版社区页面不再提供旧式英文分页文本后，总页数解析始终为空、发现页只能把下一页误显示为最大页的问题；同时修正旧解析正则的重复转义。
- 移除首页和资料库状态层对自定义页码的已知总页数截断；页码偏移、下一页及总页数计算使用溢出安全逻辑，极大页码不会产生负索引或崩溃。
- 修复作者检索继承上一发现页总数的问题；现在从 Steam 本地化分页文本解析当前作者结果数，并以 Steam 支持的每页 `30` 项请求和计算作者页数。
- 从详情页打开作者结果时仅停止内嵌播放，不再把详情页标记为永久离开；返回作者结果后详情页状态可继续使用。
- 修复资料库卡片长按预览层未按卡片形状裁剪造成的白色圆角残影。
- 资料库在搜索框外的任何点击或手势开始时都会清除搜索焦点；筛选和分页切换仍自动回顶，搜索时不再强制回顶而打断卡片重排动画。
- 修复管理页资料库在“瀑布流拼接”加载下一页后错误监听 `currentPage` 并调用 `scrollToItem(0)`，现在追加内容会保持当前位置；仅页数组件切页和筛选条件切换回顶。
- 修复资料库长按预览期间原始卡片仍参与背景模糊、在圆角外侧留下浅色边缘的问题；原卡片在重放预览显示期间不再绘入背景层，预览层继续按卡片形状裁剪。
- 修复资料库封面使用 `16.dp` 内部圆角而卡片外壳使用 `12.dp` 圆角，长按提升后两者间露出浅色月牙边的问题；封面改为仅服从卡片外壳裁剪，两页原卡片在预览期间也统一从背景模糊绘制中排除。
- 修复发现页与资料库关闭长按菜单时原卡片立即恢复、与仍在退场的阴影预览层短暂叠加而造成阴影抖动的问题；两页现在保持原卡片隐藏，直到共享阴影动画实际完成并到达零阴影后才释放预览层，不再依赖固定延时推测结束时刻。
- 修复已保存 Steam 会话偶发长期停留在恢复状态、资料库不发起首次加载的问题：正式会话改用 WebSocket CM，CM Directory 使用有限 HTTP 超时，恢复采用总期限、有限重试和 generation 防迟到；临时网络失败进入可重试的 `RESTORABLE`，不再误判为凭据过期。
- 修复 Steam 登录后意外断线仍保留失效会话，以及资料库 RPC 可无限占用请求锁的问题；断线现在只按匹配 session 身份失效当前会话，资料库与交互 RPC 均有有限等待，资料库会在会话丢失时清理 loading 状态并提供真实恢复操作。
- 在设置页 Material 3 重设计前创建可回档源码快照 `archive/wallhub-source-20260724T093313Z.tar.gz`（SHA-256：`B6B1E8ABD74253720241DD7FBAFDE659FDD9081D8E2D0FC43E4C8E1ECC68FE67`），并提供相邻恢复说明；快照排除构建输出、凭据、本地配置和已有归档。
- 重新设计设置首页与外观设置页：分类入口改为统一 tonal list，详情页增加 760dp 可读宽度约束和中等宽度边距，外观选项按“语言与主题 / 个性化配色 / 发现页”分组；当前值移至尾部，分隔线、开关整行点击、图标容器和动态色预览均使用 Material 3 语义色与不低于 48dp 的触控区域。
- 系统动态取色状态现在使用 `primaryContainer` / `onPrimaryContainer` 成对强调，并展示当前主色、辅色和第三色色板；浅色、深色、静态色板及 Android 12+ 系统壁纸取色仍沿用完整 `MaterialTheme.colorScheme`，未为设置容器增加固定颜色。
- 在继续重构其余设置分类前创建外观页完成态检查点 `archive/wallhub-source-20260724T095602Z-appearance-checkpoint.tar.gz`（SHA-256：`635B59186BF3C4AC2733B218BEC6E4FDF2639385B6980B96F150711F12435789`），可只撤销后续基本设置、下载、Steam 与实验功能改动。
- 基本设置、下载、Steam 和实验功能统一使用 Material 3 分区标题、tonal surface、语义分隔线与整行开关：基本设置拆分内容访问和诊断支持，下载拆分存储位置、下载性能和网络代理，Steam 拆分账户会话和 Web API，实验功能拆分风险提示、在线播放、系统权限和应用信息。
- 按动作重要性调整设置按钮层级：保存和导出使用 Filled，目录选择、外部 API 页面和通知权限使用 Filled Tonal，退出 Steam 使用 Outlined，恢复默认目录使用 Text；代理和 API Key 仅在内容变化后启用保存。Steam API Key 默认以密码字段遮蔽，并提供带动态无障碍说明的显隐按钮。
- 在管理页交互重构前创建检查点 `archive/wallhub-source-20260724T101800Z-pre-management-redesign.tar.gz`（SHA-256：`3EAB772E6C9EA52742F43619DA53207040D5414AE41685E16E97BE83EB8219C3`），可恢复到设置页完成、管理页尚未调整的源码状态。
- 重构管理页导航：移除同时承担内容切换与筛选的悬浮按钮，将下载/资料库改为常驻 Material 3 分段导航；下载状态与资料库分类成为可横向滚动的一击快捷筛选，当前任务或项目数量与完整筛选入口固定显示在内容上方。
- 管理页筛选底表现在只服务当前内容视图，标题、已应用筛选数量、默认状态和恢复默认动作均随下载或资料库状态实时更新；资料库分类、壁纸类型和分页方式，以及下载状态和类型仍可在一个底表中完整调整。
- 下载与资料库切换使用有方向的 spring 水平位移配合淡入淡出，快捷筛选区使用较短的同向过渡；分段导航、快捷筛选、筛选底表和资料库长按菜单背景均使用 `MaterialTheme.colorScheme`，并保持长按预览期间顶部控制区与内容一致模糊和遮罩。
- 本地 Release 构建显式使用 Android Debug 证书签署，默认 Release 目录现在直接生成可安装的 `app-release.apk`，不再只输出未签名 APK。
- 本地扫描对普通 ZIP 保留为未知格式而不丢弃；目录项目删除会覆盖扫描到的项目文件，扫描识别期间按批次发出结果并响应取消。
- 修复本地资源封面 URI 无法稳定显示的问题；网络封面先验证图片边界再原子写入缓存，下载任务可随扫描取消，临时文件会清理且缓存按最近使用顺序限额。
- 移除 MPKG/ZIP 封面解包和视频首帧抽取；MPKG 检查不再为最多 20 万条索引分配条目对象，仅流式跳过索引并保留格式识别，降低扫描 CPU、堆内存和磁盘写入。
- 修复资料库与本地页反向滑动时顶部收束抖动：收束状态只响应真实滚动方向和累计手势距离，上推 `48.dp` / `44.dp` 收束、下拉 `20.dp` 展开；Manage 标题移出动态改变 `Scaffold` content padding 的 `topBar`，改在普通 Column 内参与同一次测量，避免 Lazy 内容为 Scaffold 边距变化补偿锚点。
- 本地页不再同时切换父级摘要/筛选行与子级工具区；父级摘要保持稳定，本地工具区单独拥有内容高度变化。资料库则由父级分类区单独拥有收束，两个页面一次手势内不再出现相互独立的高度动画争夺布局位置。
- 修复资料库展开区和收束筛选图标使用两个兄弟 `AnimatedVisibility`、短手势期间短暂同时占据两行造成的反向位移；两种状态改为共享一个带单调 `SizeTransform` 的 `AnimatedContent` 高度槽位。
- 修复 MPKG 被通用 `application/octet-stream` chooser 交给系统包安装器并显示“安装失败 -2”的问题；WallHub 现在显式启动官方 `io.wallpaperengine.weclient.BrowseActivity`，使用 `application/vnd.mpkg`、ClipData 和显式 URI 读授权，并在 manifest 声明官方包可见性。
- 修复发现页筛选修改语义不一致：旧排序和时间点击即关闭并刷新、类型和评级每次点击立即刷新、分类/官方/分辨率却需要应用；现在所有条件共享同一草稿和提交边界，活动数量按被修改的筛选分区计数，不再按单个标签差异膨胀。
- 修复管理页回顶令牌在状态恢复后重复消费、Pager 动画被手势中断后选中工作区与实际页面不同步，以及资料库长按菜单打开时仍可切换工作区或顶级导航的问题。
- 修复资料库筛选切换未取消搜索防抖、旧请求覆盖新搜索词，以及新搜索尚未应用时可错误追加旧分页的问题；管理筛选恢复默认现在只提交一次状态并触发一次加载。
- 修复本地页重入扫描的空过渡快照退出详情或清空选择的问题；扫描期间保留已显示资源和刚修改的收藏、标签、导入状态，最终快照仍会移除已不存在资源。系统返回会优先退出本地选择或详情，详情自身不再因活动筛选而失去返回路径。
- 修复本地详情与批量选择可同时存在、造成同一收藏 / 标签 / 分享 / 删除功能出现两套按钮的问题；进入选择会先返回列表或网格，清空或取消最后一个选择也会恢复此前浏览模式。
- 修复发现页部分筛选仍依赖“应用”按钮、选择后关闭底表或产生待应用状态的交互不一致；现在排序、时间、类型、评级、主题和分辨率均即时归一化、更新状态并刷新结果。
- 修复管理页滑动工作区指示器固定按 `44.dp` 绘制、在 `52.dp` 紧凑导航中高度不匹配的问题；共享控件现在显式接受并统一使用容器高度。
- 修复发现页时间范围在选中后把当前值移动到首位的问题；标准时间选项保持由近到远的固定顺序，勾选图标改由 Material 3 `FilterChip` 原生选中槽管理，不在未选项中保留空槽。
- 修复筛选 Chip 自定义横向展开和父级尺寸动画叠加，导致单选切换过程中两个按钮同时占位并短暂挤压相邻项的问题。
- 扩展诊断脱敏规则，覆盖 `sessionid`、`steamLoginSecure`、`clientsessionid` 和 Cookie 赋值，避免 Community 会话字段写入诊断导出。
- 修复发现页筛选页切换和新结果即将显示时的短暂卡顿：抽屉只订阅稳定筛选配置，在打开动画结束后空闲预组合其余页；本地化映射提升为文件级常量，结果卡片仅在按压、菜单挂载或预览期间录制离屏图层，普通结果绘制不再持续创建预览层内容。
- 修复旧 PKG 转 MPKG shader 兼容逻辑全局改写整数和指数、破坏 `rounded_mask`、`clipping_mask`、`tech_circle`、`auto_sway` 等真实 shader 语义的问题；现在仅保留有明确边界的定向兼容替换。
- 修复桌面 TEX 转换失败后仍把原始纹理写入移动 MPKG、导致官方 Wallpaper Engine 显示黑色图层和黄色缺失纹理的问题；转换现在严格验证 base mip、RGBA/R8/RG88/DXT payload、嵌入图片尺寸、LZ4 解压长度和尾部数据，不兼容纹理会阻止导出并保留下载暂存文件。
- 修复大型场景转换将完整 PKG 和所有转换纹理同时常驻堆而触发 OOM 的问题；移动 TEX 输出直接写入逐纹理文件，非 base mip 不再解压，并对单 TEX 源文件、base mip 和 RGBA 工作集设置与真实样本兼容的上限，恶意尺寸无法申请无界解压缓冲。
- MPKG 和 ZIP 先在同目录完成临时文件后再原子替换目标；SAF 导出使用临时文档和旧文件备份回滚，旧版公共 Downloads 导出也不再直接截断已有文件。失败任务卡片同时显示具体错误消息，便于保留暂存后重试。
- 长按菜单透明入场首帧与退场期间清除辅助功能语义并消费触摸输入，避免不可见操作项仍可聚焦、点击或把手势透传到底层页面；Popup 在完整挂载周期继续保持模态。

#### 验证

- `:core:designsystem:testDebugUnitTest` 与 `:data:steam:testDebugUnitTest` 通过，覆盖首段、中段、末段、紧凑布局、不受已知总页数限制的输入及 `Int.MAX_VALUE` 资料库偏移。
- `testDebugUnitTest lintDebug :app:assembleRelease` 全部通过，共完成 1197 个 Gradle 任务。
- Release APK 已通过 16KB/4 字节 `zipalign` 检查和 `apksigner` v2/v3 验签，SHA-256 为 `D85DC28ACA95FEF56D7811564F399C53AFA28AF4C35EE6DB1EBC766E52C17D2A`。
- 本地签名 Release 已通过 `adb install -r` 原位部署到 `192.168.2.190:41269`；系统包标志不含 `DEBUGGABLE`。ADB 实测在已知最大第 2 页时可输入并加载第 999 页，分页栏正确显示 `1 ... 998 999 1000`，随后已恢复到第 1 页顶部。
- Steam SSR 分页元数据、发现页最大页优先级及宽/中/紧凑分页窗口回归测试通过；ADB 实测首屏底部完整显示 `上一页 1 2 3 ... 1000 下一页`，最大页按钮可加载第 `1000` 页，验证后已恢复第 `1` 页顶部。
- `:data:workshop:testDebugUnitTest`、`:feature:home:testDebugUnitTest` 和全量 `testDebugUnitTest lintDebug :app:assembleRelease` 通过；新增覆盖作者初始查询、中文分页总数、查询总数隔离和动态相邻页。
- 最终 Release 已通过 16KB/4 字节 `zipalign` 与 `apksigner` v2/v3 验签，SHA-256 为 `52434070BDA4D269B39FECAFD06E8983EE13EBCEDC05B9A8DE781B6BBEA7B073`，并通过 `adb install -r` 安装到 `192.168.2.190:41269`（`SM_G9900`，无 `DEBUGGABLE`）。
- ADB 实测 `author:76561198113367551` 显示“返回”和服务端当时的 `329` 个项目；点击“返回”立即恢复原发现页的 `2,582,397` 项结果及首张卡片，未重新搜索。
- 本轮仅执行 `:app:assembleRelease`（未运行自动化测试）；最终 APK 已通过 16KB/4 字节 `zipalign` 和签名校验，SHA-256 为 `3C93798639D2F5D781E3473F1DA4C7D59D66E966252D720973DB6521930D680E`，并通过 `adb install -r` 安装到 `192.168.2.190:41269`（`SM_G9900`）。
- 相邻页布局调整后再次执行 `:app:assembleRelease`（未运行自动化测试）；Release 已通过 16KB/4 字节 `zipalign` 和签名校验，SHA-256 为 `8B89C58A0FDD6B92A85229E81277F241326418D781DD01DAB4442626285A1083`，并已原位安装到 `192.168.2.190:41269`（`SM_G9900`）。
- 当前可安装 Release 同时输出到默认目录 `app/build/outputs/apk/release/app-release.apk`，并通过 16KB/4 字节 `zipalign` 与签名校验；其 SHA-256 与本轮已安装包一致。
- 本轮 `:feature:library:compileDebugKotlin`、`:feature:home:compileDebugKotlin`、`:data:steam:compileDebugKotlin` 和 `:app:compileDebugKotlin` 通过；`:app:assembleRelease`（含 Release lint）通过。签名后的默认 Release APK 已通过 16KB/4 字节 `zipalign` 和 v2/v3 校验，SHA-256 为 `4BFE9C8B93E0C7336F719C97585233909E02258B8796B351E1A2A271EA5C3323`，并通过 `adb install -r` 原位安装到 `192.168.2.190:39519`（`SM_G9900`）。
- 本轮 `:feature:library:compileDebugKotlin :app:compileDebugKotlin` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；默认目录中的 Release APK 已通过 16KB/4 字节 `zipalign` 与 v2 验签，SHA-256 为 `21756C1577B6D26EC60445906D7DCB76E0202F3C32ECEA8C75A14E6C9169289D`，并通过 `adb install -r` 原位安装到 `192.168.2.190:39519`（`SM_G9900`，无 `DEBUGGABLE`）。
- 远程构建任务 `20260724T081553Z-2896518224` 在 `MYCOLORFUL` 完成 `:app:assembleRelease` 与 `lintVitalRelease`，最终 APK SHA-256 为 `45C00DB54CC9BD0AF86C87B3BEDF571C41821E3DA3BB34FB2501C0A8CB9B0ABD`；已通过 `adb install -r` 安装到 `192.168.2.190:39519`（`SM-G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- ADB 截图确认发现页菜单已使用资料库同款窄幅实色 tonal surface；资料库首个候选截图复现并定位了封面与卡片圆角错配，最终产物修复并安装后 Steam 会话停留在恢复状态，因此未完成同一卡片的最终截图复核。
- 远程构建任务 `20260724T082801Z-2027213099` 在 `MYCOLORFUL` 完成 `:app:assembleRelease` 与 `lintVitalRelease`，最终 APK SHA-256 为 `4A0351DC6AAC9B30FC039099C19F8734C2349FAFFA18DA751DBDDC1BF532B92A`；已通过 `adb install -r` 安装到 `192.168.2.190:39519`（`SM-G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`，按要求未启动应用或执行界面实测。
- 远程构建任务 `20260724T091435Z-49431086` 在 `MYCOLORFUL` 重新编译 `data:steam`、`feature:library` 与 `feature:settings`，并通过 `:app:assembleRelease` 和 `lintVitalRelease`；APK SHA-256 为 `A10D503ADE3222E09F524E4C611E8A07C8D21D1BB253D746469ABD936397A1B6`，已通过 `adb install -r` 原位安装到 `192.168.2.190:39519`（Samsung `SM-G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- 安装后执行一次保留账号数据的冷启动，约 12 秒后进入管理 > 资料库，已保存 Steam 会话通过 WebSocket CM 正常恢复并实际加载个人订阅卡片，logcat 未出现 WallHub 崩溃。新增连接策略、恢复展示与 Library ViewModel JVM 回归测试，但当前远程任务仅执行 Release 组装，尚未运行这些单元测试；离线、恢复中断网和意外断线故障注入留待后续专项验证。
- 远程构建任务 `20260724T094125Z-222667174` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`，当前任务 APK SHA-256 为 `CE4949E29AB55D5DB71B5C02B1D77402121484D1571C7C18F46A2790D9B2295D`；已通过 `adb install -r` 原位安装到 `192.168.2.190:39519`，设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- 设置页已在设备端完成紧凑宽度深色静态色板、深色系统动态取色、浅色系统动态取色、完整滚动区域及 640dp 中等宽度检查；文字、尾部值、开关与底部导航无重叠，logcat 未出现 WallHub 崩溃。测试后已恢复设备原有的 345 dpi、深色主题和关闭系统动态取色设置。
- 远程构建任务 `20260724T100224Z-1099511878` 在 `MYCOLORFUL` 重新编译 `core:designsystem` 与 `feature:settings`，并通过 `lintVitalRelease` 和 `:app:assembleRelease`；APK SHA-256 为 `FA9B76E81E988227722DF02327768614481832B1DB53C4526B1622EDCE4E763E`，已通过 `adb install -r` 原位安装到 `192.168.2.190:39519`，设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- 设备端逐页检查基本设置、下载、已登录 Steam 和实验功能的紧凑布局，并在实验功能页验证系统动态取色：风险提示正确使用第三色容器，tonal action、列表容器、开关和底部导航同步取色，所有文本与控件无重叠，logcat 未出现 WallHub 崩溃。API Key 节点确认为密码字段，显隐按钮说明可在“显示/隐藏 API Key”间切换；未触发导出、目录选择、退出登录、通知申请或设置保存，动态取色已恢复为测试前的关闭状态。
- 最终远程构建任务 `20260724T103253Z-90997430` 在 `MYCOLORFUL` 通过 `:app:compileReleaseKotlin`、`lintVitalRelease` 和 `:app:assembleRelease`；APK SHA-256 为 `E42306685868B6D08ADD033117781BF0D8A3FD6F00963A3EB7EBA8AA4CAE0C2C`，已通过 `adb install -r` 原位安装到 `192.168.2.190:39519`，设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- 管理页在紧凑宽度完成下载/资料库双向切换、快捷状态与分类切换、资料库类型筛选、筛选计数、恢复默认、搜索区、长按菜单背景和莫奈深色验证；在 640dp 中等宽度确认固定控制区、三列资料库网格和底部导航无重叠，测试后恢复 345 dpi 与关闭动态取色。logcat 未出现 WallHub 崩溃。
- 最终冷启动有一次资料库在 Steam RPC 发起后长时间保持加载，切换到设置页再返回后成功恢复并加载 16 个项目；相同管理 UI 的中间构建曾直接成功加载，因此未将其归类为本轮界面回归，但仍作为 Steam 会话/RPC 的残余风险保留，后续需要独立故障注入与线程栈验证。
- 本轮实现前源码回滚快照为 `archive/wallhub-source-20260724T115524Z-pre-local-management-redesign.tar.gz`，SHA-256 为 `F1E306DD7D1B4E5992E8405DB62A75A4028646344E4238BB43F753641568C9EE`。
- 远程 Release 任务 `20260724T131037Z-255618365` 成功完成构建；当前任务 APK SHA-256 为 `CFDE6D37F896CBB5FC68AE33B05F3634AEB838FEE71D3539DCF5B209571B673F`，已用 `adb install -r` 安装到 `192.168.2.190:39519`，设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- Samsung `SM_G9900` 真机确认 Manage 三段导航、资料库搜索结果、滚动收束后的图标筛选入口与底表、本地目录扫描到 4 个资源、MPKG/HTML 分类、列表/网格/详情视图、详情滚动和批量选择操作栏；未执行删除或外部导入等破坏性操作。
- 本轮新增 JVM 测试文件尚未在 LXC 内运行；仓库要求 Gradle 使用远程构建机，本轮远程工作流仅执行 Release 组装，后续应在允许的独立测试执行环境运行 `:feature:local:testDebugUnitTest` 等测试。
- 本轮修改前快照为 `archive/wallhub-source-20260724T133457Z-pre-local-ui-network-cover.tar.gz`，SHA-256 为 `2B3988A994023012AFBE96034114A9EE5ABA80D72383FB72590C30DAAB75D00E`。
- 远程 Release 任务 `20260724T134737Z-3236919916` 在 `MYCOLORFUL` 完成 `:app:compileReleaseKotlin` 与 `:app:assembleRelease`，APK SHA-256 为 `FEDBA7FDA3E1BE8CACBCF83B89EE60C6FE76EDEC5AA18A4B0FD730CB1C27DF95`；已通过 `adb install -r` 原位安装到 `192.168.2.190:39519`（Samsung `SM_G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- ADB 真机确认本地页 3 个现存资源均显示 Steam 网络封面，列表、详情直达、详情操作栏和完整筛选底表无重叠；资料库越过阈值后稳定收束，小幅反向滚动保持收束，回到绝对顶部后正常展开。未执行删除或 Wallpaper Engine 导入。
- 新增 `SteamWorkshopThumbnailCacheTest` 覆盖有效 HTTPS 封面解析和缺失详情响应；远程 Release 工作流不执行 JVM 测试，因此该测试及既有 `:feature:local:testDebugUnitTest` 仍待获准的 Gradle 测试环境运行。
- 本轮动效重构前快照为 `archive/wallhub-source-20260724T141219Z-pre-local-motion-redesign.tar.gz`，SHA-256 为 `75C452CBC254AC50A3EF08BE0A6CCA67EB8DD4C33C6067441A1F397F9B7EA2A9`。
- 远程 Release 任务 `20260724T142609Z-8734106` 在 `MYCOLORFUL` 通过 `:app:compileReleaseKotlin`、`lintVitalRelease` 和 `:app:assembleRelease`；APK SHA-256 为 `E3FFAD983B3946A05D633E6536DE6EBCEFD931F96950E6FC53CB119A0EAD9D54`，已通过 `adb install -r` 原位安装到 `192.168.2.190:39519`（Samsung `SM_G9900`）。
- ADB 真机连续验证资料库上推收束和反向下拉展开，展开后未再出现状态反复或布局抖动；本地页验证展开/收束、列表/网格切换、更多菜单、详情直达及返回原浏览模式，所有封面、文字和底部导航均无重叠。
- 测试后已将本地浏览偏好恢复为列表，并返回发现页；未触发目录授权、文件删除、标签修改或 Wallpaper Engine 导入。
- 针对仍可见的顶部抖动建立 ADB `screenrecord` + 30fps 逐帧自动判定：修复前资料库收束存在 `10–11px` 反向位移，本地搜索区和首卡最高存在 `63px` 反向位移；最终版本资料库搜索区、本地搜索区和本地首卡在长/短收束与展开四个窗口内均为 `0px` 反向位移，全部自动断言通过。
- 根因对照实验确认动态 `Scaffold.topBar` 是资料库抖动主因；仅固定该顶栏后资料库立即全绿，而本地仍失败。进一步取消本地父子两层同步高度过渡后本地全绿；最终将标题移入普通 Column 后仍保留标题收束和完整视口增益，资料库与本地继续全绿。
- 每页执行 12 轮、共 24 次快速反向手势压力测试：资料库 `1609` 帧，FrameTimeline jank `1.12%`、P95 `5ms`、P99 `11ms`；本地 `1574` 帧，jank `0.64%`、P95 `5ms`、P99 `8ms`，两页均无慢位图上传、崩溃、ANR、OOM、跳帧或输入超时日志。
- 内存采样无 swap；本地压力测试 PSS 从 `131.4MB` 到 `139.0MB`，资料库封面加载后第二轮同量手势 PSS 从 `149.6MB` 回落到 `144.9MB`，未表现出随手势次数单调增长的泄漏。Samsung 图形 HAL 仅记录亚毫秒级未来 vsync 时间戳警告，与应用布局无关。
- 最终远程 Release 任务 `20260724T152413Z-2768222856` 通过 `:app:compileReleaseKotlin`、`lintVitalRelease` 和 `:app:assembleRelease`；APK SHA-256 为 `C5800C3C9F4D3AB3E8B48F964DEC60E4E338A1F650D2FBFC0CD6870053F374F4`，已通过 `adb install -r` 原位安装到 `192.168.2.190:39519`，设备端确认为 `0.8.24 (34)`。
- 本轮管理 Pager 与导入修复前快照为 `archive/wallhub-source-20260724T161946Z-pre-management-pager-import-fix.tar.gz`，SHA-256 为 `3AD254B54131ACCF8C65B8C1A9278B2E9AF27817D0D076D69786C3C6E850CC9B`。
- 官方 Wallpaper Engine `2.8.8 (4354)` APK manifest 和 `BrowseActivity.onCreate()` 已完成反编译核对；自动契约检查确认包名、导出 Activity、`ACTION_VIEW`、`application/vnd.mpkg`、ClipData、URI grant 和 compiled manifest package query 全部匹配。
- ADB 图像断言依次验证下载、资料库、本地三页左右滑动，并验证本地末端继续外滑进入设置、下载首端继续外滑进入发现；六个预期选中位置全部通过。边界手势改为不消费事件的 pointer observer，避免 Pager overscroll 截断 nested-scroll 增量。
- 真机使用现有 `153.4 MB` MPKG 完成实际导入：ActivityTaskManager 启动官方 `BrowseActivity`，MediaProvider 确认 Wallpaper Engine UID 成功读取 `/Download/WallHub/...mpkg`，随后官方 `PreviewActivity` 自动打开并正确渲染交互壁纸；未出现安装器、权限、文件或运行时错误，也未点击确认去改变系统当前壁纸。
- 最终 30fps 位移回归确认资料库搜索区和本地搜索区/首卡在长短收束、展开八个窗口内最大反向位移均为 `0px`；本地详情截图确认资源摘要整行隐藏、动作组无重叠，列表截图测得缩略图到卡片左、上、下边均约 `16px` 物理像素，对应设备上的 `8.dp`。
- 最终远程 Release 任务 `20260724T172313Z-2847330466` 通过 `:feature:local:compileReleaseKotlin`、`:app:compileReleaseKotlin`、`lintVitalRelease` 和 `:app:assembleRelease`；APK SHA-256 为 `962B74B6DDFA1C09FCFD8BF2706B0663E9C1ED499DA77067D12AF8C1D1261734`，通过 16KB/4 字节 zipalign 与 v2 验签，并已用 `adb install -r` 安装到 `192.168.2.190:35777`（Samsung `SM_G9900`，`0.8.24 (34)`）。
- 新增管理边界手势 JVM 测试覆盖首尾方向、阈值、单次派发、重置、垂直和向内拖动；远程 Release 工作流不执行 JVM 测试，因此仍需在获准的测试 Gradle 环境运行。
- 发现页筛选重构前快照为 `archive/wallhub-source-20260725T010018Z-pre-discover-filter-redesign.tar.gz`，SHA-256 为 `B08BBF38D10B05FDC4B7E393FF934B12F3EF25F3B3C7AB5C38A721C5D7B32649`，归档目录清单和校验均通过。
- Samsung `SM_G9900` 真机验证顶部浏览条件栏、四个筛选分区、标签与横向 Pager 同步、分区内滚动、固定底部操作栏、直接定位对应分区、排序依赖的时间禁用态、取消回滚、应用提交和恢复默认；应用“抽象”后结果从约 `2,583,409` 收敛到约 `65,767`，恢复默认后回到原结果数。
- 紧凑 `345 dpi` 与约 `640dp` 的原生 `270 dpi` 中等宽度均完成截图检查，文字、单选行、Chip、标签和底部按钮无截断或重叠；浅色静态色板、深色静态色板和浅色系统动态取色均保持正确语义色配对，测试后恢复用户原有的 `345 dpi`、浅色主题和关闭动态取色。
- 新增 `HomeFilterSelectionTest` 覆盖默认活动数、按分区计数、空的有限选择归一化、非法值清理及草稿差异计数；当前远程 Release 工作流不执行 JVM 测试，因此该测试尚待获准的 Gradle 测试环境运行。
- 最终远程 Release 任务 `20260725T012510Z-2785225793` 通过 `:feature:home:compileReleaseKotlin`、`:app:compileReleaseKotlin`、`lintVitalRelease` 和 `:app:assembleRelease`；APK SHA-256 为 `1D61A529F91303C7A1E8EBB64287C013055F2FEC84D5053161601F51B05FEDD8`，通过 16KB/4 字节 zipalign 与 v2 验签，并已用 `adb install -r` 原位安装到 `192.168.2.190:41859`，设备端确认为 `0.8.24 (34)`。最终冷启动和 logcat 未发现崩溃、ANR、OOM 或 Compose 状态异常。
- 本轮管理页重构前快照为 `local-snapshots/wallhub-android-source-before-management-redesign-20260725-054225.tar.gz`，配套 SHA-256 文件已再次校验通过；源码差异通过 `git diff --no-index --check` 静态检查。
- 最终远程 Release 任务 `20260725T085738Z-200723305` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；当前任务 APK 位于 `/root/builds/wallhub-release-20260725T085738Z-200723305.apk`，大小 `30,184,207` 字节，SHA-256 为 `3018CE77FC1A5DA7E8FB0BEF6425806493B90FFCD443EC844F04D941B21433E8`。APK 已通过 `adb install -r` 原位安装到 `192.168.2.190:39075`（Samsung `SM_G9900`），设备端 `pm path` 和 `dumpsys package` 确认 `com.wallhub.android` 为 `0.8.24 (34)`；本轮远程工作流未执行 JVM 或仪器测试。
- 本轮紧凑优化前源码快照为 `local-snapshots/wallhub-android-source-before-management-compact-optimization-20260725-100600.tar.gz`，SHA-256 为 `2673A88075D37E53A686112AC061F1E7C7519A20589C041B3AA5C79091C16B16`；配套校验文件已保存。
- 最终远程 Release 任务 `20260725T104603Z-295721754` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；APK 位于 `/root/builds/wallhub-release-20260725T104603Z-295721754.apk`，大小 `30,167,823` 字节，SHA-256 为 `30D189FEE2FE251438865FFB7866224A8D44FA08B0B99E684A5CB4E9C7FF20FB`。已通过 `adb install -r` 原位安装到 `192.168.2.190:39075`（Samsung `SM_G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- Samsung 真机确认管理页紧凑工作区栏、下载空态、资料库展开与收束、本地展开与整体收束、本地详情完整封面适配和灰白 tonal surface；收束后壁纸内容直接位于固定工作区栏下方，无重复筛选入口、残留工具行或可见反向跳位。快速往返收束、展开和工作区切换共采集 `1032` 帧，FrameTimeline jank `0.97%`、P95 `5ms`、P99 `15ms`，错误级 logcat 为空；未触发删除、导入、目录授权或数据修改操作。
- 本轮修改前快照为 `local-snapshots/wallhub-android-source-before-filter-interaction-feedback-20260725-120919.tar.gz`，SHA-256 为 `EA2E65528912C1E208E0E445D0D9C15A23D46921CE14BFC242ACA411A4057CBB`。
- 最终远程 Release 任务 `20260725T123424Z-189611991` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；APK 位于 `/root/builds/wallhub-release-20260725T123424Z-189611991.apk`，SHA-256 为 `D74E422C11B43E8E497928F8C563F1B596378A7D4E2DC9B283D0CC900F441CF6`。已通过 `adb install -r` 原位安装到 `192.168.2.190:39075`（Samsung `SM-G9900`），设备端 `pm path` 与 `dumpsys package` 确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- ADB 真机确认发现页选择“最新”后底表保持打开、选中态与活动徽标立即更新且无应用按钮；管理工作区滑动 tonal 指示器完整覆盖指定高度，本地长按选择栏正确替换搜索工具并仅直接展示分享、删除和更多；管理筛选显示关闭、默认状态和恢复默认标题区，无确认底栏或描边。冷启动及上述交互未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM；本轮远程工作流未执行 JVM 或仪器测试。
- 本轮修改前快照为 `local-snapshots/wallhub-android-source-before-shape-filter-performance-20260725-204500.tar.gz`，SHA-256 为 `E7DFABE9B9789D506A2466CD5D516CFC07586BF3995574E8FE91E15A60B4B013`。
- 最终远程 Release 任务 `20260725T140922Z-2563221884` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；APK 位于 `/root/builds/wallhub-release-20260725T140922Z-2563221884.apk`，SHA-256 为 `4888CF6C39B3854182C4C9AF6B72DC91D02F9C389030A505B530A26CE352E3EF`。已通过 `adb install -r` 原位安装到 `192.168.2.190:39075`（Samsung `SM-G9900`），设备端 `pm path` 与 `dumpsys package` 确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- Samsung 真机确认筛选页签四个未选中按钮在深色背景上保持可见且低强调，选中态无描边或阴影；时间范围从 `30 天` 切换到 `7 天` 后仍保持固定第三位，其余选项没有移动。发现卡片长按预览和操作菜单在按需图层录制后正常，管理工作区导航圆角和右下角筛选 FAB 正确显示，FAB 可打开当前工作区筛选。
- 相同 6 轮“浏览→主题→屏幕→浏览”自动往返脚本在旧版记录 `2035` 帧、FrameTimeline jank `0.34%`、P99 `6ms`、7 个卡顿帧；最终 APK 记录 `2247` 帧、jank `0.09%`、P95/P99 `5ms`、2 个卡顿帧且 missed vsync 为 0。最终冷启动及筛选、长按、管理 FAB 回归未发现 WallHub `FATAL EXCEPTION`、ANR、OOM；远程工作流未执行 JVM 或仪器测试。
- 本轮修改前快照为 `local-snapshots/wallhub-android-source-before-filter-choice-pagination-20260725-222000.tar.gz`，SHA-256 为 `D2BCF32E298C21A534EA3D8F0F48E44805F4311EEADE4B542E82A18C24B4E657`。
- 最终远程 Release 任务 `20260725T151042Z-907126699` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；APK 位于 `/root/builds/wallhub-release-20260725T151042Z-907126699.apk`，SHA-256 为 `B4ECE10CA0BF6C76A16E039F4ED20501D9DF2B302BEC9AA6DEA00EA975000375`。已通过 `adb install -r` 原位安装到 `192.168.2.190:39075`（Samsung `SM_G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- Samsung 真机确认时间范围严格按由近到远排列；发现页和管理页的选中 Chip 仅在选中态显示勾选槽，主题内容分类与屏幕分辨率的“反选”均按“不限”状态正确禁用和启用；管理顶部选择器点击后无额外波纹残留。分页首屏去重为 `1 / 1000`，点击当前页输入 `50` 后成功加载并显示 `1 / 50 / 1000`，无上一页、下一页、邻页或省略号。
- 新增分页最小/当前/最大去重测试及有限集合反选测试；当前远程工作流仅执行 Release 组装，未运行 JVM 或仪器测试。冷启动和上述交互未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。
- 本轮修改前快照为 `local-snapshots/wallhub-android-source-before-native-chip-compact-sheets-comments-20260725-232500.tar.gz`，SHA-256 为 `4C54D3159336BD7624CE478DCF4BBACEACB19D8D4CD4DD28730966EFF2F9D6AF`。
- 最终远程 Release 任务 `20260725T161844Z-2918410387` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；APK 位于 `/root/builds/wallhub-release-20260725T161844Z-2918410387.apk`，SHA-256 为 `C80E221B446D75E9BE6228F561BF9CCB38AFB757B8BE4073A46AE0D493F75226`。已通过 `adb install -r` 原位安装到 `192.168.2.190:39075`（Samsung `SM_G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- Samsung 真机确认下载状态五个 Chip 保持单行，连续切换不再出现自定义尺寸动画的双重占位；下载筛选只保留两张卡所需高度，资料库筛选只保留三张卡所需高度，底部无强制撑满的空白滚动区。已登录详情评论页显示一个紧凑多行输入框和内嵌发送按钮，草稿输入后发送态正确启用；为避免产生公开 Steam 内容，未点击发送，测试草稿已通过进程重启清除。
- 新增 Steam 评论 ID、空白和长度归一化测试，并扩展 Community 会话字段脱敏测试；当前远程流程只执行 Release 组装，未运行 JVM 或仪器测试。最终冷启动与上述交互未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM，logcat 中仅有设备转译层和图形驱动既有日志。
- 本轮评论、资料库、抽屉动效和设置 UI 修改前快照为 `local-snapshots/wallhub-android-source-before-comment-library-sheets-settings-motion-20260725.tar.gz`，SHA-256 为 `DD1F2DC7F9661969DAA96B5DFAF86BA4AD3A734C5FEC25088FE5C49200676222`。
- Wallpaper 评论页在列表拖动、点击输入框外和切换详情标签时强制释放输入焦点；评论输入框与评论卡统一使用 `MaterialTheme.shapes.medium`。评论列表增加 Material 3 下拉刷新，父级折叠头部在向下拖动时优先展开；右下角返回顶部同时归零评论列表并展开完整 Wallpaper 封面，而不再停在首条评论位置。评论刷新可取消正在进行的分页，避免两类请求并发覆盖。
- 管理 Pager 移除为筛选 FAB 预留的全宽 `72.dp` 底部带，改由下载、资料库和本地列表自身保留末项操作空间；本地嵌套 Scaffold 停止重复消费系统 Insets，根 Scaffold 明确消费传给 NavHost 的 Insets。资料库与本地搜索框统一使用 `surfaceContainerLow`，资料库底部内容和本地详情均可正常延伸到导航栏上方且不被 FAB 遮挡。
- 发现筛选底表移除固定 `86%/94%` 高度、加权 Pager 和预组合页面，改为仅由当前标签内容测量高度，并在自适应最大高度内滚动；管理、设置和下载选择底表同样保持短内容收拢、长内容受限滚动。筛选 Chip、发现筛选标签和设置选择项使用固定图标槽中的淡入缩放勾选动效，不引入标签宽度重排。
- 发现与资料库长按菜单在 Popup 先挂载后下一帧执行淡入、缩放和触点方向位移动效，并在退场完成后才释放 Popup、背景遮罩和管理导航状态，避免菜单与 FAB 突然跳显。设置一级页移除分割线，每个分类使用独立大圆角 tonal 卡片；二级页顶部精简为返回、分类图标和分类名称；设置、Steam 登录和 Guard 验证码输入统一为无描边 Filled 大圆角字段，Guard 验证码与密码均不进入 Android Saved State。
- 最终远程 Release 任务 `20260725T184254Z-2397024665` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；APK 位于 `/root/builds/wallhub-release-20260725T184254Z-2397024665.apk`，SHA-256 为 `7973033F7F6100218D4E1EC2F478EA209DB2ED60A3E22ADC274B38161D4D5498`。已通过 `adb install -r` 原位安装到 `192.168.2.190:39075`（Samsung `SM_G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`。
- Samsung 真机确认资料库底部黑色遮挡带消失、资料库与本地搜索配色一致、设置一级独立卡片和二级精简标题/无描边输入框正确、设置选择与发现筛选底表按内容高度展示。评论页确认输入后点击评论卡或滚动会关闭键盘，返回顶部恢复完整壁纸，顶部继续下拉可进入评论刷新手势；为避免产生公开 Steam 内容，未点击发送，测试草稿已通过进程重启清除。当前远程流程只执行 Release 组装，未运行 JVM 或仪器测试。
- 本轮修改前源码快照为 `local-snapshots/wallhub-android-source-before-management-tabs-fade-mpkg-textures-20260726.tar.gz`，SHA-256 为 `3CAC42E9665501C1C76EEA8BED7435EC390107E8068EECAA592F149B75F06991`。
- 对 Workshop `3742497499`（“麻匪 白泽夢”）的真实 `PKGV0024` 场景完成独立诊断：`300` 个条目、`86` 个 TEX、`38` 个 shader；原始 TEX 的 mip、payload、尺寸和尾部数据均完整，旧全局数字改写会破坏 `6` 个真实 shader。修复后的 `216707504` 字节 MPKG 为 `PKGM0020`、`302` 个条目、`86` 个 RGBA8888 + `TEXB0004` 纹理，独立检查全部通过，SHA-256 为 `DDBA460F1FCCC10C7B0F0344772C2F712E4BEAB2B669FE159A48100B3F9DE8F6`。
- 修复后的真实 MPKG 已通过 `content://` URI、`application/vnd.mpkg` 和读授权导入官方 Wallpaper Engine `2.8.8 (4354)`；`PreviewActivity` 中人物、服装、头发、背景和特效完整显示，原黑块及黄色缺失纹理全部消失。图形转译层仍记录部分 shader 编译警告，但当前画面无缺层、占位、崩溃或 OOM。
- 新增 shader 整数语义、严格移动 TEX、拒绝不兼容纹理及 MPKG 失败写入保护测试源码；远程 Release 工作流仅执行 `:app:assembleRelease`，本轮新增 JVM 测试尚未实际运行。`git diff --check` 通过。
- 最终远程 Release 任务 `20260726T091928Z-1936324955` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；APK 位于 `/root/builds/wallhub-release-20260726T091928Z-1936324955.apk`，大小 `30,184,207` 字节，包含 `classes.dex` 至 `classes8.dex`，SHA-256 为 `86FCE1DBD64970E641795F2E22764CD73A1CFE9D0511A345880D36FF183484B0`。已通过 `adb install -r` 原位安装到 `192.168.2.190:40283`（`SM-G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`；冷启动后进程保持存活，logcat 未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。
- 构建期间远程工作区曾在失败的 Hilt/ASM 增量任务后返回缺失 app class 的 APK；该异常产物分别在冷启动时暴露 `AppModule`、`CrashDiagnostics` 或 `WallHubApplication` 缺失，均未作为最终产物保留。设备已先恢复上一已知良好 Release，随后通过完整 app Kotlin 输入失效修复工作区并移除临时编译触发项；最终接受的 APK 已再次安装和冷启动验证。
- 本轮筛选、菜单与分页修改前源码快照为 `local-snapshots/wallhub-android-source-before-filter-swipe-chips-menus-pagination-20260726.tar.gz`，SHA-256 为 `4361B68FA13AAD801C0734F8DB976E9F3F5318496DCF7F39437D8EA8159EAD00`。
- 发现页筛选工作区恢复为 Foundation `HorizontalPager`：顶部 Material 3 标签点击与左右手势实时同步；内容区删除重复板块大标题，紧凑标签关闭额外方形波纹。筛选 Chip 未选中时不再预留勾选空间，选中勾选淡入缩放并通过内容尺寸动画让出位置，取消选中时先退场勾选再收回宽度。
- 管理页下载、资料库和本地选择器直接读取 Pager 的当前页与偏移量，拖动时滑块实时跟手；点击切换使用无回弹 spring，并在标题说明与选择器之间增加顶部间距。详情下载选项移除“仅转换 MPKG 文件”及其未下载提示。
- 发现页和资料库长按菜单移除可见“查看详情”操作；资料库新增正式下载、视频一击直达在线播放器和打开 Steam，普通卡片点击仍进入详情。资料库下载继续复用当前导出目录、下载任务仓库、自动格式及旧版存储权限链路。
- 共享分页控件改为固定显示首页、当前、末页和跳页四个角色：边界页不再与当前页去重，首页与末页一击直达，当前与跳页均打开正整数页码输入框；保留方向切换和按压弹簧动效。
- Samsung `SM_G9900` 真机确认筛选手势从“浏览”同步切换到“内容”，内容区无重复标题；选中“视频”时 Chip 从 `128 px` 扩展为 `167 px`，未选项无固定前导空槽。管理三段导航在 `1080 px` 宽度下等分完整且选中状态与 Pager 同步；资料库视频菜单顺序为“下载 / 视频播放 / 打开 Steam”，无“查看详情”，点击视频播放直接进入带播放器控件的在线全屏页。
- 真机把发现页临时切换为 Web 页码模式后确认首页、当前、末页和跳页四个触控区完整且互不重叠；当前为第 `1` 页时首页角色仍固定显示，验证后已恢复原“瀑布流拼接”偏好。Steam 会话在安装后首次恢复曾进入可重试断线态，点击重试后成功重新加载资料库；应用全程无崩溃。
- 最终远程 Release 任务 `20260726T112833Z-849010322` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；APK 位于 `/root/builds/wallhub-release-20260726T112833Z-849010322.apk`，大小 `30,200,591` 字节，包含 `classes.dex` 至 `classes8.dex`，SHA-256 为 `0E8EDDFEC09792036F8F0ED88F8012124E89BBC16736BD93A10085DE6E7EE192`。已通过 `adb install -r` 原位安装到 `192.168.2.190:40283`（Samsung `SM_G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`；最终冷启动后 PID `18320` 保持存活，PID 定向日志未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。本轮远程流程仅执行 Release 组装，未运行 JVM 或仪器测试。
- 工程安全网与构建脚本清理后的远程 Release 任务 `20260726T154027Z-375117234` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`；当前任务 APK 位于 `/root/builds/wallhub-release-20260726T154027Z-375117234.apk`，大小 `30,200,591` 字节，包含 `classes.dex` 至 `classes8.dex`，ZIP 完整性检查无错误，SHA-256 为 `3214353A05F1332852285D79B02D71C404DF9437D7870435CFE5405EFC8C60F8`。已通过 `adb install -r` 原位安装到 `192.168.2.190:40283`（Samsung `SM_G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`；冷启动后 PID `22966` 保持存活，PID 定向日志未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。本轮远程流程仍仅执行 Release 组装，未运行 JVM 或仪器测试。
- GitHub Actions 到 ADB 流程迁移后的 LAN 回退构建任务 `20260726T161036Z-991119252` 在 `MYCOLORFUL` 完成 `:app:assembleRelease`，验证本地未注入 `WALLHUB_RELEASE_*` 时仍使用既有 debug 签名 fallback。当前任务 APK 位于 `/root/builds/wallhub-release-20260726T161036Z-991119252.apk`，大小 `30,200,591` 字节，包含 `classes.dex` 至 `classes8.dex`，ZIP 完整性检查无错误，SHA-256 为 `3214353A05F1332852285D79B02D71C404DF9437D7870435CFE5405EFC8C60F8`，签名证书 SHA-256 为 `940402C12B4270F1000C61882A42EC610292AB776F28F85784D6954EA7DB074D`。已通过 `adb install -r` 原位安装到 `192.168.2.190:40283`（Samsung `SM_G9900`），设备端确认 `com.wallhub.android` 为 `0.8.24 (34)`；冷启动后 PID `23835` 保持存活，PID 定向日志未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。启动期间记录 `49` 帧 Choreographer skipped frame，未与本轮运行时改动关联，后续如持续出现应独立进行启动性能诊断。
- GitHub Action run `30213530872` 生成 artifact `wallhub-release-97817431aabca35fa456d1e168c38224f94bfc1f`（artifact ID `8635235735`）；其中 APK 大小 `30,184,207` 字节，SHA-256 为 `8A6390533A2F7564719E0E61B074C04C4429B4257D6E1523E30809BCE46D7D08`，包含 `classes.dex` 至 `classes8.dex`，ZIP 完整性和签名证书 `940402C12B4270F1000C61882A42EC610292AB776F28F85784D6954EA7DB074D` 均通过。LXC 脚本在安装前实时选择唯一 `device` 状态目标 `192.168.2.190:40283`，`adb install -r` 成功，设备端确认为 `0.8.24 (34)`；冷启动 PID `26012` 保持存活，PID 定向日志未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。
- GitHub Action run `30214074910` 生成 artifact `wallhub-release-9b1b0b6c8c32c32a091460e08fe7b663da46ac39`（artifact ID `8635348246`）；其中 APK 大小 `30,184,207` 字节，SHA-256 为 `4F380EE17E6F2D0513BDFE0C5445E5EB29610DA2867E3E07E8BAD29427D81E4F`，包含 `classes.dex` 至 `classes8.dex`，ZIP 完整性和签名证书连续性均通过。日常脚本原位安装后确认设备端版本为 `0.8.24 (34)`；冷启动 PID `27171` 保持存活，PID 定向日志未发现 WallHub `FATAL EXCEPTION`、ANR 或 OOM。

### 0.8.20 (30) — 2026-07-20

#### 更新

- 发现页长按菜单改为以实际触点定位：默认显示在按下位置右下角；右下方空间不足时才翻转到右上方。
- 高级筛选抽屉的内容切换按顶部标签的实际左右关系滑入/滑出；右上角计数改为“已修改 xx”，按相对默认筛选条件的差异计算。
- 首页布局切换增加快速重复操作的平滑排队过渡；四列及以上时卡片仅保留订阅数据，隐藏收藏与已隐藏的文件大小信息。
- 下载页筛选容器改用资料库同款分段样式；资料库移除顶部横向标签条，卡片封面、类型标记、固定两行标题和统计信息与发现页统一。
- 设置中的“运行与诊断”改名为“基本设置”；诊断导出保留，NSFW 模式移至该页底部，外观页不再显示限制级内容开关。
- 新增 SteamKit 在线分块播放：视频详情页可直接进入原生 Media3 播放器，播放器按需读取 JavaSteam Depot chunk，并缓存已经读取的分块。
- 为顶级页面和详情、播放器、Steam 登录等二级页面统一导航动效：顶级切换采用短促淡入与轻微缩放，二级页面采用具有严格反向返回路径的水平空间转场；宽屏布局复用稳定的 `NavHost`，避免页面切换时重建跳切。
- 设置分类改为标题栏和内容同步过渡的整页转场，并为设置索引与各分类保留独立滚动位置。
- 高级筛选抽屉固定为半屏高度，顶部分类标签支持横向滑动和两侧遮罩提示，内容页继续支持手势左右翻页与标签点击切换；应用、清除和取消操作保持固定可见。
- 发现页分页控件与 Web 版对齐：保留首尾页、邻近页、省略号和前后翻页，并支持点击当前页直接输入页码；使用 Material 3 图标。
- 默认双列卡片提高统计与文件大小信息密度，并固定标题预留两行空间，使信息始终显示在标题区之后。
- 依照 Google Compose Material 3 主题规范重构 Monet：Android 12 及以上的系统 Monet 直接使用 `dynamicLightColorScheme` 或 `dynamicDarkColorScheme`，不再对背景或表面做二次混合；旧系统与手动强调色继续使用 Material 色彩工具生成的静态标准色彩角色。
- 取消后续针对 Monet 背景、卡片和筛选层级加入的 Alpha 混色覆盖，重新直接使用官方动态或静态 Material 色彩角色。
- 保持原生 Compose 架构，以 MDUI 2 的 Material You 组件语义重整共享视觉：紧凑圆角、低层卡片表面、圆角列表分组和清晰的状态色；未引入不兼容的 Web Components 依赖。
- 外观设置新增独立的系统 Moment 开关，开启时直接使用系统动态配色，关闭或选择静态色板时使用完整的 Material 色彩角色；状态会持久化。
- 浅色主题使用三层 Monet 表面：页面背景为明显但低饱和的动态淡色，普通卡片和设置选项使用更浅的动态色，搜索框、导航和浮动控件保持纯白；首页筛选面板的外层与 Wallpaper 卡片使用同一动态卡片色，内层控件使用最低层白色表面，开关、选中态与操作按钮继续使用 Monet 状态色，深色主题保持原有 Material 3 表面层级。
- 长按卡片改用单层背景毛玻璃与清晰卡片重放，按压和菜单材质动效放缓，移除菜单本体的投影阴影分割。
- 发现页长按菜单在 Android 12 及以上启用 Popup 窗口局部高斯背景模糊，并监听系统跨窗口模糊开关；模糊可用时降低菜单材质不透明度以显露磨砂层，系统关闭模糊或旧版 Android 继续使用高不透明背景。

#### 修复

- 删除年龄评级抽屉中关于限制成人内容设置的冗余提示文本。
- 修复发现页切换网格与列表排布的起始帧中，卡片主操作按钮图标和文字短暂横向拉伸的问题；内容反缩放现在与按钮外层投影在同一绘制帧读取状态。
- 底部导航栏顶部边缘阴影由 `3.dp` 略微加深至 `4.dp`。
- 修复发现页网格与列表切换时封面左上类型标签叠加非等比投影而被拉伸的问题；标签现在跟随封面平移并保持比例缩放。
- 修复列表模式下载等主操作按钮在布局切换过程中图标被文字裁剪容器一并裁掉的问题。
- 优化发现页网格与列表切换动效：保留可中断的卡片布局投影，将主转场放缓至 400ms 强缓出节奏，类型标签使用 260ms 过渡并提前稳定。
- 操作文字改为局部绘制裁剪与透明度揭示，图标使用图层平移保持居中，避免转场期间逐帧重新测量按钮内容宽度。
- 修复发现页快速反复切换排布时卡片外壳与封面、标签、内容、操作区不同步的问题：五层投影现在以同一事务和共享进度原子提交，并从当前呈现状态连续重定向。

#### 验证

- `testDebugUnitTest` 全模块通过。
- `:app:assembleDebug` 通过，生成 `0.8.20 (30)` Debug APK。
- `:app:assembleRelease` 通过，生成 `0.8.20 (30)` Release 构建；APK 已完成 `zipalign` 和 `apksigner` v2/v3 验签。
- Release 构建已使用与目标设备现有包一致的 Android Debug 证书原地安装验证，保留已有应用数据；公开发布前仍需配置独立的生产签名证书。
- 修复后的 Release APK 已通过 v2/v3 验签并原地安装到 ADB 设备 `192.168.2.127:33445`。
- 动效优化后重新执行 `:feature:home:compileReleaseKotlin` 和 `:app:assembleRelease`，并通过 v2/v3 验签原地安装到同一 ADB 设备。
- 统一导航动效后重新执行 `:app:compileReleaseKotlin` 和 `:app:assembleRelease`；Release APK 已完成 `zipalign` 检查及 `apksigner` v2/v3 验签，本轮未安装到设备。
- `:feature:home:testDebugUnitTest`、`:core:designsystem:testDebugUnitTest`、`:feature:home:compileDebugKotlin` 和 `:app:compileDebugKotlin` 通过；Home 投影事务测试 13 项和 Material 3 主题测试 2 项均通过。
- 官方 Material 3 配色重构后，`:core:designsystem:testDebugUnitTest` 与 `:app:compileDebugKotlin` 通过；`:app:assembleRelease`（含 `lintVitalRelease`）通过，APK 已完成 `zipalign` 与 v2/v3 验签并原地安装到 ADB 设备 `192.168.2.190:41713`。
- Moment 设置改造后，`:core:designsystem:test` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签，并已原地安装到 `192.168.2.190:41713`。
- 白色表面与 Monet 背景调整后，`:core:designsystem:test` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签，并已原地安装到 `192.168.2.190:41713`。
- 降低 Monet 背景强度并分离首页筛选内层后，`:core:designsystem:test` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签，并已原地安装到 `192.168.2.190:41713`。
- 提升 Monet 背景强度并对齐首页筛选内层后，`:core:designsystem:test` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签，并已原地安装到 `192.168.2.190:41713`。
- 重建浅色 Monet 三层表面后，`:core:designsystem:test` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签，并已原地安装到 `192.168.2.190:41713`。
- 对齐 Wallpaper 卡片与首页筛选层级后，`:core:designsystem:test` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签，并已原地安装到 `192.168.2.190:41713`。
- 通过 ADB 截图采样校准浅色 Monet 层级后，`:core:designsystem:test` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签，并已原地安装到 `192.168.2.190:41713`。实测页面背景、卡片、筛选二层分别为 `#EAF2FF`、`#F4F8FF`、`#FFFFFF`。
- 下调浅色 Monet 背景强度后，`:core:designsystem:test` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签，并已原地安装到 `192.168.2.190:41713`。ADB 截图实测当前动态页面背景、卡片、筛选二层分别为 `#FFF1E8`、`#FFF6F1`、`#FFFFFF`。
- 还原官方 Material 色彩角色并完成 MDUI 2 风格原生组件重整后，`:core:designsystem:test` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签，并已原地安装到 `192.168.2.190:41713`，通过 ADB 启动和截图检查发现页及详情页。
- 移除发现页筛选外层容器后，`:feature:home:compileReleaseKotlin` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 验签并部署到 `192.168.2.190:41713`，ADB 截图确认筛选项直接排列在页面背景上。
- 发现页长按菜单高斯背景模糊增强为 `16.dp`；布局切换强调块改为平移动效，搜索框会在已显示的输入法关闭后清除焦点。高级筛选抽屉统一为固定无滚动内容区，分类页完整展示 25 个标签，三个页签与标题左对齐。下载与资料库整合为“管理”顶层页，默认显示下载内容，通过右下角 Material 3 浮动按钮打开筛选和内容切换抽屉；设置分组提高到 `surfaceContainerHigh` 以与动态背景区分。`:feature:home:compileDebugKotlin`、`:feature:downloads:compileDebugKotlin`、`:feature:library:compileDebugKotlin`、`:feature:settings:compileDebugKotlin`、`:app:compileDebugKotlin`、`:app:assembleDebug`、`:feature:home:testDebugUnitTest` 与 `:feature:downloads:testDebugUnitTest` 通过，Debug APK 已安装至 `192.168.2.190:41713` 并由 ADB 截图验证。
- 中断后重新复核并修复：独立下载页重新传递状态与类型筛选回调，布局切换图标与平移强调块重新精确对齐，分辨率页改为在同一固定高度内平铺并完整展示 25 个标签。相关单元测试和 `:app:assembleDebug` 再次通过，最新 APK 已重新部署并完成右键模糊、搜索失焦、筛选抽屉、管理页和设置页截图验证。
- 筛选抽屉改为根据“分类”标签流的实际测量高度自适应，删除标签与底部操作之间的留白；分辨率恢复分组展示，并在相同紧凑内容区内独立滚动。管理抽屉移除分隔线及全部筛选项描边。设置页面统一由透明 `ListItem` 承接 `surfaceContainerHigh` 分组表面，使全部设置卡片获得与“系统 Moment 配色”一致的背景层级。相关模块单元测试和 `:app:assembleDebug` 通过，最新 APK 已部署至 `192.168.2.190:41713` 并完成 ADB 截图验证。
- 底部导航栏增加克制的 `3.dp` 顶部分界阴影。发现页 Wallpaper 卡片将单行或双行标题与统计信息紧密排列，同时保留稳定的信息区高度和操作按钮基线；双列统计字体与图标适度增大，订阅、收藏和文件大小从左侧按实际内容自然排布与换行。`:feature:home:testDebugUnitTest` 与 `:app:assembleDebug` 通过，Debug APK 已部署至 `192.168.2.190:41713`，ADB 滚动截图确认单行、双行标题及底栏阴影均显示正常。
- 管理页筛选 FAB 的静止阴影由 Material 3 默认值降低为 `3.dp`，按压和悬停状态为 `4.dp`。高级筛选的分页内容统一顶部对齐，使较少的“官方”标签从分隔线下方开始逐行排列，不再垂直居中。`:feature:home:testDebugUnitTest` 与 `:app:assembleDebug` 通过，Debug APK 已部署至 `192.168.2.190:41713` 并完成管理页及官方标签页截图验证。
- 本轮发现页按钮投影同步与底栏阴影调整后，`testDebugUnitTest` 和 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 `zipalign`、v2/v3 签名校验，并通过 `adb install -r` 部署至 `192.168.2.190:41815`，安装期间未启动或远程操作应用界面。
- 长按菜单局部高斯模糊完成后，`:feature:home:testDebugUnitTest` 与 `:app:assembleRelease`（含 `lintVitalRelease`）通过；Release APK 已完成 16KB/4 字节 `zipalign` 检查和 v2/v3 验签，SHA-256 为 `7CA9DB34AC2640687CD35D52DC6A7F8C7593E71D7A7822D49039C4F691263A26`，并通过 `adb install -r` 原地部署至 `192.168.2.190:41815`，全程未启动或远程操作应用界面。

### 0.8.19 (29) — 2026-07-20

#### 更新

- 持续对齐 Web 版 WallHub 的移动端发现页、筛选、卡片布局、详情、下载队列、资料库、设置与主题视觉。
- 已具备独立运行所需的 Steam 登录与会话恢复、公共 Workshop 浏览/搜索/筛选、个人订阅与收藏、订阅/收藏写操作、真实 Depot 下载、断点续传、MPKG/ZIP 转换、SAF 导出与本地视频播放能力。
- 保留 Android 原生 Material 3、Compose、WorkManager、Room、JavaSteam、Media3 与 SAF 实现；不启动 Node.js 服务端，也不使用 WebView 承载应用。

#### 修复与验证

- 场景类 MPKG 已通过“转换 → 导入 Wallpaper Engine Android → 视觉效果”人工验证。
- 原先“场景 MPKG 可导入但主画面/图层可能显示异常”的发布阻断项已解除。
- 本版本人工审查结论：整体功能与视觉表现已接近 Web 版 WallHub。

### 0.8.2–0.8.18 — 历史迭代汇总

这些构建未保留可核验的逐版发布说明，以下仅记录已合并到当前版本的历史成果。

#### 更新

- 完成发现页的 Web 风格移动端布局、搜索、精确短语、排序、时间、类型、年龄评级、分类、官方标签、分辨率筛选、布局切换、自动加载与分页模式。
- 完成详情页、长按操作菜单、订阅/取消订阅、收藏/取消收藏、下载产物选择，以及下载队列的状态与类型筛选、暂停、继续、重试、删除与导出。
- 完成资料库的个人订阅、收藏、投票读取与缓存；完成设置的分类页面、下载目录、并发、代理、主题、语言和诊断导出。
- 完成 Monet 动态配色、日/夜间主题、Lucide 图标迁移、顶部应用内提示、页面/筛选/布局过渡动画与多处移动端视觉一致性优化。

#### 修复

- 修复 Steam 手机确认、手动验证码回退、登录状态持久化及会话恢复。
- 修复下载登录会话传递、下载速度与波动、暂停/续传、任务恢复、日志导出与目录选择相关问题。
- 修复发现页筛选、卡片动画、图标显示、主题色残留、资料库加载及详情状态互相污染等问题。

### 0.8.1 (11) — 历史里程碑

#### 更新

- 完成公共创意工坊浏览、搜索、排序、分页、详情、资料库、下载队列、MPKG/ZIP 转换和 SAF 导出的正式工程迁移。
- 将发现页标题和搜索合并为单行；列表滚动时自动收束筛选区域；设置拆分为二级分类页面。

### 0.8.0 (10) — 历史里程碑

#### 更新

- 将 JavaSteam、Depot 下载、WorkManager、转换器、SAF 与本地 Media3 播放器迁入 Android 正式工程。
- 建立 Room 下载任务持久化、应用私有暂存目录、前台下载任务与诊断导出能力。

### 0.3.0 (2) — 正式工程基础

#### 更新

- 创建 Android 正式模块化工程、稳定领域模型、Repository 边界、主题与导航框架。
- 建立设置、加密 Steam refresh token、下载任务记录和脱敏诊断记录的基础数据层。

### 0.1.2 (3) — 独立可行性原型

#### 更新

- 验证 Kotlin + Compose Material 3、JavaSteam 登录、Steam Guard、真实 Workshop 下载、Room、WorkManager、SAF、MPKG 转换与 Media3 本地播放的端到端可行性。

## 后续记录格式

每次发布新 APK 时，在本节上方新增一个版本条目，并使用以下结构：

```md
### <versionName> (<versionCode>) — <YYYY-MM-DD>

#### 更新

- 新功能或可见改动。

#### 修复

- 已修复的问题；无则写“无”。

#### 验证

- 已执行的构建、自动检查或人工验证结论。
```

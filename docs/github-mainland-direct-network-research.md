# GitHub 服务族在中国大陆直连 Android 客户端中的自动防阻断设计研究

> 研究日期：2026-10-27（Asia/Shanghai）
>
> 研究目标：不使用代理、VPN、内容中继或自建镜像，在 Android 客户端内为 **GitHub.com 公有云的明确服务族**设计可审计、可失败关闭（fail closed）的直连增强策略。
>
> 证据优先级：GitHub Docs、GitHub REST `/meta`、GitHub 官方开源仓库（Git LFS）优先；协议标准只用于补足 Git/HTTP/TLS 的通用语义。

## 1. 先限定“所有 GitHub 服务”的可实现含义

“所有 GitHub 服务”不能解释为以下无限集合：

- GitHub 今后新增、尚未在文档或 `/meta` 公布的域名；
- 任意 GitHub Enterprise Server（GHES）自定义主机；
- GitHub Enterprise Cloud data residency 的任意 `SUBDOMAIN.ghe.com`；
- GitHub Pages 的任意自定义域名；
- Actions workflow、README、Issue、Release 或用户内容中引用的任意第三方 URL；
- Codespaces、Copilot、Marketplace App、Webhook 接收方等未明确纳入本报告的产品；
- DNS CNAME 当前指向的任意 CDN/云厂商内部 hostname。

GitHub 自己也明确警告，REST API 返回的域名列表**不是完整清单**，只按 DNS allowlist 放行这些名称可能仍导致 GitHub 或相关服务部分/全部不可用：[Allowing access to GitHub's services from a restricted network](https://docs.github.com/en/get-started/using-github/allowing-access-to-githubs-services-from-a-restricted-network)。

因此本文把可落地范围定义成一个**版本化服务配置集**：

1. GitHub.com Web 与登录：`github.com`；
2. REST/GraphQL API：`api.github.com`（GraphQL 路径为 `/graphql`）；
3. 静态 Web 资源、头像、匿名图片：GitHub 官方列出的 `*.githubassets.com`、`*.githubusercontent.com` 中的已知具体主机；
4. raw、archive/codeload、release/assets、objects；
5. Git Smart HTTP、Git SSH 22、GitHub 官方 SSH-over-443；
6. Git LFS batch 与由 batch 响应签发的 action URL；
7. GitHub Actions 控制面、action 下载、artifact/cache、runner 更新所需的官方域名；
8. GitHub Packages 中的 Container Registry 与 npm registry，并给其他 `*.pkg.github.com` 留下明确配置位；
9. OAuth Web/device flow 的 GitHub 端点和凭据边界。

任何不在当前配置集、`/meta` 当前响应或某个操作响应的受信 URL 字段中的主机均不自动“猜测支持”。

## 2. GitHub 官方动态来源：REST `/meta`

GitHub 官方 [REST API endpoints for meta data](https://docs.github.com/en/rest/meta/meta) 指定：

```http
GET https://api.github.com/meta
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2022-11-28
```

文档要求客户端始终查询 API 获得最新值；示例值不能当成当前值。响应同时含 IPv4/IPv6，但并非所有功能都支持 IPv6。GitHub 还说明 IP 会变化，不推荐长期按 IP allowlist；若使用，必须持续监控 `/meta`：[About GitHub's IP addresses](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-githubs-ip-addresses)。

### 2.1 本次实时快照的类别（只记录结构和数量，不固化完整地址表）

2026-10-27 查询得到：

| `/meta` 字段 | 条目数 | 用途解释 |
|---|---:|---|
| `web` | 41 | GitHub Web 前门 CIDR |
| `api` | 27 | API CIDR |
| `git` | 62 | Git 传输 CIDR |
| `packages` | 30 | Packages CIDR |
| `pages` | 10 | GitHub Pages（不覆盖用户自定义域） |
| `hooks` | 9 | GitHub 出站 webhook 来源，不是 Android 客户端目的地址 |
| `importer` | 5 | Importer |
| `github_enterprise_importer` | 8 | Enterprise importer |
| `actions` | 7081 | Actions/云基础设施大范围地址，包含共享云资源 |
| `actions_macos` | 8 | macOS runner 范围 |
| `codespaces` | 191 | 本报告默认不实现 |
| `copilot` | 17 | 本报告默认不实现 |
| `shared_ingress` | 21 | 共享入口 |

当前 `domains` 对象含：

- `website`: `*.github.com`、`*.github.dev`、`*.github.io`、`*.githubassets.com`、`*.githubusercontent.com`；
- `packages`: Azure Blob 主机、`npm.pkg.github.com`、`nuget.pkg.github.com`、`rubygems.pkg.github.com`、`maven.pkg.github.com`、`docker.pkg.github.com`、`containers.pkg.github.com`、`*.ghcr.io`、`*.pkg.github.com` 等；
- `actions`: `github.com`、`*.github.com`、`*.githubapp.com`、`ghcr.io`、多个 `*.githubusercontent.com` 具体主机、`*.actions.githubusercontent.com` 和大量 `*.blob.core.windows.net`；
- `actions_inbound`: `full_domains` 与 `wildcard_domains`，用于 runner 入站/控制面清单；
- `codespaces`、`copilot`、`artifact_attestations` 等独立集合。

### 2.2 `/meta` 能做什么、不能做什么

可以：

- 每日或更低频条件刷新，校验 JSON、数量和 CIDR 上限后原子更新；
- 将 IP 类别用于诊断、企业防火墙提示、候选地址的**附加证据**；
- 获取当前 SSH host keys / fingerprints；
- 从 `domains` 更新 Actions/Packages 的声明式服务清单。

不能：

- 从 `web` 的任意 IP 推断它能承载 `api.github.com`、`raw.githubusercontent.com` 或 `ghcr.io`；
- 将 `actions` 的 7000 多个共享云 CIDR当成 GitHub 专属 origin；
- 用 IP 范围替代 TLS hostname、SSH host key 或 registry token audience 验证；
- 将 wildcard 自动扩展成所有未来敏感写操作；
- 把 CNAME 目标 hostname 当成稳定兼容 TLS authority。

## 3. 服务域名和基础设施信任分类

### 3.1 GitHub 公布的稳定服务命名空间

以下名称由 GitHub Docs 或 `/meta` 明确公布，可作为“服务 authority”，但仍须逐个验证 TLS/SSH 身份：

- `github.com`、`api.github.com`；
- `ssh.github.com`；
- `codeload.github.com`；
- `github.githubassets.com` / `*.githubassets.com`；
- `raw.githubusercontent.com`、`avatars.githubusercontent.com`、`media.githubusercontent.com`、`objects.githubusercontent.com`、`objects-origin.githubusercontent.com`、`github-releases.githubusercontent.com`、`github-registry-files.githubusercontent.com`、`release-assets.githubusercontent.com`、`pkg-containers.githubusercontent.com`、`github-cloud.githubusercontent.com`；
- `*.actions.githubusercontent.com`；
- `ghcr.io`、`*.pkg.github.com`，特别是 `npm.pkg.github.com`。

这里的“GitHub 公布”表示 GitHub 对外给出的服务合同名称，不等于承诺其物理服务器或 IP 由 GitHub 独占。

### 3.2 GitHub 官方流程依赖的第三方云 authority

这些不是 GitHub registrable domain，但 GitHub 官方 Actions/Packages/LFS 文档或 `/meta` 明确列出：

- `*.blob.core.windows.net`：Actions summaries/logs/artifacts/cache、Packages 后端；
- `github-cloud.s3.amazonaws.com`：Git LFS 存储；
- 某些产品还会列 `*.azureedge.net`、`*.windows.net`、`*.microsoft.com` 等（Codespaces，不在默认范围）。

GitHub 的 self-hosted runner 文档特别说明，一些域名通过 CNAME 配置；CNAME 目标将来可能改变，**只有文档列出的服务域名保持稳定**：[Self-hosted runners reference](https://docs.github.com/en/actions/reference/runners/self-hosted-runners)。所以不能把 Azure/AWS/CDN 的其他共享 hostname 加入“兼容 authority”。

### 3.3 用户或第三方内容域

以下不进入自动防阻断通用白名单：

- Pages 自定义域；
- Releases/README/Issue 中的外链；
- Actions workflow 自己访问的任意软件源；
- OAuth app 自己的 `redirect_uri`；
- GHES 或 GHE.com 租户域；
- LFS action 中超出产品政策的任意自定义 LFS server（除非用户显式添加该 Git remote 配置）。

## 4. 推荐的最小路由模型

每个请求绑定一个不可变 `GitHubServiceProfile`：

```text
profile =
  exact scheme + exact authority/port
  + method/path family
  + credential class
  + redirect policy
  + body/response bounds
  + allowed resolution strategy
```

### 4.1 共同顺序

```text
A. 系统网络优先
   系统 DNS + ProxySelector/VPN + 原始 SNI/Host

B. 明确传输失败时，DNS-only 增强
   可信 DoH/DoT候选 + 原始 SNI + 原始 Host + 系统 PKIX

C. 仍失败且该 exact host 已完成设备前瞻验证时，可尝试 no-SNI
   直连候选 IP + 无 server_name 扩展
   + 系统 PKIX
   + 握手后对原始 hostname 做 HostnameVerifier
   + 验证通过前不发送任何 HTTP 字节

D. 失败即返回，不进行 authority 猜测
```

只在 DNS/connect/TLS reset/timeout 等传输错误后进入 B/C。HTTP 4xx/5xx、rate limit、JSON/协议错误、token 错误、checksum 错误都不是换路由理由。

### 4.2 DNS-only：默认且最广泛适用

DNS-only 只改变“连接哪个 IP”，不改变：

```text
TLS SNI
TLS hostname verification
HTTP Host / HTTP/2 :authority
SSH hostname 和 host key policy
registry audience
```

候选必须来自**exact hostname** 的 A/AAAA 查询；过滤 private、loopback、link-local、multicast、documentation、benchmark/Fake-IP。可把 `/meta` 对应类别作为额外过滤/排序信号，但不能用类别中的任意 IP替换 DNS 结果。

### 4.3 no-SNI：可以安全实现，但不能批量假设可用

无 SNI 不等于不验证 TLS。安全条件缺一不可：

1. 默认系统 TrustManager 完成 PKIX 链验证；
2. `endpointIdentificationAlgorithm` 仅因不发送 SNI而关闭；
3. 握手后、发送任何请求字节前，用原始 authority 做 hostname/SAN 验证；
4. socket、连接池和凭据按原始 authority 隔离；
5. 当前 exact host 的 Android/Conscrypt 前瞻测试证明无 SNI 时服务器返回覆盖原始 host 的证书；
6. 服务端改变默认证书时安全失败，不降级 trust-all；
7. 不固定短期 leaf/SPKI，除非有正式 pin rotation 机制。

因此 no-SNI 可作为 `github.com`、`api.github.com`、`raw.githubusercontent.com` 等**逐主机、逐设备验证后的传输能力**，而不是 `*.githubusercontent.com` wildcard 的默认策略。第三方 Azure/S3 动态主机默认只做 DNS-only，因为其无 SNI 默认虚拟主机/证书没有 GitHub 合同保证。

### 4.4 compatible authority：HTTPS 没有可泛化的官方证据

本次未找到 GitHub 官方文档声明：可以给 `github.com` 请求改用某个 CDN hostname 作 TLS SNI，同时保留 `Host: github.com`。因此：

- 禁止把 CNAME 目标、Fastly/Akamai/Azure/S3 共享域名当兼容 TLS authority；
- 禁止 domain fronting；
- 禁止关闭 hostname verification；
- 证书同时覆盖多个名称，也只证明证书身份覆盖，不证明跨 Host 路由是稳定合同。

唯一明确的“官方替代入口”是 **SSH 协议**的 `ssh.github.com:443`，它不是 HTTPS authority substitution。GitHub 明确要求 443 使用 `ssh.github.com` 而不是 `github.com`：[Using SSH over the HTTPS port](https://docs.github.com/en/authentication/troubleshooting-ssh/using-ssh-over-the-https-port)。

### 4.5 PJZ110 中国移动直连前瞻测试

测试设备 PJZ110 处于中国移动直连环境，无系统代理、无 VPN。以下是本次代表性结果；`404`/`401`/`403` 仅证明 DNS、TCP、TLS 和 HTTP 已到达目标服务，不能当成功业务响应：

| 服务/探针 | 代表性结果 | 解释 |
|---|---|---|
| `api.github.com/meta` | HTTP 200 | API 可直连，但默认多轮不稳定 |
| API 默认地址族 10 轮 | 5/10 | 地址族/路径选择导致显著抖动 |
| API 强制 IPv4 10 轮 | 10/10 | 当前网络应优先 IPv4；这不是永久关闭 IPv6 的理由 |
| API IPv6 | 解析/连接失败 | 当前网络/目标组合不能用 IPv6 |
| `raw.githubusercontent.com` | 200 | raw 可达 |
| `codeload.github.com` | 200 | archive/action 下载入口可达 |
| `avatars.githubusercontent.com` | 200 | avatar 可达 |
| `objects.githubusercontent.com/` | 404 | authority 可达；根路径不是对象操作 |
| `release-assets.githubusercontent.com/` | 404 | authority 可达；必须使用签名 asset URL |
| `github.githubassets.com/` | 404 | authority 可达；根路径无资源 |
| GitHub Pages 测试页 | 200 | 官方 Pages 子域可达，不代表任意自定义域 |
| `ghcr.io/v2/` | 401 | Registry V2 可达并进入认证挑战 |
| `github-cloud.githubusercontent.com` / S3 LFS authority | 403 | storage authority 可达；缺少签名动作时按预期拒绝 |
| `github.com` 首页 | 收到 HTTP 200 headers，但 12 秒内 body 为 0 并超时 | Web 不可判定为可用，不能只看 status line |
| Git Smart HTTP | TCP timeout | 当前网络未恢复 Git HTTPS |
| `gist.github.com` | TCP timeout | 不纳入已验证 Core；三家 DoH 返回明显冲突的三个地址 |
| `npm.pkg.github.com` | 跳转到 `github.com` 后超时 | npm 登录/页面式流程仍受 Web 阻断；registry API需独立测试 |
| `github.com:22` SSH | 成功并收到 SSH banner | 当前网络标准 SSH 22 可用 |
| `ssh.github.com:443` | timeout | 官方备用 endpoint 在该网络反而不可达，必须逐网络探测 |

DNS 前瞻中，三家 DoH 对 `api.github.com`、`github.com`、`codeload.github.com` 的结果一致，而 `gist.github.com` 出现三个互相冲突、明显可疑的地址。因此实现不能“最快 DoH 答案即可信”，仍须以 TLS/SSH 身份验证和多解析器一致性为依据。

尝试对 `20.205.243.166` 等 GitHub regional IP使用 no-SNI 直连全部失败。由此得到一个重要的产品结论：**Curator 的 no-SNI 成功经验不能迁移成 GitHub 默认策略。当前 PJZ110 验证通过的 GitHub增强手段是 exact-host DNS + 强制/优先 IPv4 + 原始 SNI/Host，而不是 no-SNI 或 compatible authority。** no-SNI 代码能力可以保留为实验门，但本次没有任何 GitHub host达到进入生产 allowlist 的标准。

最有效的 release 下载链路不依赖 `github.com` Web：

1. 向 `api.github.com/repos/{owner}/{repo}/releases/assets/{asset_id}` 发送 `Accept: application/octet-stream`；
2. API 返回 302 到 `release-assets.githubusercontent.com` 的签名 URL；
3. 剥离 API Authorization 后原样跟随签名 URL；
4. `Range: bytes=0-1023` 最终得到 206 和 1024 字节。

该结果与 GitHub Release Assets 文档的“200 或 302”合同一致，也证明 Android 客户端应优先采用 API→签名 asset 的结构化链路，而不是抓取 `github.com/releases` Web 页面。

### 4.6 WallHub 更新检查/下载的最小可落地方案

WallHub 当前最值得先实现的不是“GitHub 全服务路由器”，而是只覆盖应用更新的三段固定操作：

```text
1. GET https://api.github.com/repos/{owner}/{repo}/releases/latest
   → 解析 tag、asset id、size、digest/browser_download_url

2. GET https://api.github.com/repos/{owner}/{repo}/releases/assets/{asset_id}
   Accept: application/octet-stream
   → 接受 200 或手动处理一个 302

3. 对 302 Location 原样请求 release-assets.githubusercontent.com
   → 不携带 API Authorization/Cookie
   → 支持 Range/206、断点续传、大小和 SHA-256 校验
```

最小网络策略：

- `api.github.com` 使用系统路径，明确 transport failure 后用可信 DNS候选并**优先 IPv4**；始终保留原始 SNI/Host和系统证书验证；
- 不为 GitHub启用 no-SNI，因为设备前瞻全失败；
- 不访问 `github.com/releases` HTML，不依赖 `browser_download_url` 首跳 Web；
- 禁用自动 redirect，代码显式验证 `Location` 为 HTTPS、无 user-info、authority 属于当前 GitHub官方 asset allowlist；
- 跨 authority 丢弃 `Authorization`、Cookie、Proxy-Authorization；签名 query不写日志；
- 只接受预期 APK MIME/文件名、上限和 asset id；最终以发布元数据中的 digest或项目发布清单 SHA-256 验证；
- `/meta` 只用于刷新 allowlist/LKG，不在每次更新检查前阻塞调用；异常 `/meta` 不覆盖最后成功配置。

这条链路已经在 PJZ110 完成 `API 302 → release-assets → Range 206/1024 bytes` 前瞻验证，并绕开当前不可用的 `github.com` Web，是最小、最可验证、凭据边界最清楚的首期实现。

## 5. 分服务族设计

### 5.1 Web、登录、OAuth

**固定 GitHub 端点：**

- `https://github.com/`
- `GET https://github.com/login/oauth/authorize`
- `POST https://github.com/login/oauth/access_token`
- `POST https://github.com/login/device/code`
- 用户输入 device code：`https://github.com/login/device`

来源：[Authorizing OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)。GitHub 支持 authorization code 和 device flow，不支持 implicit grant；官方文档推荐 `state` 与 PKCE，device polling 必须服从返回的 `interval`。

**路由：**系统路径 → DNS-only；no-SNI 只有在 exact `github.com` 设备测试通过后才启用。登录页应由 Custom Tabs/系统浏览器持有 Cookie，不把 GitHub Cookie复制进通用 HTTP 客户端。

**不可改写：**

- `redirect_uri` 是应用注册的回调，不是 GitHub service，不得改写或送进 GitHub路由器；
- 回调必须验证 `state`，authorization code 与原 PKCE verifier绑定；
- token endpoint 不跟随跨 host redirect；
- client secret 不应嵌入不能保密的 Android 公共客户端；移动端优先 device flow/PKCE；
- access token 只发送到明确的 GitHub API/操作 authority。

### 5.2 REST / GraphQL API

**authority：**`api.github.com:443`。

REST 根和 `/meta` 都在该 authority；用户 token 示例也是 `Authorization: Bearer ...` 到 `https://api.github.com/user`：[Authorizing OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)。GraphQL 为同一 authority 的 `/graphql`。

**路由：**DNS-only 默认；no-SNI 可逐 host 验证。不得把 API 请求改发到 `github.com`、GitHub Pages 或 CNAME authority。

**约束：**

- 每个 token 有明确 audience/权限；最小 scope；
- 处理 GitHub API version、rate-limit 和 abuse response，不把 403/429 当网络阻断；
- REST 响应中 URL 字段是数据，只有操作 schema 明确声明可跟随的字段才可发起下一跳；
- 跨 authority redirect 默认剥离 `Authorization`、Cookie、Proxy-Authorization。

### 5.3 raw、头像、Web 静态资源和匿名图片

**具体主机：**

- `raw.githubusercontent.com`：仓库 raw 内容；
- `avatars.githubusercontent.com`：REST 用户对象的 `avatar_url` 示例由 GitHub 文档明确给出：[Getting started with the REST API](https://docs.github.com/en/rest/using-the-rest-api/getting-started-with-the-rest-api)；
- `github.githubassets.com` / `*.githubassets.com`：GitHub Web 静态资源；
- `media.githubusercontent.com`：LFS/media 内容；
- `camo.githubusercontent.com` 或其他 `*.githubusercontent.com` 图片代理。GitHub 说明 Camo 匿名图片 URL使用 `<subdomain>.githubusercontent.com`：[About anonymized URLs](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-anonymized-urls)。

**路由：**公开 GET/HEAD 优先 DNS-only；可以为每个已知 exact host 做无 SNI 前瞻验证。不要将整个 `*.githubusercontent.com` 自动视为同一 socket/authority。

**凭据：**公开 raw/avatar/static 请求不带 GitHub PAT、OAuth token 或登录 Cookie。私有 raw 内容应优先通过 `api.github.com` Contents/Blob API获取；若 GitHub 返回临时下载 URL，只使用该 URL 自带的签名语义。

### 5.4 codeload / archive

**authority：**`codeload.github.com`。Actions 官方文档明确把它列为下载 actions 所需域名：[Self-hosted runners reference](https://docs.github.com/en/actions/reference/runners/self-hosted-runners)。典型 archive URL 是 GitHub生成的 codeload URL；客户端不应自行把任意 GitHub URL机械重写成 codeload。

**路由：**DNS-only；逐 host 验证后可 no-SNI。只允许 GET/HEAD、限制 archive 大小/解压比/路径穿越。公开 archive 不带 token；私有 archive 的认证应先走 `api.github.com` 或 `github.com` 官方端点，跨 host redirect 时不复制 bearer/PAT。

### 5.5 Releases 与 assets

入口有两类：

- `browser_download_url` 通常在 `https://github.com/OWNER/REPO/releases/download/...`；
- REST `GET https://api.github.com/repos/OWNER/REPO/releases/assets/ASSET_ID` 配合 `Accept: application/octet-stream`。

GitHub 官方说明后者可能直接返回 200，也可能返回 302，客户端必须支持两者：[REST API endpoints for release assets](https://docs.github.com/en/rest/releases/assets)。Actions 文档明确列出 `release-assets.githubusercontent.com` 为下载 release assets 所需主机，并列 `objects.githubusercontent.com`、`objects-origin.githubusercontent.com`、`github-releases.githubusercontent.com`、`github-registry-files.githubusercontent.com` 用于 runner 更新/对象下载：[Self-hosted runners reference](https://docs.github.com/en/actions/reference/runners/self-hosted-runners)。

**安全跳转：**

1. 第一跳在 `github.com`/`api.github.com` 严格认证；
2. 302 `Location` 必须是 HTTPS、无 user-info；
3. 对新 authority 重新做 DNS/TLS/hostname 验证；
4. 跨 host 一律剥离 GitHub `Authorization` 和 Cookie；
5. 签名 query 视为 secret：不记录、不归一化、不重组、不持久化超过其有效期；
6. 若 Location 不属于当前 `/meta`/操作 allowlist，则要求用户显式确认或拒绝，不能改写到“看起来相似”的 GitHub主机；
7. 下载完成校验 API 提供的 `digest`（若存在）或调用方已知哈希。

### 5.6 Git Smart HTTP

GitHub 官方支持 HTTPS remote：

```text
https://github.com/OWNER/REPOSITORY.git
```

并要求私有仓库用 PAT/credential helper，而不是账户密码：[About remote repositories](https://docs.github.com/en/get-started/git-basics/about-remote-repositories)。

**建议：**使用 JGit/libgit2/成熟 Git 实现；防阻断层只提供经过认证的 socket/DNS，不自行重写 pkt-line 或 Git credential protocol。典型 smart HTTP 在同一 `github.com` repository base 下进行 `info/refs`、`git-upload-pack`/`git-receive-pack`。

**边界：**

- PAT/Basic credential 只给 `github.com:443` 的该 repository origin；
- redirect 跨 host 不带 Authorization；写操作不自动重试，除非协议层证明未提交；
- no-SNI 只改变 ClientHello，PKIX + `github.com` hostname 验证后才能发送 Git Authorization；
- 不把 `github.com` 的 IP、Host 或凭据改写给 codeload/raw/assets。

### 5.7 Git LFS

Git LFS 官方开源协议文档定义：

```text
remote:    https://github.com/OWNER/REPO.git
LFS base:  https://github.com/OWNER/REPO.git/info/lfs
batch:     .../objects/batch
```

Batch 返回每个 object 的 `actions.download/upload/verify`，其中含 `href`、可选 `header` 和 expiry：[Batch API](https://github.com/git-lfs/git-lfs/blob/main/docs/api/batch.md)、[Basic Transfer API](https://github.com/git-lfs/git-lfs/blob/main/docs/api/basic-transfers.md)。GitHub Actions 官方网络清单还列 `github-cloud.githubusercontent.com` 与 `github-cloud.s3.amazonaws.com` 为 Git LFS 所需域名：[Self-hosted runners reference](https://docs.github.com/en/actions/reference/runners/self-hosted-runners)。

**核心凭据规则：**

- repository PAT/Basic 只用于 LFS batch 的 `github.com` origin；
- 对 action `href`，只发送 action 对象明确给出的 `header`；绝不把 repository PAT自动继承到 S3/`githubusercontent.com`；
- action URL 和 header 都是短期 secret，按 `expires_in`/`expires_at` 丢弃；
- 不改写签名 URL 的 host、path、query；
- 校验下载长度和 LFS OID（SHA-256）；
- S3/动态 storage authority 默认 DNS-only，不做兼容 authority；no-SNI 必须逐 exact host 证明默认证书覆盖后才能启用。

### 5.8 SSH：22 与官方 443

**标准：**`git@github.com:OWNER/REPO.git` → `github.com:22`。
**官方替代：**`ssh://git@ssh.github.com:443/OWNER/REPO.git`。

GitHub 明确说明 443 的 hostname 是 `ssh.github.com`，不是 `github.com`：[Using SSH over the HTTPS port](https://docs.github.com/en/authentication/troubleshooting-ssh/using-ssh-over-the-https-port)。这属于正式备用 endpoint，可在 22 connect timeout/refused 后尝试；不是 TLS/SNI 技术。

SSH 不使用 TLS，因此：

- “no-SNI”不适用；
- DNS-only 可以改变连接 IP，但身份必须通过 SSH host key 验证；
- GitHub 公布当前 RSA/ECDSA/Ed25519 fingerprints 和 known_hosts keys：[GitHub's SSH key fingerprints](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints)；
- `/meta` 也返回 `ssh_keys`/`ssh_key_fingerprints`，更新必须经过已认证 HTTPS并原子替换；
- 不允许 `StrictHostKeyChecking=no`、TOFU 静默接受、把 `github.com` 私钥发给其他 SSH host；
- 443 fallback 仍验证 GitHub已发布的 host key。

### 5.9 GitHub Actions

当前官方 self-hosted runner 文档按用途列出：[Self-hosted runners reference](https://docs.github.com/en/actions/reference/runners/self-hosted-runners)

| 功能 | 官方域名 |
|---|---|
| 核心控制 | `github.com`、`api.github.com`、`*.actions.githubusercontent.com` |
| 下载 actions | `codeload.github.com` |
| summaries/logs/artifacts/cache | `results-receiver.actions.githubusercontent.com`、`*.blob.core.windows.net` |
| runner 更新 | `objects.githubusercontent.com`、`objects-origin.githubusercontent.com`、`github-releases.githubusercontent.com`、`github-registry-files.githubusercontent.com` |
| OIDC token | `*.actions.githubusercontent.com` |
| Packages/container | `*.pkg.github.com`、`pkg-containers.githubusercontent.com`、`ghcr.io` |
| Git LFS | `github-cloud.githubusercontent.com`、`github-cloud.s3.amazonaws.com` |
| release assets | `release-assets.githubusercontent.com` |

**可实现范围：**Android 客户端可以提供这些官方控制面/下载面的 route profile；不能声称让任意 workflow“全部可用”，因为 workflow 本身可以访问任意第三方依赖、容器、云 API 和私有网络。

**策略：**

- exact 官方 GitHub host 可 DNS-only，逐 host 前瞻后可 no-SNI；
- Azure Blob/S3 只 DNS-only；
- Actions job token、OIDC token、artifact URL 全是短期受众限定凭据，只给 GitHub响应指定的 exact URL/header；
- 不将 `GITHUB_TOKEN` 发送给 codeload、raw、release-assets 或 Blob，除非官方响应明确给出该请求所需的 scoped header；
- `*.actions.githubusercontent.com` 不能变成任意 wildcard socket 共用；每个实际 hostname 独立 TLS验证；
- `/meta domains.actions/actions_inbound` 动态更新，设置最大域名数和合法 suffix，异常扩张需保留 LKG并告警。

### 5.10 Packages、Container Registry 与 npm

#### Container Registry

官方 endpoint：

```text
ghcr.io/NAMESPACE/IMAGE_NAME
```

GitHub 文档要求 `docker login ghcr.io`，Packages 使用 PAT classic 或 workflow `GITHUB_TOKEN`，scope 至少按 `read:packages`/`write:packages`/`delete:packages` 最小化：[Working with the Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)。

Registry V2 可能通过 `WWW-Authenticate` 发起 bearer challenge。客户端只能跟随 HTTPS challenge realm；token 的 service/scope 必须与 `ghcr.io` 操作匹配，不能把 PAT 发给 layer redirect 的 Blob/CDN。Manifest 和 blob digest 必须校验。`docker.pkg.github.com` 是历史 endpoint；新实现默认 `ghcr.io`，只在用户配置旧 remote 时支持历史主机。

#### npm

官方 registry：

```text
https://npm.pkg.github.com
```

`.npmrc` token key 是：

```text
//npm.pkg.github.com/:_authToken=TOKEN
```

来源：[Working with the npm registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-npm-registry)。因此 npm token 只能发给 `npm.pkg.github.com`，不能发给 `npm-proxy.pkg.github.com`、tarball redirect、Blob 或其他 `*.pkg.github.com`，除非 npm registry 响应明确给出 scoped 请求机制且客户端规则允许。

**路由：**registry metadata authority 可 DNS-only；逐 host 验证后可 no-SNI。包 tarball/blob 的签名跳转 exact-follow，剥离 registry token，校验 package integrity。其他 Maven/NuGet/RubyGems 主机从 `/meta domains.packages` 按独立 profile 支持，不以 wildcard 自动共享凭据。

## 6. 凭据与重定向总表

| 凭据 | 可发送 authority | 严禁自动转发到 |
|---|---|---|
| GitHub Web Cookie | `github.com` 的浏览器 Cookie policy | API、raw、codeload、assets、Packages |
| OAuth access token/PAT（API） | `api.github.com` | 任意 redirect host、raw/assets/Blob |
| Git HTTPS PAT/Basic | `github.com:443` repository origin | codeload/raw/LFS object/S3 |
| LFS batch credential | `github.com/...git/info/lfs` | action href；后者只用 action 自带 header |
| Release API token | `api.github.com` 初始请求 | 302 Location |
| npm token | `npm.pkg.github.com` | proxy/blob/其他 package host |
| Container PAT | `ghcr.io` auth/registry challenge所需 exact GitHub endpoint | layer CDN/Blob redirect |
| Actions `GITHUB_TOKEN` | job protocol明确的 GitHub API/origin | action作者的任意网络请求、artifact signed URL |
| OIDC request token | GitHub指定 `*.actions.githubusercontent.com` exact endpoint | 最终云厂商 token endpoint（另行策略） |
| SSH private key | SSH协议内 `github.com:22` 或 `ssh.github.com:443` | 任何 HTTPS或未知 SSH host |
| 签名 URL query/action header | 响应指定 exact HTTPS URL，且在 expiry 内 | 日志、磁盘长存、任何重写后 URL |

通用 redirect 规则：同 authority、同 scheme 的只读重定向可以按操作 policy；跨 authority 必须重新验证 allowlist/TLS，并清除 Authorization、Cookie、Proxy-Authorization。307/308 对带 body/写操作默认拒绝，避免重放。

## 7. Android 实现边界

建议四层即可，不做通用代理：

```text
GitHubServiceCatalog
  官方 exact host/profile + /meta LKG

GitHubCandidateResolver
  系统 DNS + 用户启用 DoH
  公共地址过滤、网络指纹短缓存

AuthenticatedDirectDialer
  原始 SNI 模式
  可选逐主机 no-SNI 模式
  系统 PKIX + 原始 hostname verification

Operation clients
  Web/API/Content/Git/LFS/Actions/Package/SSH
  各自凭据和 redirect policy
```

关键约束：

- 不提供 `fetch(anyUrl)` 或任意 CONNECT；
- no-SNI allowlist 使用 exact host，不用 wildcard；
- TLS验证通过前不发送 path、token或 header；
- HTTP/2连接池按原始 authority隔离；no-SNI 连接禁止跨 host coalescing；
- 只缓存“该网络指纹 + exact host + IP”的成功结果，有 TTL；网络/VPN/代理变化失效；
- `/meta` 更新必须有 schema/数量/后缀边界和 AtomicFile LKG；
- Meta API自身不可用时继续使用最后验证配置，不接受搜索引擎或第三方 hosts 文件；
- 日志不记录 token、Cookie、signed query、LFS action header、完整私有仓库路径；
- 用户关闭自动防阻断后，不使用 DoH/no-SNI，完整保留系统代理/VPN路径。

## 8. 分级能力声明

| 等级 | 能力 | 可对用户声明 |
|---|---|---|
| Core | github.com、api、raw、codeload、avatars、release/assets、Git HTTPS | “GitHub.com 核心浏览/API/内容与 Git HTTPS” |
| Git+LFS | Smart HTTP、LFS batch/action、SSH 22/443 | “Git 与 Git LFS，含官方 SSH 443” |
| Packages | ghcr.io、npm.pkg.github.com，后续逐 registry | “GitHub Container/npm Packages” |
| Actions control/download | 官方 runner/control/artifact/update 域名 | “Actions 官方控制与制品链路” |
| Not covered | workflow任意依赖、Pages自定义域、GHES/GHE.com、未来域名 | 必须明确列为不保证 |

上述表格是实现分层，不等于当前设备全部验收通过。PJZ110 当前已验证的是 API（IPv4）、raw、codeload、avatars、release asset 结构化下载、部分对象/storage authority、ghcr认证入口、Pages 和 SSH 22；`github.com` Web、Git Smart HTTP、gist、npm跳转和 SSH 443 尚未通过。

不得宣传“大陆直连支持全部 GitHub 服务”。更准确的产品文案是：

> 支持当前版本声明且已在目标网络验收的 GitHub.com 服务族；域名由 GitHub官方 `/meta` 和文档更新。未验收服务、第三方链接、企业/自定义域及 workflow 任意外部依赖不在自动防阻断保证范围。

## 9. 验收建议

### 9.1 安全负向测试

- DNS 返回 private/Fake-IP/documentation 地址时拒绝；
- 错误证书链、错误 SAN、过期证书、错误 SSH host key 均失败；
- no-SNI 默认服务器给出其他合法证书时，HTTP 字节发送量必须为 0；
- 跨 host 302 后 GitHub bearer/PAT/Cookie 为 0；
- LFS action 只携带 action header；npm/ghcr token 不泄露到 Blob；
- signed URL query不进入日志；
- 未知 host、Pages自定义域、GHES host默认拒绝增强路由；
- HTTP 403/429/5xx 不触发 DNS/SNI轮换；
- 错误 `/meta` schema、超量 domains/CIDR 不覆盖 LKG。

### 9.2 每服务族直连矩阵

在目标大陆运营商网络上分别做系统路径、DNS-only、no-SNI（若允许）三列：

- Web首页和登录页面（不自动提交凭据）；
- API `/meta`、匿名 repo、认证 `/user`；
- raw、avatar、codeload archive；
- release API 200/302 和 release-assets；
- Git public clone、private fetch、push 的非重复提交保护；
- LFS batch、download、OID校验；
- SSH 22 与 `ssh.github.com:443`、官方 fingerprint；
- Actions control/action download/artifact（只有产品真的实现 runner 时）；
- ghcr public pull、private auth pull；
- npm metadata/tarball/integrity。

no-SNI 必须逐 exact host 至少 30 次稳定成功才进入生产 allowlist；这只证明该主机/网络/时点，不证明 wildcard 子域或未来稳定性。

## 10. 最终结论

1. GitHub 官方给出了足够的动态控制面：`GET /meta` 提供域名、IP类别、SSH keys；但官方明确说清单不完整且 IP会变化。
2. **DNS-only + 原始 authority验证**是所有 HTTPS服务的默认增强方式；PJZ110 还证明当前 API 路径应采用 IPv4 优先/强制 IPv4 回退，默认 10 轮仅 5/10，而 IPv4 为 10/10。
3. **no-SNI 只有逐 exact host 前瞻通过后才可使用**；虽然可以用系统 PKIX + 原始 hostname verification 安全实现，但本轮 GitHub regional IP 全部失败，因此当前 GitHub生产 allowlist应为空，不能照搬 Curator 策略。
4. GitHub 没有公布可泛化的 HTTPS compatible authority。CNAME/CDN/云厂商 hostname不能用于 SNI替换或 domain fronting。
5. `ssh.github.com:443` 是唯一本文确认的正式替代入口，但它只承载 SSH。
6. Releases、LFS、Actions、Packages 都会出现跨 authority 或签名 URL；这些 URL 必须原样访问、凭据按响应/操作边界重新绑定，绝不能将 GitHub PAT自动带到 storage/CDN。
7. Actions 只可能保证 GitHub官方控制面和制品域名，不可能保证 workflow 的任意第三方依赖。
8. 产品必须用版本化 profile 明确支持范围，而不能宣称“所有 GitHub 服务”，更不能自动接管任意未来、Enterprise或自定义域名。

## 主要一方来源

- [REST API endpoints for meta data](https://docs.github.com/en/rest/meta/meta)
- [About GitHub's IP addresses](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-githubs-ip-addresses)
- [Allowing access to GitHub's services from a restricted network](https://docs.github.com/en/get-started/using-github/allowing-access-to-githubs-services-from-a-restricted-network)
- [Self-hosted runners reference](https://docs.github.com/en/actions/reference/runners/self-hosted-runners)
- [About remote repositories](https://docs.github.com/en/get-started/git-basics/about-remote-repositories)
- [Using SSH over the HTTPS port](https://docs.github.com/en/authentication/troubleshooting-ssh/using-ssh-over-the-https-port)
- [GitHub's SSH key fingerprints](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/githubs-ssh-key-fingerprints)
- [Authorizing OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)
- [REST API endpoints for release assets](https://docs.github.com/en/rest/releases/assets)
- [Git LFS Batch API](https://github.com/git-lfs/git-lfs/blob/main/docs/api/batch.md)
- [Git LFS Basic Transfer API](https://github.com/git-lfs/git-lfs/blob/main/docs/api/basic-transfers.md)
- [Working with the Container registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
- [Working with the npm registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-npm-registry)
- [About anonymized URLs](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-anonymized-urls)

# wallpaperengineapi.com 兼容 TLS authority 研究

研究日期：2026-10-27（Asia/Shanghai）

## 结论

截至研究时点，互联网上没有找到 Wallpaper Engine、该域名运营方或托管商公开声明的备用/兼容 TLS authority。

实测只确认了一个很窄的当前兼容关系：`wallpaperengineapi.com`（apex）和 `www.wallpaperengineapi.com` 同时被当前证书 SAN 覆盖，并且在四个被动 DNS 来源给出的 IONOS 地址上交叉使用 SNI 与 HTTP Host 时都返回相同 API 内容。因此 apex 可以被视为 `www` 的“当前操作兼容 authority”。但是：

1. 没有一方资料承诺这种交叉承载是稳定接口；
2. 公开阻断观测把 apex、www 和 www API 都列为已阻断；
3. 两者属于同一域名、相同证书和相同后端，改用 apex SNI 没有可靠的绕过价值；
4. 没有发现第三个可安全采用且更可能可达的 authority。

所以生产建议是：**不要加入第三方兼容 authority；普通 SNI 或 apex SNI 都不能恢复 PJZ110 的连接。后续补充实验找到了一条不同路径：从 `doh.pub` 获得 `212.95.50.195`，直连该地址、ClientHello 不发送 SNI、握手后仍用系统 CA 和原始 hostname 验证证书，再发送原始 HTTP Host。** 这不是兼容 authority 或中继，而是严格认证的 no-SNI direct-origin transport。

## 用户补充的精确 HTTPS 测量与设备复核

用户随后提供了 ITDOG 对以下完整 API URL 的 HTTP 测试截图：

```text
https://www.wallpaperengineapi.com/api/explore/v1
https://wallpaperengineapi.com/api/explore/v1
```

两个测试各有约 318 个监测点，完成进度分别为 99% 和 100%。中国大陆地图几乎全部为红色（图例为 `>10s`），中国电信、中国联通、中国移动及华东、华南、华中、华北、西南、西北、东北各行均为 `--`；只有港澳台出现成功值。`www` 的港澳台最快为澳门约 0.706 秒、最慢为台湾约 1.002 秒、平均约 0.869 秒；apex 最快为澳门约 0.710 秒、最慢为台北约 1.291 秒、平均约 0.963 秒。

这比此前 Ping/TCP 图更有判别力：失败对象正是产品使用的 HTTPS API 路径，且 apex 与 www 在大陆均无可见成功节点。因此 apex 不是具有绕过价值的 authority。

为区分 DNS 污染与目标网段不可达，又在 PJZ110 上对四个已验证候选地址执行：

```bash
curl --resolve www.wallpaperengineapi.com:443:<REAL_IP> \
  https://www.wallpaperengineapi.com/api/explore/v1
```

四个地址均在 5 秒 connect timeout 内失败，`time_connect=0`、`time_appconnect=0`、HTTP code `000`，说明连接在 TCP 建立前就失败，尚未进入 TLS/SNI 阶段。该设备上仅用可信 DoH 替换 DNS、继续直连这组真实地址不能恢复服务。

该阶段的结论只适用于携带原始 SNI 的连接；后续 no-SNI 实验改变了产品决策，但没有改变“apex 不是兼容 authority 绕过方案”的判断。

## 后续 no-SNI 直连证据

对 `doh.pub` 返回的 `212.95.50.195`，普通 `www` SNI 在 ClientHello 后被 reset；改为 IP URL（因此不发送 SNI）并在加密 HTTP 中保留原始 Host 后，设备收到 HTTP 200、23,469 字节有效 JSON，SHA-256 为 `8943d3f2cdaee9825fc15ac430aa8bfe3052650951b74c53eac755708d6cf678`。服务器无 SNI 时呈现的公开证书链由 Let's Encrypt YE2 签发，SAN 同时覆盖 apex 与 www；离线 PKIX + hostname 验证对两个名称均通过，对 `example.com` 的负向控制失败。当前 SPKI pin 的命令行正向控制成功，错误 pin 被拒绝；生产实现没有固定该 pin，而是使用系统 TrustManager 加原始 hostname verifier。

Windows Release revision `0a72e551` 安装到 PJZ110 后，App 内实际 no-SNI transport 连续完成 70 次 `www` ETag 重验证，全部返回 304，无 repository failure、crash 或 ANR；耗时中位数 888 ms、P95 1,068 ms、最大 1,703 ms。命令行固定请求验收中 `www` 连续 30/30 HTTP 200；apex 在一次无间隔混合压力轮中为 28/30，随后按生产节奏间隔一秒重新执行，连续 30/30 HTTP 200，P95 3.517 秒、最大 3.767 秒。因此最终实现只生产请求 canonical `www`，不把 apex 作为冗余 fallback。

## 证据分级

### 已证事实：当前证书只明确覆盖 apex 与 www

对 apex 和 www 的实时 TLS 握手得到同一张证书：

```text
Subject: CN=wallpaperengineapi.com
SAN: DNS:wallpaperengineapi.com, DNS:www.wallpaperengineapi.com
Issuer: Let's Encrypt YE2
SHA-256 fingerprint:
9B:89:12:84:D4:3E:94:A0:58:40:E9:9F:FE:54:ED:40:
52:7A:97:E6:E8:76:B0:3A:1F:A9:D7:88:47:72:A7:57
```

[crt.sh 的公开 CT 查询](https://crt.sh/?q=%25.wallpaperengineapi.com&output=json) 共返回 32 条记录，具体名称只有：

- `wallpaperengineapi.com`
- `www.wallpaperengineapi.com`
- `*.wallpaperengineapi.com`（历史 wildcard 证书条目）

CT 中存在 wildcard 证书只能证明曾为通配名称签发证书，不能证明某个具体子域存在、被当前服务器承载或可作为兼容 authority。

### 已证事实：apex 与 www 当前交叉承载同一 API

在以下四个公开被动 DNS 来源列出的地址上进行了验证：

```text
217.160.82.111
217.160.83.85
217.160.80.80
217.160.81.60
```

来源参考：[Statvoo DNS/hosting observation](https://statvoo.com/wallpaperengineapi.com)。这是第三方观测，所以地址归属只作为候选发现，不作为信任证明；真正的兼容判断来自后续严格 TLS 和内容实测。

每个地址均测试了：

```text
SNI=wallpaperengineapi.com
Host=www.wallpaperengineapi.com
GET /api/explore/v1

SNI=www.wallpaperengineapi.com
Host=wallpaperengineapi.com
GET /api/explore/v1
```

八个交叉组合全部得到：

```text
HTTP/1.1 200 OK
body bytes = 23469
body SHA-256 prefix = 8943d3f2cdaee982
```

因为 TLS 客户端使用系统 CA 和 hostname verification，且证书 SAN 同时覆盖两个名称，这能证明 apex 与 www **在当前时点、当前四个后端地址上**操作兼容。

复现实验核心形式：

```bash
openssl s_client \
  -connect 217.160.82.111:443 \
  -servername wallpaperengineapi.com

# 在该 TLS 连接内发送：
GET /api/explore/v1 HTTP/1.1
Host: www.wallpaperengineapi.com
Accept: application/json
Connection: close
```

### 已证事实：没有验证出其他可用子域 authority

对以下猜测名称在候选后端地址上进行 SNI 探测：

```text
api.wallpaperengineapi.com
explore.wallpaperengineapi.com
cdn.wallpaperengineapi.com
static.wallpaperengineapi.com
```

前三个名称返回的证书只有：

```text
DNS:ssl875634.cloudflaressl.com
```

它不覆盖这些请求名称；`static` 未得到可用证书。因此这些名称都不能通过正常 hostname verification，不能用作兼容 authority。也没有官方资料声明它们存在。

### 已证事实：公开注册信息没有给出备用 authority

[Verisign RDAP](https://rdap.verisign.com/com/v1/domain/wallpaperengineapi.com) 显示：

- 注册时间：2017-12-14；
- Registrar handle：83（IONOS）；
- Nameserver：`ns1060.ui-dns.biz`、`ns1080.ui-dns.de`、`ns1085.ui-dns.org`、`ns1111.ui-dns.com`。

RDAP 没有公开注册人身份，也没有列出备用应用域名或 CDN authority。Nameserver/托管商相同不能证明其他托管域名可以承载该 API。

### 次级观察：apex 与 www 都被报告为阻断

[GreatFire 的域名页](https://en.greatfire.org/domain/wallpaperengineapi.com) 报告其能判断的四个 URL 全部被阻断，具体包含：

- `https://wallpaperengineapi.com`
- `https://www.wallpaperengineapi.com`
- `https://www.wallpaperengineapi.com/api/explore/v1`
- 历史 HTTP 3000 端口地址

页面称 2022-12-17 至 2026-10-03 共运行 37 次测试，并给出 DNS、timeout、TLS、reset 等标签。这是第三方测量而非服务所有者声明，但它与 PJZ110 上 www API 15 秒连接超时的设备实测一致。

因此，即使 apex 当前技术上能交叉承载 www Host，也没有证据表明它能提升目标网络中的可达性。

## 未找到的证据

没有找到以下任何一项：

- Wallpaper Engine 官方文档把 `wallpaperengineapi.com` 或其他域名列为 Discover API；
- Wallpaper Engine 开发者声明此域名的备用 authority；
- 域名运营方公布 CDN CNAME、备用 SNI 或灾备域名；
- 当前 DNS CNAME 指向可独立验证的 CDN hostname；
- 第三个名称同时满足证书覆盖、Host 交叉承载、内容一致和目标网络可达。

Wallpaper Engine 开发者只曾公开确认 Discover 会访问其内部服务器，并未公开域名或兼容 authority：[开发者事故说明](https://steamcommunity.com/app/431960/discussions/2/571540483751318117)。这不能反向证明当前外部域名的官方归属。

## 对 WallHub 的建议

### 可以做

1. 对固定 URL 使用普通路径优先；
2. 传输失败后用可信 DoH 获取真实 A/AAAA；
3. 连接 DoH 地址，但保持：
   - SNI=`www.wallpaperengineapi.com`
   - hostname verification=`www.wallpaperengineapi.com`
   - Host=`www.wallpaperengineapi.com`
4. 保留 LKG、ETag、严格 manifest parser 和 generic Steam fallback；
5. 将 apex 只作为实验候选：必须在当前网络、当前证书下重新验证 apex SAN，并验证交叉 Host 返回与原始 endpoint 一致；失败立即停用。

### 不应该做

- 不应采用 `api.`、`explore.`、`cdn.`、`static.` 等猜测子域；
- 不应把历史 wildcard 证书当作子域存在或兼容的证明；
- 不应使用共享 IP、默认 Cloudflare 证书或 IONOS 托管域名作为 authority；
- 不应关闭 hostname verification；
- 不应因为 apex/www 当前同内容就把关系视为永久契约；
- 不应期待 apex SNI 绕过阻断，因为公开测量显示 apex 与 www 均受影响。

## 最终判定

| 候选 | 证书覆盖 | 交叉 Host 实测 | 一方稳定承诺 | 目标网络绕过证据 | 判定 |
|---|---:|---:|---:|---:|---|
| `wallpaperengineapi.com`（apex） | 是 | 是，四地址全部一致 | 无 | 无，且被报告阻断 | 仅当前操作兼容，不建议作为正式绕过 authority |
| `www.wallpaperengineapi.com` | 是 | 是 | 无 | 原始 SNI 超时；认证 no-SNI 成功 | 保持原始 HTTP authority，以 no-SNI TLS 传输 |
| `api.wallpaperengineapi.com` | 当前后端证书不覆盖 | 否 | 无 | 无 | 禁止 |
| `explore.wallpaperengineapi.com` | 当前后端证书不覆盖 | 否 | 无 | 无 | 禁止 |
| `cdn.wallpaperengineapi.com` | 当前后端证书不覆盖 | 否 | 无 | 无 | 禁止 |
| 任意 IONOS/Cloudflare 共享 hostname | 无对应证明 | 无 | 无 | 无 | 禁止 |

简要结论：**网络上仍没有找到有实际绕过价值且证据充分的兼容 authority；有效方案不是更换 authority，而是使用经过 DoH 发现并通过 PKIX + 原始 hostname 认证的真实地址，在 ClientHello 中省略 SNI，再以 canonical `www` 作为加密后的 HTTP Host。**

# wallpaperengineapi.com 兼容 TLS authority 研究

研究日期：2026-10-27（Asia/Shanghai）

## 结论

截至研究时点，互联网上没有找到 Wallpaper Engine、该域名运营方或托管商公开声明的备用/兼容 TLS authority。

实测只确认了一个很窄的当前兼容关系：`wallpaperengineapi.com`（apex）和 `www.wallpaperengineapi.com` 同时被当前证书 SAN 覆盖，并且在四个被动 DNS 来源给出的 IONOS 地址上交叉使用 SNI 与 HTTP Host 时都返回相同 API 内容。因此 apex 可以被视为 `www` 的“当前操作兼容 authority”。但是：

1. 没有一方资料承诺这种交叉承载是稳定接口；
2. 公开阻断观测把 apex、www 和 www API 都列为已阻断；
3. 两者属于同一域名、相同证书和相同后端，改用 apex SNI 没有可靠的绕过价值；
4. 没有发现第三个可安全采用且更可能可达的 authority。

所以生产建议仍然是：**不要加入第三方兼容 authority；优先使用 DoH 得到真实 IP，但继续保持原始 `www.wallpaperengineapi.com` SNI、证书验证和 HTTP Host。** apex 仅可作为受运行时严格验证的实验候选，不能当成稳定的防阻断方案。

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
| `www.wallpaperengineapi.com` | 是 | 是 | 无 | PJZ110 超时 | 保持原始 authority |
| `api.wallpaperengineapi.com` | 当前后端证书不覆盖 | 否 | 无 | 无 | 禁止 |
| `explore.wallpaperengineapi.com` | 当前后端证书不覆盖 | 否 | 无 | 无 | 禁止 |
| `cdn.wallpaperengineapi.com` | 当前后端证书不覆盖 | 否 | 无 | 无 | 禁止 |
| 任意 IONOS/Cloudflare 共享 hostname | 无对应证明 | 无 | 无 | 无 | 禁止 |

简要结论：**网络上没有找到有实际绕过价值且证据充分的兼容 authority。apex 与 www 当前技术互兼容，但同样受阻断，不足以改变“真实 IP + 原始 www TLS 身份”的推荐方案。**

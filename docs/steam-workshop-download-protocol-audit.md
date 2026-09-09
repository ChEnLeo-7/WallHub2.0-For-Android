# Steam Workshop Download Protocol Audit

Date: 2026-09-09

## Purpose

This note validates WallHub for Android's Workshop download flow against primary and
high-trust upstream sources. It deliberately does not treat WallHub for Webview's
behavior as the protocol contract.

Evidence is classified as follows:

- **Valve contract:** Public Steamworks documentation.
- **Wire definition:** SteamDatabase's extracted Steam protobuf schemas. These are
  strong protocol evidence, but not a public Valve compatibility guarantee.
- **Reference implementation:** Current SteamKit2 and DepotDownloader behavior.
- **Inference:** A conclusion supported by the wire schema and reference
  implementations where Valve does not publicly document SteamPipe internals.

Pinned source revisions:

- SteamKit2 `2e9b82ace38e9aa17ee1b0411c6468f4815b07f3`
- DepotDownloader `c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af`
- SteamDatabase Protobufs `f740c7db620a9b7b82ed7e7c2e478d72d2fdfae1`

## Publicly Documented Boundary

Valve documents that modern Workshop items represent folders and that subscribed
content is downloaded and installed through Steam. Valve also documents the public
published-file details endpoint. Valve does not publicly document the complete
internal SteamPipe CDN protocol, including content-server selection, manifest
request codes, or CDN authorization challenges.

Sources:

- [Steam Workshop implementation guide](https://partner.steamgames.com/doc/features/workshop/implementation)
- [GetPublishedFileDetails](https://partner.steamgames.com/doc/webapi/ISteamRemoteStorage#GetPublishedFileDetails)
- [User authentication and ownership](https://partner.steamgames.com/doc/features/auth)

The detailed CDN flow below therefore uses pinned Steam wire definitions and the two
widely used SteamRE reference implementations.

## Validated Download Flow

The validated sequence is:

1. Resolve the Workshop published-file details.
2. If `file_url` is non-empty, download that URL directly.
3. Otherwise use `hcontent_file` as the content manifest ID.
4. Fetch app info for `consumer_app_id` and read `depots/workshopdepot`.
5. Verify access and request the depot decryption key using the actual Workshop
   depot ID and parent app ID.
6. Request the SteamPipe server list and retain eligible `SteamCache` and `CDN`
   entries allowed for the app.
7. Request a manifest request code for the app, Workshop depot, manifest, and
   branch.
8. Download the manifest using the server directory's advertised protocol, virtual
   host, and port, including a Steam-provided content proxy when applicable.
9. Treat an initial HTTP 403 as the upstream CDN authorization challenge. Request a
   token for the depot and the server's `Host`, then retry the same advertised
   transport.
10. Download each chunk, then decrypt, decompress, verify its length and checksum,
    and retry the complete fetch-and-verify operation against another eligible
    server when a recoverable failure occurs.

Reference flow:

- [Published-file handling](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/ContentDownloader.cs#L348-L397)
- [Workshop depot resolution](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/ContentDownloader.cs#L468-L501)
- [Depot key acquisition](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/ContentDownloader.cs#L602-L660)

## Identifier Semantics

The identifiers must remain distinct:

```text
appId      = published-file consumer_app_id
depotId    = appinfo["depots"]["workshopdepot"]
manifestId = published-file hcontent_file
```

`file_url` and `hcontent_file` are separate published-file fields. DepotDownloader
gives a non-empty `file_url` precedence and only enters SteamPipe when it needs the
manifest identified by `hcontent_file`.

Sources:

- [Published-file protobuf fields](https://github.com/SteamDatabase/Protobufs/blob/f740c7db620a9b7b82ed7e7c2e478d72d2fdfae1/steam/steammessages_publishedfile.steamclient.proto#L205-L219)
- [Depot key wire fields](https://github.com/SteamDatabase/Protobufs/blob/f740c7db620a9b7b82ed7e7c2e478d72d2fdfae1/steam/steammessages_clientserver_2.proto#L112-L120)
- [SteamKit2 depot-key request](https://github.com/SteamRE/SteamKit/blob/2e9b82ace38e9aa17ee1b0411c6468f4815b07f3/SteamKit2/SteamKit2/Steam/Handlers/SteamApps/SteamApps.cs#L85-L104)

WallHub currently assumes `depotId == appId` in
`SteamWorkshopContentApi.kt`. This happens to work for Wallpaper Engine today,
because its Workshop depot is currently 431960, but it is not the validated flow.

## Server Directory Semantics

The server directory response carries, among other fields, `type`, `host`, `vhost`,
`use_as_proxy`, `proxy_request_path_template`, `https_support`,
`allowed_app_ids`, `weighted_load`, and `num_entries_in_client_list`.

Sources:

- [Content-server protobuf](https://github.com/SteamDatabase/Protobufs/blob/f740c7db620a9b7b82ed7e7c2e478d72d2fdfae1/steam/steammessages_contentsystem.steamclient.proto#L18-L49)
- [DepotDownloader filtering and weighting](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/CDNClientPool.cs#L32-L63)

The reference filtering rule for ordinary content servers is:

```text
(allowed_app_ids is empty OR contains appId)
AND type is SteamCache or CDN
```

`Host` and `VHost` have different roles:

- `VHost` is the URI host and therefore the normal DNS, HTTP Host, and TLS SNI
  identity.
- `Host` is the server identity used for CDN-token requests and token cache keys.

Sources:

- [SteamKit2 server model](https://github.com/SteamRE/SteamKit/blob/2e9b82ace38e9aa17ee1b0411c6468f4815b07f3/SteamKit2/SteamKit2/Steam/CDN/Server.cs#L32-L97)
- [SteamKit2 CDN URI construction](https://github.com/SteamRE/SteamKit/blob/2e9b82ace38e9aa17ee1b0411c6468f4815b07f3/SteamKit2/SteamKit2/Steam/CDN/Client.cs#L330-L353)

## Protocol And Port

SteamKit2 maps the directory response as follows:

```text
https_support == "mandatory" -> HTTPS, port 443
otherwise                    -> HTTP, port 80
```

Source:

- [ContentServerDirectory conversion](https://github.com/SteamRE/SteamKit/blob/2e9b82ace38e9aa17ee1b0411c6468f4815b07f3/SteamKit2/SteamKit2/Steam/WebAPI/ContentServerDirectoryService.cs#L89-L119)

SteamKit2 and DepotDownloader do not first force HTTPS and then downgrade after a
failure. They use the protocol selected from the server directory. A TLS failure,
I/O failure, 401, or 403 does not rewrite an advertised HTTPS endpoint to HTTP.

This invalidates the current comment in `SteamContentTransfer.kt` that attributes
post-error HTTP fallback to official DepotDownloader behavior.

## Manifest Request Code

The manifest request-code request is keyed by app ID, depot ID, manifest ID, branch,
and optional branch-password hash. Its response contains a request code but no
expiry.

Sources:

- [Request-code protobuf](https://github.com/SteamDatabase/Protobufs/blob/f740c7db620a9b7b82ed7e7c2e478d72d2fdfae1/steam/steammessages_contentsystem.steamclient.proto#L74-L84)
- [SteamKit2 request](https://github.com/SteamRE/SteamKit/blob/2e9b82ace38e9aa17ee1b0411c6468f4815b07f3/SteamKit2/SteamKit2/Steam/Handlers/SteamContent/SteamContent.cs#L77-L114)
- [DepotDownloader request-code lifecycle](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/ContentDownloader.cs#L794-L847)

SteamKit2 notes that zero may represent denied access. DepotDownloader treats zero
as fatal after explicitly requesting a code and refreshes a nonzero code after five
minutes during long retries. Five minutes is a reference implementation policy, not
a server-declared expiry contract.

WallHub currently accepts zero and issues a shorter manifest URL. That should be
replaced with an explicit access/request-code failure.

## CDN Authorization Token

The token request is keyed by `app_id`, `depot_id`, and `host_name`; the response
contains a token and Unix expiration time.

Sources:

- [CDN-token protobuf](https://github.com/SteamDatabase/Protobufs/blob/f740c7db620a9b7b82ed7e7c2e478d72d2fdfae1/steam/steammessages_contentsystem.steamclient.proto#L86-L95)
- [SteamKit2 token API](https://github.com/SteamRE/SteamKit/blob/2e9b82ace38e9aa17ee1b0411c6468f4815b07f3/SteamKit2/SteamKit2/Steam/Handlers/SteamContent/SteamContent.cs#L18-L41)
- [DepotDownloader token key](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/Steam3Session.cs#L282-L304)

The correct token identity is at least `(depotId, lowercased Host)`, not `VHost`.
WallHub already uses `Host` and honors expiration with a safety margin, which is
sound. It should also invalidate or forcibly refresh a token rejected before its
nominal expiry.

The reference implementation does not forbid anonymous clients from requesting a
token. It lets Steam return the result. WallHub's client-side authenticated-session
restriction is unsupported by the wire contract or reference behavior.

## Authorization Challenge Order

DepotDownloader's current behavior is asymmetric:

- Initial 403 without a token entry: request a CDN token and retry the same server.
- 403 after token acquisition: mark the server broken and stop that manifest/chunk
  attempt.
- 401: do not request a token; treat it as terminal authorization rejection for the
  current operation.
- Neither status changes transport.

Sources:

- [Manifest status handling](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/ContentDownloader.cs#L851-L890)
- [Chunk status handling](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/ContentDownloader.cs#L1278-L1316)

WallHub currently processes 401/403 as HTTPS-to-HTTP fallback eligibility before it
processes authentication. This can send a CDN query token over cleartext HTTP and
materially diverges from the reference flow.

## Retry Semantics

DepotDownloader uses a weighted server pool. A broken return advances its current
pool index, although it does not fully quarantine the host and may encounter a
weighted duplicate. Its penalty update remains a TODO.

Source:

- [CDNClientPool connection handling](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/CDNClientPool.cs#L66-L91)

Upstream behavior is not perfect and should not be copied blindly:

- A normal full-download manifest timeout can retry the same pool entry because its
  timeout branch does not return it as broken.
- Retry loops are not consistently bounded.
- Broken weighted entries are not completely quarantined.

Android may retain bounded retries, cooldowns, and adaptive throughput ranking. It
must, however, keep authorization failures separate from transport failures and must
not alter the server-advertised protocol during recovery.

Chunk success includes HTTP transfer, content-length validation, decryption,
decompression, and checksum processing. A failure in any of those stages should
retry the complete chunk against another eligible server.

Source:

- [SteamKit2 chunk processing](https://github.com/SteamRE/SteamKit/blob/2e9b82ace38e9aa17ee1b0411c6468f4815b07f3/SteamKit2/SteamKit2/Steam/CDN/Client.cs#L255-L312)

## Authentication And Ownership

Public Workshop visibility does not guarantee anonymous access to its depot.
DepotDownloader can use anonymous access where the anonymous package or app metadata
allows it, but authenticated ownership is the reliable path for paid applications.
Access is ultimately demonstrated by successful depot-key and request-code results.

Sources:

- [DepotDownloader anonymous usage](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/README.md#L35-L46)
- [Depot access checks](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/DepotDownloader/ContentDownloader.cs#L120-L155)
- [Old-manifest limitation](https://github.com/SteamRE/DepotDownloader/blob/c124842e86494ef3c9f7e66f2b6e4c2e3e18e6af/README.md#L124-L127)

## Confirmed Android Gaps

### Must Fix

1. Resolve and model `workshopdepot` from app info. Stop deriving `depotId` from
   `appId`.
2. Implement `file_url` as the first-choice formal-download path, matching published
   file semantics.
3. Filter normal server entries by app eligibility and supported server type.
4. Honor the server directory's protocol and port from the first request.
5. Remove process-global post-error HTTPS-to-HTTP fallback and its misleading
   DepotDownloader-parity comment.
6. Process an initial 403 as authorization before any server rotation. Do not process
   401 as a token challenge or transport fallback.
7. Never send a CDN query token over a transport that differs from the directory's
   advertised transport.
8. Allow the active anonymous or authenticated Steam session to request a CDN token;
   let Steam decide access.
9. Treat a zero manifest request code as an explicit access failure. Refresh a code
   during long retries using a bounded lifetime policy.
10. Honor a Steam content proxy's advertised protocol, VHost, port, and path
    template instead of forcing HTTPS.
11. Retry fetch, decrypt, decompress, length validation, and checksum validation as
    one chunk operation.
12. Verify the parsed manifest's depot ID and manifest GID against the request.

### Should Fix

1. Add token invalidation and one forced refresh for a rejected unexpired token,
   while retaining expiry-based caching by `(depotId, Host)`.
2. Distinguish user-facing failures for depot-key denial, request-code denial, initial
   authorization challenge failure, rejected token, missing eligible servers, and
   transport exhaustion.
3. Refresh the server directory and request code between bounded outer attempts.
4. Make recovery logs truthful: current retries reacquire access but do not rebuild
   the underlying authenticated CM engine.
5. Give streaming recovery a fresh access snapshot instead of indefinitely reusing
   its original server list and token provider.
6. Model Steam identifiers as unsigned 64-bit values at protocol boundaries, or use
   validated decimal strings where Kotlin/JSON libraries cannot safely represent
   them.

### May Retain As Android Policy

- Bounded retries rather than DepotDownloader's partly unbounded loops.
- Adaptive throughput ranking and temporary host cooldowns.
- Partial-file resume after verified chunk boundaries.
- Conservative timeout budgets suitable for WorkManager and mobile lifecycle.

These policies must be documented as WallHub behavior, not as exact
DepotDownloader behavior.

## Required Test Matrix Before Deployment

Unit and integration seams should cover:

1. Distinct app, Workshop depot, and manifest IDs.
2. Direct `file_url` precedence.
3. Server filtering by type and `allowed_app_ids`.
4. Advertised HTTP/80 and HTTPS/443 URL construction without post-error rewrite.
5. Initial 403, then token acquisition, then same-server retry.
6. 401 without token acquisition.
7. Repeated 403 after token acquisition.
8. Zero and expired manifest request codes.
9. Expired and rejected CDN tokens.
10. Steam proxy protocol and path-template rewriting.
11. Timeout/TLS/I/O rotation to another eligible server without protocol change.
12. Chunk decryption, decompression, length, and checksum failure rotation.
13. Parsed manifest depot/GID mismatch rejection.
14. Authenticated-owner, anonymous-allowed, and anonymous-denied sessions.

Device validation should use the same Workshop IDs on the same network and capture,
without secrets:

- app ID, Workshop depot ID, and manifest ID;
- server type, `Host`, `VHost`, protocol, port, and resolved IP;
- manifest request-code generation timestamp, but not its value;
- token presence, refresh reason, and expiry, but not token contents;
- HTTP status or transport exception;
- whether the server list changed between outer attempts.

## Decision

The current Android CDN workaround should not be extended by adding more ad hoc
token retries to `clngaa`. The correct next change is to restore server-directory
semantics and authorization ordering first. Only after that protocol correction
should device tests determine whether additional Android-specific server rotation or
DNS/IP-family policy is needed.

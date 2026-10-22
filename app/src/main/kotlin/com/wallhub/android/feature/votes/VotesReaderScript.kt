package com.wallhub.android.feature.votes

import org.json.JSONObject

/**
 * Only app-owned script is evaluated; response HTML is parsed in an inert document, never inserted.
 * Identity schema observed 2026-10-22 in public official help modules (no authenticated capture):
 * https://help.steampowered.com/zh-cn/public/javascript/applications/help/main.js?contenthash=Gj1oORqk
 * SHA-256 5a8f6ab59a67b8c5ee3548513f648860654a1d0ecc87871a8c85d2df15e63594
 * imports Nt as te and uses te.steamid as active account; Nt exports UserConfig in:
 * https://help.steampowered.com/zh-cn/public/javascript/applications/help/storenaveventcontext.js?contenthash=B0hquxXn
 * SHA-256 72dad8e70312a0b1c86367e48fbc6136b0da320e3793a16cfaa0a6b2922e13b9
 * InitConfig/GetConfigJSON load data-userinfo; UserConfig defines logged_in and string steamid.
 */
internal object VotesReaderScript {
    fun start(resultKey: String, cursor: String?, expectedSteamId: String): String {
        val key = JSONObject.quote(resultKey)
        val continuation = cursor?.let(JSONObject::quote) ?: "null"
        return """
            (() => {
              'use strict';
              const key = $key;
              const cursor = $continuation;
              const put = x => { window[key] = JSON.stringify(x); };
              const fail = error => { throw { votesError: error }; };
              const trusted = () => {
                if (window !== window.top || location.protocol !== 'https:' ||
                    location.hostname !== 'help.steampowered.com' || location.port && location.port !== '443' ||
                    !/^\/[a-z]{2}(?:-[a-z]{2,4})?\/accountdata\/MyUGCVotes\/?$/.test(location.pathname) ||
                    location.search || location.hash) fail('UNTRUSTED_PAGE');
              };
              const accounts = () => {
                const ids = [];
                // Public help main.js imports UserConfig.steamid; storenaveventcontext.js
                // InitConfig loads UserConfig from the element's data-userinfo JSON.
                // Read only logged_in + steamid here; never access help_user_config or any token field.
                const configs = document.querySelectorAll('#application_config[data-userinfo]');
                if (configs.length > 1) fail('ACCOUNT_UNVERIFIED');
                if (configs.length === 1) {
                  const raw = configs[0].getAttribute('data-userinfo');
                  if (!raw || raw.length > 16384) fail('ACCOUNT_UNVERIFIED');
                  let user;
                  try { user = JSON.parse(raw); } catch (_) { fail('ACCOUNT_UNVERIFIED'); }
                  if (!user || user.logged_in !== true || typeof user.steamid !== 'string' ||
                      !/^[1-9][0-9]{16}$/.test(user.steamid)) fail('ACCOUNT_UNVERIFIED');
                  ids.push(user.steamid);
                }
                if (!ids.length || ids.length > 8) fail('ACCOUNT_UNVERIFIED');
                return ids;
              };
              const rows = (doc, first) => {
                const table = doc.querySelector('#AccountDataTable_1');
                if (!table) fail('INVALID_PAGE');
                if (first) {
                  const headers = table.querySelectorAll('tr:first-child th, tr:first-child td');
                  if (headers.length !== 4 || headers[0].textContent.trim() !== 'FileID' ||
                      headers[1].textContent.trim() !== '点赞' || headers[2].textContent.trim() !== '取消点赞')
                    fail('INVALID_PAGE');
                }
                const out = [];
                for (const tr of table.querySelectorAll('tr')) {
                  const cells = Array.from(tr.children).filter(c => c.tagName === 'TD');
                  if (!cells.length || first && cells[0].textContent.trim() === 'FileID') continue;
                  if (cells.length !== 4) fail('INVALID_PAGE');
                  if (out.length >= 200) fail('LIMIT_EXCEEDED');
                  out.push(cells.slice(0, 3).map(c => {
                    const v = c.textContent.trim();
                    if (v.length > 32) fail('INVALID_PAGE');
                    return v;
                  }));
                }
                return out;
              };
              const extract = async () => {
                trusted();
                const identity = accounts();
                if (identity.some(id => id !== ${JSONObject.quote(expectedSteamId)})) fail('ACCOUNT_MISMATCH');
                if (cursor === null) {
                  // Bound the input projection before touching the potentially large table.
                  const html = document.documentElement.outerHTML;
                  if (html.length > 2097152 || new TextEncoder().encode(html).byteLength > 2097152)
                    fail('LIMIT_EXCEEDED');
                  const buttons = document.querySelectorAll('span.AccountDataLoadMore[data-tableid="AccountDataTable_1"]');
                  if (buttons.length > 1) fail('INVALID_PAGE');
                  const next = buttons.length ? buttons[0].getAttribute('data-continuevalue') : null;
                  if (next !== null && next.length > 8192) fail('LIMIT_EXCEEDED');
                  return { accounts: identity, rows: rows(document, true), cursor: next };
                }
                const endpoint = new URL(location.pathname.replace(/MyUGCVotes\/?$/, 'AjaxLoadMoreData/'), location.origin);
                endpoint.searchParams.set('url', 'MyUGCVotes');
                endpoint.searchParams.set('continue', cursor);
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), 20000);
                try {
                  const response = await fetch(endpoint.href, { credentials: 'same-origin', mode: 'same-origin',
                    redirect: 'error', signal: controller.signal, headers: {
                      'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest'
                    } });
                  if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) fail('NETWORK');
                  const reader = response.body.getReader();
                  const decoder = new TextDecoder('utf-8', { fatal: true });
                  let size = 0, text = '';
                  while (true) {
                    const part = await reader.read();
                    if (part.done) break;
                    size += part.value.byteLength;
                    if (size > 2097152) { await reader.cancel(); fail('LIMIT_EXCEEDED'); }
                    text += decoder.decode(part.value, { stream: true });
                  }
                  text += decoder.decode();
                  const body = JSON.parse(text);
                  if (!body || typeof body.html !== 'string' ||
                      body.continue != null && typeof body.continue !== 'string') fail('INVALID_PAGE');
                  if (body.continue && body.continue.length > 8192) fail('LIMIT_EXCEEDED');
                  // Inert parsing: never append to live DOM or evaluate response script/event handlers.
                  const doc = new DOMParser().parseFromString(
                    '<table id="AccountDataTable_1"><tbody>' + body.html + '</tbody></table>', 'text/html');
                  trusted();
                  const after = accounts();
                  if (JSON.stringify(after) !== JSON.stringify(identity)) fail('ACCOUNT_MISMATCH');
                  return { accounts: after, rows: rows(doc, false), cursor: body.continue ?? null };
                } finally { clearTimeout(timer); }
              };
              window[key] = null;
              extract().then(put).catch(e => put({ error: e?.votesError || 'NETWORK' }));
              return null;
            })()
        """.trimIndent()
    }

    fun poll(resultKey: String): String = """
        (() => { const k = ${JSONObject.quote(resultKey)}; const r = window[k];
          if (typeof r !== 'string') return null; delete window[k]; return r; })()
    """.trimIndent()
}

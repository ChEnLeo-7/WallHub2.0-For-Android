// Synthetic JavaScript behavior only; does not establish Android, WebView or authenticated Steam support.
// Run with Node from any directory; reads the owned Kotlin JS template, performs no network requests.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname,
    '../../../../../../../main/kotlin/com/wallhub/android/feature/votes/VotesReaderScript.kt'), 'utf8');
const account = '76561198000000001';
const match = source.match(/return """\n([\s\S]*?)\n\s*"""\.trimIndent\(\)/);
assert.ok(match, 'Owned extraction template must exist');
const script = match[1].replaceAll('$key', JSON.stringify('result'))
    .replaceAll('$continuation', 'null')
    .replaceAll('${JSONObject.quote(expectedSteamId)}', JSON.stringify(account));

async function extract(configs) {
    let touchedTable = false;
    const table = {
        querySelectorAll(selector) {
            if (selector.startsWith('tr:first-child')) {
                return ['FileID', '点赞', '取消点赞', '投票时间'].map(textContent => ({ textContent }));
            }
            return [{ children: ['123', '1', '0', 'ignored'].map(textContent => ({ tagName: 'TD', textContent })) }];
        },
    };
    const document = {
        documentElement: { outerHTML: '<html>sanitized synthetic document</html>' },
        querySelectorAll(selector) {
            if (selector === '#application_config[data-userinfo]') {
                return configs.map(value => ({ getAttribute: () => value }));
            }
            if (selector.startsWith('span.AccountDataLoadMore')) return [];
            throw new Error('Unexpected selector');
        },
        querySelector(selector) {
            assert.equal(selector, '#AccountDataTable_1');
            touchedTable = true;
            return table;
        },
    };
    const window = {};
    window.top = window;
    const context = vm.createContext({ window, document, TextEncoder,
        location: { protocol: 'https:', hostname: 'help.steampowered.com', port: '',
            pathname: '/zh-cn/accountdata/MyUGCVotes', search: '', hash: '' },
    });
    vm.runInContext(script, context, { timeout: 1000 });
    // Extraction is promise-based even for the first page.
    await new Promise(resolve => setImmediate(resolve));
    return { result: JSON.parse(window.result), touchedTable };
}

(async () => {
    const good = await extract([JSON.stringify({ logged_in: true, steamid: account })]);
    assert.deepEqual(good.result, { accounts: [account], rows: [['123', '1', '0']], cursor: null });
    assert.equal(good.touchedTable, true);
    for (const configs of [[], ['invalid JSON'], ['{}'], [JSON.stringify({ logged_in: false, steamid: account })],
        [JSON.stringify({ logged_in: true, steamid: Number(account) })],
        [JSON.stringify({ logged_in: true, steamid: 'not-an-id' })]]) {
        const observed = await extract(configs);
        assert.equal(observed.result.error, 'ACCOUNT_UNVERIFIED');
        assert.equal(observed.touchedTable, false);
    }
    const mismatch = await extract([JSON.stringify({ logged_in: true, steamid: '76561198000000002' })]);
    assert.equal(mismatch.result.error, 'ACCOUNT_MISMATCH');
    assert.equal(mismatch.touchedTable, false);
    console.log('PASS: synthetic first-page identity proof gates row extraction (8 cases)');
})().catch(error => { console.error(error); process.exitCode = 1; });

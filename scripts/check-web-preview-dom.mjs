#!/usr/bin/env node

const cdpPort = process.env.WALLHUB_CDP_PORT ?? '9222';
const previewOrigin = process.env.WALLHUB_WEB_ORIGIN ?? 'http://192.168.2.155:4180';
const pages = await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json();
const target = pages.find((page) => page.url.startsWith(previewOrigin));
if (!target) throw new Error(`No Chrome page starts with ${previewOrigin}`);

const socket = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.onopen = resolve;
  socket.onerror = reject;
});
let nextId = 0;
const pending = new Map();
socket.onmessage = (event) => {
  const message = JSON.parse(event.data);
  const request = pending.get(message.id);
  if (!request) return;
  pending.delete(message.id);
  if (message.error) request.reject(new Error(JSON.stringify(message.error)));
  else request.resolve(message.result);
};
const call = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId;
  pending.set(id, {resolve, reject});
  socket.send(JSON.stringify({id, method, params}));
});
const evaluate = async (expression) => {
  const result = await call('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
  return result.result.value;
};

await evaluate(`localStorage.removeItem('wallhub.preview.fake-backend'); true`);
await call('Page.navigate', {url: `${previewOrigin}/?acceptance=dom&lab=1`});
await new Promise((resolve) => setTimeout(resolve, 1200));
if (!(await evaluate(`Boolean(customElements.get('md-filled-button'))`))) {
  throw new Error('Material Web components are not registered');
}

const expected = {
  599: {windowClass: 'compact', navWidth: 599, marginLeft: 0, navHeight: 80},
  600: {windowClass: 'medium', navWidth: 80, marginLeft: 80},
  839: {windowClass: 'medium', navWidth: 80, marginLeft: 80},
  840: {windowClass: 'expanded', navWidth: 240, marginLeft: 240},
};
for (const width of [599, 600, 839, 840]) {
  await call('Emulation.setDeviceMetricsOverride', {
    width,
    height: 900,
    screenWidth: width,
    screenHeight: 900,
    deviceScaleFactor: 1,
    mobile: true,
  });
  await evaluate(`dispatchEvent(new Event('resize')); true`);
  await new Promise((resolve) => setTimeout(resolve, 250));
  const actual = await evaluate(`(() => {
    const nav = document.querySelector('.primary-nav').getBoundingClientRect();
    return {
      windowClass: document.documentElement.dataset.windowClass,
      navWidth: Math.round(nav.width),
      navHeight: Math.round(nav.height),
      marginLeft: Math.round(parseFloat(getComputedStyle(document.querySelector('main')).marginLeft)),
    };
  })()`);
  for (const [key, value] of Object.entries(expected[width])) {
    if (actual[key] !== value) throw new Error(`${width}px ${key}: ${actual[key]} != ${value}`);
  }
}
await call('Emulation.clearDeviceMetricsOverride');
await call('Page.reload', {ignoreCache: true});
await new Promise((resolve) => setTimeout(resolve, 700));

await call('Emulation.setEmulatedMedia', {
  features: [{name: 'prefers-reduced-motion', value: 'reduce'}],
});
if (!(await evaluate(`matchMedia('(prefers-reduced-motion: reduce)').matches`))) {
  throw new Error('Reduced-motion media preference was not observed');
}
await call('Emulation.setEmulatedMedia', {features: []});

await evaluate(`document.querySelector('#focus-dialog').close(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
await evaluate(`document.querySelector('#dialog-button').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
const focusState = `(() => {
  const active = document.activeElement;
  return {
    tag: active?.tagName,
    text: (active?.textContent ?? '').trim(),
    insideDialog: Boolean(active?.closest('md-dialog')),
  };
})()`;
const focusCycle = [];
for (let index = 0; index < 6; index += 1) {
  focusCycle.push(await evaluate(focusState));
  await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9});
  await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9});
  await new Promise((resolve) => setTimeout(resolve, 60));
}
if (!focusCycle.every((item) =>
  item.insideDialog && item.tag === 'MD-TEXT-BUTTON' && ['取消', '确认'].includes(item.text)
)) {
  throw new Error(`Dialog focus escaped: ${JSON.stringify(focusCycle)}`);
}
if (new Set(focusCycle.map((item) => item.text)).size !== 2) {
  throw new Error(`Dialog focus did not reach both actions: ${JSON.stringify(focusCycle)}`);
}
await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await new Promise((resolve) => setTimeout(resolve, 400));
const dialogResult = await evaluate(`({
  closed: !document.querySelector('#focus-dialog').open,
  restored: document.activeElement?.id === 'dialog-button',
})`);
if (!dialogResult.closed || !dialogResult.restored) {
  throw new Error(`Dialog close/focus restore failed: ${JSON.stringify(dialogResult)}`);
}

await evaluate(`localStorage.setItem('wallhub.preview.dom.theme', 'dark'); location.reload(); true`);
await new Promise((resolve) => setTimeout(resolve, 900));
await evaluate(`document.querySelector('#theme-toggle').focus(); document.querySelector('#theme-toggle').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
const themeResult = await evaluate(`({theme: document.documentElement.dataset.theme, focus: document.activeElement?.id})`);
if (themeResult.theme !== 'light' || themeResult.focus !== 'theme-toggle') {
  throw new Error(`Theme switch/focus restoration failed: ${JSON.stringify(themeResult)}`);
}
await call('Page.reload', {ignoreCache: true});
await new Promise((resolve) => setTimeout(resolve, 900));
if ((await evaluate(`document.documentElement.dataset.theme`)) !== 'light') {
  throw new Error('Theme did not survive reload');
}
await evaluate(`document.querySelector('[data-route="/discover"]').click(); true`);
const pushedRoute = await evaluate(`({path: location.pathname, lab: Boolean(document.querySelector('.state-lab')), dialogTrigger: Boolean(document.querySelector('#dialog-button'))})`);
if (pushedRoute.path !== '/discover' || pushedRoute.lab || pushedRoute.dialogTrigger) {
  throw new Error(`pushState/lab visibility failed: ${JSON.stringify(pushedRoute)}`);
}
await evaluate(`history.back(); true`);
await new Promise((resolve) => setTimeout(resolve, 250));
if ((await evaluate(`location.pathname`)) !== '/') throw new Error('popstate/back failed');

await call('Page.navigate', {url: `${previewOrigin}/?lab=1&scenario=loading`});
await new Promise((resolve) => setTimeout(resolve, 500));
if (!(await evaluate(`Boolean(document.querySelector('.state-lab') && document.querySelector('md-circular-progress'))`))) {
  throw new Error('State lab loading scenario did not render');
}
await call('Page.navigate', {url: `${previewOrigin}/`});
await new Promise((resolve) => setTimeout(resolve, 300));
if (!(await evaluate(`!document.querySelector('.state-lab') && document.querySelectorAll('.workshop-card').length === 6`))) {
  throw new Error('State lab scenario leaked into the normal Home surface');
}
await call('Page.navigate', {url: `${previewOrigin}/?lab=1&scenario=loading`});
await new Promise((resolve) => setTimeout(resolve, 300));
await evaluate(`document.querySelector('[data-route="/discover"]').click(); true`);
await evaluate(`history.back(); true`);
await new Promise((resolve) => setTimeout(resolve, 300));
if (!(await evaluate(`location.search.includes('scenario=loading') && document.querySelector('#scenario-select')?.value === 'loading' && Boolean(document.querySelector('md-circular-progress'))`))) {
  throw new Error('Browser Back did not reconcile the State Lab scenario');
}
await evaluate(`(() => {
  const select = document.querySelector('#scenario-select');
  select.value = 'empty';
  select.dispatchEvent(new Event('change', {bubbles: true}));
  return true;
})()`);
await new Promise((resolve) => setTimeout(resolve, 200));
if (!(await evaluate(`location.search.includes('scenario=empty') && document.querySelector('.center-state h2')?.textContent === '没有匹配的壁纸'`))) {
  throw new Error('State lab scenario URL/render synchronization failed');
}
await evaluate(`document.querySelector('#reset-lab').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 200));
if (!(await evaluate(`document.querySelectorAll('.workshop-card').length === 6 && !location.search.includes('scenario=')`))) {
  throw new Error('State lab reset did not restore deterministic content');
}
await evaluate(`document.querySelector('#filter-button').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 250));
if (!(await evaluate(`document.querySelector('#filter-dialog').open`))) throw new Error('Filter dialog did not open');
await evaluate(`document.querySelector('#filter-type').value = 'VIDEO'; document.querySelector('#apply-filters').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 250));
const filterResult = await evaluate(`({cards: document.querySelectorAll('.workshop-card').length, focus: document.activeElement?.id, dialogOpen: document.querySelector('#filter-dialog').open})`);
if (filterResult.cards !== 2 || filterResult.focus !== 'filter-button' || filterResult.dialogOpen) {
  throw new Error(`Filter application or focus restoration failed: ${JSON.stringify(filterResult)}`);
}
const favoriteState = await evaluate(`({id: document.querySelector('[data-favorite]').dataset.favorite, text: document.querySelector('[data-favorite]').textContent.trim()})`);
await evaluate(`document.querySelector('[data-favorite]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.activeElement?.dataset.favorite === ${JSON.stringify(favoriteState.id)} && document.activeElement?.textContent.trim() !== ${JSON.stringify(favoriteState.text)}`))) {
  throw new Error(`Mutation focus was not restored to the updated favorite action: ${JSON.stringify(await evaluate(`({id: document.activeElement?.dataset.favorite, pressed: document.activeElement?.getAttribute('aria-pressed'), text: document.activeElement?.textContent})`))}`);
}
await evaluate(`document.querySelector('#reset-lab').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-open-workshop="3357929793"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`location.pathname === '/workshop/3357929793' && Boolean(document.querySelector('.detail-overview')) && document.querySelector('.primary-nav').hidden`))) {
  throw new Error('Home card did not open deterministic Workshop Detail');
}
await evaluate(`document.querySelector('.play-button').click(); true`);
if (!(await evaluate(`document.querySelector('.play-button').getAttribute('aria-pressed') === 'true'`))) {
  throw new Error('Detail video preview control did not toggle');
}
await evaluate(`document.querySelector('[data-detail-tab="comments"]').click(); true`);
if (!(await evaluate(`location.search.includes('tab=comments') && document.querySelectorAll('.comment').length === 2 && document.activeElement?.dataset.detailTab === 'comments'`))) {
  throw new Error('Workshop Detail comments tab did not render with restored focus');
}
await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowLeft', code: 'ArrowLeft', windowsVirtualKeyCode: 37});
await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'ArrowLeft', code: 'ArrowLeft', windowsVirtualKeyCode: 37});
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`!location.search.includes('tab=comments') && document.activeElement?.dataset.detailTab === 'details' && document.querySelector('[data-detail-tab="details"]').getAttribute('aria-selected') === 'true'`))) {
  throw new Error('Workshop Detail tabs did not support ArrowLeft navigation');
}
await evaluate(`document.querySelector('[data-detail-tab="comments"]').click(); true`);
await evaluate(`document.querySelector('[data-subscribe]').focus(); document.querySelector('[data-subscribe]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.activeElement?.dataset.subscribe === '3357929793' && document.activeElement?.getAttribute('aria-pressed') === 'true'`))) {
  throw new Error('Workshop subscription state/focus did not update');
}
await evaluate(`document.querySelector('#detail-download').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`document.querySelector('#download-dialog').open`))) throw new Error('Download format dialog did not open');
await evaluate(`document.querySelector('[data-download-format="MPKG"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
const downloadResult = await evaluate(`({open: document.querySelector('#download-dialog').open, downloads: JSON.parse(localStorage.getItem('wallhub.preview.fake-backend')).downloads})`);
downloadResult.focus = await evaluate(`document.activeElement?.id`);
if (downloadResult.open || downloadResult.downloads.length !== 1 || downloadResult.downloads[0].format !== 'MPKG' || downloadResult.focus !== 'detail-download') {
  throw new Error(`Download request did not persist: ${JSON.stringify(downloadResult)}`);
}
await call('Page.navigate', {url: `${previewOrigin}/workshop/3357929793?lab=1&scenario=error`});
await new Promise((resolve) => setTimeout(resolve, 250));
await evaluate(`document.querySelector('#retry-detail').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`!location.search.includes('scenario=') && Boolean(document.querySelector('.detail-overview'))`))) {
  throw new Error('Detail retry did not clear the URL-driven error state');
}
const directHistoryLength = await evaluate(`history.replaceState(null, '', location.href); history.length`);
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
const directBack = await evaluate(`({path: location.pathname, length: history.length, route: history.state?.route})`);
if (directBack.path !== '/' || directBack.length !== directHistoryLength || directBack.route !== '/') {
  throw new Error(`Direct-entry Detail Back did not replace with Home: ${JSON.stringify(directBack)}`);
}
await evaluate(`document.querySelector('[data-route="/discover"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/discover' && document.querySelectorAll('.discover-rail').length === 3`))) {
  throw new Error('Discover did not render deterministic rails');
}
await evaluate(`document.querySelector('.discover-content-menu summary').focus(); document.querySelector('.discover-content-menu summary').click(); true`);
await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
if (!(await evaluate(`!document.querySelector('.discover-content-menu').open && document.activeElement?.matches('.discover-content-menu summary')`))) {
  throw new Error('Discover content menu did not close and restore focus on Escape');
}
await evaluate(`document.querySelector('[data-rail-rating="dislike"][data-rail="audio-responsive"]').focus(); document.querySelector('[data-rail-rating="dislike"][data-rail="audio-responsive"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`!document.querySelector('[data-rail-id="audio-responsive"]') && document.activeElement?.dataset.railId === 'creator-showcase' && document.querySelector('#discover-status').textContent.includes('音频响应')`))) {
  throw new Error('Discover rail suppression did not preserve focus and announce removal');
}
await evaluate(`document.querySelector('[data-rail-favorite="trending-month"]').focus(); document.querySelector('[data-rail-favorite="trending-month"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.activeElement?.dataset.railFavorite === 'trending-month' && document.activeElement?.getAttribute('aria-pressed') === 'true'`))) {
  throw new Error('Discover feedback did not persist with focus');
}
await evaluate(`document.querySelector('[data-open-rail="trending-month"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/discover/results/trending-month' && document.querySelectorAll('.workshop-card').length === 3 && document.querySelector('.primary-nav').hidden`))) {
  throw new Error('Discover rail did not open deterministic results');
}
for (const width of [599, 600, 839, 840]) {
  await call('Emulation.setDeviceMetricsOverride', {width, height: 900, screenWidth: width, screenHeight: 900, deviceScaleFactor: 1, mobile: true});
  await evaluate(`dispatchEvent(new Event('resize')); true`);
  await new Promise((resolve) => setTimeout(resolve, 100));
  const geometry = await evaluate(`({columns: getComputedStyle(document.querySelector('.discover-results-grid')).gridTemplateColumns.split(' ').length, marginLeft: Math.round(parseFloat(getComputedStyle(document.querySelector('main')).marginLeft)), navHidden: document.querySelector('.primary-nav').hidden})`);
  if (geometry.columns !== 2 || geometry.marginLeft !== 0 || !geometry.navHidden) throw new Error(`${width}px Discover results geometry failed: ${JSON.stringify(geometry)}`);
}
await call('Emulation.clearDeviceMetricsOverride');
await evaluate(`dispatchEvent(new Event('resize')); document.querySelector('#discover-result-columns').focus(); document.querySelector('#discover-result-columns').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.activeElement?.id === 'discover-result-columns' && document.querySelector('.discover-results-grid').classList.contains('columns-1') && getComputedStyle(document.querySelector('.discover-results-grid')).gridTemplateColumns.split(' ').length === 1`))) {
  throw new Error('Discover result columns did not persist with focus');
}
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/discover'`))) throw new Error('Discover results Back did not restore feed');
await evaluate(`document.querySelector('[data-route="/profile"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/profile' && document.querySelector('.profile-identity h2').textContent === 'Astra' && document.querySelectorAll('.profile-stats button').length === 2`))) {
  throw new Error('Profile did not render deterministic signed-in state');
}
await call('Page.navigate', {url: `${previewOrigin}/profile?lab=1&scenario=loading`});
await new Promise((resolve) => setTimeout(resolve, 200));
if (!(await evaluate(`document.querySelector('.profile-identity').textContent.includes('正在恢复') && [...document.querySelectorAll('.profile-stats button')].every((button) => button.disabled)`))) {
  throw new Error('Profile restoring state did not disable collection actions');
}
await call('Page.navigate', {url: `${previewOrigin}/profile?lab=1&scenario=empty`});
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`Boolean(document.querySelector('#profile-login')) && document.querySelector('.profile-identity h2').textContent === '匿名用户'`))) {
  throw new Error('Profile signed-out state did not render');
}
await call('Page.navigate', {url: `${previewOrigin}/profile?lab=1&scenario=error`});
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`document.querySelector('[data-profile-collection="favorites"]').textContent.includes('18')`))) throw new Error('Profile partial failure did not preserve loaded sibling');
await evaluate(`document.querySelector('#retry-profile-counts').focus(); document.querySelector('#retry-profile-counts').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`!location.search.includes('scenario=') && document.querySelector('[data-profile-collection="subscriptions"]').textContent.includes('42') && document.querySelector('[data-profile-collection="favorites"]').textContent.includes('18') && document.activeElement?.dataset.profileCollection === 'subscriptions' && document.querySelector('#profile-status').textContent === '统计已更新'`))) {
  throw new Error('Profile partial count retry did not preserve sibling, focus, and announcement');
}
await evaluate(`document.querySelector('[data-profile-collection="subscriptions"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/library/subscriptions' && document.querySelector('.primary-nav').hidden && document.querySelectorAll('.library-item').length >= 3`))) {
  throw new Error('Profile collection route did not render Library content with secondary chrome');
}
await call('Page.navigate', {url: `${previewOrigin}/library/subscriptions?lab=1&scenario=error`});
await new Promise((resolve) => setTimeout(resolve, 150));
await evaluate(`document.querySelector('#retry-library-toolbar').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`!location.search.includes('scenario=') && document.querySelectorAll('.library-item').length >= 3`))) throw new Error('Library toolbar refresh did not clear URL-driven error');
for (const width of [599, 600, 839, 840]) {
  await call('Emulation.setDeviceMetricsOverride', {width, height: 900, screenWidth: width, screenHeight: 900, deviceScaleFactor: 1, mobile: true});
  await evaluate(`dispatchEvent(new Event('resize')); true`);
  const overlaps = await evaluate(`(() => { const item = document.querySelector('.library-item'); const remove = item.querySelector('[data-library-remove]').getBoundingClientRect(); const actions = item.querySelector('.card-actions').getBoundingClientRect(); return !(remove.top >= actions.bottom || remove.bottom <= actions.top || remove.left >= actions.right || remove.right <= actions.left); })()`);
  if (overlaps) throw new Error(`${width}px Library remove action overlaps card actions`);
}
await call('Emulation.clearDeviceMetricsOverride');
await evaluate(`dispatchEvent(new Event('resize')); document.querySelector('[data-library-tab="subscriptions"]').focus(); true`);
await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39});
await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'ArrowRight', code: 'ArrowRight', windowsVirtualKeyCode: 39});
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/library/favorites' && document.activeElement?.dataset.libraryTab === 'favorites' && document.querySelectorAll('.library-item').length >= 3`))) {
  throw new Error('Library tabs did not support keyboard navigation');
}
await evaluate(`document.querySelector('[data-library-remove]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelector('#library-remove-dialog').open`))) throw new Error('Library removal confirmation did not open');
await evaluate(`history.back(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
if (!(await evaluate(`location.pathname === '/library/favorites' && !document.querySelector('#library-remove-dialog').open && document.querySelectorAll('.library-item').length >= 3`))) {
  throw new Error(`Browser Back did not dismiss Library dialog before leaving route: ${JSON.stringify(await evaluate(`({path: location.pathname, open: document.querySelector('#library-remove-dialog')?.open, count: document.querySelectorAll('.library-item').length, state: history.state})`))}`);
}
await evaluate(`document.querySelector('[data-library-remove]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#confirm-library-remove').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`document.querySelectorAll('.library-item').length === 2 && document.activeElement?.dataset.libraryTab === 'favorites' && document.querySelector('#library-status').textContent === '项目已移除'`))) {
  throw new Error('Library removal did not preserve focus and announcement');
}
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/profile'`))) throw new Error('Profile collection Back did not restore Profile');
await evaluate(`document.querySelector('[data-profile-route="/downloads"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/downloads' && document.querySelectorAll('.download-task').length === 2 && document.querySelector('.primary-nav').hidden`))) {
  throw new Error('Downloads did not render deterministic active tasks');
}
await evaluate(`document.querySelector('[data-task-action="PAUSE"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelector('[data-download-task="task-neon"]').textContent.includes('已暂停') && document.activeElement?.dataset.downloadTask === 'task-neon' && document.querySelector('#downloads-status').textContent === '下载任务已更新'`))) {
  throw new Error('Download pause did not persist focus and announcement');
}
await evaluate(`document.querySelector('#retry-failed-downloads').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelector('[data-download-task="task-mountain"]').textContent.includes('等待中') && document.activeElement?.id === 'retry-failed-downloads' && document.querySelector('#downloads-status').textContent === '失败任务已重新排队'`))) throw new Error('Retry failed downloads did not update task, focus, and announcement');
await evaluate(`document.querySelector('[data-download-tab="completed"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.search.includes('tab=completed') && document.querySelectorAll('.download-task').length === 1 && document.activeElement?.dataset.downloadTab === 'completed'`))) {
  throw new Error('Downloads completed tab did not render with focus');
}
await evaluate(`document.querySelector('#clear-finished-downloads').focus(); document.querySelector('#clear-finished-downloads').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelector('.center-state h2').textContent === '没有已完成任务' && document.activeElement?.id === 'clear-finished-downloads' && document.querySelector('#downloads-status').textContent === '已完成记录已清除'`))) {
  throw new Error('Clearing completed downloads did not preserve focus');
}
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/profile'`))) throw new Error('Downloads Back did not restore Profile');
await evaluate(`document.querySelector('[data-profile-route="/local"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/local' && document.querySelectorAll('.local-item').length === 3`))) throw new Error('Local wallpapers did not render deterministic content');
await evaluate(`document.querySelector('#local-search').focus(); true`);
await call('Input.insertText', {text: '水'});
await new Promise((resolve) => setTimeout(resolve, 60));
await call('Input.insertText', {text: '墨'});
await new Promise((resolve) => setTimeout(resolve, 300));
if (!(await evaluate(`document.querySelectorAll('.local-item').length === 1 && document.querySelector('#local-search').value === '水墨' && document.activeElement?.id === 'local-search'`))) throw new Error('Local search did not preserve multi-character typing and focus');
await evaluate(`document.querySelector('#local-search').value = ''; document.querySelector('#local-search').dispatchEvent(new InputEvent('input', {bubbles: true, composed: true})); true`);
await new Promise((resolve) => setTimeout(resolve, 300));
await evaluate(`document.querySelector('#local-view-toggle').focus(); document.querySelector('#local-view-toggle').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelector('.local-grid').classList.contains('is-list') && document.activeElement?.id === 'local-view-toggle'`))) throw new Error('Local view mode did not persist with focus');
for (const width of [320, 359, 599, 600, 839, 840]) {
  await call('Emulation.setDeviceMetricsOverride', {width, height: 900, screenWidth: width, screenHeight: 900, deviceScaleFactor: 1, mobile: true});
  await evaluate(`dispatchEvent(new Event('resize')); true`);
  const geometry = await evaluate(`(() => { const open = document.querySelector('.local-open'); const copy = open.children[1].getBoundingClientRect(); return {columns: getComputedStyle(open).gridTemplateColumns.split(' ').length, copyWidth: Math.round(copy.width)}; })()`);
  if ((width < 600 && geometry.columns !== 1) || (width >= 600 && geometry.columns !== 2) || geometry.copyWidth < 120) throw new Error(`${width}px Local list geometry failed: ${JSON.stringify(geometry)}`);
}
await call('Emulation.clearDeviceMetricsOverride');
await evaluate(`dispatchEvent(new Event('resize')); true`);
const localFavoriteState = await evaluate(`document.querySelector('[data-local-favorite]').getAttribute('aria-pressed')`);
await evaluate(`document.querySelector('[data-local-favorite]').focus(); document.querySelector('[data-local-favorite]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.activeElement?.dataset.localFavorite && document.activeElement?.getAttribute('aria-pressed') !== ${JSON.stringify(localFavoriteState)}`))) throw new Error('Local favorite did not preserve focus');
await evaluate(`document.querySelector('[data-local-delete]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`history.back(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
if (!(await evaluate(`location.pathname === '/local' && !document.querySelector('#local-delete-dialog').open && document.querySelectorAll('.local-item').length === 3`))) throw new Error('Local dialog Back did not preserve route');
await evaluate(`document.querySelector('[data-local-delete]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#confirm-local-delete').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
if (!(await evaluate(`document.querySelectorAll('.local-item').length === 2 && document.querySelector('#local-status').textContent === '本地壁纸已删除'`))) throw new Error('Local delete did not persist and announce');
await evaluate(`document.querySelector('[data-local-open]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname.startsWith('/local/') && Boolean(document.querySelector('.local-detail')) && document.querySelectorAll('.local-detail-actions button, .local-detail-actions md-filled-button, .local-detail-actions md-outlined-button').length >= 4`))) throw new Error('Local item did not open functional detail route');
await evaluate(`history.replaceState(null, '', location.href); document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/local'`))) throw new Error('Local detail Back did not restore Local');
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/profile'`))) throw new Error('Local Back did not restore Profile');
await evaluate(`document.querySelector('#open-settings').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/settings' && document.querySelectorAll('[data-settings-category]').length === 5`))) throw new Error('Settings category index did not render');
await evaluate(`document.querySelector('[data-settings-category="steam"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/settings/steam' && document.querySelector('.steam-account-card h2').textContent === 'Astra' && document.querySelector('.primary-nav').hidden`))) throw new Error('Steam settings did not render signed-in state');
await evaluate(`{ const url = new URL(location.href); url.searchParams.set('lab', '1'); history.replaceState(history.state, '', url); true }`);
await call('Page.reload', {ignoreCache: true});
await new Promise((resolve) => setTimeout(resolve, 500));
const enhancedChecked = await evaluate(`document.querySelector('[data-setting-toggle="enhancedAccess"]').checked`);
await evaluate(`document.querySelector('[data-setting-toggle="enhancedAccess"]').focus(); document.querySelector('[data-setting-toggle="enhancedAccess"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.activeElement?.dataset.settingToggle === 'enhancedAccess' && document.activeElement.checked !== ${JSON.stringify(enhancedChecked)}`))) throw new Error('Settings toggle did not persist with focus');
await evaluate(`document.querySelector('#steam-logout').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`Boolean(document.querySelector('#steam-login')) && Boolean(document.querySelector('#steam-password')) && document.activeElement?.id === 'steam-account' && document.querySelector('#settings-status').textContent === '已退出 Steam'`))) throw new Error('Steam logout did not focus credentials and announce');
const fillSteamCredentials = async () => evaluate(`document.querySelector('#steam-account').value = 'fixture-user'; document.querySelector('#steam-account').dispatchEvent(new InputEvent('input')); document.querySelector('#steam-password').value = 'not-persisted-secret'; document.querySelector('#steam-password').dispatchEvent(new InputEvent('input')); true`);
await fillSteamCredentials();
if (!(await evaluate(`!document.querySelector('#steam-login').disabled`))) throw new Error('Steam credentials did not enable login');
await evaluate(`document.querySelector('#steam-login').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`Boolean(document.querySelector('#steam-use-code')) && document.activeElement?.id === 'lab-confirm-steam'`))) throw new Error('Steam credentials did not enter device confirmation');
await evaluate(`document.querySelector('#lab-confirm-steam').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`Boolean(document.querySelector('#steam-logout')) && document.activeElement?.id === 'steam-logout'`))) throw new Error('Simulated Steam device approval did not sign in');
await evaluate(`document.querySelector('#steam-logout').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await fillSteamCredentials();
await evaluate(`document.querySelector('#steam-login').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#steam-use-code').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`Boolean(document.querySelector('#steam-guard-code')) && document.activeElement?.id === 'steam-guard-code'`))) throw new Error('Steam manual fallback did not focus Guard field');
await evaluate(`document.querySelector('#steam-guard-code').value = '12345'; document.querySelector('#steam-guard-code').dispatchEvent(new InputEvent('input', {bubbles: true, composed: true})); document.querySelector('#steam-submit-code').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`Boolean(document.querySelector('#steam-logout')) && document.activeElement?.id === 'steam-logout' && document.querySelector('#settings-status').textContent === 'Steam 登录成功' && !localStorage.getItem('wallhub.preview.fake-backend').includes('not-persisted-secret')`))) throw new Error('Steam Guard did not sign in securely with focus and announcement');
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/settings' && document.activeElement?.id === 'main-content'`))) throw new Error('Reloaded Settings category Back did not restore main focus');
await evaluate(`document.querySelector('[data-settings-category="steam"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`history.replaceState(null, '', location.href); document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/settings' && document.activeElement?.id === 'main-content'`))) throw new Error('Direct Settings category Back did not restore Settings main focus');
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-route="/discover"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-discover-route="following"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/discover/following' && document.querySelectorAll('[data-saved-query]').length === 2`))) throw new Error('Discover Following did not render saved queries');
await evaluate(`document.querySelector('[data-remove-saved-query]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelectorAll('[data-saved-query]').length === 1 && document.querySelector('#discover-status').textContent === '关注已移除' && document.activeElement?.dataset.savedQuery`))) throw new Error('Removing followed query did not persist focus and announce');
await evaluate(`document.querySelector('#add-discover-query').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[name="query-category"][value="COLLECTION"]').click(); document.querySelector('#query-input').value = 'abc'; document.querySelector('#query-input').dispatchEvent(new InputEvent('input')); true`);
if (!(await evaluate(`document.querySelector('#save-query').disabled && document.querySelector('#query-error').textContent.includes('数字')`))) throw new Error('Discover query editor did not validate numeric ID');
await evaluate(`document.querySelector('#query-input').value = '123456'; document.querySelector('#query-input').dispatchEvent(new InputEvent('input')); document.querySelector('#query-editor').requestSubmit(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname.startsWith('/discover/results/query-custom-') && document.querySelector('.detail-top-bar h1').textContent === '合集 123456' && document.querySelectorAll('.workshop-card').length > 0`))) throw new Error('Saved Discover query did not open deterministic results');
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/discover/following' && document.querySelectorAll('[data-saved-query]').length === 2`))) throw new Error('Saved query did not persist after Back');
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-discover-route="friend-favorites"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/discover/friend-favorites' && document.querySelectorAll('.workshop-card').length === 2`))) throw new Error('Friend favorites did not render signed-in content');
await evaluate(`history.pushState(null, '', '/discover/query/new'); dispatchEvent(new PopStateEvent('popstate')); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#cancel-query').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/discover' && document.activeElement?.id === 'main-content'`))) throw new Error('Direct query editor Cancel did not recover Discover');
await evaluate(`localStorage.setItem('wallhub.preview.dom.theme', 'dark'); document.documentElement.dataset.theme = 'dark'; true`);

socket.close();
console.log('WEB_DOM_ACCEPTANCE=OK breakpoints=599/600/839/840 dialog_focus=trapped theme=persistent history=restored state_lab=deterministic detail=interactive discover=interactive profile=stateful');

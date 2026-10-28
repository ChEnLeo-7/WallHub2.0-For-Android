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
if (!(await evaluate(`!document.querySelector('.state-lab') && document.querySelectorAll('.workshop-card').length === 30`))) {
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
if (!(await evaluate(`document.querySelectorAll('.workshop-card').length === 30 && !location.search.includes('scenario=')`))) {
  throw new Error('State lab reset did not restore deterministic content');
}
if (!(await evaluate(`Boolean(document.querySelector('#load-more-home'))`))) throw new Error('Infinite-scroll Home did not expose load-more');
await evaluate(`document.querySelector('#load-more-home').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 200));
if (!(await evaluate(`document.querySelectorAll('.workshop-card').length > 30`))) throw new Error('Home load-more did not append deterministic results');
await evaluate(`document.querySelector('#refresh-home').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`document.querySelectorAll('.workshop-card').length === 30 && document.activeElement?.id === 'refresh-home' && document.querySelector('#home-refresh-status').textContent.includes('1')`))) throw new Error('Home refresh did not reset page and restore focus');
await evaluate(`document.querySelector('#filter-button').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 250));
if (!(await evaluate(`document.querySelector('#home-filter-dialog').open`))) throw new Error('Filter dialog did not open');
await evaluate(`history.back(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
if (!(await evaluate(`!document.querySelector('#home-filter-dialog').open && document.activeElement?.id === 'filter-button' && location.pathname === '/'`))) throw new Error('Home filter Back did not dismiss and restore focus');
await evaluate(`document.querySelector('#filter-button').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 200));
await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await new Promise((resolve) => setTimeout(resolve, 400));
if (!(await evaluate(`!document.querySelector('#home-filter-dialog').open && document.activeElement?.id === 'filter-button'`))) throw new Error('Home filter Escape did not unwind modal history and restore focus');
await evaluate(`document.querySelector('#filter-button').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 200));
await evaluate(`const form = document.querySelector('#home-filter-form'); form.elements.days.value = '0'; form.querySelector('[name="types"][value="VIDEO"]').checked = true; form.querySelectorAll('[name="genres"]').forEach((control) => { control.checked = control.value === 'Sci-Fi'; }); document.querySelector('#apply-home-filters').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 250));
const filterResult = await evaluate(`({cards: document.querySelectorAll('.workshop-card').length, focus: document.activeElement?.id, filterText: document.querySelector('#filter-button').textContent})`);
if (filterResult.cards < 1 || filterResult.focus !== 'filter-button' || !filterResult.filterText.includes('已启用')) throw new Error(`Filter application or focus restoration failed: ${JSON.stringify(filterResult)}`);
await evaluate(`document.querySelector('[data-card-menu]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 200));
if (!(await evaluate(`document.querySelector('#home-context-dialog').open && document.querySelectorAll('[data-context-action]').length === 6`))) throw new Error('Home card context actions did not open');
await evaluate(`document.querySelector('[data-context-action="author"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`document.querySelector('#search').value.length > 0 && document.activeElement?.id === 'search'`))) throw new Error('Home context author search did not update query');
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
await evaluate(`{ const url = new URL(location.href); url.searchParams.set('lab', '1'); history.replaceState(history.state, '', url); dispatchEvent(new PopStateEvent('popstate')); true }`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('.play-button').click(); true`);
if (!(await evaluate(`document.querySelector('.video-state')?.textContent.includes('正在载入视频')`))) throw new Error('Detail video did not enter loading state');
await new Promise((resolve) => setTimeout(resolve, 50));
await evaluate(`document.querySelector('#lab-fail-video').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 20));
if (!(await evaluate(`Boolean(document.querySelector('#retry-inline-video'))`))) throw new Error('State Lab did not expose deterministic video failure');
await evaluate(`document.querySelector('#retry-inline-video').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 220));
if (!(await evaluate(`document.querySelector('.video-state')?.textContent.includes('正在载入视频')`))) throw new Error('A stale video attempt completed the retry early');
await new Promise((resolve) => setTimeout(resolve, 80));
if (!(await evaluate(`Boolean(document.querySelector('#open-video-fullscreen'))`))) throw new Error('Detail video retry did not enter playing state');
await evaluate(`document.querySelector('#open-video-fullscreen').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`Boolean(document.querySelector('.video-fullscreen')) && document.activeElement?.id === 'exit-video-fullscreen'`))) throw new Error('Detail video fullscreen did not open with focus');
await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9});
await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9});
if (!(await evaluate(`document.activeElement?.id === 'exit-video-fullscreen'`))) throw new Error('Fullscreen video did not trap forward Tab focus');
await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, modifiers: 8});
await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Tab', code: 'Tab', windowsVirtualKeyCode: 9, modifiers: 8});
if (!(await evaluate(`document.activeElement?.id === 'exit-video-fullscreen'`))) throw new Error('Fullscreen video did not trap reverse Tab focus');
await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await new Promise((resolve) => setTimeout(resolve, 300));
if (!(await evaluate(`!document.querySelector('.video-fullscreen') && location.pathname === '/workshop/3357929793' && document.activeElement?.id === 'open-video-fullscreen'`))) throw new Error('Detail video fullscreen Escape did not restore detail focus');
await evaluate(`document.querySelector('[data-detail-tab="comments"]').click(); true`);
if (!(await evaluate(`location.search.includes('tab=comments') && document.querySelectorAll('.comment').length === 2 && document.activeElement?.dataset.detailTab === 'comments' && Boolean(document.querySelector('#comment-composer'))`))) throw new Error('Workshop Detail comments tab/composer did not render with restored focus');
if (!(await evaluate(`(() => { const composer = document.querySelector('#comment-composer').getBoundingClientRect(); const actions = document.querySelector('.detail-actions').getBoundingClientRect(); return composer.bottom <= actions.top + 1 && composer.top < innerHeight; })()`))) throw new Error('Comment composer was not fixed above the Detail action bar');
for (const width of [320, 840]) {
  await call('Emulation.setDeviceMetricsOverride', {width, height: 900, screenWidth: width, screenHeight: 900, deviceScaleFactor: 1, mobile: true});
  await evaluate(`dispatchEvent(new Event('resize')); true`);
  const geometry = await evaluate(`(() => { const rect = document.querySelector('#comment-composer').getBoundingClientRect(); return {left: Math.round(rect.left), right: Math.round(rect.right), width: innerWidth}; })()`);
  if (geometry.left !== 0 || Math.abs(geometry.right - geometry.width) > 1) throw new Error(`${width}px comment composer horizontal alignment failed: ${JSON.stringify(geometry)}`);
}
await call('Emulation.clearDeviceMetricsOverride');
await evaluate(`dispatchEvent(new Event('resize')); true`);
await evaluate(`document.querySelector('#comment-draft').value = '字'.repeat(1001); document.querySelector('#comment-draft').dispatchEvent(new InputEvent('input', {bubbles: true, composed: true})); true`);
if (!(await evaluate(`document.querySelector('#comment-draft').value.length === 1000 && document.querySelector('#comment-limit').textContent.includes('1000/1000')`))) throw new Error('Comment draft did not enforce and expose 1000-character limit');
await evaluate(`document.querySelector('#comment-draft').value = '来自 Web 镜像的确定性评论'; document.querySelector('#comment-draft').dispatchEvent(new InputEvent('input', {bubbles: true, composed: true})); document.querySelector('#comment-composer').requestSubmit(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelectorAll('.comment').length === 3 && document.querySelector('.comment:last-of-type').textContent.includes('来自 Web 镜像的确定性评论') && document.activeElement?.id === 'comment-draft' && document.querySelector('#comment-status').textContent === '评论已发布'`))) throw new Error('Workshop comment submission did not persist focus and announce');
await evaluate(`document.querySelector('[data-detail-tab="comments"]').focus(); true`);
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
await call('Page.navigate', {url: `${previewOrigin}/workshop/3174098802?lab=1`});
await new Promise((resolve) => setTimeout(resolve, 300));
await evaluate(`document.querySelector('#detail-download').click(); document.querySelector('#detail-download').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`document.querySelector('#download-dialog').open && history.state?.modal === 'detail-download-format'`))) throw new Error('Download format dialog did not open idempotently');
await evaluate(`document.querySelector('[data-download-format="MPKG"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`!document.querySelector('#download-mpkg-page').hidden && document.activeElement?.id === 'back-download-format'`))) throw new Error('MPKG conversion options did not open with focus');
await evaluate(`history.back(); true`);
await new Promise((resolve) => setTimeout(resolve, 200));
if (!(await evaluate(`!document.querySelector('#download-dialog').open && document.activeElement?.id === 'detail-download'`))) throw new Error('System Back did not dismiss the download modal and restore focus');
await evaluate(`document.querySelector('#detail-download').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-download-format="MPKG"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#back-download-format').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
const downloadBackState = await evaluate(`({open: document.querySelector('#download-dialog').open, formatHidden: document.querySelector('#download-format-page').hidden, mpkgHidden: document.querySelector('#download-mpkg-page').hidden, focus: document.activeElement?.dataset.downloadFormat ?? document.activeElement?.id, history: history.state})`);
if (!downloadBackState.open || downloadBackState.formatHidden || downloadBackState.focus !== 'MPKG') throw new Error(`Download options page Back did not return to format step: ${JSON.stringify(downloadBackState)}`);
await evaluate(`document.querySelector('[data-download-format="MPKG"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[name="download-preset"][value="EXTREME_QUALITY"]').checked = true; document.querySelector('#download-preserve-audio').checked = true; document.querySelector('#download-mpkg-page').requestSubmit(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
const downloadResult = await evaluate(`({open: document.querySelector('#download-dialog').open, downloads: JSON.parse(localStorage.getItem('wallhub.preview.fake-backend')).downloads, task: JSON.parse(localStorage.getItem('wallhub.preview.fake-backend')).downloadTasks.find((task) => task.workshopId === '3174098802' && task.format === 'MPKG'), feedback: document.querySelector('#detail-download-feedback')?.textContent})`);
downloadResult.focus = await evaluate(`document.activeElement?.id`);
downloadResult.focusDiagnostics = await evaluate(`({tag: document.activeElement?.tagName, triggerExists: Boolean(document.querySelector('#detail-download')), triggerDisabled: document.querySelector('#detail-download')?.disabled, triggerTabIndex: document.querySelector('#detail-download')?.tabIndex, mainInert: document.querySelector('#main-content')?.inert})`);
if (downloadResult.open || downloadResult.downloads.length !== 1 || downloadResult.downloads[0].format !== 'MPKG' || downloadResult.task?.preset !== 'EXTREME_QUALITY' || downloadResult.task?.preserveAudio !== true || !downloadResult.feedback?.includes('已加入下载队列') || downloadResult.focus !== 'detail-download') {
  throw new Error(`Two-step download request did not persist: ${JSON.stringify(downloadResult)}`);
}
await evaluate(`document.querySelector('#detail-download').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-download-format="MPKG"]').click(); document.querySelector('#download-mpkg-page').requestSubmit(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
const duplicateFeedback = await evaluate(`({text: document.querySelector('#detail-download-feedback')?.textContent, feedbackHidden: document.querySelector('#detail-download-feedback')?.hidden, actionHidden: document.querySelector('#open-download-queue')?.hidden, dialogOpen: document.querySelector('#download-dialog').open})`);
if (!duplicateFeedback.text?.includes('已在下载队列中') || !duplicateFeedback.actionHidden) throw new Error(`Duplicate download feedback exposed the queue action: ${JSON.stringify(duplicateFeedback)}`);
await new Promise((resolve) => setTimeout(resolve, 4100));
if (!(await evaluate(`document.querySelector('#detail-download-feedback').hidden`))) throw new Error('Duplicate download feedback did not expire');
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
if (!(await evaluate(`location.pathname === '/downloads' && document.querySelectorAll('.download-task').length >= 3 && document.querySelector('.primary-nav').hidden`))) throw new Error('Downloads did not render deterministic active tasks');
await evaluate(`document.querySelector('[data-task-move="down"][data-task-id="task-neon"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
const reorderState = await evaluate(`({ids: [...document.querySelectorAll('.download-task')].map((item) => item.dataset.downloadTask), focus: document.activeElement?.dataset.taskMove, status: document.querySelector('#downloads-status').textContent})`);
if (reorderState.ids[0] !== 'task-resolving' || reorderState.ids[1] !== 'task-mountain' || reorderState.focus !== 'down' || !reorderState.status.includes('第 2 位')) throw new Error(`Download queue reorder did not persist focus and announce position: ${JSON.stringify(reorderState)}`);
await evaluate(`document.querySelector('[data-open-download-task="task-mountain"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/downloads/task/task-mountain' && Boolean(document.querySelector('.download-failure')) && Boolean(document.querySelector('#export-raw-zip'))`))) throw new Error('Failed task detail did not expose failure and raw ZIP recovery');
await evaluate(`document.querySelector('#export-raw-zip').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/downloads/task/task-mountain' && document.querySelector('.download-failure').textContent.includes('原始 ZIP 已导出') && document.activeElement?.id === 'export-raw-zip' && document.querySelector('#download-detail-status').textContent === '原始 ZIP 已导出'`))) throw new Error('Raw ZIP export did not preserve task identity, focus, and feedback');
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-task-action="PAUSE"][data-task-id="task-neon"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelector('[data-download-task="task-neon"]').textContent.includes('已暂停') && document.activeElement?.dataset.downloadTask === 'task-neon' && document.querySelector('#downloads-status').textContent === '下载任务已更新'`))) {
  throw new Error('Download pause did not persist focus and announcement');
}
await evaluate(`document.querySelector('#retry-failed-downloads').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelector('[data-download-task="task-mountain"]').textContent.includes('等待中') && document.activeElement?.id === 'retry-failed-downloads' && document.querySelector('#downloads-status').textContent === '失败任务已重新排队'`))) throw new Error('Retry failed downloads did not update task, focus, and announcement');
await evaluate(`document.querySelector('[data-download-tab="completed"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.search.includes('tab=completed') && document.querySelectorAll('.download-task').length === 2 && document.activeElement?.dataset.downloadTab === 'completed'`))) throw new Error('Downloads completed tab did not render with focus');
await evaluate(`document.querySelector('[data-open-download-task="task-cafe"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/downloads/task/task-cafe' && Boolean(document.querySelector('#open-reconvert'))`))) throw new Error('Completed Scene task detail did not expose reconversion');
await evaluate(`document.querySelector('#open-reconvert').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`history.back(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
if (!(await evaluate(`location.pathname === '/downloads/task/task-cafe' && !document.querySelector('#reconvert-dialog').open && document.activeElement?.id === 'open-reconvert'`))) throw new Error('Reconversion Back did not dismiss dialog and restore focus');
await evaluate(`document.querySelector('#open-reconvert').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
await new Promise((resolve) => setTimeout(resolve, 400));
if (!(await evaluate(`location.pathname === '/downloads/task/task-cafe' && !document.querySelector('#reconvert-dialog').open && document.activeElement?.id === 'open-reconvert'`))) throw new Error('Reconversion Escape did not dismiss dialog and restore focus');
await evaluate(`document.querySelector('#open-reconvert').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#reconvert-preset').value = 'COMPATIBILITY'; document.querySelector('#reconvert-audio').checked = true; document.querySelector('#confirm-reconvert').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/downloads' && document.querySelector('[data-download-task="task-cafe"]').textContent.includes('等待中')`))) throw new Error('Reconversion did not return task to active queue');
await evaluate(`document.querySelector('[data-download-tab="completed"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-open-download-task="task-video"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`Boolean(document.querySelector('#play-downloaded-video'))`))) throw new Error('Completed Video task detail did not expose playback');
await evaluate(`document.querySelector('#play-downloaded-video').click(); true`);
if (!(await evaluate(`document.querySelector('#download-detail-status').textContent === '已请求播放本地视频'`))) throw new Error('Downloaded video playback was not announced');
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
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
await evaluate(`document.querySelector('[data-settings-category="basic"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#open-language').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[name="language"][value="ENGLISH"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#export-diagnostics').click(); document.querySelector('#notifications-setting').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`location.pathname === '/settings/basic' && document.querySelector('#open-language').textContent.includes('English') && document.querySelector('.settings-notice').textContent.includes('诊断日志已导出') && document.querySelector('#notifications-setting').textContent.includes('已授予')`))) throw new Error('Basic settings did not persist language, diagnostics, and notification state');
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-settings-category="appearance"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`const themeSelect = document.querySelector('[data-setting-select="themePreference"]'); themeSelect.value = 'LIGHT'; themeSelect.dispatchEvent(new Event('change', {bubbles: true})); const pageSize = document.querySelector('[data-setting-select="homePageSize"]'); pageSize.value = '50'; pageSize.dispatchEvent(new Event('change', {bubbles: true})); document.querySelector('[data-setting-toggle="useSystemMonet"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.documentElement.dataset.theme === 'light' && document.querySelector('[data-setting-select="homePageSize"]').value === '50' && !document.querySelector('[data-setting-select="accent"]').disabled`))) throw new Error('Appearance settings did not persist theme, page size, and dynamic color');
await evaluate(`const accent = document.querySelector('[data-setting-select="accent"]'); accent.value = 'CUSTOM'; accent.dispatchEvent(new Event('change', {bubbles: true})); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelector('#custom-accent-dialog').open`))) throw new Error('Custom accent editor did not open');
await evaluate(`document.querySelector('#custom-accent-color').value = '#123456'; document.querySelector('#apply-custom-accent').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.documentElement.style.getPropertyValue('--md-sys-color-primary') === '#123456' && document.activeElement?.dataset.settingSelect === 'accent'`))) throw new Error('Custom accent did not apply or restore focus');
await evaluate(`const accent = document.querySelector('[data-setting-select="accent"]'); accent.value = 'CUSTOM'; accent.dispatchEvent(new Event('change', {bubbles: true})); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#custom-accent-color').value = '#ffffff'; document.querySelector('#apply-custom-accent').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
const whiteAccentRoles = await evaluate(`({primary: document.documentElement.style.getPropertyValue('--md-sys-color-primary'), onPrimary: document.documentElement.style.getPropertyValue('--md-sys-color-on-primary')})`);
if (whiteAccentRoles.onPrimary !== '#000000') throw new Error(`White custom accent did not receive contrast-safe foreground: ${JSON.stringify(whiteAccentRoles)}`);
await evaluate(`const themeSelect = document.querySelector('[data-setting-select="themePreference"]'); themeSelect.value = 'DARK'; themeSelect.dispatchEvent(new Event('change', {bubbles: true})); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`const accent = document.querySelector('[data-setting-select="accent"]'); accent.value = 'CUSTOM'; accent.dispatchEvent(new Event('change', {bubbles: true})); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#custom-accent-color').value = '#000000'; document.querySelector('#apply-custom-accent').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.documentElement.style.getPropertyValue('--md-sys-color-on-primary') === '#ffffff'`))) throw new Error('Black custom accent did not receive contrast-safe foreground');
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-settings-category="downloads"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#choose-output-directory').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`const concurrent = document.querySelector('[data-setting-select="maxConcurrentDownloads"]'); concurrent.value = '8'; concurrent.dispatchEvent(new Event('change', {bubbles: true})); const source = document.querySelector('[data-setting-select="workshopSourceCacheBudgetGiB"]'); source.value = 'CUSTOM'; source.dispatchEvent(new Event('change', {bubbles: true})); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#custom-source-cache').value = '3'; document.querySelector('#apply-custom-source').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`const stream = document.querySelector('[data-setting-select="onlineStreamCacheLimitMb"]'); stream.value = 'CUSTOM'; stream.dispatchEvent(new Event('change', {bubbles: true})); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#custom-stream-cache').value = '1536'; document.querySelector('#apply-custom-stream').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#clear-source-cache').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`history.back(); true`);
await new Promise((resolve) => setTimeout(resolve, 400));
const clearBackState = await evaluate(`({open: document.querySelector('#clear-source-dialog').open, focus: document.activeElement?.id, path: location.pathname, history: history.state})`);
if (clearBackState.open || clearBackState.focus !== 'clear-source-cache' || clearBackState.path !== '/settings/downloads') throw new Error(`Source-cache browser Back did not dismiss and restore focus: ${JSON.stringify(clearBackState)}`);
await evaluate(`document.querySelector('#clear-source-cache').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#confirm-clear-source').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 150));
if (!(await evaluate(`document.activeElement?.id === 'clear-source-cache' && document.querySelector('#settings-status').textContent.includes('已清除')`))) throw new Error('Source-cache clear did not restore focus and announce result');
await evaluate(`document.querySelector('#download-proxy-url').value = 'socks5://127.0.0.1:1080'; document.querySelector('#save-download-proxy').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
const downloadSettingsState = await evaluate(`({text: document.querySelector('.settings-page').textContent, concurrent: document.querySelector('[data-setting-select="maxConcurrentDownloads"]')?.value, proxy: document.querySelector('#download-proxy-url')?.value, persisted: JSON.parse(localStorage.getItem('wallhub.preview.fake-backend')).settings})`);
if (!downloadSettingsState.text.includes('Web 选择的 WallHub 目录') || downloadSettingsState.concurrent !== '8' || downloadSettingsState.persisted.workshopSourceCacheBudgetGiB !== 3 || downloadSettingsState.persisted.onlineStreamCacheLimitMb !== 1536 || !downloadSettingsState.text.includes('0 MB') || !downloadSettingsState.proxy?.includes('socks5://')) throw new Error(`Download settings did not persist storage, performance, cache, and proxy: ${JSON.stringify(downloadSettingsState)}`);
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('[data-settings-category="about"]').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`document.querySelectorAll('[data-about-person]').length === 4 && document.querySelector('[data-about-person="CHENLEO_7"]').dataset.aboutAccount === 'ChEnLeo-7' && document.querySelector('[data-about-person="uwugl"]').dataset.aboutAccount === 'uwu-gl' && document.querySelector('.about-logo').textContent === 'W'`))) throw new Error('About WallHub people and hero did not render');
await evaluate(`document.querySelector('#check-updates').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 350));
if (!(await evaluate(`Boolean(document.querySelector('#download-update')) && document.querySelector('.settings-notice').textContent.includes('发现新版本')`))) throw new Error('About update check did not reach available state');
await evaluate(`document.querySelector('#download-update').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`Boolean(document.querySelector('#cancel-update')) && Boolean(document.querySelector('.update-actions progress'))`))) throw new Error('About update download state did not render');
await new Promise((resolve) => setTimeout(resolve, 450));
if (!(await evaluate(`Boolean(document.querySelector('#install-update'))`))) throw new Error('About update download did not reach install state');
await evaluate(`document.querySelector('#restart-setup').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
if (!(await evaluate(`Boolean(document.querySelector('.setup-wizard')) && JSON.parse(localStorage.getItem('wallhub.preview.fake-backend')).settings.setupRestartRequested === true`))) throw new Error('Setup restart request did not open wizard');
await evaluate(`document.querySelector('#complete-setup').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
await evaluate(`document.querySelector('#detail-back').click(); true`);
await new Promise((resolve) => setTimeout(resolve, 100));
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

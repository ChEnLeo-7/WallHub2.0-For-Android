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

await call('Page.navigate', {url: `${previewOrigin}/?acceptance=dom`});
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
  throw new Error('Dialog focus did not reach both actions');
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
await evaluate(`document.querySelector('#theme-toggle').click(); true`);
if ((await evaluate(`document.documentElement.dataset.theme`)) !== 'light') {
  throw new Error('Theme did not switch to light');
}
await call('Page.reload', {ignoreCache: true});
await new Promise((resolve) => setTimeout(resolve, 900));
if ((await evaluate(`document.documentElement.dataset.theme`)) !== 'light') {
  throw new Error('Theme did not survive reload');
}
await evaluate(`document.querySelector('[data-route="/discover"]').click(); true`);
if ((await evaluate(`location.pathname`)) !== '/discover') throw new Error('pushState failed');
await evaluate(`history.back(); true`);
await new Promise((resolve) => setTimeout(resolve, 250));
if ((await evaluate(`location.pathname`)) !== '/') throw new Error('popstate/back failed');
await evaluate(`localStorage.setItem('wallhub.preview.dom.theme', 'dark'); document.documentElement.dataset.theme = 'dark'; true`);

socket.close();
console.log('WEB_DOM_ACCEPTANCE=OK breakpoints=599/600/839/840 dialog_focus=trapped theme=persistent history=restored');

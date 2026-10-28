import '@material/web/button/filled-button.js';
import '@material/web/button/outlined-button.js';
import '@material/web/button/text-button.js';
import '@material/web/dialog/dialog.js';
import '@material/web/progress/circular-progress.js';
import '@material/web/textfield/outlined-text-field.js';
import './contract.css';
import './styles.css';
import contract from '../../../shared/ui-contract/wallhub-current.json';
import {FakeBackend, stateLabScenarios} from '../../state-lab/core.js';

const iconPaths = {
  home: 'M12 3 2 12h3v8h6v-6h2v6h6v-8L12 3Zm5 15h-2v-6H9v6h-2v-7.1l5-4.5 5 4.5V18Z',
  explore: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19ZM12 10.9a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2Z',
  person: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4 1.79-4 4 1.79 4 4 4Zm0-6a2 2 0 1 1 0 4 2 2 0 0 1 0-4Zm0 8c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4Zm-5.33 4c.73-1.02 3.32-2 5.33-2 2.02 0 4.61.99 5.34 2H6.67Z',
};
const destinations = contract.destinations.map((destination) => ({...destination, iconPath: iconPaths[destination.icon]}));
const themeKey = 'wallhub.preview.dom.theme';
const storage = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch { /* Persistence is optional. */ } },
};
const backend = new FakeBackend(storage);
let theme = storage.get(themeKey) === 'light' ? 'light' : 'dark';

const app = document.querySelector('#app');
app.innerHTML = `
  <div class="app-shell">
    <nav class="primary-nav" aria-label="主导航"></nav>
    <main id="main-content" tabindex="-1"></main>
  </div>
  <md-dialog id="focus-dialog" aria-label="焦点与对话框验证">
    <div slot="headline">焦点与对话框验证</div>
    <form slot="content" id="dialog-form" method="dialog">
      <p>键盘焦点应被限制在此对话框中，并在关闭后返回触发按钮。</p>
    </form>
    <div slot="actions">
      <md-text-button form="dialog-form" value="cancel">取消</md-text-button>
      <md-text-button form="dialog-form" value="confirm">确认</md-text-button>
    </div>
  </md-dialog>
  <md-dialog id="filter-dialog" aria-label="筛选创意工坊">
    <div slot="headline">筛选</div>
    <form slot="content" id="filter-form" method="dialog" class="filter-form">
      <label>排序
        <select id="filter-sort">
          <option value="TRENDING">热门趋势</option>
          <option value="MOST_SUBSCRIBED">最多订阅</option>
        </select>
      </label>
      <label>类型
        <select id="filter-type">
          <option value="ALL">全部类型</option>
          <option value="SCENE">场景</option>
          <option value="VIDEO">视频</option>
        </select>
      </label>
      <label class="checkbox-row"><input id="filter-exact" type="checkbox"> 精确短语</label>
    </form>
    <div slot="actions">
      <md-text-button id="reset-filters">重置</md-text-button>
      <md-text-button form="filter-form" value="cancel">取消</md-text-button>
      <md-filled-button id="apply-filters">应用</md-filled-button>
    </div>
  </md-dialog>
  <md-dialog id="download-dialog" aria-label="选择下载格式">
    <div slot="headline">下载壁纸</div>
    <div slot="content" class="download-options">
      <button type="button" data-download-format="MPKG"><strong>MPKG</strong><span>推荐 · 保留 WallHub 动态功能</span></button>
      <button type="button" data-download-format="ZIP"><strong>ZIP</strong><span>原始创意工坊文件</span></button>
    </div>
    <div slot="actions"><md-text-button id="close-download">关闭</md-text-button></div>
  </md-dialog>
`;
const nav = document.querySelector('.primary-nav');
const main = document.querySelector('#main-content');
const dialog = document.querySelector('#focus-dialog');
const filterDialog = document.querySelector('#filter-dialog');
const downloadDialog = document.querySelector('#download-dialog');

const escapeHtml = (value) => String(value)
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;').replaceAll("'", '&#039;');
const compactNumber = new Intl.NumberFormat('zh-CN', {notation: 'compact', maximumFractionDigits: 1});
const formatBytes = (bytes) => `${(bytes / 1024 / 1024).toFixed(0)} MB`;

function isLabVisible() {
  return new URLSearchParams(location.search).get('lab') === '1';
}

function requestedScenario() {
  if (!isLabVisible()) return 'content';
  const scenario = new URLSearchParams(location.search).get('scenario');
  return stateLabScenarios.includes(scenario) ? scenario : null;
}

function currentTopRoute() {
  if (location.pathname.startsWith('/workshop/')) return '/';
  return destinations.some((item) => item.route === location.pathname) ? location.pathname : '/';
}

function navigate(destination) {
  history.pushState({wallhub: true, from: `${location.pathname}${location.search}`, route: destination}, '', destination);
  renderApp();
  main.focus();
}

function renderNavigation() {
  const route = currentTopRoute();
  nav.innerHTML = destinations.map((item) => `
    <button class="nav-item" type="button" data-route="${item.route}"
      aria-current="${item.route === route ? 'page' : 'false'}">
      <svg class="nav-icon" aria-hidden="true" viewBox="0 0 24 24"><path d="${item.iconPath}"></path></svg>
      <span>${item.label}</span>
    </button>
  `).join('');
}

function shellHeader(title, subtitle = '') {
  return `
    <header class="top-bar">
      <div class="title-block">
        <div class="eyebrow">CURRENT UI · WEB MIRROR</div>
        <h1>${escapeHtml(title)}</h1>
        ${subtitle ? `<div class="page-summary">${escapeHtml(subtitle)}</div>` : ''}
      </div>
      <div class="toolbar-actions">
        ${isLabVisible() ? '<md-outlined-button id="dialog-button">验证焦点</md-outlined-button>' : ''}
        <md-outlined-button id="theme-toggle">${theme === 'dark' ? '浅色外观' : '深色外观'}</md-outlined-button>
      </div>
    </header>`;
}

function renderStateLab(snapshot) {
  const labels = {content: '内容', loading: '初始加载', empty: '空结果', error: '加载失败'};
  return `
    <aside class="state-lab" aria-label="状态实验室">
      <span class="state-lab-label">状态实验室</span>
      <label>场景
        <select id="scenario-select">
          ${stateLabScenarios.map((scenario) => `<option value="${scenario}" ${snapshot.scenario === scenario ? 'selected' : ''}>${labels[scenario]}</option>`).join('')}
        </select>
      </label>
      <span>虚拟时间 ${new Date(snapshot.clockMs).toISOString().slice(0, 16).replace('T', ' ')}</span>
      <button type="button" id="advance-clock">+1 小时</button>
      <button type="button" id="reset-lab">重置</button>
    </aside>`;
}

function workshopCard(item, snapshot) {
  const favorite = snapshot.favorites.includes(item.id);
  return `
    <article class="workshop-card" data-workshop-id="${item.id}">
      <button class="card-open" type="button" data-open-workshop="${item.id}" aria-label="查看 ${escapeHtml(item.title)} 的详情">
        <div class="card-media card-media-${item.accent}" role="img" aria-label="${escapeHtml(item.title)} 预览占位图">
          <span>${escapeHtml(item.type)}</span>
        </div>
        <div class="card-copy">
          <h2>${escapeHtml(item.title)}</h2>
          <p class="card-author">${escapeHtml(item.author)}</p>
          <div class="card-stats" aria-label="作品统计">
            <span title="订阅">↓ ${compactNumber.format(item.subscriptions)}</span>
            <span title="收藏">♥ ${compactNumber.format(item.favorites)}</span>
            <span title="文件大小">${formatBytes(item.fileSizeBytes)}</span>
          </div>
        </div>
      </button>
      <div class="card-actions">
        <md-text-button data-favorite="${item.id}">${favorite ? '已收藏' : '收藏'}</md-text-button>
        <md-filled-button data-download="${item.id}">下载</md-filled-button>
      </div>
    </article>`;
}

function renderHome() {
  const snapshot = backend.snapshot();
  const result = backend.listWorkshops();
  const total = result.items.length;
  let results;
  if (result.status === 'loading') {
    results = `<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress><p>正在载入创意工坊…</p></div>`;
  } else if (result.status === 'error') {
    results = `<div class="center-state" role="alert"><div class="state-icon">↻</div><h2>${escapeHtml(result.message)}</h2><md-filled-button id="retry-home">重试</md-filled-button></div>`;
  } else if (result.status === 'empty') {
    results = `<div class="center-state"><div class="state-icon">⌕</div><h2>没有匹配的壁纸</h2><md-filled-button id="retry-home">重新加载</md-filled-button></div>`;
  } else {
    results = `<div class="workshop-grid ${snapshot.viewMode === 'list' ? 'is-list' : ''}">${result.items.map((item) => workshopCard(item, snapshot)).join('')}</div>`;
  }
  main.innerHTML = `
    ${shellHeader('WallHub', `steam-community · 已加载 ${total} / 42 个项目 · 第 1/2 页`)}
    ${isLabVisible() ? renderStateLab(snapshot) : ''}
    <section class="home-tools" aria-label="浏览工具">
      <md-outlined-text-field id="search" label="搜索创意工坊" value="${escapeHtml(snapshot.query)}"></md-outlined-text-field>
      <div class="view-toggle" role="group" aria-label="视图模式">
        <button type="button" data-view-mode="grid" aria-pressed="${snapshot.viewMode === 'grid'}">网格</button>
        <button type="button" data-view-mode="list" aria-pressed="${snapshot.viewMode === 'list'}">列表</button>
      </div>
      <md-outlined-button id="filter-button">筛选${snapshot.filters.type !== 'ALL' || snapshot.filters.sort !== 'TRENDING' || snapshot.filters.exactPhrase ? ' · 已启用' : ''}</md-outlined-button>
    </section>
    <section class="home-results" aria-label="创意工坊结果">${results}</section>`;
  document.title = '首页 · WallHub Web Preview';
}

function relativeTime(timestamp, now) {
  const hours = Math.max(1, Math.round((now - timestamp) / 3_600_000));
  return hours < 24 ? `${hours} 小时前` : `${Math.round(hours / 24)} 天前`;
}

function renderDetail() {
  const id = location.pathname.match(/^\/workshop\/(\d+)$/u)?.[1];
  const item = id ? backend.workshop(id) : null;
  const snapshot = backend.snapshot();
  const scenario = snapshot.scenario;
  if (scenario === 'loading') {
    main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back">←</button><h1>壁纸详情</h1></header><div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress></div>`;
    return;
  }
  if (scenario === 'error' || !item) {
    main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back">←</button><h1>壁纸详情</h1></header><div class="center-state" role="alert"><div class="state-icon">!</div><h2>${item ? '无法载入此创意工坊项目' : '找不到此创意工坊项目'}</h2><md-filled-button id="${item ? 'retry-detail' : 'missing-detail-home'}">${item ? '重试' : '返回首页'}</md-filled-button></div>`;
    return;
  }
  const params = new URLSearchParams(location.search);
  const tab = scenario === 'empty' || params.get('tab') === 'comments' ? 'comments' : 'details';
  const comments = scenario === 'empty' ? [] : backend.comments(id);
  const favorite = snapshot.favorites.includes(id);
  const subscribed = snapshot.subscriptions.includes(id);
  const tabContent = tab === 'comments' ? `
    <section class="comment-list" id="detail-tabpanel" role="tabpanel" aria-labelledby="detail-tab-comments" tabindex="0">
      ${comments.length ? comments.map((comment) => `<article class="comment"><div class="avatar" aria-hidden="true">${escapeHtml(comment.author.slice(0, 1))}</div><div><h3>${escapeHtml(comment.author)} ${comment.isCreator ? '<span>作者</span>' : ''}</h3><p class="comment-time">${relativeTime(comment.timestamp, snapshot.clockMs)}</p><p>${escapeHtml(comment.text)}</p></div></article>`).join('') : '<div class="center-state"><h2>暂无评论</h2></div>'}
    </section>` : `
    <section class="detail-overview" id="detail-tabpanel" role="tabpanel" aria-labelledby="detail-tab-details" tabindex="0">
      <div class="description"><h2>描述</h2><p>${escapeHtml(item.description || '未提供描述')}</p></div>
      <dl class="detail-metrics">
        <div><dt>作者</dt><dd>${escapeHtml(item.author)}</dd></div>
        <div><dt>项目 ID</dt><dd>${item.id}</dd></div>
        <div><dt>订阅</dt><dd>${compactNumber.format(item.subscriptions)}</dd></div>
        <div><dt>收藏</dt><dd>${compactNumber.format(item.favorites)}</dd></div>
        <div><dt>查看</dt><dd>${compactNumber.format(item.views)}</dd></div>
        <div><dt>文件大小</dt><dd>${formatBytes(item.fileSizeBytes)}</dd></div>
        <div><dt>类型</dt><dd>${item.type}</dd></div>
        <div><dt>最后更新</dt><dd>${new Date(item.updatedAt).toISOString().slice(0, 10)}</dd></div>
      </dl>
      <div class="tag-list" aria-label="标签">${item.tags.map((tag) => `<button type="button" data-tag="${escapeHtml(tag)}">${escapeHtml(tag)}</button>`).join('')}</div>
    </section>`;
  main.innerHTML = `
    <header class="detail-top-bar">
      <button type="button" class="back-button" id="detail-back" aria-label="返回">←</button>
      <h1>${escapeHtml(item.title)}</h1>
      <div class="toolbar-actions"><md-outlined-button id="copy-title">复制标题</md-outlined-button><md-outlined-button id="open-steam">Steam</md-outlined-button></div>
    </header>
    ${isLabVisible() ? renderStateLab(snapshot) : ''}
    <article class="detail-page">
      <div class="detail-media card-media-${item.accent}" role="img" aria-label="${escapeHtml(item.title)} 预览"><span>${item.type}</span>${item.type === 'VIDEO' ? '<button type="button" class="play-button" aria-label="播放预览" aria-pressed="false">▶</button>' : ''}</div>
      <div class="detail-tabs" role="tablist" aria-label="详情内容">
        <button type="button" role="tab" id="detail-tab-details" aria-controls="detail-tabpanel" tabindex="${tab === 'details' ? '0' : '-1'}" data-detail-tab="details" aria-selected="${tab === 'details'}">详情</button>
        <button type="button" role="tab" id="detail-tab-comments" aria-controls="detail-tabpanel" tabindex="${tab === 'comments' ? '0' : '-1'}" data-detail-tab="comments" aria-selected="${tab === 'comments'}">评论${comments.length ? ` · ${comments.length}` : ''}</button>
      </div>
      ${tabContent}
    </article>
    <div class="detail-actions" aria-label="壁纸操作">
      <button type="button" data-subscribe="${id}" aria-pressed="${subscribed}">${subscribed ? '已订阅' : '订阅'}</button>
      <button type="button" data-favorite="${id}" aria-pressed="${favorite}">${favorite ? '已收藏' : '收藏'}</button>
      <md-filled-button id="detail-download">下载</md-filled-button>
    </div>`;
  document.title = `${item.title} · WallHub Web Preview`;
}

function renderPlaceholder() {
  const selected = destinations.find((item) => item.route === currentTopRoute()) ?? destinations[0];
  main.innerHTML = `${shellHeader(selected.label)}<section class="placeholder"><h2>${selected.label} · Current UI</h2><p>此页面将在后续垂直切片中接入确定性状态。</p></section>`;
  document.title = `${selected.label} · WallHub Web Preview`;
}

function bindSharedControls() {
  document.querySelector('#dialog-button')?.addEventListener('click', () => dialog.show());
  document.querySelector('#theme-toggle')?.addEventListener('click', () => {
    theme = theme === 'dark' ? 'light' : 'dark';
    storage.set(themeKey, theme);
    document.documentElement.dataset.theme = theme;
    renderApp();
    requestAnimationFrame(() => document.querySelector('#theme-toggle')?.focus());
  });
  document.querySelector('#scenario-select')?.addEventListener('change', (event) => {
    const url = new URL(location.href);
    url.searchParams.set('lab', '1');
    url.searchParams.set('scenario', event.target.value);
    history.replaceState(history.state, '', url);
    backend.setScenario(event.target.value);
  });
  document.querySelector('#advance-clock')?.addEventListener('click', () => backend.advanceClock(3_600_000));
  document.querySelector('#reset-lab')?.addEventListener('click', () => {
    const url = new URL(location.href);
    url.searchParams.delete('scenario');
    history.replaceState(history.state, '', url);
    backend.reset();
  });
  document.querySelector('#retry-home')?.addEventListener('click', () => backend.setScenario('content'));
  document.querySelector('#search')?.addEventListener('change', (event) => backend.setQuery(event.target.value));
  document.querySelector('#search')?.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') backend.setQuery(event.target.value);
  });
  document.querySelectorAll('[data-view-mode]').forEach((button) => button.addEventListener('click', () => backend.setViewMode(button.dataset.viewMode)));
  document.querySelectorAll('[data-open-workshop]').forEach((button) => button.addEventListener('click', () => navigate(`/workshop/${button.dataset.openWorkshop}`)));
  document.querySelectorAll('[data-favorite]').forEach((button) => button.addEventListener('click', () => backend.toggleFavorite(button.dataset.favorite)));
  document.querySelectorAll('[data-download]').forEach((button) => button.addEventListener('click', () => backend.requestDownload(button.dataset.download)));
  document.querySelectorAll('[data-subscribe]').forEach((button) => button.addEventListener('click', () => backend.toggleSubscription(button.dataset.subscribe)));
  document.querySelector('#detail-back')?.addEventListener('click', () => {
    if (history.state?.wallhub === true && String(history.state.from).startsWith('/')) {
      history.back();
    } else {
      history.replaceState({wallhub: true, route: '/'}, '', '/');
      renderApp();
      main.focus();
    }
  });
  document.querySelector('#retry-detail')?.addEventListener('click', () => {
    const url = new URL(location.href);
    url.searchParams.delete('scenario');
    history.replaceState(history.state, '', url);
    backend.setScenario('content');
  });
  document.querySelector('#missing-detail-home')?.addEventListener('click', () => navigate('/'));
  const activateDetailTab = (tab, restoreFocus = false) => {
    const url = new URL(location.href);
    if (tab === 'comments') url.searchParams.set('tab', 'comments');
    else url.searchParams.delete('tab');
    history.replaceState(history.state, '', url);
    renderApp();
    if (restoreFocus) requestAnimationFrame(() => document.querySelector(`[data-detail-tab="${tab}"]`)?.focus());
  };
  document.querySelectorAll('[data-detail-tab]').forEach((button) => {
    button.addEventListener('click', () => activateDetailTab(button.dataset.detailTab, true));
    button.addEventListener('keydown', (event) => {
      const next = ['ArrowRight', 'End'].includes(event.key) ? 'comments' : ['ArrowLeft', 'Home'].includes(event.key) ? 'details' : null;
      if (next) {
        event.preventDefault();
        activateDetailTab(next, true);
      }
    });
  });
  document.querySelectorAll('[data-tag]').forEach((button) => button.addEventListener('click', () => {
    backend.setQuery(button.dataset.tag);
    navigate('/');
  }));
  document.querySelector('#copy-title')?.addEventListener('click', () => {
    const item = backend.workshop(location.pathname.split('/').pop());
    navigator.clipboard?.writeText(item?.title ?? '').catch(() => {});
  });
  document.querySelector('#open-steam')?.addEventListener('click', () => {
    const id = location.pathname.split('/').pop();
    window.open(`https://steamcommunity.com/sharedfiles/filedetails/?id=${encodeURIComponent(id)}`, '_blank', 'noopener,noreferrer');
  });
  document.querySelector('.play-button')?.addEventListener('click', (event) => {
    const playing = event.currentTarget.getAttribute('aria-pressed') === 'true';
    event.currentTarget.setAttribute('aria-pressed', String(!playing));
    event.currentTarget.textContent = playing ? '▶' : '❚❚';
  });
  document.querySelector('#detail-download')?.addEventListener('click', () => downloadDialog.show());
  document.querySelector('#close-download').onclick = () => downloadDialog.close();
  document.querySelectorAll('[data-download-format]').forEach((button) => button.onclick = () => {
    const id = location.pathname.split('/').pop();
    downloadDialog.addEventListener('closed', () => document.querySelector('#detail-download')?.focus(), {once: true});
    downloadDialog.close();
    backend.requestDownload(id, button.dataset.downloadFormat);
  });
  document.querySelector('#filter-button')?.addEventListener('click', () => {
    const snapshot = backend.snapshot();
    document.querySelector('#filter-sort').value = snapshot.filters.sort;
    document.querySelector('#filter-type').value = snapshot.filters.type;
    document.querySelector('#filter-exact').checked = snapshot.filters.exactPhrase;
    filterDialog.show();
  });
  document.querySelector('#reset-filters').onclick = () => {
    document.querySelector('#filter-sort').value = 'TRENDING';
    document.querySelector('#filter-type').value = 'ALL';
    document.querySelector('#filter-exact').checked = false;
  };
  document.querySelector('#apply-filters').onclick = () => {
    const filters = {
      sort: document.querySelector('#filter-sort').value,
      type: document.querySelector('#filter-type').value,
      exactPhrase: document.querySelector('#filter-exact').checked,
    };
    filterDialog.addEventListener('closed', () => document.querySelector('#filter-button')?.focus(), {once: true});
    filterDialog.close();
    backend.setFilters(filters);
  };
}

function renderApp() {
  const scenario = requestedScenario();
  if (scenario && backend.snapshot().scenario !== scenario) {
    backend.setScenario(scenario);
    return;
  }
  renderNavigation();
  if (location.pathname === '/') renderHome();
  else if (/^\/workshop\/\d+$/u.test(location.pathname)) renderDetail();
  else renderPlaceholder();
  bindSharedControls();
}

function applyWindowSizeClass() {
  const width = Math.round(window.visualViewport?.width ?? innerWidth);
  const next = width < contract.breakpoints.medium ? 'compact' : width < contract.breakpoints.expanded ? 'medium' : 'expanded';
  if (document.documentElement.dataset.windowClass !== next) document.documentElement.dataset.windowClass = next;
}

nav.addEventListener('click', (event) => {
  const button = event.target.closest('[data-route]');
  if (button) navigate(button.dataset.route);
});
window.addEventListener('popstate', renderApp);
window.addEventListener('resize', applyWindowSizeClass);
window.visualViewport?.addEventListener('resize', applyWindowSizeClass);
backend.subscribe(() => {
  const active = document.activeElement;
  const focusSelector =
    active?.dataset?.favorite ? `[data-favorite="${active.dataset.favorite}"]` :
    active?.dataset?.subscribe ? `[data-subscribe="${active.dataset.subscribe}"]` :
    active?.dataset?.download ? `[data-download="${active.dataset.download}"]` :
    active?.dataset?.viewMode ? `[data-view-mode="${active.dataset.viewMode}"]` :
    active?.id ? `#${CSS.escape(active.id)}` : null;
  renderApp();
  if (focusSelector) requestAnimationFrame(() => document.querySelector(focusSelector)?.focus());
});
document.documentElement.dataset.theme = theme;
applyWindowSizeClass();
renderApp();

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
const storage = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch { /* Persistence is optional. */ } },
};
const backend = new FakeBackend(storage);
const systemThemeQuery = matchMedia('(prefers-color-scheme: light)');
const effectiveTheme = (preference) => preference === 'LIGHT' || (preference === 'SYSTEM' && systemThemeQuery.matches) ? 'light' : 'dark';
let theme = effectiveTheme(backend.snapshot().settings.themePreference);
const accentSeeds = {DEFAULT: '#6750A4', BLUE: '#385CA8', GREEN: '#356A3F', RED: '#A33B4E', PURPLE: '#76518D'};
const parseHex = (hex) => [1, 3, 5].map((index) => Number.parseInt(hex.slice(index, index + 2), 16));
const toHex = (rgb) => `#${rgb.map((value) => Math.round(value).toString(16).padStart(2, '0')).join('')}`;
const mixHex = (foreground, background, amount) => toHex(parseHex(foreground).map((value, index) => value * amount + parseHex(background)[index] * (1 - amount)));
const relativeLuminance = (hex) => parseHex(hex).map((value) => value / 255).map((value) => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
const contrastText = (background) => {
  const luminance = relativeLuminance(background);
  const whiteContrast = 1.05 / (luminance + .05);
  const blackContrast = (luminance + .05) / .05;
  return whiteContrast >= blackContrast ? '#ffffff' : '#000000';
};
function applyAppearance() {
  const settings = backend.snapshot().settings;
  theme = effectiveTheme(settings.themePreference);
  document.documentElement.dataset.theme = theme;
  const seed = settings.useSystemMonet ? '#6750A4' : settings.accent === 'CUSTOM' ? settings.customAccentColor : accentSeeds[settings.accent] ?? accentSeeds.DEFAULT;
  const surface = theme === 'dark' ? '#1c1c1c' : '#f9fafb';
  const container = mixHex(seed, surface, theme === 'dark' ? .42 : .22);
  document.documentElement.style.setProperty('--md-sys-color-primary', seed);
  document.documentElement.style.setProperty('--md-sys-color-on-primary', contrastText(seed));
  document.documentElement.style.setProperty('--md-sys-color-primary-container', container);
  document.documentElement.style.setProperty('--md-sys-color-on-primary-container', contrastText(container));
}
systemThemeQuery.addEventListener('change', () => { if (backend.snapshot().settings.themePreference === 'SYSTEM') { applyAppearance(); renderApp(); } });

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
    <div slot="content">
      <div class="download-options" id="download-format-page">
        <p>选择加入下载队列的输出格式。</p>
        <button type="button" data-download-format="MPKG"><strong>MPKG</strong><span>推荐 · 保留 WallHub 动态功能</span></button>
        <button type="button" data-download-format="ZIP"><strong>ZIP</strong><span>原始创意工坊文件</span></button>
      </div>
      <form class="download-conversion-options" id="download-mpkg-page" hidden>
        <button type="button" id="back-download-format" aria-label="返回下载格式">←</button>
        <fieldset><legend>转换质量</legend><label><input type="radio" name="download-preset" value="COMPATIBILITY">兼容优先</label><label><input type="radio" name="download-preset" value="BALANCED" checked>均衡</label><label><input type="radio" name="download-preset" value="HIGH_QUALITY">高质量</label><label><input type="radio" name="download-preset" value="EXTREME_QUALITY">极致质量</label></fieldset>
        <label><input type="checkbox" id="download-preserve-audio">保留音频</label>
        <md-filled-button id="start-mpkg-download" type="submit">开始下载 MPKG</md-filled-button>
      </form>
    </div>
    <div slot="actions"><md-text-button id="close-download">关闭</md-text-button></div>
  </md-dialog>
`;
const nav = document.querySelector('.primary-nav');
const main = document.querySelector('#main-content');
const dialog = document.querySelector('#focus-dialog');
const filterDialog = document.querySelector('#filter-dialog');
const downloadDialog = document.querySelector('#download-dialog');
let pendingLibraryRemoval = null;
let confirmLibraryRemoval = false;
let pendingLocalDelete = null;
let confirmLocalDelete = false;
let localSearchTimer = null;
let settingsReturnFocus = null;
let pendingReconversion = null;
let reconvertDismissed = false;
let pendingDetailDownload = null;
let downloadDismissed = false;
let detailDownloadFeedbackTimer = null;
let clearSourceDismissed = false;
let clearSourceModalActive = false;
let pendingClearSource = false;
let updateDownloadAttempt = 0;
let fullscreenVideoId = null;
let inlineVideo = {id: null, state: 'idle', attempt: 0, timer: null};
let renderedWorkshopId = null;

function resetInlineVideo() {
  if (inlineVideo.timer) clearTimeout(inlineVideo.timer);
  inlineVideo = {id: null, state: 'idle', attempt: inlineVideo.attempt + 1, timer: null};
  fullscreenVideoId = null;
}

function setInlineVideoState(id, state) {
  inlineVideo = {...inlineVideo, id: String(id), state};
  renderApp();
}

function focusControl(selector) {
  const element = document.querySelector(selector);
  element?.focus();
  if (document.activeElement !== element) element?.shadowRoot?.querySelector('button, input, textarea, select')?.focus();
}

function showDownloadStep(step) {
  document.querySelector('#download-format-page').hidden = step !== 'format';
  document.querySelector('#download-mpkg-page').hidden = step !== 'mpkg';
  requestAnimationFrame(() => document.querySelector(step === 'mpkg' ? '#back-download-format' : '[data-download-format="MPKG"]')?.focus());
}

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
  if (location.pathname.startsWith('/discover/')) return '/discover';
  if (location.pathname.startsWith('/profile/') || location.pathname.startsWith('/library/') || ['/downloads', '/local', '/settings'].includes(location.pathname) || location.pathname.startsWith('/local/') || location.pathname.startsWith('/settings/') || location.pathname.startsWith('/downloads/')) return '/profile';
  return destinations.some((item) => item.route === location.pathname) ? location.pathname : '/';
}

function navigate(destination) {
  history.pushState({wallhub: true, from: `${location.pathname}${location.search}`, route: destination}, '', destination);
  renderApp();
  main.focus();
}

function renderNavigation() {
  const route = currentTopRoute();
  const secondary = !destinations.some((item) => item.route === location.pathname);
  nav.hidden = secondary;
  document.documentElement.dataset.secondary = String(secondary);
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
      ${snapshot.steamAuthPhase === 'device-confirmation' ? '<button type="button" id="lab-confirm-steam">模拟 Steam 后端批准</button>' : ''}
      ${/^\/workshop\/\d+$/u.test(location.pathname) && inlineVideo.id === location.pathname.split('/').pop() && ['loading', 'playing'].includes(inlineVideo.state) ? '<button type="button" id="lab-fail-video">模拟视频失败</button>' : ''}
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

function isValidDownloadProxy(value) {
  try { const parsed = new URL(value); return ['http:', 'https:', 'socks:', 'socks5:'].includes(parsed.protocol) && Boolean(parsed.hostname) && !parsed.username && !parsed.password && !parsed.search && !parsed.hash && ['', '/'].includes(parsed.pathname) && (!parsed.port || (Number(parsed.port) >= 1 && Number(parsed.port) <= 65535)); } catch { return false; }
}

function settingsSelect(title, key, selected, options, disabled = false) {
  return `<label class="settings-select"><span><strong>${title}</strong></span><select data-setting-select="${key}" ${disabled ? 'disabled' : ''}>${options.map(([value, label]) => `<option value="${value}" ${String(selected) === String(value) ? 'selected' : ''}>${label}</option>`).join('')}</select></label>`;
}

function renderSettings() {
  const slug = location.pathname.match(/^\/settings\/([a-z-]+)$/u)?.[1] ?? null;
  const snapshot = backend.snapshot();
  const state = backend.settingsState();
  const categories = [
    {slug: 'basic', title: '基础', description: '内容、通知与应用行为'},
    {slug: 'appearance', title: '外观', description: '主题、强调色与首页布局'},
    {slug: 'downloads', title: '下载', description: '目录、转换与网络访问'},
    {slug: 'steam', title: 'Steam', description: '账户、会话与内容数据源'},
  ];
  let body;
  if (!slug) {
    body = `<div class="settings-list">${categories.map((category) => `<button type="button" data-settings-category="${category.slug}"><span><strong>${category.title}</strong><small>${category.description}</small></span><span aria-hidden="true">›</span></button>`).join('')}<button type="button" data-settings-category="about"><span><strong>关于 WallHub</strong><small>版本、更新与诊断</small></span><span aria-hidden="true">›</span></button></div>`;
  } else if (slug === 'steam') {
    const steamBody = state.steamPhase === 'signed-in'
      ? '<h2>Astra</h2><p>Steam 会话已连接</p><md-outlined-button id="steam-logout">退出登录</md-outlined-button>'
      : state.steamPhase === 'restoring'
        ? '<md-circular-progress indeterminate></md-circular-progress><h2>正在恢复 Steam 会话…</h2>'
        : state.steamPhase === 'failed'
          ? '<h2>Steam 连接失败</h2><p>可重试恢复已有会话。</p><md-filled-button id="steam-retry">重试</md-filled-button>'
          : state.steamPhase === 'device-confirmation'
            ? `<h2>在 Steam 手机应用中确认</h2><p>批准登录请求，或改用 Steam Guard 验证码。</p><md-outlined-button id="steam-use-code">使用验证码</md-outlined-button><md-text-button id="steam-cancel-auth">取消</md-text-button>`
            : state.steamPhase === 'waiting-code'
              ? '<h2>输入 Steam Guard 验证码</h2><md-outlined-text-field id="steam-guard-code" label="手机验证码" inputmode="numeric" autocomplete="one-time-code"></md-outlined-text-field><md-filled-button id="steam-submit-code" disabled>提交验证码</md-filled-button><md-text-button id="steam-cancel-auth">取消</md-text-button>'
              : '<h2>登录 Steam</h2><p>登录后同步订阅、收藏和好友活动。</p><md-outlined-text-field id="steam-account" label="账户名称" autocomplete="username"></md-outlined-text-field><md-outlined-text-field id="steam-password" label="密码" type="password" autocomplete="current-password"></md-outlined-text-field><md-filled-button id="steam-login" disabled>登录</md-filled-button>';
    body = `<section class="settings-group steam-account-card" id="steam-account-status" tabindex="-1">${steamBody}</section><section class="settings-group"><label class="settings-switch"><span><strong>增强访问</strong><small>通过兼容连接路径访问 Steam</small></span><input type="checkbox" data-setting-toggle="enhancedAccess" ${state.preferences.enhancedAccess ? 'checked' : ''}></label></section>`;
  } else if (slug === 'basic') {
    const languages = {SYSTEM: '跟随系统', SIMPLIFIED_CHINESE: '简体中文', ENGLISH: 'English'};
    body = `<section class="settings-section"><h2>语言</h2><button type="button" class="settings-row" id="open-language"><span><strong>语言</strong><small>${languages[state.preferences.language]}</small></span><span>›</span></button></section><section class="settings-section"><h2>诊断与支持</h2><button type="button" class="settings-row" id="export-diagnostics"><span><strong>导出诊断日志</strong><small>${state.preferences.diagnosticMessage || '导出用于排查问题的文本日志'}</small></span><span>›</span></button>${state.preferences.diagnosticMessage ? `<p class="settings-notice" role="status">${escapeHtml(state.preferences.diagnosticMessage)}</p>` : ''}</section><section class="settings-section"><h2>内容访问</h2><label class="settings-switch"><span><strong>成人内容</strong><small>在浏览与搜索中允许成熟内容</small></span><input type="checkbox" data-setting-toggle="matureContent" ${state.preferences.matureContent ? 'checked' : ''}></label></section><section class="settings-section"><h2>系统权限</h2><button type="button" class="settings-row" id="notifications-setting"><span><strong>后台任务通知</strong><small>${state.preferences.notificationsGranted ? '通知权限已授予' : '允许显示下载与更新进度'}</small></span><span>›</span></button></section><md-dialog id="language-dialog" aria-label="选择语言"><div slot="headline">语言</div><form slot="content" class="settings-choice-list" id="language-form">${Object.entries(languages).map(([value,label]) => `<label><input type="radio" name="language" value="${value}" ${state.preferences.language === value ? 'checked' : ''}>${label}</label>`).join('')}</form><div slot="actions"><md-text-button id="cancel-language">取消</md-text-button></div></md-dialog>`;
  } else if (slug === 'appearance') {
    body = `<section class="settings-section"><h2>主题</h2>${settingsSelect('外观模式', 'themePreference', state.preferences.themePreference, [['SYSTEM','跟随系统'],['LIGHT','浅色'],['DARK','深色']])}</section><section class="settings-section"><h2>个性化颜色</h2><label class="settings-switch"><span><strong>系统动态颜色</strong><small>${state.preferences.useSystemMonet ? 'Web 使用 WallHub 兼容动态配色' : '使用静态调色板'}</small></span><input type="checkbox" data-setting-toggle="useSystemMonet" ${state.preferences.useSystemMonet ? 'checked' : ''}></label><label class="settings-switch"><span><strong>主题应用图标</strong><small>Android 13 及以上版本</small></span><input type="checkbox" data-setting-toggle="themedLauncherIcon" ${state.preferences.themedLauncherIcon ? 'checked' : ''}></label>${settingsSelect('静态调色板', 'accent', state.preferences.accent, [['DEFAULT','默认'],['BLUE','蓝色'],['GREEN','绿色'],['RED','红色'],['PURPLE','紫色'],['CUSTOM','自定义']], state.preferences.useSystemMonet)}</section><section class="settings-section"><h2>发现</h2>${settingsSelect('每页项目数', 'homePageSize', state.preferences.homePageSize, [[10,'10'],[15,'15'],[30,'30'],[50,'50']])}${settingsSelect('分页方式', 'homePaginationMode', state.preferences.homePaginationMode, [['INFINITE','无限滚动'],['PAGED','网页式分页']])}${settingsSelect('手机列数', 'homeColumns', state.preferences.homeColumns, [[1,'1 列'],[2,'2 列'],[3,'3 列'],[4,'4 列']])}<label class="settings-switch"><span><strong>首页浮动搜索</strong><small>显示浮动搜索按钮</small></span><input type="checkbox" data-setting-toggle="homeSearchFab" ${state.preferences.homeSearchFab ? 'checked' : ''}></label><label class="settings-switch"><span><strong>多选类型与分级</strong><small>筛选器允许同时选择多个选项</small></span><input type="checkbox" data-setting-toggle="homeFilterMultiSelect" ${state.preferences.homeFilterMultiSelect ? 'checked' : ''}></label>${settingsSelect('默认卡片操作', 'homeCardAction', state.preferences.homeCardAction, [['DOWNLOAD','下载'],['PLAY','播放'],['STEAM','Steam']])}</section><md-dialog id="custom-accent-dialog" aria-label="自定义调色板"><div slot="headline">自定义调色板</div><div slot="content" class="custom-accent-editor"><div id="custom-accent-preview" style="background:${escapeHtml(state.preferences.customAccentColor)}"></div><label>自定义颜色<input id="custom-accent-color" type="color" value="${escapeHtml(state.preferences.customAccentColor)}"></label><div class="hue-presets" aria-label="色相预设">${['#A33B4E','#B44B28','#9A6500','#6F7300','#356A3F','#006B5E','#006590','#385CA8','#5B5FB5','#76518D','#92506F','#9E4057'].map((color) => `<button type="button" data-custom-accent="${color}" aria-label="选择 ${color}" style="background:${color}"></button>`).join('')}</div></div><div slot="actions"><md-text-button id="cancel-custom-accent">取消</md-text-button><md-filled-button id="apply-custom-accent">应用</md-filled-button></div></md-dialog>`;
  } else if (slug === 'downloads') {
    const sourceCache = formatBytes(state.preferences.workshopSourceCacheRetainedBytes);
    const streamCache = formatBytes(state.preferences.onlineStreamCacheBytes);
    body = `<section class="settings-section"><h2>存储位置</h2><div class="settings-row"><span><strong>当前导出目录</strong><small>${escapeHtml(state.preferences.outputDirectoryCustom ? state.preferences.outputDirectory : '默认：Download/WallHub')}</small></span></div><button type="button" class="settings-row" id="choose-output-directory"><span><strong>${state.preferences.outputDirectoryCustom ? '更改自定义目录' : '选择自定义目录'}</strong></span><span>›</span></button>${state.preferences.outputDirectoryCustom ? '<button type="button" class="settings-row" id="restore-output-directory"><span><strong>恢复默认目录</strong></span><span>›</span></button>' : ''}</section><section class="settings-section"><h2>MPKG 转换</h2>${settingsSelect('默认转换质量', 'detailDownloadPreset', state.preferences.detailDownloadPreset, [['BALANCED','性能优先'],['COMPATIBILITY','高性能'],['HIGH_QUALITY','高质量'],['EXTREME_QUALITY','极致质量']])}</section><section class="settings-section"><h2>创意工坊源文件缓存</h2><label class="settings-switch"><span><strong>保留已下载源文件</strong><small>用于重新转换和恢复</small></span><input type="checkbox" data-setting-toggle="workshopSourceCacheEnabled" ${state.preferences.workshopSourceCacheEnabled ? 'checked' : ''}></label>${settingsSelect('请求缓存上限', 'workshopSourceCacheBudgetGiB', [1,2,5,10].includes(state.preferences.workshopSourceCacheBudgetGiB) ? state.preferences.workshopSourceCacheBudgetGiB : 'CUSTOM', [[1,'1 GiB'],[2,'2 GiB'],[5,'5 GiB'],[10,'10 GiB'],['CUSTOM',`自定义 · ${state.preferences.workshopSourceCacheBudgetGiB} GiB`]])}<div class="settings-row"><span><strong>已保留</strong><small>${sourceCache}</small></span></div><p class="settings-notice">有效上限取请求值、可用空间 10% 与 5 GiB 中的最小值。</p><button type="button" class="settings-row" id="clear-source-cache"><span><strong>立即清除</strong><small>不删除已导出的壁纸</small></span><span>›</span></button></section><section class="settings-section"><h2>下载性能</h2>${settingsSelect('并发下载任务', 'maxConcurrentDownloads', state.preferences.maxConcurrentDownloads, [1,2,3,4,5,6,7,8].map((value) => [value,String(value)]))}${settingsSelect('每个任务分块数', 'chunkDownloadConcurrency', state.preferences.chunkDownloadConcurrency, [1,2,4,8,12,16,24,32,48].map((value) => [value,String(value)]))}</section><section class="settings-section"><h2>在线播放</h2><label class="settings-switch"><span><strong>Steam 分块流式播放</strong><small>关闭时先完整下载</small></span><input type="checkbox" data-setting-toggle="onlineChunkPlaybackEnabled" ${state.preferences.onlineChunkPlaybackEnabled ? 'checked' : ''}></label>${settingsSelect('在线播放缓存', 'onlineStreamCacheLimitMb', [512,1024,2048,5120,8192].includes(state.preferences.onlineStreamCacheLimitMb) ? state.preferences.onlineStreamCacheLimitMb : 'CUSTOM', [[512,'512 MB'],[1024,'1 GB'],[2048,'2 GB'],[5120,'5 GB'],[8192,'8 GB'],['CUSTOM',`自定义 · ${state.preferences.onlineStreamCacheLimitMb} MB`]])}<div class="settings-row"><span><strong>当前缓存</strong><small>${streamCache}</small></span></div><button type="button" class="settings-row" id="clear-stream-cache"><span><strong>清除播放缓存</strong></span><span>›</span></button></section><section class="settings-section"><h2>网络代理</h2><label class="settings-switch"><span><strong>启用下载代理</strong><small>${state.preferences.downloadProxyUrl || '请先保存有效代理地址'}</small></span><input type="checkbox" data-setting-toggle="downloadProxyEnabled" ${state.preferences.downloadProxyEnabled ? 'checked' : ''} ${isValidDownloadProxy(state.preferences.downloadProxyUrl) ? '' : 'disabled'}></label><label class="settings-field">代理地址<input id="download-proxy-url" value="${escapeHtml(state.preferences.downloadProxyUrl)}" placeholder="socks5://127.0.0.1:1080"></label><md-filled-button id="save-download-proxy">保存</md-filled-button></section><md-dialog id="clear-source-dialog" aria-label="清除源文件缓存"><div slot="headline">清除源文件缓存？</div><div slot="content">将删除私有暂存、检查点与转换计划，但不会删除已导出的壁纸。</div><div slot="actions"><md-text-button id="cancel-clear-source">取消</md-text-button><md-filled-button id="confirm-clear-source">立即清除</md-filled-button></div></md-dialog><md-dialog id="custom-source-cache-dialog" aria-label="自定义源文件缓存"><div slot="headline">自定义缓存上限</div><label slot="content" class="settings-field">GiB<input id="custom-source-cache" type="number" min="1" step="1" value="${state.preferences.workshopSourceCacheBudgetGiB}"></label><div slot="actions"><md-text-button id="cancel-custom-source">取消</md-text-button><md-filled-button id="apply-custom-source">应用</md-filled-button></div></md-dialog><md-dialog id="custom-stream-cache-dialog" aria-label="自定义在线播放缓存"><div slot="headline">自定义在线播放缓存</div><label slot="content" class="settings-field">MB<input id="custom-stream-cache" type="number" min="128" step="1" value="${state.preferences.onlineStreamCacheLimitMb}"></label><div slot="actions"><md-text-button id="cancel-custom-stream">取消</md-text-button><md-filled-button id="apply-custom-stream">应用</md-filled-button></div></md-dialog>`;
  } else {
    const update = state.preferences.aboutUpdatePhase;
    const updateAction = update === 'AVAILABLE' || update === 'FAILED' ? '<md-filled-button id="download-update">下载最新通用 APK</md-filled-button>' : update === 'DOWNLOADING' ? '<progress max="1" value="0.54" aria-label="更新下载进度 54%"></progress><p>27.0 / 50.0 MB</p><md-text-button id="cancel-update">取消</md-text-button>' : update === 'DOWNLOADED' ? '<md-filled-button id="install-update">安装更新</md-filled-button>' : '';
    body = `<section class="about-hero"><div class="about-logo" aria-hidden="true">W</div><h2>WallHub</h2><p>面向 Wallpaper Engine 创意工坊的开源 Android 客户端</p></section><section class="settings-section"><h2>开发者</h2>${['CHENLEO_7','uwugl'].map((name) => `<button type="button" class="settings-row" data-about-person="${name}" data-about-account="${name === 'CHENLEO_7' ? 'ChEnLeo-7' : name === 'uwugl' ? 'uwu-gl' : name}"><span><strong>${name}</strong><small>${name === 'CHENLEO_7' ? '作者、后端维护与问题修复' : '前端、设计与指导'}</small></span><span>↗</span></button>`).join('')}</section><section class="settings-section"><h2>贡献者</h2>${['cccp114','hf5203344'].map((name) => `<button type="button" class="settings-row" data-about-person="${name}" data-about-account="${name === 'CHENLEO_7' ? 'ChEnLeo-7' : name === 'uwugl' ? 'uwu-gl' : name}"><span><strong>${name}</strong><small>${name === 'cccp114' ? 'Logo 与 UI' : '早期测试与 UI'}</small></span><span>↗</span></button>`).join('')}</section><section class="settings-section"><h2>应用</h2><button type="button" class="settings-row" id="check-updates" ${['CHECKING','DOWNLOADING'].includes(update) ? 'disabled' : ''}><span><strong>${update === 'CHECKING' ? '正在检查…' : '版本 2.0.0'}</strong><small>检查 GitHub 更新</small></span><span>›</span></button><div class="settings-row"><span><strong>更新日期</strong><small>2026-10-28</small></span></div>${state.preferences.aboutUpdateMessage ? `<p class="settings-notice" role="status">${escapeHtml(state.preferences.aboutUpdateMessage)}</p>` : ''}<div class="update-actions">${updateAction}</div></section><section class="settings-section"><h2>项目链接</h2><button type="button" class="settings-row" id="open-repository"><span><strong>GitHub 仓库</strong><small>ChEnLeo-7/WallHub2.0-For-Android</small></span><span>↗</span></button><button type="button" class="settings-row" id="open-qq-group"><span><strong>QQ 群</strong><small>1082323527</small></span><span>↗</span></button><button type="button" class="settings-row" id="restart-setup"><span><strong>重新运行设置向导</strong></span><span>›</span></button></section>`;
  }
  main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back" aria-label="返回">←</button><h1>${slug ? categories.find((category) => category.slug === slug)?.title ?? '关于 WallHub' : '设置'}</h1></header>${isLabVisible() ? renderStateLab(snapshot) : ''}<p id="settings-status" class="sr-only" aria-live="polite"></p><section class="settings-page">${body}</section>`;
  document.title = `${slug ? '设置 · ' + slug : '设置'} · WallHub Web Preview`;
}

function renderLocalDetail() {
  const id = location.pathname.match(/^\/local\/([a-z0-9-]+)$/u)?.[1];
  const item = id ? backend.localWallpaper(id) : null;
  if (!item) {
    main.innerHTML = '<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back">←</button><h1>本地壁纸详情</h1></header><div class="center-state" role="alert"><h2>找不到此本地壁纸</h2></div>';
    return;
  }
  main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back" aria-label="返回本地壁纸">←</button><h1>${escapeHtml(item.title)}</h1></header><section class="local-detail"><div class="local-detail-preview"><span>${item.type}</span></div><div class="description"><h2>信息</h2><dl class="detail-metrics"><div><dt>来源</dt><dd>${escapeHtml(item.source)}</dd></div><div><dt>本地 ID</dt><dd>${item.id}</dd></div><div><dt>类型</dt><dd>${item.type}</dd></div></dl><div class="tag-list">${item.tags.map((tag) => `<span>${escapeHtml(tag)}</span>`).join('')}</div></div><div class="local-detail-actions"><md-filled-button id="local-open-native">打开</md-filled-button><md-outlined-button id="local-share">分享</md-outlined-button><md-outlined-button id="local-copy-location">复制位置</md-outlined-button><button type="button" data-local-favorite="${item.id}" aria-pressed="${item.favorite}">${item.favorite ? '已收藏' : '收藏'}</button></div><p id="local-status" class="sr-only" aria-live="polite"></p></section>`;
  document.title = `${item.title} · WallHub Web Preview`;
}

function renderLocal() {
  const snapshot = backend.snapshot();
  const result = backend.localWallpapers();
  let content;
  if (result.status === 'loading') content = '<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress><p>正在扫描本地壁纸…</p></div>';
  else if (result.status === 'error') content = '<div class="center-state" role="alert"><h2>无法扫描本地壁纸</h2><md-filled-button id="retry-local">重试</md-filled-button></div>';
  else if (result.status === 'empty') content = '<div class="center-state"><h2>没有匹配的本地壁纸</h2></div>';
  else content = `<div class="local-grid ${snapshot.localViewMode === 'list' ? 'is-list' : ''}">${result.items.map((item) => `<article class="local-item" data-local-item="${item.id}" tabindex="-1"><button type="button" class="local-open" data-local-open="${item.id}"><div class="local-preview"><span>${item.type}</span></div><div><h2>${escapeHtml(item.title)}</h2><p>${escapeHtml(item.source)} · ${item.tags.map(escapeHtml).join(' · ')}</p></div></button><div class="local-actions"><button type="button" data-local-favorite="${item.id}" aria-pressed="${item.favorite}">${item.favorite ? '已收藏' : '收藏'}</button><button type="button" data-local-delete="${item.id}">删除</button></div></article>`).join('')}</div>`;
  main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back" aria-label="返回我的">←</button><h1>本地壁纸</h1><div class="toolbar-actions"><md-outlined-button id="local-view-toggle">${snapshot.localViewMode === 'grid' ? '列表' : '网格'}</md-outlined-button></div></header>${isLabVisible() ? renderStateLab(snapshot) : ''}<div class="local-tools"><md-outlined-text-field id="local-search" label="搜索本地壁纸" value="${escapeHtml(snapshot.localQuery)}"></md-outlined-text-field></div><p id="local-status" class="sr-only" aria-live="polite"></p><section class="local-page">${content}</section><md-dialog id="local-delete-dialog" aria-label="确认删除"><div slot="headline">删除本地壁纸？</div><div slot="content">此操作将从确定性预览中移除此项目。</div><div slot="actions"><md-text-button id="cancel-local-delete">取消</md-text-button><md-filled-button id="confirm-local-delete">删除</md-filled-button></div></md-dialog>`;
  document.title = '本地壁纸 · WallHub Web Preview';
}

function renderDownloadTaskDetail() {
  const taskId = location.pathname.match(/^\/downloads\/task\/([a-z0-9-]+)$/u)?.[1];
  const task = taskId ? backend.downloadTask(taskId) : null;
  if (!task) {
    main.innerHTML = '<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back">←</button><h1>下载任务</h1></header><div class="center-state" role="alert"><h2>找不到此下载任务</h2></div>';
    return;
  }
  const title = task.workshop?.title ?? task.workshopId;
  const statusLabels = {QUEUED: '等待中', RESOLVING: '正在解析', DOWNLOADING: '下载中', PAUSED: '已暂停', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消'};
  const canReconvert = task.status === 'COMPLETED' && task.format === 'MPKG' && task.workshop?.type === 'SCENE';
  const canPlay = task.status === 'COMPLETED' && task.workshop?.type === 'VIDEO' && task.stagingAvailable;
  const canExport = task.status === 'FAILED' && task.hasIntactStaging;
  main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back" aria-label="返回下载">←</button><h1>下载任务</h1></header><section class="download-detail"><div class="download-detail-hero"><span>${task.workshop?.type ?? task.format}</span><h2>${escapeHtml(title)}</h2><p>${statusLabels[task.status]} · ${task.format}</p><progress max="1" value="${task.progress}" aria-label="进度 ${Math.round(task.progress * 100)}%"></progress></div><dl class="detail-metrics"><div><dt>输出格式</dt><dd>${task.format}</dd></div><div><dt>转换档位</dt><dd>${task.preset}</dd></div><div><dt>保留音频</dt><dd>${task.preserveAudio ? '是' : '否'}</dd></div><div><dt>任务 ID</dt><dd>${task.id}</dd></div></dl>${task.message ? `<div class="download-failure" role="alert"><h3>失败详情</h3><p>${escapeHtml(task.message)}</p>${task.rawZipExported ? '<strong>原始 ZIP 已导出</strong>' : ''}</div>` : ''}<div class="download-detail-actions"><md-outlined-button id="open-download-workshop">打开项目</md-outlined-button>${canPlay ? '<md-filled-button id="play-downloaded-video">播放视频</md-filled-button>' : ''}${canReconvert ? '<md-filled-button id="open-reconvert">重新转换</md-filled-button>' : ''}${canExport ? '<md-filled-button id="export-raw-zip">导出原始 ZIP</md-filled-button>' : ''}</div><p id="download-detail-status" class="sr-only" aria-live="polite"></p></section><md-dialog id="reconvert-dialog" aria-label="重新转换"><div slot="headline">重新转换 MPKG</div><div slot="content" class="reconvert-form"><label>转换档位<select id="reconvert-preset"><option value="COMPATIBILITY">兼容优先</option><option value="BALANCED">均衡</option><option value="HIGH_QUALITY">高质量</option><option value="EXTREME_QUALITY">极致质量</option></select></label><label><input type="checkbox" id="reconvert-audio" ${task.preserveAudio ? 'checked' : ''}>保留音频</label></div><div slot="actions"><md-text-button id="cancel-reconvert">取消</md-text-button><md-filled-button id="confirm-reconvert">开始转换</md-filled-button></div></md-dialog>`;
  const preset = document.querySelector('#reconvert-preset'); if (preset) preset.value = task.preset;
  document.title = `${title} · 下载任务 · WallHub Web Preview`;
}

function renderDownloads() {
  const result = backend.downloadTaskGroups();
  const tab = new URLSearchParams(location.search).get('tab') === 'completed' ? 'completed' : 'active';
  const statusLabels = {QUEUED: '等待中', RESOLVING: '正在解析', DOWNLOADING: '下载中', PAUSED: '已暂停', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消'};
  const tasks = tab === 'completed' ? result.completed : result.active;
  const reorderableTasks = result.active.filter((task) => ['QUEUED', 'RESOLVING', 'DOWNLOADING', 'PAUSED'].includes(task.status));
  let content;
  if (result.status === 'loading') content = '<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress></div>';
  else if (result.status === 'error') content = '<div class="center-state" role="alert"><h2>无法载入下载任务</h2><md-filled-button id="retry-downloads">重试</md-filled-button></div>';
  else if (result.status === 'empty' || tasks.length === 0) content = `<div class="center-state"><h2>${tab === 'active' ? '没有活动任务' : '没有已完成任务'}</h2></div>`;
  else content = `<div class="download-task-list">${tasks.map((task, index) => {
    const title = task.workshop?.title ?? task.workshopId;
    const primaryAction = ['DOWNLOADING', 'QUEUED', 'RESOLVING'].includes(task.status) ? 'PAUSE' : task.status === 'PAUSED' ? 'RESUME' : ['FAILED', 'CANCELLED'].includes(task.status) ? 'RETRY' : null;
    const secondaryAction = task.status === 'CANCELLED' ? 'DELETE' : tab === 'active' ? 'CANCEL' : null;
    const reorderIndex = reorderableTasks.findIndex((candidate) => candidate.id === task.id);
    return `<article class="download-task" data-download-task="${task.id}" tabindex="-1"><div class="download-task-icon" aria-hidden="true">↓</div><button type="button" class="download-task-open" data-open-download-task="${task.id}"><h2>${escapeHtml(title)}</h2><p>${statusLabels[task.status]} · ${task.format}</p><progress max="1" value="${task.progress}" aria-label="${escapeHtml(title)} ${Math.round(task.progress * 100)}%"></progress></button><div class="download-task-actions">${tab === 'active' && reorderIndex >= 0 ? `<div class="download-reorder" role="group" aria-label="调整 ${escapeHtml(title)} 的队列位置"><button type="button" data-task-move="up" data-task-id="${task.id}" aria-label="上移 ${escapeHtml(title)}" ${reorderIndex === 0 ? 'disabled' : ''}>↑</button><button type="button" data-task-move="down" data-task-id="${task.id}" aria-label="下移 ${escapeHtml(title)}" ${reorderIndex === reorderableTasks.length - 1 ? 'disabled' : ''}>↓</button></div>` : ''}${primaryAction ? `<md-outlined-button data-task-action="${primaryAction}" data-task-id="${task.id}">${{PAUSE: '暂停', RESUME: '继续', RETRY: '重试'}[primaryAction]}</md-outlined-button>` : ''}${secondaryAction ? `<md-text-button data-task-action="${secondaryAction}" data-task-id="${task.id}">${secondaryAction === 'DELETE' ? '删除' : '取消'}</md-text-button>` : ''}</div></article>`;
  }).join('')}</div>`;
  main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back" aria-label="返回我的">←</button><h1>下载</h1><div class="toolbar-actions"><md-outlined-button id="retry-failed-downloads">重试失败项</md-outlined-button><md-text-button id="clear-finished-downloads">清除已完成</md-text-button></div></header>${isLabVisible() ? renderStateLab(backend.snapshot()) : ''}<div class="library-tabs" role="tablist" aria-label="下载分类"><button type="button" role="tab" data-download-tab="active" aria-selected="${tab === 'active'}" tabindex="${tab === 'active' ? '0' : '-1'}">活动</button><button type="button" role="tab" data-download-tab="completed" aria-selected="${tab === 'completed'}" tabindex="${tab === 'completed' ? '0' : '-1'}">已完成</button></div><p id="downloads-status" class="sr-only" aria-live="polite"></p><section class="downloads-page">${content}</section>`;
  document.title = '下载 · WallHub Web Preview';
}

function renderLibrary() {
  const collection = location.pathname.endsWith('/favorites') ? 'favorites' : 'subscriptions';
  const result = backend.library(collection);
  const labels = {subscriptions: '订阅', favorites: '收藏'};
  let content;
  if (result.status === 'loading') content = '<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress></div>';
  else if (result.status === 'error') content = '<div class="center-state" role="alert"><h2>无法载入资料库</h2><md-filled-button id="retry-library">重试</md-filled-button></div>';
  else if (result.status === 'restoring') content = '<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress><h2>正在恢复 Steam 会话…</h2></div>';
  else if (result.status === 'auth') content = '<div class="center-state"><h2>登录后查看资料库</h2><md-filled-button id="library-login">登录 Steam</md-filled-button></div>';
  else if (result.status === 'empty') content = `<div class="center-state"><h2>${labels[collection]}中暂无项目</h2></div>`;
  else content = `<div class="workshop-grid library-grid">${result.items.map((item) => `<div class="library-item">${workshopCard(item, backend.snapshot())}<md-text-button data-library-remove="${item.id}">从${labels[collection]}移除</md-text-button></div>`).join('')}</div>`;
  main.innerHTML = `
    <header class="detail-top-bar"><button type="button" class="back-button" id="detail-back" aria-label="返回我的">←</button><h1>资料库</h1><div class="toolbar-actions"><md-outlined-button id="retry-library-toolbar">刷新</md-outlined-button></div></header>
    ${isLabVisible() ? renderStateLab(backend.snapshot()) : ''}
    <div class="library-tabs" role="tablist" aria-label="资料库分类">
      <button type="button" role="tab" data-library-tab="subscriptions" aria-selected="${collection === 'subscriptions'}" tabindex="${collection === 'subscriptions' ? '0' : '-1'}">订阅</button>
      <button type="button" role="tab" data-library-tab="favorites" aria-selected="${collection === 'favorites'}" tabindex="${collection === 'favorites' ? '0' : '-1'}">收藏</button>
    </div>
    <p id="library-status" class="sr-only" aria-live="polite"></p>
    <section class="library-page" aria-label="${labels[collection]}">${content}</section>
    <md-dialog id="library-remove-dialog" aria-label="确认移除"><div slot="headline">从${labels[collection]}移除？</div><div slot="content">此操作只更新当前确定性预览。</div><div slot="actions"><md-text-button id="cancel-library-remove">取消</md-text-button><md-filled-button id="confirm-library-remove">移除</md-filled-button></div></md-dialog>`;
  document.title = `${labels[collection]} · WallHub Web Preview`;
}

function renderProfile() {
  const snapshot = backend.snapshot();
  const profile = backend.profile();
  const signedIn = profile.session === 'signed-in';
  const restoring = profile.session === 'restoring';
  const countLabel = (value) => Number.isSafeInteger(value) ? compactNumber.format(value) : '-';
  const countState = (value) => Number.isSafeInteger(value) ? `${value}` : restoring ? '正在载入' : signedIn ? '暂不可用' : '需要登录';
  main.innerHTML = `
    <header class="top-bar"><div class="title-group"><p class="eyebrow">CURRENT UI · WEB MIRROR</p><h1>我的</h1></div><md-outlined-button id="open-settings">设置</md-outlined-button></header>
    ${isLabVisible() ? renderStateLab(snapshot) : ''}
    <section class="profile-page">
      <p id="profile-status" class="sr-only" aria-live="polite"></p>
      <div class="profile-identity"><div class="profile-avatar" aria-hidden="true">${signedIn || restoring ? 'A' : '?'}</div><div><h2>${signedIn || restoring ? 'Astra' : '匿名用户'}</h2><p>${signedIn ? 'wallhub_fixture · Wallpaper Engine 128 小时' : restoring ? '正在恢复 Steam 会话…' : '登录 Steam 以同步你的内容'}</p></div>${!signedIn && !restoring ? '<md-filled-button id="profile-login">登录 Steam</md-filled-button>' : ''}</div>
      <div class="profile-stats">
        <button type="button" data-profile-collection="subscriptions" ${restoring ? 'disabled' : ''} aria-label="订阅 ${countState(profile.counts.subscriptions)}"><span>${countLabel(profile.counts.subscriptions)}</span><strong>订阅</strong></button>
        <button type="button" data-profile-collection="favorites" ${restoring ? 'disabled' : ''} aria-label="收藏 ${countState(profile.counts.favorites)}"><span>${countLabel(profile.counts.favorites)}</span><strong>收藏</strong></button>
      </div>
      ${signedIn && Object.values(profile.counts).some((value) => value === null) ? '<md-outlined-button id="retry-profile-counts">重试统计</md-outlined-button>' : ''}
      <div class="profile-links">
        <button type="button" data-profile-route="/downloads"><span aria-hidden="true">↓</span><span><strong>下载</strong><small>查看活动与已完成任务</small></span><span aria-hidden="true">›</span></button>
        <button type="button" data-profile-route="/local"><span aria-hidden="true">▣</span><span><strong>本地壁纸</strong><small>管理设备上的壁纸</small></span><span aria-hidden="true">›</span></button>
      </div>
    </section>`;
  document.title = '我的 · WallHub Web Preview';
}

function renderDiscover() {
  const snapshot = backend.snapshot();
  const result = backend.discoverRails();
  let body;
  if (result.status === 'loading') {
    body = '<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress><p>正在准备发现内容…</p></div>';
  } else if (result.status === 'error') {
    body = '<div class="center-state" role="alert"><div class="state-icon">!</div><h2>无法载入发现内容</h2><md-filled-button id="retry-discover">重试</md-filled-button></div>';
  } else if (result.status === 'empty') {
    body = '<div class="center-state"><h2>暂无推荐内容</h2></div>';
  } else {
    const visibleRails = result.rails.filter((rail) => snapshot.discoverFeedback[rail.id]?.rating !== 'dislike');
    body = visibleRails.length ? visibleRails.map((rail) => {
      const feedback = snapshot.discoverFeedback[rail.id] ?? {rating: 'none', favorite: false};
      return `<section class="discover-rail" data-rail-id="${rail.id}" tabindex="-1" aria-labelledby="rail-${rail.id}-title">
        <div class="rail-header"><h2 id="rail-${rail.id}-title">${escapeHtml(rail.title)}</h2><button type="button" data-open-rail="${rail.id}">查看全部 →</button></div>
        <div class="discover-row">${rail.items.map((item) => `
          <button type="button" class="discover-card" data-open-workshop="${item.id}" aria-label="查看 ${escapeHtml(item.title)}">
            <div class="discover-cover card-media-${item.accent}"><span>${item.type}</span></div>
            <strong>${escapeHtml(item.title)}</strong><small>${escapeHtml(item.author)}</small>
          </button>`).join('')}</div>
        <div class="rail-feedback" aria-label="${escapeHtml(rail.title)} 反馈">
          <button type="button" data-rail-rating="like" data-rail="${rail.id}" aria-pressed="${feedback.rating === 'like'}">喜欢</button>
          <button type="button" data-rail-rating="dislike" data-rail="${rail.id}" aria-pressed="${feedback.rating === 'dislike'}">不感兴趣</button>
          <button type="button" data-rail-favorite="${rail.id}" aria-pressed="${feedback.favorite}">${feedback.favorite ? '已保存' : '保存'}</button>
        </div>
      </section>`;
    }).join('') : '<div class="center-state"><h2>已隐藏所有推荐内容</h2><p>可在状态实验室重置反馈。</p></div>';
  }
  main.innerHTML = `
    <header class="top-bar discover-top-bar">
      <div class="title-group"><p class="eyebrow">CURRENT UI · WEB MIRROR</p><details class="discover-content-menu"><summary>你的内容 <span aria-hidden="true">⌄</span></summary><div aria-label="你的内容">
        <button type="button" data-discover-route="following">我的关注</button>
        <button type="button" data-discover-route="friend-favorites">好友收藏</button>
        <button type="button" data-discover-route="friend-created">好友作品</button>
      </div></details></div>
      <md-outlined-button id="theme-toggle">${theme === 'dark' ? '浅色外观' : '深色外观'}</md-outlined-button>
    </header>
    ${isLabVisible() ? renderStateLab(snapshot) : ''}
    <p id="discover-status" class="sr-only" aria-live="polite"></p>
    <div class="discover-feed">${body}</div>`;
  document.title = '发现 · WallHub Web Preview';
}

function renderDiscoverFollowing() {
  const snapshot = backend.snapshot();
  const result = backend.savedQueryList();
  let content;
  if (result.status === 'auth') content = '<div class="center-state"><h2>需要登录 Steam</h2><md-filled-button id="following-login">登录</md-filled-button></div>';
  else if (result.status === 'restoring' || result.status === 'loading') content = '<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress><p>正在恢复 Steam 会话…</p></div>';
  else if (result.status === 'error') content = '<div class="center-state" role="alert"><h2>无法载入关注</h2></div>';
  else if (result.status === 'empty') content = '<div class="center-state"><h2>还没有关注的查询</h2><md-filled-button id="add-discover-query-empty">创建查询</md-filled-button></div>';
  else content = `<div class="following-list">${result.items.map((query) => `<article data-saved-query="${query.id}" tabindex="-1"><button type="button" data-open-saved-query="${query.id}"><span class="saved-query-icon">${query.category === 'KEYWORD' ? '⌕' : query.category === 'CREATOR' ? '♙' : '▣'}</span><span><strong>${escapeHtml(query.title)}</strong><small>${query.category === 'KEYWORD' ? '关键词' : query.category === 'CREATOR' ? '创作者' : '合集'} · ${escapeHtml(query.sort)}</small></span></button><button type="button" data-remove-saved-query="${query.id}" aria-label="移除 ${escapeHtml(query.title)}">删除</button></article>`).join('')}</div>`;
  main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back">←</button><h1>我的关注</h1>${snapshot.profileSession === 'signed-in' ? '<md-filled-button id="add-discover-query">创建查询</md-filled-button>' : ''}</header>${isLabVisible() ? renderStateLab(snapshot) : ''}<p id="discover-status" class="sr-only" aria-live="polite"></p><section class="discover-secondary">${content}</section>`;
  document.title = '我的关注 · WallHub Web Preview';
}

function renderDiscoverQueryEditor() {
  const session = backend.profile().session;
  if (session !== 'signed-in') {
    main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back">←</button><h1>创建查询</h1></header><div class="center-state">${session === 'restoring' ? '<md-circular-progress indeterminate></md-circular-progress><h2>正在恢复 Steam 会话…</h2>' : '<h2>需要登录 Steam</h2><md-filled-button id="following-login">登录</md-filled-button>'}</div>`;
    return;
  }
  main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back">←</button><h1>创建查询</h1></header><form class="query-editor" id="query-editor"><fieldset><legend>查询类别</legend>${[['KEYWORD','关键词'],['CREATOR','创作者'],['COLLECTION','合集']].map(([value,label], index) => `<label><input type="radio" name="query-category" value="${value}" ${index === 0 ? 'checked' : ''}>${label}</label>`).join('')}</fieldset><label>排序<select id="query-sort"><option value="TREND_YEAR">年度趋势</option><option value="TREND_WEEK">本周趋势</option><option value="RECENT">最近发布</option><option value="SUBSCRIPTIONS">订阅最多</option></select></label><md-outlined-text-field id="query-input" label="关键词" required></md-outlined-text-field><p id="query-error" role="alert"></p><div class="query-actions"><md-text-button type="button" id="cancel-query">取消</md-text-button><md-filled-button type="submit" id="save-query" disabled>保存</md-filled-button></div></form>`;
  document.title = '创建查询 · WallHub Web Preview';
}

function renderFriendDiscover() {
  const kind = location.pathname.endsWith('friend-created') ? 'created' : 'favorites';
  const result = backend.friendDiscover(kind);
  const title = kind === 'created' ? '好友作品' : '好友收藏';
  let content;
  if (result.status === 'auth') content = '<div class="center-state"><h2>需要登录 Steam</h2><md-filled-button id="friend-login">登录</md-filled-button></div>';
  else if (result.status === 'restoring' || result.status === 'loading') content = '<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress><p>正在载入好友活动…</p></div>';
  else if (result.status === 'error') content = '<div class="center-state" role="alert"><h2>无法载入好友活动</h2><md-filled-button id="retry-friends">重试</md-filled-button></div>';
  else if (result.status === 'empty') content = '<div class="center-state"><h2>暂无好友活动</h2></div>';
  else content = `<div class="workshop-grid discover-results-grid columns-2">${result.items.map((item) => workshopCard(item, backend.snapshot())).join('')}</div>`;
  main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back">←</button><h1>${title}</h1></header><section class="discover-results">${content}</section>`;
  document.title = `${title} · WallHub Web Preview`;
}

function renderDiscoverResults() {
  const railId = location.pathname.match(/^\/discover\/results\/([a-z0-9-]+)$/u)?.[1];
  const snapshot = backend.snapshot();
  const savedRoute = railId?.startsWith('query-') === true;
  const saved = savedRoute ? backend.savedQueryResults(railId.slice(6)) : null;
  const savedSession = savedRoute ? backend.profile().session : 'signed-in';
  const result = backend.discoverRails();
  const rail = saved ? {id: railId, title: saved.query.title, items: saved.items} : result.rails?.find((entry) => entry.id === railId);
  const title = rail?.title ?? '发现结果';
  let content;
  if (savedRoute && savedSession === 'signed-out') {
    content = '<div class="center-state"><h2>需要登录 Steam</h2><md-filled-button id="following-login">登录</md-filled-button></div>';
  } else if (savedRoute && savedSession === 'restoring') {
    content = '<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress><p>正在恢复 Steam 会话…</p></div>';
  } else if (result.status === 'loading') {
    content = '<div class="center-state" role="status"><md-circular-progress indeterminate></md-circular-progress></div>';
  } else if (result.status === 'error') {
    content = '<div class="center-state" role="alert"><h2>无法载入结果</h2><md-filled-button id="retry-discover">重试</md-filled-button></div>';
  } else if (!rail || result.status === 'empty') {
    content = '<div class="center-state"><h2>没有匹配的壁纸</h2></div>';
  } else {
    content = `<div class="workshop-grid discover-results-grid columns-${snapshot.discoverResultColumns}">${rail.items.map((item) => workshopCard(item, snapshot)).join('')}</div>`;
  }
  main.innerHTML = `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back" aria-label="返回发现">←</button><h1>${escapeHtml(title)}</h1><md-outlined-button id="discover-result-columns">${snapshot.discoverResultColumns === 1 ? '单列' : '双列'}</md-outlined-button></header><div class="discover-results">${content}</div>`;
  document.title = `${title} · WallHub Web Preview`;
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
  const videoState = item.type === 'VIDEO' && inlineVideo.id === id ? inlineVideo.state : 'idle';
  const videoControl = videoState === 'loading' ? '<div class="video-state" role="status"><md-circular-progress indeterminate></md-circular-progress><span>正在载入视频…</span></div>' : videoState === 'failed' ? '<div class="video-state" role="alert"><span>视频载入失败</span><button type="button" id="retry-inline-video">重试</button></div>' : videoState === 'playing' ? '<div class="video-state is-playing"><span>正在播放预览</span><button type="button" id="open-video-fullscreen">全屏</button></div>' : '<button type="button" class="play-button" aria-label="播放预览">▶</button>';
  const commentComposer = snapshot.profileSession === 'signed-in'
    ? '<form class="comment-composer" id="comment-composer"><md-outlined-text-field id="comment-draft" label="发表评论" type="textarea" rows="3" maxlength="1000" aria-describedby="comment-limit"></md-outlined-text-field><span id="comment-limit">最多 1000 字符 · 0/1000</span><md-filled-button id="submit-comment" type="submit" disabled>发布</md-filled-button></form>'
    : snapshot.profileSession === 'restoring'
      ? '<div class="comment-auth" role="status"><md-circular-progress indeterminate></md-circular-progress><span>正在恢复 Steam 会话…</span></div>'
      : '<div class="comment-auth"><span>登录 Steam 后发表评论</span><md-filled-button id="comment-login">登录</md-filled-button></div>';
  const tabContent = tab === 'comments' ? `
    <section class="comment-list" id="detail-tabpanel" role="tabpanel" aria-labelledby="detail-tab-comments" tabindex="0">
      ${comments.length ? comments.map((comment) => `<article class="comment"><div class="avatar" aria-hidden="true">${escapeHtml(comment.author.slice(0, 1))}</div><div><h3>${escapeHtml(comment.author)} ${comment.isCreator ? '<span>作者</span>' : ''}</h3><p class="comment-time">${relativeTime(comment.timestamp, snapshot.clockMs)}</p><p>${escapeHtml(comment.text)}</p></div></article>`).join('') : '<div class="center-state"><h2>暂无评论</h2></div>'}
      ${commentComposer}<p id="comment-status" class="sr-only" aria-live="polite"></p>
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
      <div class="detail-media card-media-${item.accent}"><div class="detail-media-image" role="img" aria-label="${escapeHtml(item.title)} 预览"></div><span>${item.type}</span>${item.type === 'VIDEO' ? videoControl : ''}</div>
      <div class="detail-tabs" role="tablist" aria-label="详情内容">
        <button type="button" role="tab" id="detail-tab-details" aria-controls="detail-tabpanel" tabindex="${tab === 'details' ? '0' : '-1'}" data-detail-tab="details" aria-selected="${tab === 'details'}">详情</button>
        <button type="button" role="tab" id="detail-tab-comments" aria-controls="detail-tabpanel" tabindex="${tab === 'comments' ? '0' : '-1'}" data-detail-tab="comments" aria-selected="${tab === 'comments'}">评论${comments.length ? ` · ${comments.length}` : ''}</button>
      </div>
      ${tabContent}
    </article>
    <div class="detail-actions" aria-label="壁纸操作">
      <button type="button" data-subscribe="${id}" aria-pressed="${subscribed}">${subscribed ? '已订阅' : '订阅'}</button>
      <button type="button" data-favorite="${id}" aria-pressed="${favorite}">${favorite ? '已收藏' : '收藏'}</button>
      <md-filled-button id="detail-download" tabindex="0">下载</md-filled-button>
    </div><div class="detail-download-feedback" id="detail-download-feedback" role="status" hidden><span></span><button type="button" id="open-download-queue">查看队列</button></div>${fullscreenVideoId === id && videoState === 'playing' ? `<div class="video-fullscreen" role="dialog" aria-modal="true" aria-label="${escapeHtml(item.title)} 全屏视频"><span>正在全屏播放 ${escapeHtml(item.title)}</span><button type="button" id="exit-video-fullscreen" aria-label="退出全屏">×</button></div>` : ''}`;
  if (fullscreenVideoId === id && videoState === 'playing') {
    document.querySelectorAll('#main-content > :not(.video-fullscreen)').forEach((element) => { element.inert = true; });
  }
  document.title = `${item.title} · WallHub Web Preview`;
}

function renderPlaceholder() {
  const selected = destinations.find((item) => item.route === currentTopRoute()) ?? destinations[0];
  const secondary = location.pathname !== selected.route;
  const secondaryTitle = location.pathname.startsWith('/local/') ? '本地壁纸详情' : ({
    '/library/subscriptions': '订阅', '/library/favorites': '收藏', '/downloads': '下载', '/local': '本地壁纸', '/settings': '设置', '/settings/steam': 'Steam 账户',
  })[location.pathname] ?? selected.label;
  main.innerHTML = secondary
    ? `<header class="detail-top-bar"><button type="button" class="back-button" id="detail-back" aria-label="返回">←</button><h1>${secondaryTitle}</h1></header><section class="placeholder"><h2>${secondaryTitle} · Current UI</h2><p>此页面将在后续垂直切片中接入确定性状态。</p></section>`
    : `${shellHeader(selected.label)}<section class="placeholder"><h2>${selected.label} · Current UI</h2><p>此页面将在后续垂直切片中接入确定性状态。</p></section>`;
  document.title = `${selected.label} · WallHub Web Preview`;
}

function bindSharedControls() {
  document.querySelector('#dialog-button')?.addEventListener('click', () => dialog.show());
  document.querySelector('#theme-toggle')?.addEventListener('click', () => {
    backend.setSetting('themePreference', theme === 'dark' ? 'LIGHT' : 'DARK');
    applyAppearance();
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
  document.querySelector('#lab-fail-video')?.addEventListener('click', () => {
    const id = location.pathname.match(/^\/workshop\/(\d+)$/u)?.[1];
    if (id) {
      if (inlineVideo.timer) clearTimeout(inlineVideo.timer);
      inlineVideo = {id, state: 'failed', attempt: inlineVideo.attempt + 1, timer: null};
      renderApp();
    }
  });
  document.querySelector('#lab-confirm-steam')?.addEventListener('click', () => {
    backend.approveSteamDeviceConfirmation();
    requestAnimationFrame(() => {
      document.querySelector('#steam-logout')?.focus();
      const status = document.querySelector('#settings-status'); if (status) status.textContent = 'Steam 后端已确认设备批准';
    });
  });
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
  document.querySelectorAll('[data-settings-category]').forEach((button) => button.addEventListener('click', () => { settingsReturnFocus = button.dataset.settingsCategory; navigate(`/settings/${button.dataset.settingsCategory}`); }));
  document.querySelectorAll('[data-setting-toggle]').forEach((control) => control.addEventListener('change', () => { backend.toggleSetting(control.dataset.settingToggle); if (control.dataset.settingToggle === 'useSystemMonet') applyAppearance(); }));
  document.querySelectorAll('[data-setting-select]').forEach((control) => control.addEventListener('change', () => {
    const key = control.dataset.settingSelect;
    const numericKeys = new Set(['homePageSize', 'homeColumns', 'workshopSourceCacheBudgetGiB', 'maxConcurrentDownloads', 'chunkDownloadConcurrency', 'onlineStreamCacheLimitMb']);
    if (key === 'accent' && control.value === 'CUSTOM') { document.querySelector('#custom-accent-dialog')?.show(); return; }
    if (key === 'workshopSourceCacheBudgetGiB' && control.value === 'CUSTOM') { document.querySelector('#custom-source-cache-dialog')?.show(); return; }
    if (key === 'onlineStreamCacheLimitMb' && control.value === 'CUSTOM') { document.querySelector('#custom-stream-cache-dialog')?.show(); return; }
    if (key === 'accent') backend.setAccent(control.value);
    else if (key === 'detailDownloadPreset') backend.setDetailDownloadPreferences(control.value, backend.snapshot().detailPreserveAudio);
    else backend.setSetting(key, numericKeys.has(key) ? Number(control.value) : control.value);
    if (['themePreference', 'accent'].includes(key)) applyAppearance();
  }));
  const languageDialog = document.querySelector('#language-dialog');
  document.querySelector('#open-language')?.addEventListener('click', () => languageDialog?.show());
  document.querySelector('#cancel-language')?.addEventListener('click', () => languageDialog?.close());
  document.querySelectorAll('[name="language"]').forEach((control) => control.addEventListener('change', () => { languageDialog?.close(); backend.setSetting('language', control.value); requestAnimationFrame(() => document.querySelector('#open-language')?.focus()); }));
  document.querySelector('#export-diagnostics')?.addEventListener('click', () => { backend.exportDiagnostics(); requestAnimationFrame(() => document.querySelector('#export-diagnostics')?.focus()); });
  const customAccentDialog = document.querySelector('#custom-accent-dialog');
  const updateAccentPreview = (color) => { const input = document.querySelector('#custom-accent-color'); const preview = document.querySelector('#custom-accent-preview'); if (input) input.value = color; if (preview) preview.style.background = color; };
  document.querySelector('#custom-accent-color')?.addEventListener('input', (event) => updateAccentPreview(event.target.value));
  document.querySelectorAll('[data-custom-accent]').forEach((button) => button.addEventListener('click', () => updateAccentPreview(button.dataset.customAccent)));
  document.querySelector('#cancel-custom-accent')?.addEventListener('click', () => { customAccentDialog?.close(); const accent = document.querySelector('[data-setting-select="accent"]'); if (accent) accent.value = backend.snapshot().settings.accent; });
  document.querySelector('#apply-custom-accent')?.addEventListener('click', () => { const color = document.querySelector('#custom-accent-color')?.value.toUpperCase(); backend.setAccent('CUSTOM', color); applyAppearance(); customAccentDialog?.close(); requestAnimationFrame(() => document.querySelector('[data-setting-select="accent"]')?.focus()); });
  document.querySelector('#choose-output-directory')?.addEventListener('click', () => backend.setOutputDirectory('Web 选择的 WallHub 目录'));
  document.querySelector('#restore-output-directory')?.addEventListener('click', () => backend.setOutputDirectory(''));
  const clearSourceDialog = document.querySelector('#clear-source-dialog');
  document.querySelector('#clear-source-cache')?.addEventListener('click', () => {
    history.pushState({...history.state, modal: 'clear-source-cache'}, '', location.href);
    clearSourceModalActive = true;
    clearSourceDialog?.addEventListener('closed', () => { if (history.state?.modal === 'clear-source-cache') { clearSourceDismissed = true; history.back(); } }, {once: true});
    clearSourceDialog?.show();
  });
  document.querySelector('#cancel-clear-source')?.addEventListener('click', () => history.back());
  document.querySelector('#confirm-clear-source')?.addEventListener('click', () => { pendingClearSource = true; history.back(); });
  document.querySelector('#clear-stream-cache')?.addEventListener('click', () => { const hadBytes = backend.settingsState().preferences.onlineStreamCacheBytes > 0; backend.clearOnlineStreamCache(); requestAnimationFrame(() => { document.querySelector('#clear-stream-cache')?.focus(); const status = document.querySelector('#settings-status'); if (status) status.textContent = hadBytes ? '在线播放缓存已清除' : '在线播放缓存已经为空'; }); });
  const customSourceDialog = document.querySelector('#custom-source-cache-dialog');
  document.querySelector('#cancel-custom-source')?.addEventListener('click', () => customSourceDialog?.close());
  document.querySelector('#apply-custom-source')?.addEventListener('click', () => { const value = Number(document.querySelector('#custom-source-cache')?.value); if (Number.isInteger(value) && value > 0) { backend.setSetting('workshopSourceCacheBudgetGiB', value); customSourceDialog?.close(); } });
  const customStreamDialog = document.querySelector('#custom-stream-cache-dialog');
  document.querySelector('#cancel-custom-stream')?.addEventListener('click', () => customStreamDialog?.close());
  document.querySelector('#apply-custom-stream')?.addEventListener('click', () => { const value = Number(document.querySelector('#custom-stream-cache')?.value); if (Number.isInteger(value) && value >= 128) { backend.setSetting('onlineStreamCacheLimitMb', value); customStreamDialog?.close(); } });
  const proxyField = document.querySelector('#download-proxy-url');
  proxyField?.addEventListener('input', () => { const button = document.querySelector('#save-download-proxy'); const proxyToggle = document.querySelector('[data-setting-toggle="downloadProxyEnabled"]'); const changed = proxyField.value.trim() !== backend.settingsState().preferences.downloadProxyUrl; if (button) button.disabled = !changed; if (proxyToggle) proxyToggle.disabled = changed || !isValidDownloadProxy(backend.settingsState().preferences.downloadProxyUrl); const status = document.querySelector('#settings-status'); if (status && changed) status.textContent = '代理地址有未保存的更改'; });
  document.querySelector('#save-download-proxy')?.addEventListener('click', () => backend.saveDownloadProxy(proxyField?.value));
  document.querySelectorAll('[data-about-person]').forEach((button) => button.addEventListener('click', () => { window.open(`https://github.com/${encodeURIComponent(button.dataset.aboutAccount)}`, '_blank', 'noopener,noreferrer'); const status = document.querySelector('#settings-status'); if (status) status.textContent = `已请求打开 ${button.dataset.aboutPerson} 的 GitHub`; }));
  document.querySelector('#open-repository')?.addEventListener('click', () => { window.open('https://github.com/ChEnLeo-7/WallHub2.0-For-Android', '_blank', 'noopener,noreferrer'); const status = document.querySelector('#settings-status'); if (status) status.textContent = '已请求打开 GitHub 仓库'; });
  document.querySelector('#open-qq-group')?.addEventListener('click', () => { const status = document.querySelector('#settings-status'); if (status) status.textContent = '已请求加入 QQ 群 1082323527'; });
  document.querySelector('#restart-setup')?.addEventListener('click', () => backend.restartSetupWizard());
  document.querySelector('#check-updates')?.addEventListener('click', () => { backend.setAboutUpdatePhase('CHECKING'); setTimeout(() => backend.setAboutUpdatePhase('AVAILABLE'), 250); });
  document.querySelector('#download-update')?.addEventListener('click', () => { const attempt = ++updateDownloadAttempt; backend.setAboutUpdatePhase('DOWNLOADING'); setTimeout(() => { if (attempt === updateDownloadAttempt && backend.settingsState().preferences.aboutUpdatePhase === 'DOWNLOADING') backend.setAboutUpdatePhase('DOWNLOADED'); }, 450); });
  document.querySelector('#cancel-update')?.addEventListener('click', () => { updateDownloadAttempt += 1; backend.setAboutUpdatePhase('AVAILABLE'); });
  document.querySelector('#install-update')?.addEventListener('click', () => { const status = document.querySelector('#settings-status'); if (status) status.textContent = '已请求打开 Android 安装器'; });
  const completeSteamTransition = (message, focusSelector = '#steam-account-status') => requestAnimationFrame(() => {
    document.querySelector(focusSelector)?.focus();
    const status = document.querySelector('#settings-status'); if (status) status.textContent = message;
  });
  const accountField = document.querySelector('#steam-account');
  const passwordField = document.querySelector('#steam-password');
  const updateSteamLoginEnabled = () => { const button = document.querySelector('#steam-login'); if (button) button.disabled = !(accountField?.value.trim() && passwordField?.value); };
  accountField?.addEventListener('input', updateSteamLoginEnabled);
  passwordField?.addEventListener('input', updateSteamLoginEnabled);
  document.querySelector('#steam-login')?.addEventListener('click', () => { backend.submitSteamCredentials(accountField?.value, passwordField?.value); completeSteamTransition('请在 Steam 手机应用中确认', isLabVisible() ? '#lab-confirm-steam' : '#steam-use-code'); });
  document.querySelector('#steam-use-code')?.addEventListener('click', () => { backend.useManualSteamGuard(); completeSteamTransition('请输入 Steam Guard 验证码', '#steam-guard-code'); });
  document.querySelector('#steam-cancel-auth')?.addEventListener('click', () => { backend.cancelSteamAuthentication(); completeSteamTransition('Steam 登录已取消', '#steam-account'); });
  const guardField = document.querySelector('#steam-guard-code');
  guardField?.addEventListener('input', () => { const button = document.querySelector('#steam-submit-code'); if (button) button.disabled = !guardField.value.trim(); });
  document.querySelector('#steam-submit-code')?.addEventListener('click', () => { backend.submitSteamGuardCode(guardField?.value); completeSteamTransition('Steam 登录成功', '#steam-logout'); });
  document.querySelector('#steam-logout')?.addEventListener('click', () => { backend.setProfileSession('signed-out'); completeSteamTransition('已退出 Steam', '#steam-account'); });
  document.querySelector('#steam-retry')?.addEventListener('click', () => {
    const url = new URL(location.href); url.searchParams.delete('scenario'); history.replaceState(history.state, '', url); backend.setScenario('content'); backend.setProfileSession('signed-in'); completeSteamTransition('Steam 会话已恢复', '#steam-logout');
  });
  document.querySelector('#settings-theme-toggle')?.addEventListener('click', () => document.querySelector('#theme-toggle')?.click());
  document.querySelector('#notifications-setting')?.addEventListener('click', () => { backend.toggleSetting('notificationsGranted'); requestAnimationFrame(() => { document.querySelector('#notifications-setting')?.focus(); const status = document.querySelector('#settings-status'); if (status) status.textContent = '通知权限状态已更新'; }); });
  const setLocalActionStatus = (message) => { const status = document.querySelector('#local-status'); if (status) status.textContent = message; };
  document.querySelector('#local-open-native')?.addEventListener('click', () => setLocalActionStatus('已请求打开本地壁纸'));
  document.querySelector('#local-share')?.addEventListener('click', () => setLocalActionStatus('已准备分享本地壁纸'));
  document.querySelector('#local-copy-location')?.addEventListener('click', () => setLocalActionStatus('位置已复制'));
  document.querySelector('#local-search')?.addEventListener('input', (event) => {
    const value = event.target.value;
    clearTimeout(localSearchTimer);
    localSearchTimer = setTimeout(() => backend.setLocalQuery(value), 180);
  });
  document.querySelector('#local-search')?.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter') return;
    clearTimeout(localSearchTimer);
    backend.setLocalQuery(event.target.value);
  });
  document.querySelector('#local-view-toggle')?.addEventListener('click', () => backend.setLocalViewMode(backend.snapshot().localViewMode === 'grid' ? 'list' : 'grid'));
  document.querySelectorAll('[data-local-open]').forEach((button) => button.addEventListener('click', () => navigate(`/local/${button.dataset.localOpen}`)));
  document.querySelectorAll('[data-local-favorite]').forEach((button) => button.addEventListener('click', () => backend.toggleLocalFavorite(button.dataset.localFavorite)));
  document.querySelectorAll('[data-local-delete]').forEach((button) => button.addEventListener('click', () => {
    pendingLocalDelete = button.dataset.localDelete;
    history.pushState({...history.state, modal: 'local-delete'}, '', location.href);
    const deleteDialog = document.querySelector('#local-delete-dialog');
    deleteDialog.addEventListener('closed', () => { if (history.state?.modal === 'local-delete') history.back(); }, {once: true});
    deleteDialog.show();
  }));
  document.querySelector('#cancel-local-delete')?.addEventListener('click', () => history.back());
  document.querySelector('#confirm-local-delete')?.addEventListener('click', () => { confirmLocalDelete = true; history.back(); });
  document.querySelector('#retry-local')?.addEventListener('click', () => {
    const url = new URL(location.href); url.searchParams.delete('scenario'); history.replaceState(history.state, '', url); backend.setScenario('content');
  });
  document.querySelectorAll('[data-open-download-task]').forEach((button) => button.addEventListener('click', () => navigate(`/downloads/task/${button.dataset.openDownloadTask}`)));
  document.querySelector('#open-download-workshop')?.addEventListener('click', () => {
    const taskId = location.pathname.match(/^\/downloads\/task\/([a-z0-9-]+)$/u)?.[1];
    const task = taskId ? backend.downloadTask(taskId) : null;
    if (task) navigate(`/workshop/${task.workshopId}`);
  });
  document.querySelector('#play-downloaded-video')?.addEventListener('click', () => { const status = document.querySelector('#download-detail-status'); if (status) status.textContent = '已请求播放本地视频'; });
  document.querySelector('#open-reconvert')?.addEventListener('click', () => {
    history.pushState({...history.state, modal: 'reconvert'}, '', location.href);
    const reconvertDialog = document.querySelector('#reconvert-dialog');
    reconvertDialog?.addEventListener('closed', () => {
      if (history.state?.modal === 'reconvert') { reconvertDismissed = true; history.back(); }
    }, {once: true});
    reconvertDialog?.show();
  });
  document.querySelector('#cancel-reconvert')?.addEventListener('click', () => history.back());
  document.querySelector('#confirm-reconvert')?.addEventListener('click', () => {
    pendingReconversion = {
      taskId: location.pathname.match(/^\/downloads\/task\/([a-z0-9-]+)$/u)?.[1],
      preset: document.querySelector('#reconvert-preset')?.value,
      preserveAudio: document.querySelector('#reconvert-audio')?.checked === true,
    };
    history.back();
  });
  document.querySelector('#export-raw-zip')?.addEventListener('click', () => {
    const taskId = location.pathname.match(/^\/downloads\/task\/([a-z0-9-]+)$/u)?.[1];
    backend.exportRawZip(taskId);
    requestAnimationFrame(() => {
      document.querySelector('#export-raw-zip')?.focus();
      const status = document.querySelector('#download-detail-status'); if (status) status.textContent = '原始 ZIP 已导出';
    });
  });
  const recoverDownloads = () => {
    const url = new URL(location.href); url.searchParams.delete('scenario'); history.replaceState(history.state, '', url); backend.setScenario('content');
  };
  document.querySelector('#retry-downloads')?.addEventListener('click', recoverDownloads);
  const activateDownloadTab = (target) => {
    const url = new URL(location.href);
    if (target === 'completed') url.searchParams.set('tab', 'completed'); else url.searchParams.delete('tab');
    history.replaceState(history.state, '', url); renderApp(); requestAnimationFrame(() => document.querySelector(`[data-download-tab="${target}"]`)?.focus());
  };
  document.querySelectorAll('[data-download-tab]').forEach((button) => {
    button.addEventListener('click', () => activateDownloadTab(button.dataset.downloadTab));
    button.addEventListener('keydown', (event) => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      activateDownloadTab(['ArrowRight', 'End'].includes(event.key) ? 'completed' : 'active');
    });
  });
  document.querySelectorAll('[data-task-move]').forEach((button) => button.addEventListener('click', () => {
    const taskId = button.dataset.taskId;
    const direction = button.dataset.taskMove;
    backend.moveDownloadTask(taskId, direction);
    requestAnimationFrame(() => {
      (document.querySelector(`[data-task-move="${direction}"][data-task-id="${taskId}"]:not(:disabled)`) ?? document.querySelector(`[data-task-move][data-task-id="${taskId}"]:not(:disabled)`))?.focus();
      const task = backend.downloadTask(taskId);
      const position = backend.downloadTaskGroups().active.filter((item) => ['QUEUED', 'RESOLVING', 'DOWNLOADING', 'PAUSED'].includes(item.status)).findIndex((item) => item.id === taskId) + 1;
      const status = document.querySelector('#downloads-status'); if (status) status.textContent = `${task?.workshop?.title ?? '任务'}已移至第 ${position} 位`;
    });
  }));
  document.querySelectorAll('[data-task-action]').forEach((button) => button.addEventListener('click', () => {
    const taskId = button.dataset.taskId;
    backend.requestDownloadTaskAction(taskId, button.dataset.taskAction);
    requestAnimationFrame(() => {
      (document.querySelector(`[data-download-task="${CSS.escape(taskId)}"]`) ?? document.querySelector('[data-download-tab="active"]'))?.focus();
      const status = document.querySelector('#downloads-status'); if (status) status.textContent = '下载任务已更新';
    });
  }));
  document.querySelector('#retry-failed-downloads')?.addEventListener('click', () => {
    backend.retryFailedDownloadTasks();
    requestAnimationFrame(() => {
      document.querySelector('#retry-failed-downloads')?.focus();
      const status = document.querySelector('#downloads-status'); if (status) status.textContent = '失败任务已重新排队';
    });
  });
  document.querySelector('#clear-finished-downloads')?.addEventListener('click', () => {
    backend.clearFinishedDownloadTasks();
    requestAnimationFrame(() => {
      document.querySelector('#clear-finished-downloads')?.focus();
      const status = document.querySelector('#downloads-status'); if (status) status.textContent = '已完成记录已清除';
    });
  });
  const recoverLibrary = () => {
    const url = new URL(location.href);
    url.searchParams.delete('scenario');
    history.replaceState(history.state, '', url);
    backend.setScenario('content');
  };
  document.querySelector('#retry-library')?.addEventListener('click', recoverLibrary);
  document.querySelector('#retry-library-toolbar')?.addEventListener('click', recoverLibrary);
  document.querySelector('#library-login')?.addEventListener('click', () => navigate('/settings/steam'));
  document.querySelectorAll('[data-library-tab]').forEach((button) => {
    const activate = () => {
      const target = button.dataset.libraryTab;
      history.replaceState({...history.state, route: `/library/${target}`}, '', `/library/${target}`);
      renderApp();
      requestAnimationFrame(() => document.querySelector(`[data-library-tab="${target}"]`)?.focus());
    };
    button.addEventListener('click', activate);
    button.addEventListener('keydown', (event) => {
      if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
      event.preventDefault();
      const target = ['ArrowRight', 'End'].includes(event.key) ? 'favorites' : 'subscriptions';
      history.replaceState({...history.state, route: `/library/${target}`}, '', `/library/${target}`);
      renderApp();
      requestAnimationFrame(() => document.querySelector(`[data-library-tab="${target}"]`)?.focus());
    });
  });
  document.querySelectorAll('[data-library-remove]').forEach((button) => button.addEventListener('click', () => {
    pendingLibraryRemoval = button.dataset.libraryRemove;
    history.pushState({...history.state, modal: 'library-remove'}, '', location.href);
    const removeDialog = document.querySelector('#library-remove-dialog');
    removeDialog.addEventListener('closed', () => {
      if (history.state?.modal === 'library-remove') history.back();
    }, {once: true});
    removeDialog.show();
  }));
  document.querySelector('#cancel-library-remove')?.addEventListener('click', () => history.back());
  document.querySelector('#confirm-library-remove')?.addEventListener('click', () => {
    confirmLibraryRemoval = true;
    history.back();
  });
  document.querySelector('#open-settings')?.addEventListener('click', () => navigate('/settings'));
  document.querySelector('#profile-login')?.addEventListener('click', () => navigate('/settings/steam'));
  document.querySelectorAll('[data-profile-route]').forEach((button) => button.addEventListener('click', () => navigate(button.dataset.profileRoute)));
  document.querySelectorAll('[data-profile-collection]').forEach((button) => button.addEventListener('click', () => {
    const destination = backend.profile().session === 'signed-in' ? `/library/${button.dataset.profileCollection}` : '/settings/steam';
    navigate(destination);
  }));
  document.querySelector('#retry-profile-counts')?.addEventListener('click', () => {
    const currentCounts = backend.profile().counts;
    const url = new URL(location.href);
    url.searchParams.delete('scenario');
    history.replaceState(history.state, '', url);
    backend.update({profileCounts: {
      subscriptions: currentCounts.subscriptions ?? 42,
      favorites: currentCounts.favorites ?? 18,
    }, scenario: 'content'});
    requestAnimationFrame(() => {
      document.querySelector('[data-profile-collection="subscriptions"]')?.focus();
      const status = document.querySelector('#profile-status');
      if (status) status.textContent = '统计已更新';
    });
  });
  document.querySelector('#retry-discover')?.addEventListener('click', () => {
    const url = new URL(location.href);
    url.searchParams.delete('scenario');
    history.replaceState(history.state, '', url);
    backend.setScenario('content');
  });
  document.querySelector('#discover-result-columns')?.addEventListener('click', () => backend.update({discoverResultColumns: backend.snapshot().discoverResultColumns === 1 ? 2 : 1}));
  document.querySelectorAll('[data-open-rail]').forEach((button) => button.addEventListener('click', () => navigate(`/discover/results/${button.dataset.openRail}`)));
  document.querySelector('.discover-content-menu')?.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      event.currentTarget.removeAttribute('open');
      event.currentTarget.querySelector('summary')?.focus();
    }
  });
  document.querySelectorAll('[data-discover-route]').forEach((button) => button.addEventListener('click', () => navigate(`/discover/${button.dataset.discoverRoute}`)));
  document.querySelectorAll('#add-discover-query, #add-discover-query-empty').forEach((button) => button.addEventListener('click', () => navigate('/discover/query/new')));
  document.querySelector('#cancel-query')?.addEventListener('click', () => document.querySelector('#detail-back')?.click());
  const queryInput = document.querySelector('#query-input');
  const updateQueryValidity = () => {
    const category = document.querySelector('[name="query-category"]:checked')?.value ?? 'KEYWORD';
    const valid = Boolean(queryInput?.value.trim()) && (category === 'KEYWORD' || /^\d+$/u.test(queryInput.value.trim()));
    const save = document.querySelector('#save-query'); if (save) save.disabled = !valid;
    const error = document.querySelector('#query-error'); if (error) error.textContent = queryInput?.value && !valid ? '请输入有效的数字 ID' : '';
    if (queryInput) queryInput.label = category === 'KEYWORD' ? '关键词' : category === 'CREATOR' ? '创作者 ID' : '合集 ID';
  };
  const updateQuerySorts = () => {
    const category = document.querySelector('[name="query-category"]:checked')?.value ?? 'KEYWORD';
    const options = {
      KEYWORD: [['TREND_YEAR', '年度趋势'], ['TREND_WEEK', '本周趋势'], ['RECENT', '最近发布'], ['SUBSCRIPTIONS', '订阅最多']],
      CREATOR: [['CREATOR_VOTES', '获赞最多'], ['CREATOR_RECENT', '最近发布']],
      COLLECTION: [['COLLECTION', '合集']],
    }[category];
    const select = document.querySelector('#query-sort');
    if (select) select.innerHTML = options.map(([value, label]) => `<option value="${value}">${label}</option>`).join('');
  };
  queryInput?.addEventListener('input', updateQueryValidity);
  document.querySelectorAll('[name="query-category"]').forEach((radio) => radio.addEventListener('change', () => { updateQuerySorts(); updateQueryValidity(); }));
  document.querySelector('#query-editor')?.addEventListener('submit', (event) => {
    event.preventDefault();
    const before = backend.snapshot().savedQueries;
    backend.saveDiscoverQuery({category: document.querySelector('[name="query-category"]:checked')?.value, input: queryInput?.value, sort: document.querySelector('#query-sort')?.value});
    const created = backend.snapshot().savedQueries.find((query) => !before.some((item) => item.id === query.id));
    if (created) navigate(`/discover/results/query-${created.id}`);
  });
  document.querySelectorAll('[data-open-saved-query]').forEach((button) => button.addEventListener('click', () => navigate(`/discover/results/query-${button.dataset.openSavedQuery}`)));
  document.querySelectorAll('[data-remove-saved-query]').forEach((button) => button.addEventListener('click', () => {
    backend.removeDiscoverQuery(button.dataset.removeSavedQuery);
    requestAnimationFrame(() => { (document.querySelector('[data-saved-query]') ?? document.querySelector('#add-discover-query'))?.focus(); const status = document.querySelector('#discover-status'); if (status) status.textContent = '关注已移除'; });
  }));
  document.querySelectorAll('#friend-login, #following-login').forEach((button) => button.addEventListener('click', () => navigate('/settings/steam')));
  document.querySelector('#retry-friends')?.addEventListener('click', () => backend.setScenario('content'));
  document.querySelectorAll('[data-rail-rating]').forEach((button) => button.addEventListener('click', () => {
    const current = backend.snapshot().discoverFeedback[button.dataset.rail]?.rating ?? 'none';
    const next = current === button.dataset.railRating ? 'none' : button.dataset.railRating;
    const rail = button.closest('.discover-rail');
    const visibleRails = [...document.querySelectorAll('.discover-rail')];
    const railIndex = visibleRails.indexOf(rail);
    const focusRailId = visibleRails[railIndex + 1]?.dataset.railId ?? visibleRails[railIndex - 1]?.dataset.railId;
    const hiddenTitle = rail?.querySelector('h2')?.textContent?.trim() ?? '推荐内容';
    backend.setDiscoverFeedback(button.dataset.rail, {rating: next});
    if (next === 'dislike') requestAnimationFrame(() => {
      (focusRailId ? document.querySelector(`[data-rail-id="${CSS.escape(focusRailId)}"]`) : main)?.focus();
      const status = document.querySelector('#discover-status');
      if (status) status.textContent = `已隐藏${hiddenTitle}`;
    });
  }));
  document.querySelectorAll('[data-rail-favorite]').forEach((button) => button.addEventListener('click', () => {
    const current = backend.snapshot().discoverFeedback[button.dataset.railFavorite]?.favorite === true;
    backend.setDiscoverFeedback(button.dataset.railFavorite, {favorite: !current});
  }));
  document.querySelectorAll('[data-favorite]').forEach((button) => button.addEventListener('click', () => backend.toggleFavorite(button.dataset.favorite)));
  document.querySelectorAll('[data-download]').forEach((button) => button.addEventListener('click', () => backend.requestDownload(button.dataset.download)));
  document.querySelectorAll('[data-subscribe]').forEach((button) => button.addEventListener('click', () => backend.toggleSubscription(button.dataset.subscribe)));
  document.querySelector('#detail-back')?.addEventListener('click', () => {
    if (history.state?.wallhub === true && String(history.state.from).startsWith('/')) {
      history.back();
    } else {
      const fallback = location.pathname.startsWith('/discover/') ? '/discover' : location.pathname.startsWith('/local/') ? '/local' : location.pathname.startsWith('/settings/') ? '/settings' : location.pathname.startsWith('/downloads/') ? '/downloads' : currentTopRoute() === '/profile' ? '/profile' : '/';
      history.replaceState({wallhub: true, route: fallback}, '', fallback);
      renderApp();
      main.focus();
    }
  });
  const commentDraft = document.querySelector('#comment-draft');
  commentDraft?.addEventListener('input', () => {
    if (commentDraft.value.length > 1000) commentDraft.value = commentDraft.value.slice(0, 1000);
    const submit = document.querySelector('#submit-comment'); if (submit) submit.disabled = !commentDraft.value.trim();
    const limit = document.querySelector('#comment-limit'); if (limit) limit.textContent = `最多 1000 字符 · ${commentDraft.value.length}/1000`;
  });
  document.querySelector('#comment-composer')?.addEventListener('submit', (event) => {
    event.preventDefault();
    const text = commentDraft?.value;
    if (!text?.trim()) return;
    const result = backend.postComment(location.pathname.match(/^\/workshop\/(\d+)$/u)?.[1], text);
    requestAnimationFrame(() => {
      document.querySelector('#comment-draft')?.focus();
      const status = document.querySelector('#comment-status'); if (status) status.textContent = result.ok ? '评论已发布' : '评论未能保存，请重试';
    });
  });
  document.querySelector('#comment-login')?.addEventListener('click', () => navigate('/settings/steam'));
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
  const startInlineVideo = () => {
    const id = location.pathname.match(/^\/workshop\/(\d+)$/u)?.[1];
    if (!id) return;
    if (inlineVideo.timer) clearTimeout(inlineVideo.timer);
    const attempt = inlineVideo.attempt + 1;
    inlineVideo = {id, state: 'loading', attempt, timer: null};
    renderApp();
    inlineVideo.timer = setTimeout(() => {
      if (inlineVideo.id === id && inlineVideo.state === 'loading' && inlineVideo.attempt === attempt) setInlineVideoState(id, 'playing');
    }, 250);
  };
  document.querySelector('.play-button')?.addEventListener('click', startInlineVideo);
  document.querySelector('#retry-inline-video')?.addEventListener('click', startInlineVideo);
  document.querySelector('#open-video-fullscreen')?.addEventListener('click', () => {
    fullscreenVideoId = location.pathname.match(/^\/workshop\/(\d+)$/u)?.[1] ?? null;
    history.pushState({...history.state, modal: 'video-fullscreen'}, '', location.href);
    renderApp(); requestAnimationFrame(() => document.querySelector('#exit-video-fullscreen')?.focus());
  });
  document.querySelector('#exit-video-fullscreen')?.addEventListener('click', () => history.back());
  document.querySelector('#open-download-queue')?.addEventListener('click', () => navigate('/downloads'));
  document.querySelector('#detail-download')?.addEventListener('click', () => {
    if (downloadDialog.open || history.state?.modal === 'detail-download-format') return;
    const preferences = backend.snapshot();
    const selectedPreset = document.querySelector(`[name="download-preset"][value="${preferences.detailDownloadPreset}"]`);
    if (selectedPreset) selectedPreset.checked = true;
    document.querySelector('#download-preserve-audio').checked = preferences.detailPreserveAudio;
    history.pushState({...history.state, modal: 'detail-download-format'}, '', location.href);
    showDownloadStep('format');
    downloadDialog.addEventListener('closed', () => {
      if (history.state?.modal?.startsWith('detail-download')) {
        downloadDismissed = true;
        history.back();
      }
    }, {once: true});
    downloadDialog.show();
  });
  document.querySelector('#close-download').onclick = () => history.back();
  document.querySelector('[data-download-format="MPKG"]').onclick = () => showDownloadStep('mpkg');
  document.querySelector('[data-download-format="ZIP"]').onclick = () => {
    pendingDetailDownload = {id: location.pathname.split('/').pop(), format: 'ZIP', preset: 'BALANCED', preserveAudio: false};
    history.back();
  };
  document.querySelector('#back-download-format').onclick = () => showDownloadStep('format');
  document.querySelectorAll('[name="download-preset"]').forEach((control) => { control.onchange = () => backend.setDetailDownloadPreferences(control.value, document.querySelector('#download-preserve-audio').checked); });
  document.querySelector('#download-preserve-audio').onchange = (event) => backend.setDetailDownloadPreferences(document.querySelector('[name="download-preset"]:checked').value, event.currentTarget.checked);
  document.querySelector('#download-mpkg-page').onsubmit = (event) => {
    event.preventDefault();
    pendingDetailDownload = {id: location.pathname.split('/').pop(), format: 'MPKG', preset: new FormData(event.currentTarget).get('download-preset'), preserveAudio: document.querySelector('#download-preserve-audio')?.checked === true};
    backend.setDetailDownloadPreferences(pendingDetailDownload.preset, pendingDetailDownload.preserveAudio);
    history.back();
  };
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
  if (backend.snapshot().settings.setupRestartRequested) {
    nav.hidden = true; document.documentElement.dataset.secondary = 'true';
    main.innerHTML = '<section class="setup-wizard" role="main"><div class="about-logo" aria-hidden="true">W</div><h1>欢迎使用 WallHub</h1><p>重新配置语言、存储、网络与 Steam。</p><md-filled-button id="complete-setup">完成设置</md-filled-button></section>';
    document.querySelector('#complete-setup')?.addEventListener('click', () => backend.completeSetupWizard());
    return;
  }
  const nextWorkshopId = location.pathname.match(/^\/workshop\/(\d+)$/u)?.[1] ?? null;
  if (renderedWorkshopId && renderedWorkshopId !== nextWorkshopId) resetInlineVideo();
  renderedWorkshopId = nextWorkshopId;
  const scenario = requestedScenario();
  if (scenario && backend.snapshot().scenario !== scenario) {
    backend.setScenario(scenario);
    return;
  }
  renderNavigation();
  if (location.pathname === '/') renderHome();
  else if (location.pathname === '/discover') renderDiscover();
  else if (location.pathname === '/profile') renderProfile();
  else if (location.pathname === '/downloads') renderDownloads();
  else if (/^\/downloads\/task\/[a-z0-9-]+$/u.test(location.pathname)) renderDownloadTaskDetail();
  else if (location.pathname === '/settings' || /^\/settings\/[a-z-]+$/u.test(location.pathname)) renderSettings();
  else if (location.pathname === '/local') renderLocal();
  else if (/^\/local\/[a-z0-9-]+$/u.test(location.pathname)) renderLocalDetail();
  else if (/^\/library\/(subscriptions|favorites)$/u.test(location.pathname)) renderLibrary();
  else if (location.pathname === '/discover/following') renderDiscoverFollowing();
  else if (location.pathname === '/discover/query/new') renderDiscoverQueryEditor();
  else if (/^\/discover\/friend-(favorites|created)$/u.test(location.pathname)) renderFriendDiscover();
  else if (/^\/discover\/results\/[a-z0-9-]+$/u.test(location.pathname)) renderDiscoverResults();
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
window.addEventListener('keydown', (event) => {
  if (event.key === 'Tab' && fullscreenVideoId) {
    event.preventDefault();
    document.querySelector('#exit-video-fullscreen')?.focus();
  } else if (event.key === 'Escape' && fullscreenVideoId) {
    event.preventDefault();
    history.back();
  }
});
window.addEventListener('popstate', () => {
  if (fullscreenVideoId) {
    fullscreenVideoId = null;
    renderApp();
    requestAnimationFrame(() => document.querySelector('#open-video-fullscreen')?.focus());
    return;
  }
  const clearSourceDialog = document.querySelector('#clear-source-dialog');
  if (clearSourceModalActive || clearSourceDialog?.open || clearSourceDismissed) {
    if (clearSourceDialog?.open) {
      clearSourceDialog.addEventListener('closed', () => setTimeout(() => document.querySelector('#clear-source-cache')?.focus(), 0), {once: true});
      setTimeout(() => clearSourceDialog.close(), 0);
    }
    clearSourceDismissed = false;
    clearSourceModalActive = false;
    if (pendingClearSource) { const hadBytes = backend.settingsState().preferences.workshopSourceCacheRetainedBytes > 0; pendingClearSource = false; backend.clearWorkshopSourceCache(); setTimeout(() => { document.querySelector('#clear-source-cache')?.focus(); const status = document.querySelector('#settings-status'); if (status) status.textContent = hadBytes ? '源文件缓存已清除' : '源文件缓存已经为空'; }, 100); }
    else setTimeout(() => document.querySelector('#clear-source-cache')?.focus(), 100);
    return;
  }
  if (downloadDialog?.open && history.state?.modal === 'detail-download-format') {
    showDownloadStep('format');
    return;
  }
  if (downloadDialog?.open || downloadDismissed) {
    if (downloadDialog.open) {
      downloadDialog.addEventListener('closed', () => setTimeout(() => focusControl('#detail-download'), 0), {once: true});
      downloadDialog.close();
    }
    downloadDismissed = false;
    if (pendingDetailDownload) {
      const request = pendingDetailDownload; pendingDetailDownload = null;
      const {created} = backend.requestDownload(request.id, request.format, request);
      requestAnimationFrame(() => {
        const feedback = document.querySelector('#detail-download-feedback');
        if (feedback) {
          feedback.hidden = false;
          feedback.querySelector('span').textContent = created ? `${request.format} 已加入下载队列` : `${request.format} 已在下载队列中`;
          feedback.querySelector('#open-download-queue').hidden = !created;
          if (detailDownloadFeedbackTimer) clearTimeout(detailDownloadFeedbackTimer);
          detailDownloadFeedbackTimer = setTimeout(() => { feedback.hidden = true; }, created ? 10000 : 4000);
        }
        setTimeout(() => focusControl('#detail-download'), 100);
      });
    } else setTimeout(() => focusControl('#detail-download'), 100);
    return;
  }
  const reconvertDialog = document.querySelector('#reconvert-dialog');
  if (reconvertDialog?.open || reconvertDismissed) {
    if (reconvertDialog?.open) reconvertDialog.close();
    reconvertDismissed = false;
    if (pendingReconversion) {
      const request = pendingReconversion; pendingReconversion = null;
      backend.reconvertDownloadTask(request.taskId, request.preset, request.preserveAudio);
      history.replaceState({wallhub: true, route: '/downloads'}, '', '/downloads');
      renderApp();
      requestAnimationFrame(() => document.querySelector(`[data-download-task="${request.taskId}"]`)?.focus());
    } else {
      requestAnimationFrame(() => document.querySelector('#open-reconvert')?.focus());
    }
    return;
  }
  const localDeleteDialog = document.querySelector('#local-delete-dialog');
  if (localDeleteDialog?.open) {
    localDeleteDialog.close();
    if (confirmLocalDelete && pendingLocalDelete) {
      const deletedId = pendingLocalDelete;
      confirmLocalDelete = false;
      backend.deleteLocalWallpaper(deletedId);
      requestAnimationFrame(() => {
        (document.querySelector('.local-item') ?? document.querySelector('#local-search'))?.focus();
        const status = document.querySelector('#local-status'); if (status) status.textContent = '本地壁纸已删除';
      });
    }
    pendingLocalDelete = null; confirmLocalDelete = false;
    return;
  }
  const removeDialog = document.querySelector('#library-remove-dialog');
  if (removeDialog?.open) {
    removeDialog.close();
    if (confirmLibraryRemoval && pendingLibraryRemoval) {
      const collection = location.pathname.endsWith('/favorites') ? 'favorites' : 'subscriptions';
      confirmLibraryRemoval = false;
      backend.removeFromLibrary(collection, pendingLibraryRemoval);
      requestAnimationFrame(() => {
        document.querySelector(`[data-library-tab="${collection}"]`)?.focus();
        const status = document.querySelector('#library-status');
        if (status) status.textContent = '项目已移除';
      });
    }
    pendingLibraryRemoval = null;
    confirmLibraryRemoval = false;
    return;
  }
  const categoryFocus = location.pathname === '/settings' ? settingsReturnFocus : null;
  renderApp();
  requestAnimationFrame(() => {
    if (categoryFocus) document.querySelector(`[data-settings-category="${categoryFocus}"]`)?.focus();
    else main.focus();
    settingsReturnFocus = null;
  });
});
window.addEventListener('resize', applyWindowSizeClass);
window.visualViewport?.addEventListener('resize', applyWindowSizeClass);
backend.subscribe(() => {
  const active = document.activeElement;
  const focusSelector =
    active?.dataset?.favorite ? `[data-favorite="${active.dataset.favorite}"]` :
    active?.dataset?.subscribe ? `[data-subscribe="${active.dataset.subscribe}"]` :
    active?.dataset?.railRating ? `[data-rail-rating="${active.dataset.railRating}"][data-rail="${active.dataset.rail}"]` :
    active?.dataset?.railFavorite ? `[data-rail-favorite="${active.dataset.railFavorite}"]` :
    active?.dataset?.localFavorite ? `[data-local-favorite="${active.dataset.localFavorite}"]` :
    active?.dataset?.settingToggle ? `[data-setting-toggle="${active.dataset.settingToggle}"]` :
    active?.dataset?.download ? `[data-download="${active.dataset.download}"]` :
    active?.dataset?.viewMode ? `[data-view-mode="${active.dataset.viewMode}"]` :
    active?.id ? `#${CSS.escape(active.id)}` : null;
  renderApp();
  if (focusSelector) requestAnimationFrame(() => document.querySelector(focusSelector)?.focus());
});
applyAppearance();
applyWindowSizeClass();
renderApp();

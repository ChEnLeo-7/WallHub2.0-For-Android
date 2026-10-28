import '@material/web/button/filled-button.js';
import '@material/web/button/outlined-button.js';
import '@material/web/button/text-button.js';
import '@material/web/dialog/dialog.js';
import '@material/web/textfield/outlined-text-field.js';
import './contract.css';
import './styles.css';
import contract from '../../../shared/ui-contract/wallhub-current.json';

const iconPaths = {
  home: 'M12 3 2 12h3v8h6v-6h2v6h6v-8L12 3Zm5 15h-2v-6H9v6H7v-7.1l5-4.5 5 4.5V18Z',
  explore: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm2.19 12.19L6 18l3.81-8.19L18 6l-3.81 8.19ZM12 10.9a1.1 1.1 0 1 0 0 2.2 1.1 1.1 0 0 0 0-2.2Z',
  person: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4Zm0-6a2 2 0 1 1 0 4 2 2 0 0 1 0-4Zm0 8c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4Zm-5.33 4c.73-1.02 3.32-2 5.33-2 2.02 0 4.61.99 5.34 2H6.67Z',
};
const destinations = contract.destinations.map((destination) => ({
  ...destination,
  iconPath: iconPaths[destination.icon],
}));
const clickKey = 'wallhub.preview.dom.clicks';
const themeKey = 'wallhub.preview.dom.theme';
const storage = {
  get(key) {
    try { return localStorage.getItem(key); } catch { return null; }
  },
  set(key, value) {
    try { localStorage.setItem(key, value); } catch { /* Persistence is optional. */ }
  },
};
let clicks = Number.parseInt(storage.get(clickKey) ?? '0', 10) || 0;
let theme = storage.get(themeKey) === 'light' ? 'light' : 'dark';

const app = document.querySelector('#app');
app.innerHTML = `
  <div class="app-shell">
    <nav class="primary-nav" aria-label="主导航"></nav>
    <main id="main-content" tabindex="-1">
      <header class="top-bar">
        <div>
          <div class="eyebrow">CURRENT UI · DOM ACCESSIBILITY PROBE</div>
          <h1>WallHub</h1>
        </div>
        <md-outlined-button id="theme-toggle">切换外观</md-outlined-button>
      </header>
      <section class="content" aria-labelledby="page-title">
        <h2 id="page-title"></h2>
        <p class="supporting">共享 WallHub 令牌 + Material Web 2.5.0 原生 DOM 渲染层</p>
        <md-outlined-text-field id="search" label="搜索创意工坊"></md-outlined-text-field>
        <div class="actions">
          <md-filled-button id="state-button"></md-filled-button>
          <md-filled-button id="dialog-button">打开焦点对话框</md-filled-button>
        </div>
        <p id="route-status" aria-live="polite"></p>
        <p>减少动态效果：${matchMedia('(prefers-reduced-motion: reduce)').matches ? '开启' : '关闭'}</p>
      </section>
    </main>
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
`;

const nav = document.querySelector('.primary-nav');
const pageTitle = document.querySelector('#page-title');
const routeStatus = document.querySelector('#route-status');
const stateButton = document.querySelector('#state-button');
const dialogButton = document.querySelector('#dialog-button');
const dialog = document.querySelector('#focus-dialog');
const themeToggle = document.querySelector('#theme-toggle');

function normalizedRoute() {
  return destinations.some((item) => item.route === location.pathname) ? location.pathname : '/';
}

function renderRoute() {
  const route = normalizedRoute();
  const selected = destinations.find((item) => item.route === route) ?? destinations[0];
  nav.innerHTML = destinations.map((item) => `
    <button class="nav-item" type="button" data-route="${item.route}"
      aria-current="${item.route === route ? 'page' : 'false'}">
      <svg class="nav-icon" aria-hidden="true" viewBox="0 0 24 24"><path d="${item.iconPath}"></path></svg>
      <span>${item.label}</span>
    </button>
  `).join('');
  pageTitle.textContent = `${selected.label} · Current UI extraction shell`;
  routeStatus.textContent = `当前路由：${location.pathname}`;
  document.title = `${selected.label} · WallHub Web Preview`;
}

function renderClicks() {
  stateButton.textContent = `真实状态切换 · ${clicks}`;
}

function applyTheme() {
  document.documentElement.dataset.theme = theme;
  themeToggle.textContent = theme === 'dark' ? '切换到浅色' : '切换到深色';
}

function applyWindowSizeClass() {
  const next =
    innerWidth < contract.breakpoints.medium
      ? 'compact'
      : innerWidth < contract.breakpoints.expanded ? 'medium' : 'expanded';
  if (document.documentElement.dataset.windowClass !== next) {
    document.documentElement.dataset.windowClass = next;
  }
}

nav.addEventListener('click', (event) => {
  const button = event.target.closest('[data-route]');
  if (!button) return;
  history.pushState({ route: button.dataset.route }, '', button.dataset.route);
  renderRoute();
  document.querySelector('#main-content').focus();
});
window.addEventListener('popstate', renderRoute);
window.addEventListener('resize', applyWindowSizeClass);

stateButton.addEventListener('click', () => {
  clicks += 1;
  storage.set(clickKey, String(clicks));
  renderClicks();
});
dialogButton.addEventListener('click', () => dialog.show());
themeToggle.addEventListener('click', () => {
  theme = theme === 'dark' ? 'light' : 'dark';
  storage.set(themeKey, theme);
  applyTheme();
});

applyWindowSizeClass();
renderRoute();
renderClicks();
applyTheme();

import {
  FIXTURE_CLOCK_EPOCH_MS,
  FIXTURE_SCHEMA_VERSION,
  workshopComments,
  workshopFixtures,
} from './fixtures-v1.js';

export const FAKE_BACKEND_STORAGE_KEY = 'wallhub.preview.fake-backend';
export const stateLabScenarios = Object.freeze(['content', 'loading', 'empty', 'error']);

function defaultState() {
  return {
    schemaVersion: FIXTURE_SCHEMA_VERSION,
    seed: 'wallhub-current-v1',
    clockMs: FIXTURE_CLOCK_EPOCH_MS,
    scenario: 'content',
    query: '',
    viewMode: 'grid',
    filters: {type: 'ALL', sort: 'TRENDING', exactPhrase: false},
    favorites: ['3357929793', '3048819267', '2871466005'],
    subscriptions: ['3174098802', '2985110421', '2769012550'],
    libraryInitialized: true,
    downloadTasks: [
      {id: 'task-neon', workshopId: '3357929793', status: 'DOWNLOADING', progress: 0.62, format: 'MPKG'},
      {id: 'task-mountain', workshopId: '3174098802', status: 'FAILED', progress: 0.34, format: 'ZIP'},
      {id: 'task-cafe', workshopId: '3048819267', status: 'COMPLETED', progress: 1, format: 'MPKG'},
    ],
    downloadsInitialized: true,
    localInitialized: true,
    localViewMode: 'grid',
    localQuery: '',
    localItems: [
      {id: 'local-neon', title: '雨夜霓虹（本地）', type: 'VIDEO', source: 'WallHub', favorite: true, tags: ['赛博朋克', '雨夜']},
      {id: 'local-ink', title: '水墨游鱼（导入）', type: 'SCENE', source: '导入', favorite: false, tags: ['水墨', '鱼']},
      {id: 'local-cafe', title: '像素咖啡馆（缓存）', type: 'SCENE', source: '下载', favorite: false, tags: ['像素', '咖啡']},
    ],
    discoverFeedback: {},
    discoverResultColumns: 2,
    profileSession: 'signed-in',
    profileCounts: {subscriptions: 42, favorites: 18},
    downloads: [],
  };
}

function normalizedState(candidate) {
  if (!candidate || candidate.schemaVersion !== FIXTURE_SCHEMA_VERSION) return defaultState();
  const fallback = defaultState();
  const profileSession = ['signed-out', 'restoring', 'signed-in'].includes(candidate.profileSession) ? candidate.profileSession : fallback.profileSession;
  const profileCounts = candidate.profileCounts && typeof candidate.profileCounts === 'object' ? {
    subscriptions: Number.isSafeInteger(candidate.profileCounts.subscriptions) && candidate.profileCounts.subscriptions >= 0 ? candidate.profileCounts.subscriptions : null,
    favorites: Number.isSafeInteger(candidate.profileCounts.favorites) && candidate.profileCounts.favorites >= 0 ? candidate.profileCounts.favorites : null,
  } : {...fallback.profileCounts};
  const libraryInitialized = candidate.libraryInitialized === true;
  const normalizeMembership = (key) => {
    const persisted = Array.isArray(candidate[key]) ? candidate[key].map(String) : [];
    return [...new Set(libraryInitialized ? persisted : [...fallback[key], ...persisted])];
  };
  const normalizedDownloads = Array.isArray(candidate.downloads)
    ? candidate.downloads.filter((entry) =>
      entry && typeof entry === 'object' && typeof entry.workshopId === 'string' &&
      typeof entry.id === 'string' && (entry.format === undefined || ['MPKG', 'ZIP'].includes(entry.format)) &&
      entry.state === 'queued' && Number.isSafeInteger(entry.createdAt)
    ).map((entry) => ({
      id: entry.id.slice(0, 128), workshopId: entry.workshopId.slice(0, 32), format: entry.format ?? 'MPKG',
      state: 'queued', createdAt: entry.createdAt,
    }))
    : [];
  const normalizedTasks = Array.isArray(candidate.downloadTasks) ? candidate.downloadTasks.filter((task) =>
    task && typeof task === 'object' && typeof task.id === 'string' && typeof task.workshopId === 'string' &&
    ['QUEUED', 'DOWNLOADING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status) &&
    Number.isFinite(task.progress) && ['MPKG', 'ZIP'].includes(task.format)
  ).map((task) => ({id: task.id.slice(0, 64), workshopId: task.workshopId.slice(0, 32), status: task.status, progress: Math.min(1, Math.max(0, task.progress)), format: task.format})) : [];
  const normalizedLocalItems = candidate.localInitialized === true && Array.isArray(candidate.localItems)
    ? [...new Map(candidate.localItems.filter((item) => item && typeof item.id === 'string' && /^[a-z0-9-]{1,64}$/u.test(item.id) && typeof item.title === 'string' && ['SCENE', 'VIDEO', 'WEB'].includes(item.type)).map((item) => ({id: item.id, title: item.title.slice(0, 120), type: item.type, source: String(item.source ?? '').slice(0, 40), favorite: item.favorite === true, tags: Array.isArray(item.tags) ? item.tags.map(String).slice(0, 8) : []})).map((item) => [item.id, item])).values()]
    : fallback.localItems.map((item) => ({...item, tags: [...item.tags]}));
  const taskCandidates = candidate.downloadsInitialized === true
    ? normalizedTasks
    : [...fallback.downloadTasks.map((task) => ({...task})), ...normalizedDownloads.map((entry) => ({id: entry.id, workshopId: entry.workshopId, status: 'QUEUED', progress: 0, format: entry.format}))];
  const downloadTasks = [...new Map(taskCandidates.map((task) => [`${task.workshopId}:${task.format}`, task])).values()];
  return {
    schemaVersion: FIXTURE_SCHEMA_VERSION,
    seed: typeof candidate.seed === 'string' ? candidate.seed.slice(0, 64) : fallback.seed,
    clockMs: Number.isSafeInteger(candidate.clockMs) ? candidate.clockMs : fallback.clockMs,
    scenario: stateLabScenarios.includes(candidate.scenario) ? candidate.scenario : fallback.scenario,
    query: typeof candidate.query === 'string' ? candidate.query.slice(0, 120) : fallback.query,
    viewMode: candidate.viewMode === 'list' ? 'list' : 'grid',
    filters: {
      type: ['ALL', 'SCENE', 'VIDEO'].includes(candidate.filters?.type) ? candidate.filters.type : fallback.filters.type,
      sort: ['TRENDING', 'MOST_SUBSCRIBED'].includes(candidate.filters?.sort) ? candidate.filters.sort : fallback.filters.sort,
      exactPhrase: candidate.filters?.exactPhrase === true,
    },
    favorites: normalizeMembership('favorites'),
    subscriptions: normalizeMembership('subscriptions'),
    libraryInitialized: true,
    downloadTasks,
    downloadsInitialized: true,
    localInitialized: true,
    localViewMode: candidate.localViewMode === 'list' ? 'list' : fallback.localViewMode,
    localQuery: typeof candidate.localQuery === 'string' ? candidate.localQuery.slice(0, 120) : fallback.localQuery,
    localItems: normalizedLocalItems,
    discoverFeedback: candidate.discoverFeedback && typeof candidate.discoverFeedback === 'object'
      ? Object.fromEntries(Object.entries(candidate.discoverFeedback).filter(([, value]) =>
        value && typeof value === 'object' && ['none', 'like', 'dislike'].includes(value.rating)
      ).map(([key, value]) => [String(key).slice(0, 64), {rating: value.rating, favorite: value.favorite === true}]))
      : {},
    discoverResultColumns: candidate.discoverResultColumns === 1 ? 1 : 2,
    profileSession,
    profileCounts: profileSession === 'signed-in' ? profileCounts : {subscriptions: null, favorites: null},
    downloads: normalizedDownloads,
  };
}

export class FakeBackend {
  constructor(storage) {
    this.storage = storage;
    this.listeners = new Set();
    let persisted = null;
    try { persisted = JSON.parse(storage.get(FAKE_BACKEND_STORAGE_KEY)); } catch { /* Reset below. */ }
    this.state = normalizedState(persisted);
    this.persist();
  }

  snapshot() {
    return structuredClone(this.state);
  }

  subscribe(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  update(patch) {
    this.state = normalizedState({...this.state, ...patch});
    this.persist();
    this.listeners.forEach((listener) => listener(this.snapshot()));
    return this.snapshot();
  }

  persist() {
    this.storage.set(FAKE_BACKEND_STORAGE_KEY, JSON.stringify(this.state));
  }

  reset() {
    return this.update(defaultState());
  }

  advanceClock(milliseconds) {
    return this.update({clockMs: this.state.clockMs + milliseconds});
  }

  setScenario(scenario) {
    if (!stateLabScenarios.includes(scenario)) throw new Error(`Unknown state lab scenario: ${scenario}`);
    return this.update({scenario});
  }

  setQuery(query) {
    return this.update({query: String(query).slice(0, 120)});
  }

  setViewMode(viewMode) {
    return this.update({viewMode: viewMode === 'list' ? 'list' : 'grid'});
  }

  setFilters(filters) {
    return this.update({filters});
  }

  listWorkshops() {
    if (this.state.scenario === 'loading') return {status: 'loading', items: []};
    if (this.state.scenario === 'error') return {status: 'error', message: '无法载入创意工坊内容，请稍后重试。', items: []};
    if (this.state.scenario === 'empty') return {status: 'empty', items: []};
    const needle = this.state.query.trim().toLocaleLowerCase('zh-CN');
    let items = workshopFixtures.filter((item) => {
      const searchable = [item.title, item.author, ...item.tags].map((value) => value.toLocaleLowerCase('zh-CN'));
      const queryMatches = !needle || (
        this.state.filters.exactPhrase
          ? searchable.some((value) => value.includes(needle))
          : needle.split(/\s+/u).every((token) => searchable.some((value) => value.includes(token)))
      );
      return queryMatches && (this.state.filters.type === 'ALL' || item.type === this.state.filters.type);
    });
    if (this.state.filters.sort === 'MOST_SUBSCRIBED') {
      items = [...items].sort((left, right) => right.subscriptions - left.subscriptions);
    }
    return {status: items.length ? 'content' : 'empty', items};
  }

  localWallpaper(id) {
    const item = this.state.localItems.find((candidate) => candidate.id === String(id));
    return item ? {...item, tags: [...item.tags]} : null;
  }

  localWallpapers() {
    if (this.state.scenario === 'loading') return {status: 'loading', items: []};
    if (this.state.scenario === 'error') return {status: 'error', items: []};
    if (this.state.scenario === 'empty') return {status: 'empty', items: []};
    const needle = this.state.localQuery.trim().toLocaleLowerCase('zh-CN');
    const items = needle ? this.state.localItems.filter((item) => [item.title, item.source, ...item.tags].some((value) => value.toLocaleLowerCase('zh-CN').includes(needle))) : this.state.localItems;
    return {status: items.length ? 'content' : 'empty', items: items.map((item) => ({...item, tags: [...item.tags]}))};
  }

  setLocalQuery(query) { return this.update({localQuery: String(query).slice(0, 120)}); }
  setLocalViewMode(mode) { return this.update({localViewMode: mode === 'list' ? 'list' : 'grid'}); }
  toggleLocalFavorite(id) { return this.update({localItems: this.state.localItems.map((item) => item.id === id ? {...item, favorite: !item.favorite} : item)}); }
  deleteLocalWallpaper(id) { return this.update({localItems: this.state.localItems.filter((item) => item.id !== id)}); }

  downloadTaskGroups() {
    if (this.state.scenario === 'loading') return {status: 'loading', active: [], completed: []};
    if (this.state.scenario === 'error') return {status: 'error', active: [], completed: []};
    if (this.state.scenario === 'empty') return {status: 'empty', active: [], completed: []};
    const hydrate = (task) => ({...task, workshop: this.workshop(task.workshopId)});
    return {
      status: 'content',
      active: this.state.downloadTasks.filter((task) => task.status !== 'COMPLETED').map(hydrate),
      completed: this.state.downloadTasks.filter((task) => task.status === 'COMPLETED').map(hydrate),
    };
  }

  requestDownloadTaskAction(id, action) {
    const transitions = {
      PAUSE: {from: ['DOWNLOADING', 'QUEUED'], to: 'PAUSED'},
      RESUME: {from: ['PAUSED'], to: 'DOWNLOADING'},
      RETRY: {from: ['FAILED', 'CANCELLED'], to: 'QUEUED'},
      CANCEL: {from: ['QUEUED', 'DOWNLOADING', 'PAUSED', 'FAILED'], to: 'CANCELLED'},
    };
    if (action === 'DELETE') {
      const target = this.state.downloadTasks.find((task) => task.id === id);
      return this.update({
        downloadTasks: this.state.downloadTasks.filter((task) => task.id !== id),
        downloads: target ? this.state.downloads.filter((entry) => !(entry.workshopId === target.workshopId && entry.format === target.format)) : this.state.downloads,
      });
    }
    const transition = transitions[action];
    if (!transition) return this.snapshot();
    return this.update({downloadTasks: this.state.downloadTasks.map((task) =>
      task.id === id && transition.from.includes(task.status) ? {...task, status: transition.to} : task
    )});
  }

  clearFinishedDownloadTasks() {
    const removed = this.state.downloadTasks.filter((task) => ['COMPLETED', 'CANCELLED'].includes(task.status));
    return this.update({
      downloadTasks: this.state.downloadTasks.filter((task) => !['COMPLETED', 'CANCELLED'].includes(task.status)),
      downloads: this.state.downloads.filter((entry) => !removed.some((task) => task.workshopId === entry.workshopId && task.format === entry.format)),
    });
  }

  retryFailedDownloadTasks() {
    return this.update({downloadTasks: this.state.downloadTasks.map((task) => task.status === 'FAILED' ? {...task, status: 'QUEUED'} : task)});
  }

  library(collection) {
    const key = collection === 'favorites' ? 'favorites' : 'subscriptions';
    if (this.state.profileSession === 'restoring') return {status: 'restoring', items: []};
    if (this.state.profileSession !== 'signed-in') return {status: 'auth', items: []};
    if (this.state.scenario === 'loading') return {status: 'loading', items: []};
    if (this.state.scenario === 'error') return {status: 'error', items: []};
    if (this.state.scenario === 'empty') return {status: 'empty', items: []};
    const items = this.state[key].map((id) => this.workshop(id)).filter(Boolean);
    return {status: items.length ? 'content' : 'empty', items};
  }

  removeFromLibrary(collection, id) {
    const key = collection === 'favorites' ? 'favorites' : 'subscriptions';
    return this.update({[key]: this.state[key].filter((value) => value !== String(id))});
  }

  profile() {
    const scenario = this.state.scenario;
    if (scenario === 'loading') return {session: 'restoring', counts: {subscriptions: null, favorites: null}};
    if (scenario === 'empty') return {session: 'signed-out', counts: {subscriptions: null, favorites: null}};
    if (scenario === 'error') return {session: 'signed-in', counts: {subscriptions: null, favorites: 18}};
    return {session: this.state.profileSession, counts: {...this.state.profileCounts}};
  }

  discoverRails() {
    if (this.state.scenario === 'loading') return {status: 'loading', rails: []};
    if (this.state.scenario === 'error') return {status: 'error', rails: []};
    if (this.state.scenario === 'empty') return {status: 'empty', rails: []};
    const rails = [
      {id: 'trending-month', title: '本月热门', itemIds: ['3357929793', '3174098802', '3048819267']},
      {id: 'audio-responsive', title: '音频响应', itemIds: ['3048819267', '2985110421', '2769012550']},
      {id: 'creator-showcase', title: '创作者精选', itemIds: ['2871466005', '3357929793', '3174098802']},
    ].map((rail) => ({...rail, items: rail.itemIds.map((id) => this.workshop(id)).filter(Boolean)}));
    return {status: 'content', rails};
  }

  setDiscoverFeedback(railId, patch) {
    const current = this.state.discoverFeedback[railId] ?? {rating: 'none', favorite: false};
    return this.update({discoverFeedback: {
      ...this.state.discoverFeedback,
      [railId]: {...current, ...patch},
    }});
  }

  workshop(id) {
    return workshopFixtures.find((item) => item.id === String(id)) ?? null;
  }

  comments(id) {
    return workshopComments[String(id)] ?? [];
  }

  toggleFavorite(id) {
    const value = String(id);
    const set = new Set(this.state.favorites);
    set.has(value) ? set.delete(value) : set.add(value);
    return this.update({favorites: [...set]});
  }

  toggleSubscription(id) {
    const value = String(id);
    const set = new Set(this.state.subscriptions);
    set.has(value) ? set.delete(value) : set.add(value);
    return this.update({subscriptions: [...set]});
  }

  requestDownload(id, format = 'MPKG') {
    const value = String(id);
    const normalizedFormat = ['MPKG', 'ZIP'].includes(format) ? format : 'MPKG';
    if (this.state.downloads.some((download) => download.workshopId === value && download.format === normalizedFormat)) {
      return this.snapshot();
    }
    const taskId = `download-${value}-${normalizedFormat.toLowerCase()}`;
    return this.update({
      downloads: [...this.state.downloads, {
        id: taskId,
        workshopId: value,
        format: normalizedFormat,
        state: 'queued',
        createdAt: this.state.clockMs,
      }],
      downloadTasks: this.state.downloadTasks.some((task) => task.workshopId === value && task.format === normalizedFormat)
        ? this.state.downloadTasks
        : [...this.state.downloadTasks, {id: taskId, workshopId: value, status: 'QUEUED', progress: 0, format: normalizedFormat}],
    });
  }
}

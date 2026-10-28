import {
  FIXTURE_CLOCK_EPOCH_MS,
  FIXTURE_SCHEMA_VERSION,
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
    favorites: [],
    subscriptions: [],
    downloads: [],
  };
}

function normalizedState(candidate) {
  if (!candidate || candidate.schemaVersion !== FIXTURE_SCHEMA_VERSION) return defaultState();
  const fallback = defaultState();
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
    favorites: Array.isArray(candidate.favorites) ? [...new Set(candidate.favorites.map(String))] : [],
    subscriptions: Array.isArray(candidate.subscriptions) ? [...new Set(candidate.subscriptions.map(String))] : [],
    downloads: Array.isArray(candidate.downloads)
      ? candidate.downloads.filter((entry) =>
        entry && typeof entry === 'object' && typeof entry.workshopId === 'string' &&
        typeof entry.id === 'string' && entry.state === 'queued' && Number.isSafeInteger(entry.createdAt)
      ).map((entry) => ({
        id: entry.id.slice(0, 128), workshopId: entry.workshopId.slice(0, 32), state: 'queued', createdAt: entry.createdAt,
      }))
      : [],
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

  workshop(id) {
    return workshopFixtures.find((item) => item.id === String(id)) ?? null;
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

  requestDownload(id) {
    const value = String(id);
    if (this.state.downloads.some((download) => download.workshopId === value)) return this.snapshot();
    return this.update({
      downloads: [...this.state.downloads, {
        id: `download-${value}`,
        workshopId: value,
        state: 'queued',
        createdAt: this.state.clockMs,
      }],
    });
  }
}

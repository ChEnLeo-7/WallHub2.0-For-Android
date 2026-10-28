import assert from 'node:assert/strict';
import test from 'node:test';
import {
  FAKE_BACKEND_STORAGE_KEY,
  FakeBackend,
} from '../../state-lab/core.js';
import {FIXTURE_CLOCK_EPOCH_MS, FIXTURE_SCHEMA_VERSION} from '../../state-lab/fixtures-v1.js';

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  return {
    get: (key) => values.get(key) ?? null,
    set: (key, value) => values.set(key, value),
    values,
  };
}

test('starts from deterministic fixture state and persists it', () => {
  const storage = memoryStorage();
  const backend = new FakeBackend(storage);
  assert.equal(backend.snapshot().clockMs, FIXTURE_CLOCK_EPOCH_MS);
  assert.equal(backend.listWorkshops().items.length, 6);
  assert.equal(JSON.parse(storage.values.get(FAKE_BACKEND_STORAGE_KEY)).schemaVersion, FIXTURE_SCHEMA_VERSION);
  assert.throws(() => backend.listWorkshops().items[0].tags.push('mutated'));
});

test('resets incompatible persisted schemas', () => {
  const storage = memoryStorage({
    [FAKE_BACKEND_STORAGE_KEY]: JSON.stringify({schemaVersion: 0, scenario: 'error', clockMs: 1}),
  });
  const backend = new FakeBackend(storage);
  assert.equal(backend.snapshot().scenario, 'content');
  assert.equal(backend.snapshot().clockMs, FIXTURE_CLOCK_EPOCH_MS);
});

test('normalizes malformed fields in the current schema', () => {
  const storage = memoryStorage({
    [FAKE_BACKEND_STORAGE_KEY]: JSON.stringify({
      schemaVersion: FIXTURE_SCHEMA_VERSION,
      query: null,
      seed: 42,
      clockMs: 'today',
      downloads: [{workshopId: null}],
    }),
  });
  const backend = new FakeBackend(storage);
  assert.equal(backend.snapshot().query, '');
  assert.equal(backend.snapshot().clockMs, FIXTURE_CLOCK_EPOCH_MS);
  assert.deepEqual(backend.snapshot().downloads, []);
  assert.equal(backend.listWorkshops().status, 'content');
});

test('migrates schema-v1 downloads without format to MPKG', () => {
  const storage = memoryStorage({
    [FAKE_BACKEND_STORAGE_KEY]: JSON.stringify({
      schemaVersion: FIXTURE_SCHEMA_VERSION,
      downloads: [{
        id: 'download-legacy', workshopId: '3357929793', state: 'queued', createdAt: FIXTURE_CLOCK_EPOCH_MS,
      }],
    }),
  });
  const backend = new FakeBackend(storage);
  assert.equal(backend.snapshot().downloads[0].format, 'MPKG');
});

test('migrates pre-Profile schema-v1 state and canonicalizes signed-out counts', () => {
  const legacyStorage = memoryStorage({
    [FAKE_BACKEND_STORAGE_KEY]: JSON.stringify({schemaVersion: FIXTURE_SCHEMA_VERSION, scenario: 'content'}),
  });
  assert.deepEqual(new FakeBackend(legacyStorage).profile(), {
    session: 'signed-in', counts: {subscriptions: 42, favorites: 18},
  });
  const signedOutStorage = memoryStorage({
    [FAKE_BACKEND_STORAGE_KEY]: JSON.stringify({
      schemaVersion: FIXTURE_SCHEMA_VERSION,
      profileSession: 'signed-out',
      profileCounts: {subscriptions: 99, favorites: 88},
    }),
  });
  const signedOutBackend = new FakeBackend(signedOutStorage);
  assert.deepEqual(signedOutBackend.snapshot().profileCounts, {subscriptions: 99, favorites: 88});
  assert.deepEqual(signedOutBackend.profile().counts, {subscriptions: null, favorites: null});
});

test('supports repeatable state-lab scenarios and virtual time', () => {
  const backend = new FakeBackend(memoryStorage());
  backend.setScenario('loading');
  assert.equal(backend.listWorkshops().status, 'loading');
  backend.setScenario('empty');
  assert.equal(backend.listWorkshops().items.length, 0);
  backend.advanceClock(60_000);
  assert.equal(backend.snapshot().clockMs, FIXTURE_CLOCK_EPOCH_MS + 60_000);
  assert.throws(() => backend.setScenario('unknown'));
});

test('exact phrase distinguishes exact fields from partial matches', () => {
  const backend = new FakeBackend(memoryStorage());
  backend.setQuery('Rain Neon');
  assert.deepEqual(backend.listWorkshops().items.map((item) => item.id), ['3357929793']);
  backend.setFilters({type: 'ALL', sort: 'TRENDING', exactPhrase: true});
  assert.deepEqual(backend.listWorkshops().items, []);
  backend.setQuery('Neon Rain');
  assert.deepEqual(backend.listWorkshops().items.map((item) => item.id), ['3357929793']);
});

test('settings and Steam session states persist deterministically', () => {
  const storage = memoryStorage();
  const backend = new FakeBackend(storage);
  assert.equal(backend.settingsState().preferences.enhancedAccess, true);
  backend.toggleSetting('enhancedAccess');
  backend.toggleSetting('matureContent');
  backend.setProfileSession('signed-out');
  const restored = new FakeBackend(storage);
  assert.deepEqual(restored.settingsState(), {
    preferences: {matureContent: true, enhancedAccess: false, outputDirectory: 'WallHub Downloads'},
    steamPhase: 'signed-out',
  });
  restored.submitSteamCredentials('fixture-user', 'not-persisted-secret');
  assert.equal(restored.settingsState().steamPhase, 'device-confirmation');
  assert.equal(JSON.stringify(restored.snapshot()).includes('not-persisted-secret'), false);
  restored.approveSteamDeviceConfirmation();
  assert.equal(restored.settingsState().steamPhase, 'signed-in');
  restored.setProfileSession('signed-out');
  restored.submitSteamCredentials('fixture-user', 'secret');
  restored.cancelSteamAuthentication();
  assert.equal(restored.settingsState().steamPhase, 'signed-out');
  restored.submitSteamCredentials('fixture-user', 'secret');
  restored.useManualSteamGuard();
  const afterChallengeReload = new FakeBackend(storage);
  assert.equal(afterChallengeReload.settingsState().steamPhase, 'signed-out');
  afterChallengeReload.submitSteamGuardCode('stale-code');
  assert.equal(afterChallengeReload.settingsState().steamPhase, 'signed-out');
  afterChallengeReload.submitSteamCredentials('fixture-user', 'secret');
  afterChallengeReload.useManualSteamGuard();
  afterChallengeReload.submitSteamGuardCode('12345');
  assert.deepEqual(new FakeBackend(storage).profile().counts, {subscriptions: 42, favorites: 18});
  restored.setScenario('loading');
  assert.equal(restored.settingsState().steamPhase, 'restoring');
  restored.setScenario('error');
  assert.equal(restored.settingsState().steamPhase, 'failed');
});

test('normalizes malformed and duplicate persisted Local IDs', () => {
  const storage = memoryStorage({[FAKE_BACKEND_STORAGE_KEY]: JSON.stringify({
    schemaVersion: FIXTURE_SCHEMA_VERSION,
    localInitialized: true,
    localItems: [
      {id: 'bad\" onclick=\"alert(1)', title: 'bad', type: 'SCENE'},
      {id: 'local-safe', title: 'first', type: 'SCENE'},
      {id: 'local-safe', title: 'last', type: 'VIDEO'},
    ],
  })});
  const items = new FakeBackend(storage).localWallpapers().items;
  assert.equal(items.length, 1);
  assert.equal(items[0].id, 'local-safe');
});

test('local wallpaper search, favorites, and deletion persist', () => {
  const storage = memoryStorage();
  const backend = new FakeBackend(storage);
  assert.equal(backend.localWallpapers().items.length, 3);
  backend.setLocalQuery('水墨');
  assert.deepEqual(backend.localWallpapers().items.map((item) => item.id), ['local-ink']);
  backend.setLocalQuery('');
  backend.toggleLocalFavorite('local-ink');
  backend.deleteLocalWallpaper('local-cafe');
  const restored = new FakeBackend(storage);
  assert.equal(restored.localWallpapers().items.length, 2);
  assert.equal(restored.localWallpapers().items.find((item) => item.id === 'local-ink').favorite, true);
});

test('download task controls and migration remain deterministic', () => {
  const legacy = memoryStorage({[FAKE_BACKEND_STORAGE_KEY]: JSON.stringify({
    schemaVersion: FIXTURE_SCHEMA_VERSION,
    downloads: [{id: 'download-legacy-zip', workshopId: '2985110421', state: 'queued', createdAt: FIXTURE_CLOCK_EPOCH_MS, format: 'ZIP'}],
  })});
  const backend = new FakeBackend(legacy);
  assert.equal(backend.downloadTaskGroups().active.length, 3);
  assert.equal(backend.snapshot().downloadTasks.find((task) => task.id === 'download-legacy-zip').status, 'QUEUED');
  backend.requestDownloadTaskAction('task-neon', 'PAUSE');
  assert.equal(backend.snapshot().downloadTasks.find((task) => task.id === 'task-neon').status, 'PAUSED');
  backend.retryFailedDownloadTasks();
  assert.equal(backend.snapshot().downloadTasks.find((task) => task.id === 'task-mountain').status, 'QUEUED');
  backend.requestDownloadTaskAction('task-mountain', 'PAUSE');
  assert.equal(backend.snapshot().downloadTasks.find((task) => task.id === 'task-mountain').status, 'PAUSED');
  backend.requestDownloadTaskAction('task-mountain', 'CANCEL');
  assert.equal(backend.downloadTaskGroups().active.find((task) => task.id === 'task-mountain').status, 'CANCELLED');
  backend.requestDownloadTaskAction('task-mountain', 'RETRY');
  assert.equal(backend.snapshot().downloadTasks.find((task) => task.id === 'task-mountain').status, 'QUEUED');
  backend.clearFinishedDownloadTasks();
  assert.equal(new FakeBackend(legacy).downloadTaskGroups().completed.length, 0);
  backend.requestDownload('2769012550', 'ZIP');
  const roundTripId = 'download-2769012550-zip';
  backend.requestDownloadTaskAction(roundTripId, 'CANCEL');
  backend.requestDownloadTaskAction(roundTripId, 'DELETE');
  assert.equal(backend.snapshot().downloads.some((entry) => entry.workshopId === '2769012550' && entry.format === 'ZIP'), false);
  backend.requestDownload('2769012550', 'ZIP');
  assert.equal(backend.snapshot().downloadTasks.some((task) => task.id === roundTripId), true);
  backend.requestDownload('2871466005', 'ZIP');
  const clearedId = 'download-2871466005-zip';
  backend.requestDownloadTaskAction(clearedId, 'CANCEL');
  backend.clearFinishedDownloadTasks();
  assert.equal(backend.snapshot().downloads.some((entry) => entry.workshopId === '2871466005' && entry.format === 'ZIP'), false);
  backend.requestDownload('2871466005', 'ZIP');
  assert.equal(backend.snapshot().downloadTasks.some((task) => task.id === clearedId), true);
});

test('migrates pre-Library schema-v1 membership without losing user additions', () => {
  const storage = memoryStorage({
    [FAKE_BACKEND_STORAGE_KEY]: JSON.stringify({
      schemaVersion: FIXTURE_SCHEMA_VERSION,
      favorites: ['2985110421'],
      subscriptions: [],
    }),
  });
  const backend = new FakeBackend(storage);
  assert.equal(backend.library('subscriptions').items.length, 3);
  assert.equal(backend.library('favorites').items.length, 4);
  assert.equal(backend.snapshot().libraryInitialized, true);
});

test('library collections and removals remain deterministic', () => {
  const storage = memoryStorage();
  const backend = new FakeBackend(storage);
  assert.equal(backend.library('subscriptions').items.length, 3);
  backend.removeFromLibrary('subscriptions', '3174098802');
  assert.equal(new FakeBackend(storage).library('subscriptions').items.length, 2);
  backend.removeFromLibrary('subscriptions', '2985110421');
  backend.removeFromLibrary('subscriptions', '2769012550');
  assert.equal(backend.library('subscriptions').status, 'empty');
  backend.setScenario('empty');
  assert.equal(backend.library('favorites').status, 'empty');
  backend.setScenario('content');
  backend.update({profileSession: 'restoring'});
  assert.equal(backend.library('favorites').status, 'restoring');
  backend.update({profileSession: 'signed-out'});
  assert.equal(backend.library('favorites').status, 'auth');
});

test('profile session and partial count states remain deterministic', () => {
  const backend = new FakeBackend(memoryStorage());
  assert.deepEqual(backend.profile(), {session: 'signed-in', counts: {subscriptions: 42, favorites: 18}});
  backend.setScenario('loading');
  assert.deepEqual(backend.profile(), {session: 'restoring', counts: {subscriptions: null, favorites: null}});
  backend.setScenario('empty');
  assert.deepEqual(backend.profile(), {session: 'signed-out', counts: {subscriptions: null, favorites: null}});
  backend.setScenario('error');
  assert.deepEqual(backend.profile(), {session: 'signed-in', counts: {subscriptions: null, favorites: 18}});
});

test('discover rails and feedback remain deterministic', () => {
  const storage = memoryStorage();
  const backend = new FakeBackend(storage);
  assert.deepEqual(backend.discoverRails().rails.map((rail) => rail.id), [
    'trending-month', 'audio-responsive', 'creator-showcase',
  ]);
  backend.setDiscoverFeedback('trending-month', {rating: 'like', favorite: true});
  backend.update({discoverResultColumns: 1});
  const restored = new FakeBackend(storage).snapshot();
  assert.deepEqual(restored.discoverFeedback['trending-month'], {rating: 'like', favorite: true});
  assert.equal(restored.discoverResultColumns, 1);
});

test('detail comments remain deterministic and immutable', () => {
  const backend = new FakeBackend(memoryStorage());
  assert.equal(backend.comments('3357929793').length, 2);
  assert.throws(() => backend.comments('3357929793').push({}));
  assert.deepEqual(backend.comments('unknown'), []);
});

test('search and mutations remain deterministic across reloads', () => {
  const storage = memoryStorage();
  const backend = new FakeBackend(storage);
  backend.setQuery('像素');
  assert.deepEqual(backend.listWorkshops().items.map((item) => item.id), ['3048819267']);
  backend.toggleFavorite('3048819267');
  backend.toggleSubscription('3048819267');
  backend.requestDownload('3048819267', 'MPKG');
  backend.requestDownload('3048819267', 'ZIP');
  backend.requestDownload('3048819267', 'ZIP');
  const restored = new FakeBackend(storage);
  assert.equal(restored.snapshot().favorites.includes('3048819267'), false);
  assert.equal(restored.snapshot().subscriptions.includes('3048819267'), true);
  assert.equal(restored.snapshot().downloads[0].createdAt, FIXTURE_CLOCK_EPOCH_MS);
  assert.deepEqual(restored.snapshot().downloads.map((entry) => entry.format), ['MPKG', 'ZIP']);
});

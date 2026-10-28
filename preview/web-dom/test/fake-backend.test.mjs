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
  assert.deepEqual(new FakeBackend(signedOutStorage).snapshot().profileCounts, {subscriptions: null, favorites: null});
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

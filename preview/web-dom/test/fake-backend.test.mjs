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

test('search and mutations remain deterministic across reloads', () => {
  const storage = memoryStorage();
  const backend = new FakeBackend(storage);
  backend.setQuery('像素');
  assert.deepEqual(backend.listWorkshops().items.map((item) => item.id), ['3048819267']);
  backend.toggleFavorite('3048819267');
  backend.requestDownload('3048819267');
  const restored = new FakeBackend(storage);
  assert.deepEqual(restored.snapshot().favorites, ['3048819267']);
  assert.equal(restored.snapshot().downloads[0].createdAt, FIXTURE_CLOCK_EPOCH_MS);
});

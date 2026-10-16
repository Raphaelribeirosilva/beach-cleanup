const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createPortalApi } = require('../src/main/resources/static/portal-api.js');

const response = (status, body) => ({ status, ok: status >= 200 && status < 300, json: async () => body });
const token = { accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 1800, userId: 17, role: 'VOLUNTEER' };
const sessionKey = 'beachcleanup.session.v1';
function memoryStorage() {
  const data = new Map();
  return {
    getItem: key => data.get(key) ?? null,
    setItem: (key, value) => data.set(key, value),
    removeItem: key => data.delete(key)
  };
}

test('reload restores the session without extending its original expiry; logout removes it', async () => {
  const storage = memoryStorage();
  let time = 100000;
  const fetcher = async (path, options) => {
    if (path === '/auth/login') return response(200, token);
    assert.equal(options.headers.Authorization, 'Bearer test-token');
    return response(200, { name: 'Ana' });
  };
  const options = { storage, now: () => time };
  const first = createPortalApi(fetcher, undefined, options);
  await first.login({ email: 'ana@example.com', password: 'never-store-this' });
  const persisted = storage.getItem(sessionKey);
  assert.ok(!persisted.includes('never-store-this') && !persisted.includes('ana@example.com'));
  time += 600000;
  const reloaded = createPortalApi(fetcher, undefined, options);
  assert.deepEqual(reloaded.session(), token);
  assert.equal(storage.getItem(sessionKey), persisted);
  assert.equal((await reloaded.profile()).name, 'Ana');
  time = 1900000;
  assert.equal(reloaded.session(), null);
  assert.equal(storage.getItem(sessionKey), null);
  await reloaded.login({});
  reloaded.logout();
  assert.equal(createPortalApi(fetcher, undefined, options).session(), null);
});

test('expiration clears storage and notifies once even before a network request', async () => {
  const storage = memoryStorage();
  let time = 100000;
  let expired = 0;
  let calls = 0;
  let timer;
  const api = createPortalApi(async () => { calls++; return response(200, token); }, () => expired++, {
    storage, now: () => time, schedule: callback => { timer = callback; }, cancel: () => {}
  });
  await api.login({});
  time += 1800000;
  timer();
  assert.equal(api.session(), null);
  assert.equal(storage.getItem(sessionKey), null);
  await assert.rejects(api.profile(), /Entre na sua conta/);
  assert.equal(expired, 1);
  assert.equal(calls, 1);
});

test('corrupt, malformed and expired stored sessions are discarded', () => {
  const storage = memoryStorage();
  for (const saved of ['invalid-json', 'null', '{}',
    JSON.stringify({ session: token, expiresAt: 1000 }),
    JSON.stringify({ session: { ...token, role: 'ROOT' }, expiresAt: 2000000 }),
    JSON.stringify({ session: { ...token, userId: -1 }, expiresAt: 2000000 }),
    JSON.stringify({ session: token, expiresAt: 9999999999 })]) {
    storage.setItem(sessionKey, saved);
    const api = createPortalApi(async () => { throw new Error('No request expected'); }, undefined,
      { storage, now: () => 1000000 });
    assert.equal(api.session(), null);
    assert.equal(storage.getItem(sessionKey), null);
  }
});

test('unavailable storage does not prevent login or logout', async () => {
  const blocked = () => { throw new Error('Storage blocked'); };
  const api = createPortalApi(async () => response(200, token), undefined, {
    storage: { getItem: blocked, setItem: blocked, removeItem: blocked }
  });
  await api.login({});
  assert.deepEqual(api.session(), token);
  api.logout();
  assert.equal(api.session(), null);
});

test('a restored session rejected by the server is removed from storage', async () => {
  const storage = memoryStorage();
  const first = createPortalApi(async () => response(200, token), undefined, { storage });
  await first.login({});
  let expired = 0;
  const restored = createPortalApi(async () => response(401, {}), () => expired++, { storage });
  await assert.rejects(restored.profile(), /sessão expirou/);
  assert.equal(restored.session(), null);
  assert.equal(expired, 1);
  assert.equal(storage.getItem(sessionKey), null);
});

test('logout during a pending login cannot restore credentials', async () => {
  const storage = memoryStorage();
  let complete;
  const api = createPortalApi(() => new Promise(resolve => { complete = resolve; }), undefined, { storage });
  const pending = api.login({});
  api.logout();
  complete(response(200, token));
  await assert.rejects(pending, /sessão mudou/);
  assert.equal(api.session(), null);
  assert.equal(storage.getItem(sessionKey), null);
});

test('public browsing encodes filters without credentials or a token', async () => {
  const api = createPortalApi(async (path, options) => {
    const url = new URL(path, 'http://localhost');
    assert.equal(url.pathname, '/cleanupEvent/search');
    assert.equal(url.searchParams.get('location'), 'Praia & Sol');
    assert.equal(url.searchParams.get('page'), '2');
    assert.equal(url.searchParams.get('size'), '6');
    assert.equal(options.headers.Authorization, undefined);
    assert.equal(options.credentials, 'omit');
    return response(200, { content: [] });
  });
  await api.events({ location: 'Praia & Sol', page: 2 });
});

test('private actions use JWT, constrain collections to the current account and clear on logout', async () => {
  const calls = [];
  const api = createPortalApi(async (path, options) => {
    calls.push({ path, options });
    return path === '/auth/login' ? response(200, token) : response(204);
  });
  await api.login({ email: 'ana@example.com', password: 'secret' });
  await api.collections({ volunteerId: 99, page: 0 });
  assert.equal(new URL(calls[1].path, 'http://localhost').searchParams.get('volunteerId'), '17');
  await api.register(23);
  assert.equal(calls[2].path, '/users/me/events/23');
  assert.equal(calls[2].options.method, 'PUT');
  assert.equal(calls[2].options.headers.Authorization, 'Bearer test-token');
  await api.cancel(23);
  assert.equal(calls[3].options.method, 'DELETE');
  const input = { beach: 'Praia A', materialType: 'Vidro', weightKg: 2.5, registrationDate: '2026-10-15', event: { id: 23 } };
  await api.createCollection(input);
  assert.deepEqual(JSON.parse(calls[4].options.body), input);
  api.logout();
  assert.equal(api.session(), null);
  await assert.rejects(api.profile(), /Entre na sua conta/);
  assert.equal(calls.length, 5);
});

test('401 clears the session, while a login failure does not masquerade as expiration', async () => {
  let expired = 0;
  let validLogin = true;
  const api = createPortalApi(async path => path === '/auth/login' && validLogin
    ? response(200, token) : response(401, {}), () => expired++);
  await api.login({});
  await assert.rejects(api.profile(), /sessão expirou/);
  assert.equal(api.session(), null);
  assert.equal(expired, 1);
  validLogin = false;
  await assert.rejects(api.login({}), /E-mail ou senha inválidos/);
  assert.equal(expired, 1);
});

test('old private responses cannot reveal data after logout or clear a newer session', async () => {
  let complete;
  const api = createPortalApi(path => path === '/auth/login'
    ? Promise.resolve(response(200, { ...token })) : new Promise(resolve => { complete = resolve; }));
  await api.login({});
  const pending = api.profile();
  api.logout();
  complete(response(200, { name: 'Previous account' }));
  await assert.rejects(pending, /sessão mudou/);
  await api.login({});
  const oldRequest = api.profile();
  await api.login({});
  complete(response(401, {}));
  await assert.rejects(oldRequest, /sessão expirou/);
  assert.ok(api.session());
});

test('validation and conflict messages reach the UI and network failures have a useful message', async () => {
  let result = response(400, { errors: [{ message: 'Informe um peso positivo.' }] });
  const api = createPortalApi(async () => result);
  await assert.rejects(api.signup({}), /Informe um peso positivo/);
  result = response(409, { message: 'Este e-mail já está cadastrado.' });
  await assert.rejects(api.signup({}), error => error.status === 409 && /já está cadastrado/.test(error.message));
  const offline = createPortalApi(async () => { throw new TypeError('fetch failed'); });
  await assert.rejects(offline.events({}), /Verifique sua conexão/);
});

test('administrative client rejects anonymous and volunteer operations without sending requests', async () => {
  let calls = 0;
  const api = createPortalApi(async () => { calls++; return response(200, token); });
  for (const logged of [false, true]) {
    if (logged) await api.login({});
    for (const operation of [() => api.admin.users({}), () => api.admin.events({}),
      () => api.admin.collections({}), () => api.admin.event(2), () => api.admin.collection(3),
      () => api.admin.saveEvent(null, {}), () => api.admin.saveEvent(2, {}), () => api.admin.deleteEvent(2),
      () => api.admin.saveCollection(null, {}), () => api.admin.saveCollection(3, {}), () => api.admin.deleteCollection(3)]) {
      await assert.rejects(operation(), error => error.status === 403);
    }
    assert.equal(calls, logged ? 1 : 0);
  }
});

test('administrators query all collections with pagination and filters, and send CRUD payloads with JWT', async () => {
  const calls = [];
  const api = createPortalApi(async (path, options) => {
    calls.push({ path, options });
    return path === '/auth/login' ? response(200, { ...token, role: 'ADMIN' }) : response(200, {});
  });
  await api.login({});
  await api.admin.collections({ page: 2, beach: 'Praia & Sol', eventId: 9 });
  const url = new URL(calls[1].path, 'http://localhost');
  assert.equal(url.searchParams.get('volunteerId'), null, 'administrative query must include other owners');
  assert.equal(url.searchParams.get('page'), '2');
  assert.equal(url.searchParams.get('beach'), 'Praia & Sol');
  assert.equal(url.searchParams.get('eventId'), '9');
  await api.admin.users({ name: 'Ana', page: 1 });
  await api.admin.events({ status: 'Pausado' });
  const event = { title: 'Mutirão', location: 'Praia A', dateTime: '2026-11-01T09:00:00', status: 'Agendado' };
  const collection = { beach: 'Praia A', materialType: 'Vidro', weightKg: 2, registrationDate: '2026-10-15', volunteer: { id: 8 }, event: null };
  await api.admin.saveEvent(null, event);
  await api.admin.saveEvent(9, { ...event, status: 'Em andamento' });
  await api.admin.saveCollection(null, collection);
  await api.admin.saveCollection(10, { ...collection, volunteer: null });
  await api.admin.deleteEvent(9);
  await api.admin.deleteCollection(10);
  assert.deepEqual(calls.slice(4).map(call => [call.path, call.options.method]), [
    ['/cleanupEvent', 'POST'], ['/cleanupEvent/9', 'PUT'], ['/trashCollection', 'POST'],
    ['/trashCollection/10', 'PUT'], ['/cleanupEvent/9', 'DELETE'], ['/trashCollection/10', 'DELETE']
  ]);
  assert.deepEqual(JSON.parse(calls[6].options.body), collection);
  assert.equal(JSON.parse(calls[7].options.body).volunteer, null);
  for (const call of calls.slice(1)) assert.equal(call.options.headers.Authorization, 'Bearer test-token');
});

test('administrative requests preserve conflict details, expire sessions and discard late responses', async () => {
  let result = response(409, { message: 'O evento não aceita novas coletas.' });
  let complete;
  const api = createPortalApi(path => path === '/auth/login'
    ? Promise.resolve(response(200, { ...token, role: 'ADMIN' }))
    : result ? Promise.resolve(result) : new Promise(resolve => { complete = resolve; }));
  await api.login({});
  await assert.rejects(api.admin.saveCollection(null, {}), error => error.status === 409 && /não aceita/.test(error.message));
  result = response(401, {});
  await assert.rejects(api.admin.users({}), /sessão expirou/);
  assert.equal(api.session(), null);
  await api.login({});
  result = null;
  const pending = api.admin.users({});
  api.logout();
  complete(response(200, { content: [{ email: 'private@example.com' }] }));
  await assert.rejects(pending, /sessão mudou/);
});

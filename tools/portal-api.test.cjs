const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createPortalApi } = require('../src/main/resources/static/portal-api.js');

const response = (status, body) => ({ status, ok: status >= 200 && status < 300, json: async () => body });
const token = { accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 1800, userId: 17, role: 'VOLUNTEER', refreshToken: 'r'.repeat(43), refreshExpiresIn: 604800 };
const sessionKey = 'beachcleanup.session.v2';

test('authentication preserves structured field errors and email conflict codes', async () => {
  const fields = [{ fieldName: 'password', message: 'Evite senhas comuns.' }];
  const api = createPortalApi(async () => response(400, { errors: fields }), undefined, { storage: null });
  await assert.rejects(api.signup({}), error => {
    assert.deepEqual(error.fields, fields);
    assert.equal(error.status, 400);
    return true;
  });
  const conflict = createPortalApi(async () => response(409, { error: 'Email conflict', message: 'E-mail já cadastrado.' }), undefined, { storage: null });
  await assert.rejects(conflict.signup({}), error => error.code === 'Email conflict' && error.status === 409);
});

test('recovery and confirmation use public JSON without persisting action tokens', async () => {
  const calls = [], storage = memoryStorage();
  const api = createPortalApi(async (path, options) => { calls.push({ path, options }); return response(200, { message: 'Resposta genérica' }); }, undefined, { storage });
  await api.requestRecovery('ana@example.com');
  await api.requestVerification('ana@example.com');
  await api.resetPassword({ token: 'x'.repeat(43), newPassword: 'Uma praia mais limpa!' });
  await api.verifyEmail('y'.repeat(43));
  assert.deepEqual(calls.map(call => call.path), ['/auth/recovery', '/auth/verification', '/auth/reset-password', '/auth/verify-email']);
  for (const call of calls) {
    assert.equal(call.options.method, 'POST'); assert.equal(call.options.credentials, 'omit');
    assert.equal(call.options.headers.Authorization, undefined); assert.equal(call.options.headers['Content-Type'], 'application/json');
  }
  assert.deepEqual(JSON.parse(calls[0].options.body), { email: 'ana@example.com' });
  assert.deepEqual(JSON.parse(calls[3].options.body), { token: 'y'.repeat(43) });
  assert.equal(storage.getItem(sessionKey), null);
});

test('concurrent private requests share one refresh, rotate storage and retain the original session deadline', async () => {
  const storage = memoryStorage();
  let time = 100000, resolveRefresh;
  const calls = [];
  const next = { ...token, accessToken: 'renewed-access', refreshToken: 's'.repeat(43) };
  const api = createPortalApi(async (path, options) => {
    calls.push({ path, options });
    if (path === '/auth/login') return response(200, token);
    if (path === '/auth/refresh') return new Promise(resolve => { resolveRefresh = resolve; });
    return response(200, {});
  }, undefined, { storage, now: () => time, schedule: () => 0, cancel: () => {} });
  await api.login({});
  const deadline = JSON.parse(storage.getItem(sessionKey)).refreshExpiresAt;
  time += 1800000;
  const profile = api.profile(), collections = api.collections({});
  assert.equal(calls.filter(call => call.path === '/auth/refresh').length, 1);
  assert.equal(calls.length, 2, 'private requests wait until renewal completes');
  resolveRefresh(response(200, next));
  await Promise.all([profile, collections]);
  const renewal = calls[1];
  assert.deepEqual(JSON.parse(renewal.options.body), { refreshToken: token.refreshToken });
  assert.equal(renewal.options.credentials, 'omit');
  assert.equal(renewal.options.headers.Authorization, undefined);
  for (const call of calls.slice(2)) assert.equal(call.options.headers.Authorization, 'Bearer renewed-access');
  const saved = JSON.parse(storage.getItem(sessionKey));
  assert.equal(saved.refreshExpiresAt, deadline);
  assert.equal(saved.session.refreshToken, next.refreshToken);
});

test('reload with expired access silently renews while refresh is valid', async () => {
  const storage = memoryStorage();
  let time = 100000;
  const settings = { storage, now: () => time, schedule: () => 0, cancel: () => {} };
  await createPortalApi(async () => response(200, token), undefined, settings).login({});
  time += 1800000;
  const calls = [];
  const restored = createPortalApi(async path => { calls.push(path); return response(200, path === '/auth/refresh' ? { ...token, accessToken: 'new' } : { name: 'Ana' }); }, undefined, settings);
  assert.ok(restored.session());
  assert.equal((await restored.profile()).name, 'Ana');
  assert.deepEqual(calls, ['/auth/refresh', '/users/17']);
});

test('proactive renewal fires before access expiry and does not reauthenticate', async () => {
  let time = 100000, callback;
  const calls = [];
  const api = createPortalApi(async path => {
    calls.push(path); return response(200, { ...token, accessToken: path === '/auth/refresh' ? 'renewed' : 'initial' });
  }, undefined, { now: () => time, schedule: fn => { callback = fn; }, cancel: () => {} });
  await api.login({}); time += 1770000; callback();
  await api.renew(); // Joins the timer's pending renewal.
  assert.deepEqual(calls, ['/auth/login', '/auth/refresh']);
  assert.equal(api.session().accessToken, 'renewed');
});

test('logout while refresh is pending revokes the family and a late result cannot restore credentials', async () => {
  let resolveRefresh;
  const revoked = [];
  const api = createPortalApi(async (path, options) => {
    if (path === '/auth/login') return response(200, token);
    if (path === '/auth/logout') { revoked.push(JSON.parse(options.body).refreshToken); return response(204); }
    return new Promise(resolve => { resolveRefresh = resolve; });
  });
  await api.login({});
  const renewal = api.renew(); await api.logout();
  resolveRefresh(response(200, { ...token, refreshToken: 's'.repeat(43) }));
  await assert.rejects(renewal, /sessão mudou/);
  assert.equal(api.session(), null);
  assert.deepEqual(revoked, [token.refreshToken, 's'.repeat(43)]);
});

test('logout failure clears local storage but retains a credential in memory only for explicit retry', async () => {
  const storage = memoryStorage();
  let offline = true, attempts = 0;
  const api = createPortalApi(async path => {
    if (path === '/auth/login') return response(200, token);
    attempts++;
    if (offline) throw new Error('offline');
    return response(204);
  }, undefined, { storage });
  await api.login({});
  await assert.rejects(api.logout(), /não foi possível encerrar no servidor/);
  assert.equal(api.session(), null); assert.equal(storage.getItem(sessionKey), null);
  offline = false; await api.logout(); await api.logout();
  assert.equal(attempts, 2, 'successful revocation removes the pending credential');
});

test('failed refresh is never retried automatically and expires the session exactly once', async () => {
  for (const failure of [response(401, {}), null]) {
    let calls = 0, expired = 0;
    const api = createPortalApi(async path => {
      if (path === '/auth/login') return response(200, token);
      calls++; if (!failure) throw new Error('lost response'); return failure;
    }, () => expired++);
    await api.login({});
    await assert.rejects(api.renew());
    await assert.rejects(api.profile(), /Entre na sua conta/);
    assert.equal(calls, 1); assert.equal(expired, 1); assert.equal(api.session(), null);
  }
});

test('401 on a write does not repeat the write or attempt refresh', async () => {
  const calls = [];
  const api = createPortalApi(async path => { calls.push(path); return path === '/auth/login' ? response(200, token) : response(401, {}); });
  await api.login({});
  await assert.rejects(api.createCollection({ weightKg: 1 }), { status: 401 });
  assert.deepEqual(calls, ['/auth/login', '/trashCollection']);
  assert.equal(api.session(), null);
});

test('impact is public; participation is admin-only and personal changes send versions with JWT', async () => {
  const calls = [];
  const api = createPortalApi(async (path, options) => {
    calls.push({ path, options });
    return response(200, path === '/auth/login' ? token : {});
  });
  await api.report({ from: '2026-01-01', beachId: 4, materialId: 9, groupBy: 'event' });
  assert.equal(calls[0].path, '/reports/impact?from=2026-01-01&beachId=4&materialId=9&groupBy=event');
  assert.equal(calls[0].options.headers.Authorization, undefined);
  await assert.rejects(api.participation({}), { status: 403 });
  await api.login({});
  await assert.rejects(api.participation({}), { status: 403 });
  await api.updateProfile({ name: 'Ana', email: 'ana@example.com', version: 3 });
  await api.updateCollection(42, { weightKg: 1.125, version: 7 });
  await api.deleteCollection(42, 3);
  await api.changePassword({ currentPassword: 'old', newPassword: 'new' });
  assert.deepEqual(calls.slice(2).map(call => [call.path, call.options.method]), [
    ['/users/17', 'PUT'], ['/trashCollection/42', 'PUT'], ['/trashCollection/42?version=3', 'DELETE'], ['/auth/password', 'PUT']
  ]);
  for (const call of calls.slice(2)) assert.equal(call.options.headers.Authorization, 'Bearer test-token');
  assert.equal(JSON.parse(calls[2].options.body).version, 3);
  assert.equal(JSON.parse(calls[3].options.body).version, 7);
});
test('collection forms enforce gram precision, mass limits and the Sao Paulo day boundary', () => {
  const rules = createPortalApi(async () => response(200, {})).dataRules;
  for (const weight of ['0.001', '100000', '1.2300', '.125']) assert.equal(rules.weightValid(weight), true, weight);
  for (const weight of ['0', '-1', '0.0009', '100000.001', '1.2345', 'NaN', 'Infinity']) assert.equal(rules.weightValid(weight), false, weight);
  const today = rules.today(new Date('2026-10-18T01:30:00Z'));
  assert.equal(today, '2026-10-17');
  assert.equal(rules.dateValid('1900-01-01', today), true);
  assert.equal(rules.dateValid(today, today), true);
  assert.equal(rules.dateValid('2026-10-18', today), false);
  assert.equal(rules.dateValid('1899-12-31', today), false);
});

test('quality and rounding review use authenticated admin requests and pagination', async () => {
  const calls = [];
  const api = createPortalApi(async (path, options) => {
    calls.push({ path, options });
    return path === '/auth/login' ? response(200, { ...token, role: 'ADMIN' }) : response(200, {});
  });
  await api.login({});
  await api.admin.quality({ page: 2 });
  await api.admin.rounding({ page: 1 });
  assert.equal(calls[1].path, '/admin/data-quality?size=6&page=2');
  assert.equal(calls[2].path, '/admin/data-quality/rounding?size=6&page=1');
  for (const call of calls.slice(1)) assert.equal(call.options.headers.Authorization, 'Bearer test-token');
});
function memoryStorage() {
  const data = new Map();
  return {
    getItem: key => data.get(key) ?? null,
    setItem: (key, value) => data.set(key, value),
    removeItem: key => data.delete(key)
  };
}

test('reload restores the session without extending its refresh deadline; logout revokes it', async () => {
  const storage = memoryStorage();
  let time = 100000;
  const fetcher = async (path, options) => {
    if (path === '/auth/login') return response(200, token);
    if (path === '/auth/logout') return response(204);
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
  time = 604900000;
  assert.equal(reloaded.session(), null);
  assert.equal(storage.getItem(sessionKey), null);
  await reloaded.login({});
  await reloaded.logout();
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
  time += 604800000;
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
    JSON.stringify({ session: token, expiresAt: 1000, refreshExpiresAt: 1000 }),
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
  await api.logout();
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
  await api.logout();
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
  await api.logout();
  assert.equal(api.session(), null);
  await assert.rejects(api.profile(), /Entre na sua conta/);
  assert.equal(calls.length, 6);
  assert.equal(calls[5].path, '/auth/logout');
  assert.deepEqual(JSON.parse(calls[5].options.body), { refreshToken: token.refreshToken });
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
    ? Promise.resolve(response(200, { ...token })) : path === '/auth/logout' ? Promise.resolve(response(204)) : new Promise(resolve => { complete = resolve; }));
  await api.login({});
  const pending = api.profile();
  await api.logout();
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
      () => api.admin.quality({}), () => api.admin.rounding({}),
      () => api.admin.collections({}), () => api.admin.event(2), () => api.admin.collection(3),
      () => api.admin.saveEvent(null, {}), () => api.admin.saveEvent(2, {}), () => api.admin.deleteEvent(2),
      () => api.admin.saveCollection(null, {}), () => api.admin.saveCollection(3, {}), () => api.admin.deleteCollection(3)]) {
      await assert.rejects(operation(), error => error.status === 403);
    }
    assert.equal(calls, logged ? 1 : 0);
  }
});

test('catalogs are read publicly and mutations carry admin JWT and explicit versions', async () => {
  const calls = [];
  const api = createPortalApi(async (path, options) => {
    calls.push({ path, options });
    return path === '/auth/login' ? response(200, { ...token, role: 'ADMIN' }) : response(200, []);
  });
  await api.catalog('beaches');
  assert.equal(calls[0].options.headers.Authorization, undefined);
  await assert.rejects(api.admin.saveCatalog('materials', null, { name: 'Vidro' }), error => error.status === 403);
  await assert.rejects(api.admin.mergeCatalog('beaches', 1, {}), error => error.status === 403);
  await api.login({});
  const input = { name: 'Vidro', aliases: ['Vidros'], version: 2 };
  await api.admin.saveCatalog('materials', 7, input);
  const merge = { targetId: 3, version: 2, targetVersion: 4 };
  await api.admin.mergeCatalog('beaches', 8, merge);
  assert.equal(calls[2].path, '/catalog/materials/7');
  assert.equal(calls[2].options.method, 'PUT');
  assert.deepEqual(JSON.parse(calls[2].options.body), input);
  assert.equal(calls[3].path, '/catalog/beaches/8/merge');
  assert.deepEqual(JSON.parse(calls[3].options.body), merge);
  assert.equal(calls[3].options.headers.Authorization, 'Bearer test-token');
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
  await api.admin.deleteEvent(9, 2);
  await api.admin.deleteCollection(10, 4);
  assert.deepEqual(calls.slice(4).map(call => [call.path, call.options.method]), [
    ['/cleanupEvent', 'POST'], ['/cleanupEvent/9', 'PUT'], ['/trashCollection', 'POST'],
    ['/trashCollection/10', 'PUT'], ['/cleanupEvent/9?version=2', 'DELETE'], ['/trashCollection/10?version=4', 'DELETE']
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
    : path === '/auth/logout' ? Promise.resolve(response(204)) : result ? Promise.resolve(result) : new Promise(resolve => { complete = resolve; }));
  await api.login({});
  await assert.rejects(api.admin.saveCollection(null, {}), error => error.status === 409 && /não aceita/.test(error.message));
  result = response(401, {});
  await assert.rejects(api.admin.users({}), /sessão expirou/);
  assert.equal(api.session(), null);
  await api.login({});
  result = null;
  const pending = api.admin.users({});
  await api.logout();
  complete(response(200, { content: [{ email: 'private@example.com' }] }));
  await assert.rejects(pending, /sessão mudou/);
});


test('audit requests require admin role and preserve investigation filters', async () => {
  const calls = [];
  const api = createPortalApi(async (path, options) => {
    calls.push({ path, options });
    return response(200, path === '/auth/login' ? { ...token, role: 'ADMIN' } : {});
  });
  await assert.rejects(api.admin.audit({}), error => error.status === 403);
  assert.equal(calls.length, 0);
  await api.login({});
  await api.admin.audit({ resource: 'COLLECTION', resourceId: 42, actorId: 7, action: 'TRANSFER', from: '2026-10-01T00:00:00Z', page: 1 });
  const url = new URL(calls[1].path, 'http://localhost');
  assert.equal(url.pathname, '/admin/audit');
  assert.equal(url.searchParams.get('resourceId'), '42');
  assert.equal(url.searchParams.get('actorId'), '7');
  assert.equal(url.searchParams.get('from'), '2026-10-01T00:00:00Z');
  assert.equal(url.searchParams.get('page'), '1');
  assert.equal(calls[1].options.headers.Authorization, 'Bearer test-token');
});


test('registrations use a bounded page and selected event IDs without downloading all events', async () => {
  const calls = [];
  const api = createPortalApi(async (path, options) => {
    calls.push({ path, options });
    return response(200, path === '/auth/login' ? token : { content: [], totalElements: 500, registeredEventIds: [7] });
  });
  await api.login({});
  const result = await api.registrations({ page: 2, eventIds: '7,8' });
  const url = new URL(calls[1].path, 'http://localhost');
  assert.equal(url.pathname, '/users/me/events/search');
  assert.equal(url.searchParams.get('size'), '6');
  assert.equal(url.searchParams.get('page'), '2');
  assert.equal(url.searchParams.get('eventIds'), '7,8');
  assert.equal(result.totalElements, 500);
  assert.deepEqual(result.registeredEventIds, [7]);
});

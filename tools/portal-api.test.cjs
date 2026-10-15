const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createPortalApi } = require('../src/main/resources/static/portal-api.js');

const response = (status, body) => ({ status, ok: status >= 200 && status < 300, json: async () => body });
const token = { accessToken: 'test-token', userId: 17, role: 'VOLUNTEER' };

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

/* End-to-end checks against an owned, disposable H2 application. No external packages. */
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const net = require('node:net');
const assert = require('node:assert/strict');

const root = path.resolve(__dirname, '..');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(operation, description, timeout = 30000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    try { const value = await operation(); if (value) return value; } catch (_) { /* still starting */ }
    await pause(150);
  }
  throw new Error(`Timeout: ${description}`);
}
async function freePort() {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function main() {
  const browserPath = process.env.PORTAL_BROWSER || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
  assert.ok(fs.existsSync(browserPath), 'Set PORTAL_BROWSER to a Chromium/Edge executable.');
  const jar = path.join(root, 'target', 'beachcleanup-0.0.1-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar), 'Run Maven package before browser verification.');
  const temporaryRoot = fs.realpathSync(os.tmpdir());
  const profile = fs.mkdtempSync(path.join(temporaryRoot, 'beachcleanup-portal-'));
  const appPort = await freePort();
  const base = `http://127.0.0.1:${appPort}`;
  const children = [];
  let socket;
  const browserErrors = [];
  let logs = '';
  function launch(command, args) {
    const child = spawn(command, args, { cwd: root, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    child.on('error', error => { logs += error.message; });
    child.stdout.on('data', data => { logs = (logs + data).slice(-8000); });
    child.stderr.on('data', data => { logs = (logs + data).slice(-8000); });
    children.push(child);
    return child;
  }
  try {
    launch('java', ['-jar', jar, '--spring.profiles.active=test', `--server.port=${appPort}`,
      '--server.address=127.0.0.1', '--spring.datasource.url=jdbc:h2:mem:portal-browser',
      '--spring.jpa.show-sql=false', '--logging.level.root=ERROR']);
    await until(async () => (await fetch(`${base}/actuator/health/readiness`)).ok, 'disposable API startup', 60000);
    async function api(route, body, token) {
      const result = await fetch(`${base}${route}`, { method: 'POST',
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: JSON.stringify(body) });
      assert.ok(result.ok, `${route}: HTTP ${result.status}`);
      return result.json();
    }
    const admin = await api('/auth/login', { email: 'admin@example.com', password: 'admin123' });
    const ana = await api('/auth/login', { email: 'ana@example.com', password: 'ana123' });
    const bruno = await api('/auth/login', { email: 'bruno@example.com', password: 'bruno123' });
    // Register controlled names explicitly; application writes never invent catalog entries.
    for (const [kind, names] of [
      ['beaches', ['Praia API', 'Praia do navegador', 'Praia independente', 'Praia administrativa', 'Paginação administrativa', 'Lugar inexistente']],
      ['materials', ['<img src=x onerror=alert(1)>']]
    ]) for (const name of names) await api(`/catalog/${kind}`, { name }, admin.accessToken);
    for (let i = 0; i < 2; i++) await api('/users', {
      name: `Consulta ${i}`, email: `consulta-${i}@example.com`, password: 'browser-test-password'
    });
    async function request(route, method = 'GET', body, token = admin.accessToken) {
      return fetch(`${base}${route}`, { method,
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
    }
    // Verify server permissions with real JWTs, independently of hidden controls and client guards.
    const eventInput = { title: 'Não permitido', location: 'Praia API', dateTime: '2026-11-01T09:00:00', status: 'Agendado' };
    const collectionInput = { beach: 'Praia API', materialType: 'Vidro', weightKg: 1, registrationDate: '2026-10-15' };
    for (const [route, method, body] of [
      ['/users/search', 'GET'], ['/users', 'GET'], [`/users/${admin.userId}`, 'GET'],
      ['/cleanupEvent', 'POST', eventInput], ['/cleanupEvent/1', 'PUT', eventInput], ['/cleanupEvent/1', 'DELETE'],
      ['/trashCollection/1', 'GET'], ['/trashCollection/1', 'PUT', collectionInput], ['/trashCollection/1', 'DELETE'],
      ['/trashCollection', 'POST', { ...collectionInput, volunteer: { id: bruno.userId } }],
      [`/trashCollection/search?volunteerId=${bruno.userId}`, 'GET']
    ]) assert.equal((await request(route, method, body, ana.accessToken)).status, 403, `${method} ${route} must deny volunteer`);
    for (let i = 1; i <= 7; i++) await api('/cleanupEvent', {
      title: `Mutirão navegador ${i}`, location: 'Praia do navegador', dateTime: `2026-11-${String(i).padStart(2, '0')}T09:00:00`, status: 'Agendado'
    }, admin.accessToken);
    launch(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
      '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank']);
    const debuggerPort = await until(() => {
      const file = path.join(profile, 'DevToolsActivePort');
      return fs.existsSync(file) && Number(fs.readFileSync(file, 'utf8').split('\n')[0]);
    }, 'browser startup');
    const targets = await (await fetch(`http://127.0.0.1:${debuggerPort}/json/list`)).json();
    socket = new WebSocket(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
    await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }); });
    let sequence = 0;
    const pending = new Map();
    let acceptDialog = true;
    socket.addEventListener('message', event => {
      const data = JSON.parse(event.data);
      if (data.method === 'Page.javascriptDialogOpening') void cdp('Page.handleJavaScriptDialog', { accept: acceptDialog });
      if (data.method === 'Runtime.exceptionThrown') browserErrors.push(data.params.exceptionDetails.text);
      if (data.method === 'Log.entryAdded' && data.params.entry.source === 'security') browserErrors.push(data.params.entry.text);
      if (!pending.has(data.id)) return;
      const { resolve, reject, timer } = pending.get(data.id);
      clearTimeout(timer);
      pending.delete(data.id);
      if (data.error) reject(new Error(data.error.message)); else resolve(data.result);
    });
    function cdp(method, params = {}) {
      return new Promise((resolve, reject) => {
        const id = ++sequence;
        const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 15000);
        pending.set(id, { resolve, reject, timer });
        socket.send(JSON.stringify({ id, method, params }));
      });
    }
    async function evaluate(expression) {
      const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
      return result.result.value;
    }
    const click = selector => evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const fill = (selector, value) => evaluate(`(() => {
      const element = document.querySelector(${JSON.stringify(selector)});
      const value = ${JSON.stringify(value)};
      if (element.tagName === 'SELECT') {
        const option = [...element.options].find(option => option.value === value || option.textContent === value);
        if (!option) throw new Error('Missing catalog choice: ' + value);
        element.value = option.value;
      } else element.value = value;
    })()`);
    const waitFor = expression => until(() => evaluate(expression), expression);
    async function reloadPage() {
      await evaluate('window.portalBeforeReload = true');
      await cdp('Page.reload');
      await waitFor('window.portalBeforeReload !== true && document.readyState === "complete"');
    }
    await cdp('Runtime.enable');
    await cdp('Log.enable');
    await cdp('Page.enable');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 1280, height: 960, deviceScaleFactor: 1, mobile: false });
    await cdp('Page.navigate', { url: base });
    await waitFor('document.querySelectorAll(".card").length === 6 && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('#next');
    await waitFor('document.querySelector("#page-info").textContent.includes("2 de 2") && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await fill('#location-filter', 'Lugar inexistente');
    await click('#filters button');
    await waitFor('document.querySelector("#results").textContent.includes("Nenhum evento")');
    await fill('#location-filter', 'Praia do navegador');
    await click('#filters button');
    await waitFor('document.querySelectorAll(".card").length === 6');
    await click('#signup-open');
    await fill('#auth-form [name=name]', 'Voluntário navegador');
    await fill('#auth-form [name=email]', 'portal-browser@example.com');
    await fill('#auth-form [name=password]', 'portal-test-password');
    await click('#auth-submit');
    await waitFor('!document.querySelector("#auth-dialog").open && !document.querySelector("#impact").hidden && document.querySelector("#account-name").textContent === "Voluntário navegador"');
    await waitFor('document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await reloadPage();
    await waitFor('document.querySelector("#account-name").textContent === "Voluntário navegador" && !document.querySelector("#signed-account").hidden && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('.card-actions .primary');
    await waitFor('document.querySelector("#total-events").textContent === "1" && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('[data-view=mine]');
    await waitFor('document.querySelectorAll(".card").length === 1 && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('.card-actions button:last-child');
    await waitFor('document.querySelector("#collection-dialog").open');
    await fill('#collection-form [name=materialType]', 'Vidro');
    await fill('#collection-form [name=weightKg]', '2.5');
    await click('#collection-form [type=submit]');
    await waitFor('!document.querySelector("#collection-dialog").open && document.querySelector("#total-kg").textContent === "2,5" && document.querySelector("#results").textContent.includes("Mutirão navegador")');
    await click('#collection-open');
    await waitFor('document.querySelector("#collection-dialog").open');
    await fill('#collection-form [name=beach]', 'Praia independente');
    await fill('#collection-form [name=materialType]', '<img src=x onerror=alert(1)>');
    await fill('#collection-form [name=weightKg]', '1.25');
    await click('#collection-form [type=submit]');
    await waitFor('document.querySelector("#total-kg").textContent === "3,75" && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    assert.equal(await evaluate('document.querySelectorAll("#results img").length'), 0, 'API text must not be inserted as HTML');
    await click('[data-view=mine]');
    await waitFor('document.querySelectorAll(".card").length === 1 && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('.card-actions button:first-child');
    await waitFor('document.querySelector("#total-events").textContent === "0"');
    await click('[data-view=collections]');
    await waitFor('document.querySelectorAll(".card").length === 2 && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await cdp('Page.captureScreenshot', { format: 'png' }).then(result => fs.writeFileSync(path.join(root, 'target', 'portal-desktop.png'), Buffer.from(result.data, 'base64')));
    await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Mobile layout must not overflow');
    await cdp('Page.captureScreenshot', { format: 'png' }).then(result => fs.writeFileSync(path.join(root, 'target', 'portal-mobile.png'), Buffer.from(result.data, 'base64')));
    await click('#logout');
    await waitFor('document.querySelector("#impact").hidden && !document.querySelector("#account").hidden');
    assert.equal(await evaluate('sessionStorage.getItem("beachcleanup.session.v1")'), null);
    await reloadPage();
    await waitFor('!document.querySelector("#account").hidden && document.querySelectorAll(".card").length > 0');
    await click('#login-open');
    await fill('#auth-form [name=email]', 'ana@example.com');
    await fill('#auth-form [name=password]', 'wrong-password');
    await click('#auth-submit');
    await waitFor('!document.querySelector("#auth-error").hidden && !document.querySelector("#auth-submit").disabled');
    await fill('#auth-form [name=password]', 'ana123');
    await click('#auth-submit');
    await waitFor('document.querySelector("#account-name").textContent === "Ana Silva"');
    await click('[data-view=collections]');
    await waitFor('document.querySelector("#results").textContent.includes("Sua primeira coleta")');
    await reloadPage();
    await waitFor('document.querySelector("#account-name").textContent === "Ana Silva" && !document.querySelector("#signed-account").hidden && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    assert.equal(await evaluate('document.querySelector("#admin-tab").hidden'), true);
    // Reloading must not revive an expired token or silently extend its deadline.
    await evaluate('(() => { const key = "beachcleanup.session.v1"; const saved = JSON.parse(sessionStorage.getItem(key)); saved.expiresAt = Date.now() - 1; sessionStorage.setItem(key, JSON.stringify(saved)); })()');
    await reloadPage();
    await waitFor('document.querySelector("#impact").hidden && !document.querySelector("#account").hidden && document.querySelectorAll(".card").length > 0');
    assert.equal(await evaluate('sessionStorage.getItem("beachcleanup.session.v1")'), null);
    assert.equal(await evaluate('document.querySelector("#admin-tab").hidden'), true);

    // Administrative browser flows use the same login and styles as the volunteer portal.
    await click('#login-open');
    await fill('#auth-form [name=email]', 'admin@example.com');
    await fill('#auth-form [name=password]', 'admin123');
    await click('#auth-submit');
    await waitFor('!document.querySelector("#admin-tab").hidden && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('#admin-tab');
    const adminReady = () => waitFor('!document.querySelector("#admin-workspace").hidden && document.querySelector("#admin-results").getAttribute("aria-busy") === "false"');
    await adminReady();
    await click('#admin-next');
    await waitFor('document.querySelector("#admin-page-info").textContent.includes("2 de 2") && document.querySelector("#admin-results").getAttribute("aria-busy") === "false"');
    await click('#admin-previous');
    await adminReady();
    await fill('#admin-filters [name=location]', 'Praia administrativa');
    await click('#admin-filters [type=submit]');
    await waitFor('document.querySelector("#admin-results").textContent.includes("Nenhum registro")');
    await click('#admin-create');
    await waitFor('document.querySelector("#admin-dialog").open');
    await fill('#admin-form [name=title]', 'Mutirão administrativo');
    await fill('#admin-form [name=location]', 'Praia administrativa');
    await fill('#admin-form [name=dateTime]', '2026-11-15T09:00:00');
    assert.deepEqual(await evaluate('[...document.querySelector("#admin-form [name=status]").options].map(o => o.value)'), ['Agendado']);
    const saveAdmin = () => click('#admin-form [type=submit]');
    const savedAdmin = async () => {
      await waitFor('!document.querySelector("#admin-dialog").open && document.querySelector("#admin-results").getAttribute("aria-busy") === "false"');
    };
    await saveAdmin();
    await savedAdmin();
    assert.ok(await evaluate('document.querySelector("#admin-results").textContent.includes("Mutirão administrativo")'));
    const managedEvent = (await (await request('/cleanupEvent/search?location=Praia%20administrativa')).json()).content[0];
    for (const status of ['Em andamento', 'Pausado']) {
      await click('#admin-results .card-actions button:first-child');
      await waitFor('document.querySelector("#admin-dialog").open');
      await fill('#admin-form [name=status]', status);
      await saveAdmin();
      await savedAdmin();
      assert.ok(await evaluate(`document.querySelector('#admin-results').textContent.includes(${JSON.stringify(status)})`));
    }
    // A separately committed edit must invalidate the open event form.
    await click('#admin-results .card-actions button:first-child');
    await waitFor('document.querySelector("#admin-dialog").open');
    const eventSnapshot = await (await request(`/cleanupEvent/${managedEvent.id}`)).json();
    assert.equal((await request(`/cleanupEvent/${managedEvent.id}`, 'PUT', { ...eventSnapshot, title: 'Saved elsewhere' })).status, 200);
    await fill('#admin-form [name=title]', 'Old form draft');
    await saveAdmin();
    await waitFor('!document.querySelector("#admin-form-error").hidden && !document.querySelector("#admin-form [type=submit]").disabled');
    assert.equal(await evaluate('document.querySelector("#admin-dialog").open'), true);
    assert.equal(await evaluate('document.querySelector("#admin-form [name=title]").value'), 'Old form draft');
    assert.equal((await (await request(`/cleanupEvent/${managedEvent.id}`)).json()).title, 'Saved elsewhere');
    await evaluate(`[...document.querySelectorAll('#admin-form button')].find(button => button.textContent === 'Recarregar registro atual').click()`);
    await waitFor('document.querySelector("#admin-form [name=title]").value === "Saved elsewhere"');
    await fill('#admin-form [name=title]', 'Reviewed event');
    await saveAdmin();
    await savedAdmin();
    await click('[data-admin-view=users]');
    await adminReady();
    assert.equal(await evaluate('document.querySelector("#admin-create").hidden'), true);
    await click('#admin-next');
    await waitFor('document.querySelector("#admin-page-info").textContent.includes("2 de 2") && document.querySelector("#admin-results").getAttribute("aria-busy") === "false"');
    await fill('#admin-filters [name=email]', 'ana@example.com');
    await click('#admin-filters [type=submit]');
    await waitFor('document.querySelectorAll("#admin-results .card").length === 1 && document.querySelector("#admin-results").getAttribute("aria-busy") === "false"');
    assert.ok(await evaluate('document.querySelector("#admin-results").textContent.includes("Ana Silva")'));
    assert.equal(await evaluate('document.querySelectorAll("#admin-results .card-actions").length'), 0);
    await click('[data-admin-view=collections]');
    await adminReady();
    await click('#admin-create');
    await waitFor('document.querySelector("#admin-dialog").open');
    await fill('#admin-form [name=beach]', 'Praia administrativa');
    await fill('#admin-form [name=materialType]', '<img src=x onerror=alert(1)>');
    await fill('#admin-form [name=weightKg]', '0');
    await fill('#admin-form [name=registrationDate]', '2026-10-15');
    await fill('#admin-form [name=volunteerId]', String(ana.userId));
    await fill('#admin-form [name=eventId]', String(managedEvent.id));
    await saveAdmin();
    await waitFor('document.querySelector("#admin-form-error").textContent.includes("maior que zero")');
    await fill('#admin-form [name=weightKg]', '4.5');
    await saveAdmin();
    await waitFor('document.querySelector("#admin-form-error").textContent.includes("não aceita") && !document.querySelector("#admin-form [type=submit]").disabled');
    assert.equal(await evaluate('document.querySelector("#admin-form [name=weightKg]").value'), '4.5', 'Conflict must preserve the form');
    await fill('#admin-form [name=eventId]', '999999999');
    await saveAdmin();
    await waitFor('document.querySelector("#admin-form-error").textContent.includes("999999999") && !document.querySelector("#admin-form [type=submit]").disabled');
    assert.equal((await request(`/cleanupEvent/${managedEvent.id}`, 'PUT', { ...await (await request(`/cleanupEvent/${managedEvent.id}`)).json(), status: 'Em andamento' })).status, 200);
    await fill('#admin-form [name=eventId]', String(managedEvent.id));
    await saveAdmin();
    await savedAdmin();
    await fill('#admin-filters [name=beach]', 'Praia administrativa');
    await click('#admin-filters [type=submit]');
    await waitFor('document.querySelectorAll("#admin-results .card").length === 1 && document.querySelector("#admin-results").getAttribute("aria-busy") === "false"');
    assert.equal(await evaluate('document.querySelectorAll("#admin-results img").length'), 0);
    assert.equal((await (await request(`/users/${ana.userId}`)).json()).totalCollectedKg, 4.5);
    // Attempt to delete a linked event: the API conflict is shown without hiding the record.
    await click('[data-admin-view=events]');
    await adminReady();
    await fill('#admin-filters [name=location]', 'Praia administrativa');
    await click('#admin-filters [type=submit]');
    await adminReady();
    await click('#admin-results .card-actions button:last-child');
    await waitFor('!document.querySelector("#admin-message").hidden && document.querySelector("#admin-message").classList.contains("error")');
    assert.equal((await request(`/cleanupEvent/${managedEvent.id}`)).status, 200);
    await click('[data-admin-view=collections]');
    await adminReady();
    await fill('#admin-filters [name=beach]', 'Praia administrativa');
    await click('#admin-filters [type=submit]');
    await adminReady();
    await click('#admin-results .card-actions button:first-child');
    await waitFor('document.querySelector("#admin-dialog").open');
    // The same protection applies to the collection form and its totals.
    const collectionSnapshot = (await (await request('/trashCollection/search?beach=Praia%20administrativa')).json()).content[0];
    assert.equal((await request(`/trashCollection/${collectionSnapshot.id}`, 'PUT', { ...collectionSnapshot, weightKg: 5 })).status, 200);
    await fill('#admin-form [name=weightKg]', '7');
    await saveAdmin();
    await waitFor('!document.querySelector("#admin-form-error").hidden && !document.querySelector("#admin-form [type=submit]").disabled');
    assert.equal(await evaluate('document.querySelector("#admin-form [name=weightKg]").value'), '7');
    assert.equal((await (await request(`/users/${ana.userId}`)).json()).totalCollectedKg, 5);
    await evaluate(`[...document.querySelectorAll('#admin-form button')].find(button => button.textContent === 'Recarregar registro atual').click()`);
    await waitFor('document.querySelector("#admin-form [name=weightKg]").value === "5"');
    await fill('#admin-form [name=weightKg]', '6');
    await fill('#admin-form [name=volunteerId]', String(bruno.userId));
    await fill('#admin-form [name=eventId]', '');
    await saveAdmin();
    await savedAdmin();
    assert.equal((await (await request(`/users/${ana.userId}`)).json()).totalCollectedKg, 0);
    assert.equal((await (await request(`/users/${bruno.userId}`)).json()).totalCollectedKg, 6);
    acceptDialog = false;
    await click('#admin-results .card-actions button:last-child');
    assert.equal((await (await request('/trashCollection/search?beach=Praia%20administrativa')).json()).totalElements, 1, 'Cancelling deletion preserves collection');
    acceptDialog = true;
    await click('#admin-results .card-actions button:last-child');
    await waitFor('document.querySelector("#admin-results").textContent.includes("Nenhum registro")');
    assert.equal((await (await request(`/users/${bruno.userId}`)).json()).totalCollectedKg, 0);
    await click('[data-admin-view=events]');
    await adminReady();
    await fill('#admin-filters [name=location]', 'Praia administrativa');
    await click('#admin-filters [type=submit]');
    await adminReady();
    await click('#admin-results .card-actions button:last-child');
    await waitFor('document.querySelector("#admin-results").textContent.includes("Nenhum registro")');
    assert.equal((await request(`/cleanupEvent/${managedEvent.id}`)).status, 404);
    // Exercise collection pagination and returning to the previous page after deleting its last item.
    for (let i = 0; i < 7; i++) await api('/trashCollection', { ...collectionInput, beach: 'Paginação administrativa' }, admin.accessToken);
    await click('[data-admin-view=collections]');
    await adminReady();
    await fill('#admin-filters [name=beach]', 'Paginação administrativa');
    await click('#admin-filters [type=submit]');
    await adminReady();
    await click('#admin-next');
    await waitFor('document.querySelector("#admin-page-info").textContent.includes("2 de 2") && document.querySelector("#admin-results").getAttribute("aria-busy") === "false"');
    await click('#admin-results .card-actions button:last-child');
    await waitFor('document.querySelector("#admin-page-info").textContent.includes("1 de 1") && document.querySelector("#admin-results").getAttribute("aria-busy") === "false"');
    await evaluate('document.querySelector("#admin-results").scrollIntoView({block: "start"})');
    await cdp('Page.captureScreenshot', { format: 'png' }).then(result => fs.writeFileSync(path.join(root, 'target', 'admin-mobile.png'), Buffer.from(result.data, 'base64')));
    assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Administrative mobile layout must not overflow');
    await cdp('Emulation.setDeviceMetricsOverride', { width: 1280, height: 960, deviceScaleFactor: 1, mobile: false });
    await evaluate('document.querySelector("#workspace").scrollIntoView({block: "start"})');
    await cdp('Page.captureScreenshot', { format: 'png' }).then(result => fs.writeFileSync(path.join(root, 'target', 'admin-desktop.png'), Buffer.from(result.data, 'base64')));
    // Manage aliases and merge two explicit beach identities through the administrative UI.
    await click('[data-admin-view=beaches]');
    await adminReady();
    await click('#admin-create');
    await waitFor('document.querySelector("#admin-dialog").open');
    await fill('#admin-form [name=name]', 'Praia de catálogo');
    await fill('#admin-form [name=aliases]', 'Apelido de catálogo');
    await saveAdmin();
    await savedAdmin();
    const catalogBeach = (await (await request('/catalog/beaches')).json()).find(item => item.name === 'Praia de catálogo');
    assert.ok(catalogBeach.aliases.includes('apelido de catalogo'));
    const legacyBeach = await api('/catalog/beaches', { name: 'Nome antigo da praia' }, admin.accessToken);
    const managedMaterial = (await (await request('/catalog/materials')).json()).find(item => item.name === 'Vidro');
    const mergedCollection = await api('/trashCollection', { beachId: legacyBeach.id, materialId: managedMaterial.id,
      weightKg: 2, registrationDate: '2026-10-15' }, admin.accessToken);
    await click('[data-admin-view=beaches]');
    await adminReady();
    await evaluate(`[...document.querySelectorAll('#admin-results .card')].find(card => card.querySelector('h3').textContent === 'Nome antigo da praia').querySelector('button:last-child').click()`);
    await waitFor('document.querySelector("#admin-dialog").open');
    await fill('#admin-form [name=targetId]', String(catalogBeach.id));
    await saveAdmin();
    await savedAdmin();
    const afterMerge = await (await request(`/trashCollection/${mergedCollection.id}`)).json();
    assert.equal(afterMerge.beachId, catalogBeach.id);
    assert.equal(afterMerge.beach, 'Praia de catálogo');
    assert.equal(afterMerge.weightKg, 2);
    assert.equal((await (await request('/catalog/beaches')).json()).some(item => item.id === legacyBeach.id), false);
    await click('[data-admin-view=materials]');
    await adminReady();
    await evaluate(`[...document.querySelectorAll('#admin-results .card')].find(card => card.querySelector('h3').textContent === 'Vidro').querySelector('button').click()`);
    await waitFor('document.querySelector("#admin-dialog").open');
    await fill('#admin-form [name=aliases]', 'Vidros');
    await saveAdmin();
    await savedAdmin();
    assert.ok((await (await request('/catalog/materials')).json()).find(item => item.name === 'Vidro').aliases.includes('vidros'));
    await click('#logout');
    await waitFor('document.querySelector("#admin-workspace").hidden && document.querySelector("#admin-results").textContent === ""');
    await click('#login-open');
    await fill('#auth-form [name=email]', 'admin@example.com');
    await fill('#auth-form [name=password]', 'admin123');
    await click('#auth-submit');
    await waitFor('!document.querySelector("#admin-tab").hidden && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('#admin-tab');
    await adminReady();
    assert.equal((await request('/auth/password', 'PUT', { currentPassword: 'admin123', newPassword: 'admin-browser-new-password' })).status, 204);
    await click('[data-admin-view=users]');
    await waitFor('document.querySelector("#auth-dialog").open && document.querySelector("#admin-workspace").hidden && document.querySelector("#admin-results").textContent === ""');
    await fill('#auth-form [name=email]', 'ana@example.com');
    await fill('#auth-form [name=password]', 'ana123');
    await click('#auth-submit');
    await waitFor('document.querySelector("#account-name").textContent === "Ana Silva"');
    assert.equal(await evaluate('document.querySelector("#admin-tab").hidden'), true);
    await evaluate('document.querySelector("#admin-tab").hidden = false');
    await click('#admin-tab');
    assert.equal(await evaluate('document.querySelector("#admin-workspace").hidden'), true, 'Revealing a control must not grant administrative access');
    assert.deepEqual(browserErrors, [], 'No script exceptions or CSP violations');
    console.log('PASS: volunteer regression flows; admin event CRUD/status, user searches, collection CRUD/transfer/unlink, all paginations, last-page deletion, validation/404/409, confirmation, totals, desktop/mobile, CSP, logout clearing, volunteer UI denial and direct API denial with real JWTs.');
  } catch (error) {
    console.error(logs.slice(-3000));
    throw error;
  } finally {
    if (socket) socket.close();
    for (const child of children.reverse()) {
      if (child.exitCode !== null) continue;
      child.kill();
      await Promise.race([new Promise(resolve => child.once('exit', resolve)), pause(5000)]);
    }
    // Only remove the exact temporary browser profile created by this invocation.
    const resolved = path.resolve(profile);
    assert.equal(path.dirname(resolved), temporaryRoot);
    assert.ok(path.basename(resolved).startsWith('beachcleanup-portal-'));
    fs.rmSync(resolved, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });

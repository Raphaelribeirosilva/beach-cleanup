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
    socket.addEventListener('message', event => {
      const data = JSON.parse(event.data);
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
    const fill = (selector, value) => evaluate(`document.querySelector(${JSON.stringify(selector)}).value = ${JSON.stringify(value)}`);
    const waitFor = expression => until(() => evaluate(expression), expression);
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
    await cdp('Page.reload');
    await waitFor('document.querySelector("#impact").hidden && !document.querySelector("#account").hidden && document.querySelectorAll(".card").length > 0');
    assert.deepEqual(browserErrors, [], 'No script exceptions or CSP violations');
    console.log('PASS: public search/pagination, signup/login, registration/cancellation, linked/independent collections, totals, text escaping, account isolation, reload/logout, desktop/mobile layout and CSP.');
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

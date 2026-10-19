/* End-to-end checks against an owned, disposable H2 application. No external packages. */
const { spawn, spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const net = require('node:net');
const assert = require('node:assert/strict');
const { audit } = require('./portal-accessibility.cjs');

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
  const browserPath = process.env.PORTAL_BROWSER || [
    'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
    '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser'
  ].find(candidate => fs.existsSync(candidate));
  assert.ok(browserPath && fs.existsSync(browserPath), 'Set PORTAL_BROWSER to a Chromium/Edge executable.');
  const jar = path.join(root, 'target', 'beachcleanup-0.0.1-SNAPSHOT.jar');
  assert.ok(fs.existsSync(jar), 'Run Maven package before browser verification.');
  const temporaryRoot = fs.realpathSync(os.tmpdir());
  const profile = fs.mkdtempSync(path.join(temporaryRoot, 'beachcleanup-portal-'));
  const appPort = await freePort();
  const base = `http://127.0.0.1:${appPort}`;
  fs.rmSync(path.join(root,'target','portal-browser-failure.log'),{force:true});
  const children = [];
  let socket;
  let closeBrowser;
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
    const legacyData = path.join(profile, 'legacy-data.sql');
    fs.writeFileSync(legacyData, `
      insert into tb_trash_collection(id,beach,material_type,weight_kg,registration_date,row_version)
      values (9001,null,null,null,'2020-01-01',0);
      insert into tb_weight_conversion_audit(collection_id) values (9001);
    `);
    launch('java', ['-jar', jar, '--spring.profiles.active=test', `--server.port=${appPort}`,
      '--server.address=127.0.0.1', '--spring.datasource.url=jdbc:h2:mem:portal-browser',
      `--spring.sql.init.data-locations=${require('node:url').pathToFileURL(legacyData).href}`,
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
    ]) for (const name of names) await api(`/catalog/${kind}`, { name, ...(kind === 'beaches' ? { state: 'SP', city: 'Santos' } : {}) }, admin.accessToken);
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
      title: `Mutirão navegador ${i}`, location: 'Praia do navegador', dateTime: '2026-10-15T09:00:00', status: 'Agendado'
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
    closeBrowser = () => cdp('Browser.close');
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
        element.dispatchEvent(new Event('change', { bubbles: true }));
      } else element.value = value;
    })()`);
    const waitFor = expression => until(() => evaluate(expression), expression);
    const accessibility = [];
    const checkAccessibility = async stage => {
      accessibility.push(await audit(evaluate,stage));
      fs.writeFileSync(path.join(root,'target','portal-browser-accessibility.json'),JSON.stringify(accessibility,null,2));
      assert.deepEqual(accessibility.at(-1).failures,[],`Accessibility (${stage})`);
    };
    async function key(key, code, modifiers = 0) {
      const virtualKey = { Tab:9, Enter:13, Escape:27 }[key];
      await cdp('Input.dispatchKeyEvent',{ type:'keyDown',key,code,modifiers,windowsVirtualKeyCode:virtualKey, ...(key === 'Enter' ? { text:'\r', unmodifiedText:'\r' } : {}) });
      await cdp('Input.dispatchKeyEvent',{ type:'keyUp',key,code,modifiers,windowsVirtualKeyCode:virtualKey });
    }
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
    await checkAccessibility('public-desktop');
    await key('Tab','Tab');
    assert.equal(await evaluate('document.activeElement.className'),'skip','First keyboard stop is the skip link');
    await key('Enter','Enter');
    assert.equal(await evaluate('document.activeElement.id'),'main','Skip link focuses main content');
    await evaluate('document.querySelector("#login-open").focus()');
    await key('Enter','Enter');
    await waitFor('document.querySelector("#auth-dialog").open');
    assert.equal(await evaluate('document.activeElement.name'),'email');
    await checkAccessibility('login-dialog');
    await fill('#auth-password', 'Senha visível de teste');
    await evaluate('document.querySelector("#auth-password-toggle").focus()');
    await key(' ','Space');
    assert.equal(await evaluate('document.querySelector("#auth-password").type'),'text');
    assert.equal(await evaluate('document.querySelector("#auth-password-toggle").checked'),true);
    assert.equal(await evaluate('document.querySelector("#auth-password").value'),'Senha visível de teste');
    await key(' ','Space');
    assert.equal(await evaluate('document.querySelector("#auth-password").type'),'password');
    assert.equal(await evaluate('document.querySelector("#password-help")'),null,'No password requirements below the checkbox');
    assert.ok(await evaluate('document.querySelector("#recovery-open").getBoundingClientRect().top > document.querySelector("#auth-switch").getBoundingClientRect().bottom'),'Account links have visible spacing');
    await fill('#auth-password', '');
    for(let i=0;i<12;i++) {
      await key('Tab','Tab');
      assert.ok(await evaluate('document.activeElement === document.body || document.activeElement.closest("#auth-dialog") !== null'),'Tab remains within modal');
    }
    await key('Tab','Tab',8);
    assert.ok(await evaluate('document.activeElement === document.body || document.activeElement.closest("#auth-dialog") !== null'),'Shift+Tab remains within modal');
    await evaluate('document.querySelector("#auth-form [name=email]").focus()');
    await cdp('Input.insertText',{text:'invalid-email'});
    await key('Enter','Enter');
    assert.ok(await evaluate('document.activeElement.name === "email" && !document.activeElement.checkValidity()'),'Native validation focuses invalid email');
    await evaluate('document.querySelector("#auth-form [name=email]").value = ""');
    await cdp('Input.insertText',{text:'unknown-keyboard@example.com'});
    await key('Tab','Tab');
    assert.equal(await evaluate('document.activeElement.name'),'password');
    await cdp('Input.insertText',{text:'incorrect-password'});
    await key('Enter','Enter');
    await waitFor('!document.querySelector("#auth-error").hidden && !document.querySelector("#auth-submit").disabled');
    assert.equal(await evaluate('document.activeElement.id'),'auth-email','Invalid credentials focus the first affected field');
    assert.ok(await evaluate('["email","password"].every(name => document.querySelector(`#auth-${name}`).getAttribute("aria-invalid") === "true" && !document.querySelector(`#auth-${name}-error`).hidden)'));
    await checkAccessibility('login-error');
    await click('#auth-password-toggle');
    await key('Escape','Escape');
    await waitFor('!document.querySelector("#auth-dialog").open && document.querySelector("#auth-password").type === "password"');
    assert.equal(await evaluate('document.activeElement.id'),'login-open','Escape restores the opener');
    assert.ok(await evaluate('parseFloat(getComputedStyle(document.activeElement).outlineWidth) >= 2'),'Keyboard focus is visible');
    assert.equal(await evaluate('document.querySelector("#auth-password").type'),'password','Closing always conceals the password');
    await click('#signup-open');
    assert.equal(await evaluate('document.querySelector("#auth-password").minLength'),7);
    await click('#auth-submit');
    assert.equal(await evaluate('document.activeElement.id'),'auth-name');
    assert.ok(await evaluate('["name","email","password"].every(name => document.querySelector(`#auth-${name}`).getAttribute("aria-invalid") === "true")'));
    await checkAccessibility('signup-required-errors');
    await fill('#auth-name','Cadastro navegador');
    await fill('#auth-email','cadastro-navegador@example.com');
    await fill('#auth-password','Mar7!a');
    await click('#auth-submit');
    assert.ok(await evaluate('document.querySelector("#auth-password-error").textContent.includes("7 caracteres")'));
    await fill('#auth-password','passwordpassword');
    await click('#auth-submit');
    await waitFor('document.querySelector("#auth-password-error").textContent.includes("evite senhas comuns") && !document.querySelector("#auth-submit").disabled');
    assert.equal(await evaluate('document.activeElement.id'),'auth-password','Server password policy identifies its field');
    await fill('#auth-password','🌊'.repeat(19));
    await click('#auth-submit');
    assert.ok(await evaluate('document.querySelector("#auth-password-error").textContent.includes("72 bytes")'));
    await fill('#auth-password','Praia7!');
    await fill('#auth-email','ana@example.com');
    await click('#auth-submit');
    await waitFor('document.querySelector("#auth-email").getAttribute("aria-invalid") === "true" && !document.querySelector("#auth-submit").disabled');
    assert.equal(await evaluate('document.activeElement.id'),'auth-email');
    assert.equal(await evaluate('document.querySelector("#auth-password").getAttribute("aria-invalid")'),null);
    assert.equal(await evaluate('document.querySelector("#auth-name").value'),'Cadastro navegador','Server errors preserve input');
    await checkAccessibility('signup-email-conflict');
    await evaluate('document.querySelector("#auth-email").select()');
    await cdp('Input.insertText',{text:'corrigido@example.com'});
    assert.equal(await evaluate('document.querySelector("#auth-email").getAttribute("aria-invalid")'),null,'Editing clears the field error');
    await click('#auth-password-toggle');
    await click('#auth-switch');
    assert.equal(await evaluate('document.querySelector("#auth-password").type'),'password');
    assert.ok(await evaluate('[...document.querySelectorAll("#auth-form [aria-invalid]")].length === 0'));
    await key('Escape','Escape');
    for(const width of [320,768,1280]) {
      await cdp('Emulation.setDeviceMetricsOverride',{width,height:960,deviceScaleFactor:1,mobile:width<580});
      assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'),`No horizontal page overflow at ${width}px`);
    }
    await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:960,deviceScaleFactor:1,mobile:false});
    assert.equal(await evaluate('document.title'), 'Elo do mar · Cada coleta conta');
    await fill('#location-filters [name=state]', 'SP');
    await fill('#location-filters [name=city]', 'Santos');
    await click('#filters button');
    await waitFor('document.querySelector("#results").getAttribute("aria-busy") === "false"');
    assert.equal(await evaluate('document.querySelector("#location-filters [name=city]").value'), 'Santos');
    assert.equal(await evaluate('document.querySelectorAll("#results .card").length'), 6);
    await fill('#location-filter', 'Praia do navegador');
    await fill('#location-filters [name=state]', 'RJ');
    assert.equal(await evaluate('document.querySelector("#location-filter").value'), '', 'Changing state clears the beach');
    assert.equal(await evaluate('document.querySelector("#location-filters [name=city]").value'), '', 'Changing state clears the city');
    assert.ok(await evaluate('![...document.querySelector("#location-filter").options].some(option => option.textContent === "Praia do navegador")'));
    await fill('#location-filters [name=city]', 'Rio de Janeiro');
    await click('#filters button');
    await waitFor('document.querySelector("#results").textContent.includes("Nenhum evento")');
    await fill('#location-filters [name=state]', '');
    await click('#filters button');
    await waitFor('document.querySelectorAll("#results .card").length === 6 && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('#next');
    await waitFor('document.querySelector("#page-info").textContent.includes("2 de 2") && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await fill('#location-filter', 'Lugar inexistente');
    await click('#filters button');
    await waitFor('document.querySelector("#results").textContent.includes("Nenhum evento")');
    await fill('#location-filter', 'Praia do navegador');
    await click('#filters button');
    await waitFor('document.querySelectorAll(".card").length === 6');
    await click('[data-view=reports]');
    await waitFor('document.querySelector("#report-results table") !== null');
    assert.equal(await evaluate('document.querySelector("#auth-dialog").open'), false, 'Impact reports are public');
    assert.equal(await evaluate('document.querySelector("#report-results").textContent.includes("acesso administrativo")'), false);
    await click('[data-view=events]');
    await waitFor('document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('#login-open');
    await click('#recovery-open');
    await waitFor('document.querySelector("#account-help-dialog").open');
    await fill('#account-help-form [name=email]', 'not-registered@example.com');
    await click('#account-help-submit');
    await waitFor('!document.querySelector("#account-help-dialog").open && document.querySelector("#notice").textContent.includes("Se o endereço estiver apto")');
    await cdp('Page.navigate', { url: `${base}/#verify=${'x'.repeat(43)}` });
    await waitFor('document.querySelector("#account-help-dialog")?.open && location.hash === ""');
    assert.equal(await evaluate('document.querySelector("#account-help-error").hidden'), true, 'Opening a verification link must not consume it');
    await click('#account-help-submit');
    await waitFor('!document.querySelector("#account-help-error").hidden');
    assert.ok(await evaluate('document.querySelector("#account-help-error").textContent.includes("Link inválido")'));
    await click('[data-close=account-help-dialog]');
    await click('#signup-open');
    await fill('#auth-form [name=name]', 'Voluntário navegador');
    await fill('#auth-form [name=email]', 'portal-browser@example.com');
    await fill('#auth-form [name=password]', 'portal-test-password');
    await click('#auth-submit');
    await waitFor('document.querySelector("#auth-status").textContent.includes("Conta criada") && !document.querySelector("#auth-submit").disabled');
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
    await cdp('Emulation.setDeviceMetricsOverride',{width:320,height:844,deviceScaleFactor:1,mobile:true});
    assert.ok(await evaluate('document.querySelector("#collection-dialog").scrollWidth <= document.querySelector("#collection-dialog").clientWidth'),'Collection form fits a 320px viewport');
    await checkAccessibility('collection-dialog-small');
    await cdp('Emulation.setDeviceMetricsOverride',{width:1280,height:960,deviceScaleFactor:1,mobile:false});
    assert.equal(await evaluate('document.querySelector("#collection-form [name=beach]").disabled'), true);
    assert.equal(await evaluate('document.querySelector("#collection-form [name=registrationDate]").readOnly'), true);
    assert.equal(await evaluate('document.querySelector("#collection-form [name=registrationDate]").value'), '2026-10-15');
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
    const pagingSession = await evaluate('JSON.parse(sessionStorage.getItem("beachcleanup.session.v2")).session');
    const sevenEvents = (await (await request('/cleanupEvent/search?size=100&location=Praia%20do%20navegador')).json()).content;
    assert.equal(sevenEvents.length,7);
    for(const event of sevenEvents) assert.equal((await request(`/users/me/events/${event.id}`,'PUT',undefined,pagingSession.accessToken)).status,204);
    await click('[data-view=mine]');
    await waitFor('document.querySelector("#total-events").textContent === "7" && document.querySelector("#page-info").textContent.includes("1 de 2") && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    assert.equal(await evaluate('document.querySelectorAll("#results .card").length'),6);
    await click('#next');
    await waitFor('document.querySelector("#page-info").textContent.includes("2 de 2") && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    assert.equal(await evaluate('document.querySelectorAll("#results .card").length'),1);
    await click('#results .card-actions button:first-child');
    await waitFor('document.querySelector("#total-events").textContent === "6" && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    assert.equal(await evaluate('document.querySelectorAll("#results .card").length'),6,'Canceling final record returns to previous page');
    for(const event of sevenEvents) await request(`/users/me/events/${event.id}`,'DELETE',undefined,pagingSession.accessToken);

    await click('[data-view=collections]');
    await waitFor('document.querySelectorAll(".card").length === 2 && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await cdp('Page.captureScreenshot', { format: 'png' }).then(result => fs.writeFileSync(path.join(root, 'target', 'portal-desktop.png'), Buffer.from(result.data, 'base64')));
    await cdp('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 1, mobile: true });
    assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Mobile layout must not overflow');
    await cdp('Page.captureScreenshot', { format: 'png' }).then(result => fs.writeFileSync(path.join(root, 'target', 'portal-mobile.png'), Buffer.from(result.data, 'base64')));
    // Personal collection edits preserve drafts on stale versions and require confirmed deletions.
    const ownSession = await evaluate('JSON.parse(sessionStorage.getItem("beachcleanup.session.v2")).session');
    const ownCollections = (await (await request(`/trashCollection/search?volunteerId=${ownSession.userId}`, 'GET', undefined, ownSession.accessToken)).json()).content;
    const independent = ownCollections.find(item => !item.event);
    await click('#results .card-actions button:first-child');
    await waitFor('document.querySelector("#collection-dialog").open');
    const savedElsewhere = await request(`/trashCollection/${independent.id}`, 'PUT', { ...independent, weightKg: 1.5 }, ownSession.accessToken);
    assert.equal(savedElsewhere.status, 200);
    await fill('#collection-form [name=weightKg]', '1.75');
    await click('#collection-form [type=submit]');
    await waitFor('!document.querySelector("#collection-error").hidden && !document.querySelector("#collection-form [type=submit]").disabled');
    assert.equal(await evaluate('document.querySelector("#collection-form [name=weightKg]").value'), '1.75');
    assert.equal((await (await request(`/trashCollection/${independent.id}`, 'GET', undefined, ownSession.accessToken)).json()).weightKg, 1.5);
    await click('[data-close=collection-dialog]');
    await click('#results .card-actions button:first-child');
    await waitFor('document.querySelector("#collection-dialog").open');
    assert.equal(await evaluate('document.querySelector("#collection-form [name=weightKg]").value'), '1.5', 'Reopening edit fetches the current record');
    await fill('#collection-form [name=weightKg]', '1.75');
    await click('#collection-form [type=submit]');
    await waitFor('!document.querySelector("#collection-dialog").open && document.querySelector("#total-kg").textContent === "4,25" && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    acceptDialog = false;
    await click('#results .card-actions button:last-child');
    await pause(200);
    assert.equal((await request(`/trashCollection/${independent.id}`, 'GET', undefined, ownSession.accessToken)).status, 200);
    acceptDialog = true;
    await click('#results .card-actions button:last-child');
    await waitFor('document.querySelector("#total-kg").textContent === "2,5" && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('[data-view=profile]');
    await waitFor('document.querySelector("#profile-form [name=name]").value === "Voluntário navegador"');
    let ownProfile = await (await request(`/users/${ownSession.userId}`)).json();
    assert.equal((await request(`/users/${ownSession.userId}`, 'PUT', { ...ownProfile, name: 'Nome externo' })).status, 200);
    await fill('#profile-form [name=name]', 'Meu rascunho');
    await click('#profile-form [type=submit]');
    await waitFor('document.querySelector("#notice").textContent.includes("perfil mudou") && !document.querySelector("#profile-form [type=submit]").disabled');
    assert.equal(await evaluate('document.querySelector("#profile-form [name=name]").value'), 'Meu rascunho');
    await click('[data-view=profile]');
    await waitFor('document.querySelector("#profile-form [name=name]").value === "Nome externo"');
    await fill('#profile-form [name=name]', 'Perfil atualizado');
    await fill('#profile-form [name=email]', 'portal-updated@example.com');
    await fill('#profile-form [name=currentPassword]', 'portal-test-password');
    await click('#profile-form [type=submit]');
    await waitFor('document.querySelector("#auth-dialog").open && document.querySelector("#signed-account").hidden');
    await fill('#auth-form [name=email]', 'portal-updated@example.com');
    await fill('#auth-form [name=password]', 'portal-test-password');
    await click('#auth-submit');
    await waitFor('document.querySelector("#account-name").textContent === "Perfil atualizado"');
    await click('[data-view=reports]');
    await waitFor('document.querySelector("#report-results table") !== null');
    await fill('#report-filters [name=beachId]', 'Praia do navegador');
    await fill('#report-filters [name=from]', '2026-10-15');
    await fill('#report-filters [name=to]', '2026-10-15');
    await click('#report-filters [type=submit]');
    await waitFor('document.querySelector("#report-results h3")?.textContent === "2,5 kg coletados"');
    assert.equal(await evaluate('document.querySelectorAll("#report-results meter").length'), 1);
    assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Mobile report must not overflow the page');
    await checkAccessibility('impact-mobile');
    await cdp('Page.captureScreenshot', { format: 'png' }).then(result => fs.writeFileSync(path.join(root, 'target', 'impact-mobile.png'), Buffer.from(result.data, 'base64')));
    await click('[data-view=profile]');
    await waitFor('document.querySelector("#profile-form [name=name]").value === "Perfil atualizado"');
    await checkAccessibility('profile-mobile');
    await fill('#password-form [name=currentPassword]', 'portal-test-password');
    await fill('#password-form [name=newPassword]', 'portal-new-password');
    await fill('#password-form [name=confirmation]', 'does-not-match');
    await click('#password-form [type=submit]');
    await waitFor('document.querySelector("#notice").textContent.includes("confirmação")');
    await fill('#password-form [name=confirmation]', 'portal-new-password');
    await click('#password-form [type=submit]');
    await waitFor('document.querySelector("#auth-dialog").open && document.querySelector("#profile-workspace").hidden');
    assert.equal((await request(`/users/${ownSession.userId}`, 'GET', undefined, ownSession.accessToken)).status, 401, 'Password change invalidates the old JWT');
    await fill('#auth-form [name=email]', 'portal-updated@example.com');
    await fill('#auth-form [name=password]', 'portal-new-password');
    await click('#auth-submit');
    await waitFor('!document.querySelector("#auth-dialog").open && document.querySelector("#account-name").textContent === "Perfil atualizado"');
    await click('#logout');
    await waitFor('document.querySelector("#impact").hidden && !document.querySelector("#account").hidden');
    assert.equal(await evaluate('sessionStorage.getItem("beachcleanup.session.v2")'), null);
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
    // Expired access renews without login, but the absolute refresh deadline remains fixed.
    const beforeRenewal = await evaluate('JSON.parse(sessionStorage.getItem("beachcleanup.session.v2"))');
    await evaluate('(() => { const key = "beachcleanup.session.v2"; const saved = JSON.parse(sessionStorage.getItem(key)); saved.expiresAt = Date.now() - 1; sessionStorage.setItem(key, JSON.stringify(saved)); })()');
    await reloadPage();
    await waitFor('document.querySelector("#account-name").textContent === "Ana Silva" && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    const afterRenewal = await evaluate('JSON.parse(sessionStorage.getItem("beachcleanup.session.v2"))');
    assert.notEqual(afterRenewal.session.accessToken, beforeRenewal.session.accessToken);
    assert.notEqual(afterRenewal.session.refreshToken, beforeRenewal.session.refreshToken);
    assert.ok(afterRenewal.refreshExpiresAt <= beforeRenewal.refreshExpiresAt);
    assert.equal(await evaluate('document.cookie'), '', 'Session credentials do not use cookies');
    await cdp('Network.enable');
    await cdp('Network.setBlockedURLs', { urls: [`${base}/auth/logout`] });
    await click('#logout');
    await waitFor('document.querySelector("#notice button") !== null && document.querySelector("#notice").textContent.includes("não foi possível encerrar no servidor")');
    assert.equal(await evaluate('sessionStorage.getItem("beachcleanup.session.v2")'), null);
    assert.equal((await request(`/users/${afterRenewal.session.userId}`, 'GET', undefined, afterRenewal.session.accessToken)).status, 200, 'A failed logout must not claim server revocation');
    await cdp('Network.setBlockedURLs', { urls: [] });
    await click('#notice button');
    await waitFor('!document.querySelector("#account").hidden && document.querySelector("#notice").textContent.includes("Sessão encerrada no servidor") && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    assert.equal((await request(`/users/${afterRenewal.session.userId}`, 'GET', undefined, afterRenewal.session.accessToken)).status, 401, 'Logout revokes issued access tokens');
    assert.equal((await request('/auth/refresh', 'POST', { refreshToken: afterRenewal.session.refreshToken }, '')).status, 401);
    // Simulate a fully expired saved session: reload cannot revive it.
    await evaluate(`sessionStorage.setItem('beachcleanup.session.v2', ${JSON.stringify(JSON.stringify({ ...afterRenewal, expiresAt: 1, refreshExpiresAt: 1 }))})`);
    await reloadPage();
    await waitFor('document.querySelector("#impact").hidden && !document.querySelector("#account").hidden && document.querySelectorAll(".card").length > 0');
    assert.equal(await evaluate('sessionStorage.getItem("beachcleanup.session.v2")'), null);
    assert.equal(await evaluate('document.querySelector("#admin-tab").hidden'), true);

    // Administrative browser flows use the same login and styles as the volunteer portal.
    await click('#login-open');
    await fill('#auth-form [name=email]', 'admin@example.com');
    await fill('#auth-form [name=password]', 'admin123');
    await click('#auth-submit');
    await waitFor('!document.querySelector("#admin-tab").hidden && document.querySelector("#results").getAttribute("aria-busy") === "false"');
    await click('[data-view=reports]');
    await waitFor('document.querySelector("#report-results").textContent.includes("Inscrições por evento")');
    await fill('#report-filters [name=eventId]', '1');
    await click('#report-filters [type=submit]');
    await waitFor('document.querySelector("#report-results").textContent.includes("Inscrições por evento")');
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
    await fill('#admin-form [name=state]', 'SP');
    await fill('#admin-form [name=city]', 'Santos');
    await fill('#admin-form [name=location]', 'Praia administrativa');
    await fill('#admin-form [name=dateTime]', '2026-10-15T09:00:00');
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
    assert.equal(await evaluate('document.querySelector("#admin-form [name=weightKg]").checkValidity()'), false);
    await fill('#admin-form [name=weightKg]', '1.2345');
    assert.equal(await evaluate('document.querySelector("#admin-form [name=weightKg]").checkValidity()'), false);
    await fill('#admin-form [name=weightKg]', '100000.001');
    assert.equal(await evaluate('document.querySelector("#admin-form [name=weightKg]").checkValidity()'), false);
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
    await waitFor('!document.querySelector("#admin-message").hidden && document.querySelector("#admin-message").classList.contains("error")');
    assert.equal((await request(`/cleanupEvent/${managedEvent.id}`)).status, 200, 'Historical event remains after collection removal');
    await click('[data-admin-view=audit]');
    await adminReady();
    assert.equal(await evaluate('document.querySelector("#admin-create").hidden'), true);
    await fill('#admin-filters [name=resource]', 'COLLECTION');
    await fill('#admin-filters [name=resourceId]', String(collectionSnapshot.id));
    await fill('#admin-filters [name=action]', 'TRANSFER');
    await click('#admin-filters [type=submit]');
    await adminReady();
    assert.equal(await evaluate('document.querySelectorAll("#admin-results .card").length'), 1);
    assert.ok(await evaluate('document.querySelector("#admin-results").textContent.includes("Transferência")'));
    assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), 'Mobile audit must not overflow');
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
    await checkAccessibility('admin-mobile');
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
    await fill('#admin-form [name=state]', 'SP');
    await fill('#admin-form [name=city]', 'Santos');
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
    await click('[data-admin-view=quality]');
    await adminReady();
    assert.equal(await evaluate('document.querySelector("#admin-create").hidden'), true);
    assert.equal((await (await request('/admin/data-quality')).json()).totalElements, 3);
    await click('#admin-results button');
    await waitFor('document.querySelector("#admin-dialog").open');
    assert.equal(await evaluate('document.querySelector("#admin-form [name=weightKg]").value'), '');
    await fill('#admin-form [name=beach]', 'Praia de catálogo');
    await fill('#admin-form [name=materialType]', 'Vidro');
    await fill('#admin-form [name=weightKg]', '2.500');
    await saveAdmin();
    await savedAdmin();
    assert.equal(await evaluate('document.querySelector("#admin-results").textContent.includes("Nenhum registro para revisar")'), true);
    const repairedLegacy = await (await request('/trashCollection/9001')).json();
    assert.equal(repairedLegacy.id, 9001);
    assert.equal(repairedLegacy.beachId, catalogBeach.id);
    assert.equal(repairedLegacy.weightKg, 2.5);
    assert.equal(repairedLegacy.volunteer, null);
    await click('[data-admin-view=rounding]');
    await adminReady();
    assert.equal(await evaluate('document.querySelector("#admin-results").textContent.includes("Peso anterior: não informado")'), true);
    assert.equal((await (await request('/admin/data-quality/rounding')).json()).content[0].roundedWeight, null);
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
    const metrics = await evaluate(`({ navigation: performance.getEntriesByType('navigation').map(entry => ({ domContentLoadedMs:entry.domContentLoadedEventEnd, loadMs:entry.loadEventEnd })),
      resources: performance.getEntriesByType('resource').map(entry => ({ path:new URL(entry.name).pathname, durationMs:entry.duration, bytes:entry.transferSize })) })`);
    fs.writeFileSync(path.join(root,'target','portal-browser-performance.json'),JSON.stringify(metrics,null,2));
    console.log('PASS: recovery/verification forms, private link fragments and password policy; silent renewal, refresh rotation/deadline, server logout and revoked JWTs; public impact dashboard and admin registrations; personal profile/password, collection edits/deletions, stale drafts, totals and permissions; administrative audit, keyboard/focus, contrast, desktop/mobile and CSP.');
  } catch (error) {
    fs.writeFileSync(path.join(root, 'target', 'portal-browser-failure.log'), logs);
    console.error(logs.slice(-3000));
    throw error;
  } finally {
    // Gracefully close this CDP browser, including its renderer/GPU children, before deleting its profile.
    if (socket?.readyState === WebSocket.OPEN && closeBrowser) await closeBrowser().catch(() => {});
    if (socket) socket.close();
    for (const child of children.reverse()) {
      if (child.exitCode !== null) continue;
      if (process.platform === 'win32') {
        spawnSync('taskkill.exe', ['/PID', String(child.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
      } else child.kill();
      await Promise.race([new Promise(resolve => child.once('exit', resolve)), pause(5000)]);
    }
    // Only remove the exact temporary browser profile created by this invocation.
    const resolved = path.resolve(profile);
    assert.equal(path.dirname(resolved), temporaryRoot);
    assert.ok(path.basename(resolved).startsWith('beachcleanup-portal-'));
    fs.rmSync(resolved, { recursive: true, force: true, maxRetries: 15, retryDelay: 200 });
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });

/* global createPortalApi, createAdminPortal */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const api = createPortalApi(window.fetch.bind(window), () => {
    resetAccount();
    if ($('collection-dialog').open) $('collection-dialog').close();
    notify('Sua sessão expirou. Entre novamente para continuar.', true);
    openAuth('login');
  });
  const state = { view: 'events', page: 0, pages: 0, generation: 0, signup: false,
    profile: null, registrations: [], selectedEvent: null, loading: false };
  const kg = value => new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 }).format(value);
  const date = value => {
    if (!value) return 'Data não informada';
    // API event times represent local wall time; date-only values must not shift to the previous day.
    const parsed = new Date(value.includes('T') ? value : `${value}T12:00:00`);
    return Number.isNaN(parsed.getTime()) ? 'Data não informada' : new Intl.DateTimeFormat('pt-BR',
      value.includes('T') ? { dateStyle: 'medium', timeStyle: 'short' } : { dateStyle: 'medium' }).format(parsed);
  };
  const activeEvent = event => ['Agendado', 'Em andamento'].includes(event.status);
  const admin = createAdminPortal(api, { node, action, busy, kg, date });
  function node(tag, className, content) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (content !== undefined) element.textContent = content;
    return element;
  }
  function notify(message, error = false) {
    $('notice').textContent = message;
    $('notice').className = error ? 'notice error' : 'notice';
    $('notice').hidden = !message;
  }
  function errorAt(id, message = '') {
    $(id).textContent = message;
    $(id).hidden = !message;
  }
  async function busy(button, operation) {
    if (button.disabled) return;
    button.disabled = true;
    const previous = button.textContent;
    button.textContent = 'Aguarde…';
    try { await operation(); }
    finally { button.disabled = false; if (button.textContent === 'Aguarde…') button.textContent = previous; }
  }
  function syncAccount() {
    const signed = Boolean(api.session());
    $('account').hidden = signed;
    $('signed-account').hidden = !signed;
    $('impact').hidden = !signed || state.view === 'admin';
    $('admin-tab').hidden = api.session()?.role !== 'ADMIN';
    $('account-name').textContent = state.profile?.name || 'Minha conta';
    $('total-kg').textContent = state.profile ? kg(state.profile.totalCollectedKg) : '—';
    $('total-events').textContent = state.registrations.length;
  }
  function resetAccount() {
    admin.reset();
    state.generation++;
    state.profile = null;
    state.registrations = [];
    state.view = 'events';
    state.page = 0;
    $('results').replaceChildren();
    $('pagination').hidden = true;
    syncAccount();
    syncView();
    // Load public data again after invalidating any in-flight private request.
    void load();
  }
  function syncView() {
    document.querySelectorAll('[data-view]').forEach(button => {
      const active = button.dataset.view === state.view;
      button.classList.toggle('active', active);
      if (active) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    const events = state.view === 'events';
    const administrative = state.view === 'admin' && api.session()?.role === 'ADMIN';
    document.querySelector('.hero').hidden = administrative;
    $('impact').hidden = administrative || !api.session();
    admin.setActive(administrative);
    $('results').hidden = administrative;
    $('collection-open').hidden = administrative;
    if (administrative) $('pagination').hidden = true;
    $('filters').hidden = !events;
    $('workspace-title').textContent = events ? 'Encontre sua próxima ação'
      : administrative ? 'Administração dos mutirões'
      : state.view === 'mine' ? 'Seu lugar na próxima mudança' : 'Cada coleta conta';
  }
  function empty(title, description) {
    const box = node('div', 'empty');
    box.append(node('strong', '', title), node('span', '', description));
    $('results').replaceChildren(box);
  }
  function action(label, handler, primary = false) {
    const button = node('button', `button ${primary ? 'primary' : 'outline'}`, label);
    button.type = 'button';
    button.addEventListener('click', () => void busy(button, handler));
    return button;
  }
  async function registrationAction(event, registered) {
    if (!api.session()) { openAuth('login'); return; }
    try {
      if (registered) await api.cancel(event.id);
      else await api.register(event.id);
      const refreshed = await load();
      if (refreshed) notify(registered ? 'Inscrição cancelada.' : 'Inscrição confirmada. Nos vemos na praia!');
      else if (api.session()) notify('Inscrição atualizada. Não foi possível recarregar os dados; tente novamente pelo botão abaixo.', true);
    } catch (error) {
      if (error.status === 409 || error.status === 404) await load();
      notify(error.message, true);
    }
  }
  function eventCard(event) {
    const registered = state.registrations.some(item => item.id === event.id);
    const card = node('article', 'card');
    const top = node('div', 'card-top');
    top.append(node('span', 'badge', event.status), node('span', 'card-id', `Mutirão #${event.id}`));
    const body = node('div', 'card-body');
    body.append(node('h3', '', event.title), node('p', '', `⌖ ${event.location}`), node('p', '', `◷ ${date(event.dateTime)}`));
    const actions = node('div', 'card-actions');
    if (registered || activeEvent(event)) actions.append(action(registered ? 'Cancelar inscrição' : 'Quero participar',
      () => registrationAction(event, registered), !registered));
    else body.append(node('p', '', 'Este evento não aceita novas inscrições.'));
    if (activeEvent(event)) actions.append(action('Registrar coleta', () => openCollection(event)));
    if (registered) body.append(node('p', '', '✓ Você está inscrito neste evento.'));
    body.append(actions);
    card.append(top, body);
    return card;
  }
  function collectionCard(collection) {
    const card = node('article', 'card');
    const top = node('div', 'card-top');
    top.append(node('span', 'badge', collection.materialType), node('span', 'card-id', `Coleta #${collection.id}`));
    const body = node('div', 'card-body');
    body.append(node('h3', '', `${kg(collection.weightKg)} kg · ${collection.beach}`),
      node('p', '', date(collection.registrationDate)),
      node('p', '', collection.event ? `Mutirão: ${collection.event.title}` : 'Coleta independente'));
    card.append(top, body);
    return card;
  }
  async function load() {
    const generation = ++state.generation;
    if (state.view === 'admin') { state.loading = false; return admin.load(); }
    const view = state.view;
    state.loading = true;
    $('results').setAttribute('aria-busy', 'true');
    $('pagination').hidden = true;
    empty('Carregando…', 'Estamos buscando as informações.');
    try {
      const signed = Boolean(api.session());
      const resultPromise = view === 'events' ? api.events({ page: state.page,
        location: $('location-filter').value.trim(), status: $('status-filter').value })
        : view === 'collections' ? api.collections({ page: state.page }) : Promise.resolve(null);
      const [result, profile, registrations] = await Promise.all([
        resultPromise, signed ? api.profile() : null, signed ? api.registrations() : []
      ]);
      if (generation !== state.generation) return false;
      state.profile = profile;
      state.registrations = registrations;
      syncAccount();
      const records = view === 'mine' ? registrations : result.content;
      if (!records.length) empty(view === 'mine' ? 'Sua próxima ação está por vir.'
        : view === 'collections' ? 'Sua primeira coleta começa aqui.' : 'Nenhum evento encontrado.',
        view === 'mine' ? 'Explore os eventos e escolha um mutirão para participar.'
          : view === 'collections' ? 'Registre o que você retirou da praia para acompanhar seu impacto.'
          : 'Tente outro local ou status. Novos mutirões aparecerão por aqui.');
      else $('results').replaceChildren(...records.map(view === 'collections' ? collectionCard : eventCard));
      state.pages = result?.totalPages || 0;
      $('pagination').hidden = view === 'mine' || state.pages < 2;
      $('page-info').textContent = `Página ${state.page + 1} de ${state.pages}`;
      $('previous').disabled = state.page === 0;
      $('next').disabled = state.page + 1 >= state.pages;
      return true;
    } catch (error) {
      if (generation !== state.generation) return false;
      empty('Não conseguimos carregar os dados.', 'Use o botão abaixo para tentar novamente.');
      $('results').firstChild.append(action('Tentar novamente', load));
      notify(error.message, true);
      return false;
    } finally {
      if (generation === state.generation) {
        state.loading = false;
        $('results').setAttribute('aria-busy', 'false');
      }
    }
  }
  function openAuth(mode) {
    state.signup = mode === 'signup';
    $('auth-form').reset();
    errorAt('auth-error');
    $('name-label').hidden = !state.signup;
    $('auth-form').elements.name.required = state.signup;
    $('auth-form').elements.password.autocomplete = state.signup ? 'new-password' : 'current-password';
    $('auth-title').textContent = state.signup ? 'Comece a fazer a diferença.' : 'Que bom ter você aqui.';
    $('auth-description').textContent = state.signup ? 'Crie sua conta para cuidar das praias com a gente.'
      : 'Entre para participar dos eventos e acompanhar suas coletas.';
    $('auth-submit').textContent = state.signup ? 'Criar minha conta' : 'Entrar';
    $('auth-switch').textContent = state.signup ? 'Já tenho uma conta' : 'Ainda não tenho uma conta';
    if (!$('auth-dialog').open) $('auth-dialog').showModal();
  }
  function openCollection(event = null) {
    if (!api.session()) { openAuth('login'); return; }
    state.selectedEvent = event;
    $('collection-form').reset();
    const now = new Date();
    $('collection-form').elements.registrationDate.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
    if (event) $('collection-form').elements.beach.value = event.location;
    syncCollectionEvent();
    errorAt('collection-error');
    $('collection-dialog').showModal();
  }
  function syncCollectionEvent() {
    $('collection-event').textContent = state.selectedEvent ? `Evento: ${state.selectedEvent.title}` : 'Coleta independente, sem evento.';
    $('unlink-event').hidden = !state.selectedEvent;
  }
  $('login-open').addEventListener('click', () => openAuth('login'));
  $('signup-open').addEventListener('click', () => openAuth('signup'));
  $('auth-switch').addEventListener('click', () => openAuth(state.signup ? 'login' : 'signup'));
  $('logout').addEventListener('click', () => { api.logout(); resetAccount(); notify('Você saiu da sua conta.'); });
  $('collection-open').addEventListener('click', () => openCollection());
  $('unlink-event').addEventListener('click', () => { state.selectedEvent = null; syncCollectionEvent(); });
  document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
  document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => {
    if (button.dataset.view === 'admin' && api.session()?.role !== 'ADMIN') {
      notify('Esta área é exclusiva de administradores.', true); return;
    }
    if (button.dataset.view !== 'events' && !api.session()) { openAuth('login'); return; }
    state.view = button.dataset.view;
    state.page = 0;
    syncView();
    notify('');
    void load();
  }));
  $('filters').addEventListener('submit', event => { event.preventDefault(); state.page = 0; notify(''); void load(); });
  $('previous').addEventListener('click', () => { if (!state.loading && state.page > 0) { state.page--; void load(); } });
  $('next').addEventListener('click', () => { if (!state.loading && state.page + 1 < state.pages) { state.page++; void load(); } });
  $('auth-form').addEventListener('submit', event => {
    event.preventDefault();
    const form = event.currentTarget;
    const signup = state.signup;
    const input = { email: form.elements.email.value.trim(), password: form.elements.password.value };
    if (new TextEncoder().encode(input.password).length > 72) {
      errorAt('auth-error', 'A senha deve ter no máximo 72 bytes em UTF-8.'); return;
    }
    void busy($('auth-submit'), async () => {
      errorAt('auth-error');
      let created = false;
      try {
        if (signup) {
          await api.signup({ ...input, name: form.elements.name.value.trim() });
          created = true;
        }
        await api.login(input);
        form.reset();
        $('auth-dialog').close();
        syncAccount();
        if (await load()) notify(created ? 'Conta criada. Bem-vindo à sua próxima ação!' : 'Você entrou na sua conta.');
      } catch (error) {
        if (created) openAuth('login');
        errorAt('auth-error', created ? `Conta criada. Faça login para continuar. ${error.message}` : error.message);
      }
    });
  });
  $('collection-form').addEventListener('submit', event => {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const input = { beach: data.get('beach').trim(), materialType: data.get('materialType').trim(),
      weightKg: Number(data.get('weightKg')), registrationDate: data.get('registrationDate') };
    if (state.selectedEvent) input.event = { id: state.selectedEvent.id };
    if (!input.beach || !input.materialType || !Number.isFinite(input.weightKg) || input.weightKg <= 0) {
      errorAt('collection-error', 'Informe praia, material e um peso maior que zero.'); return;
    }
    void busy(form.querySelector('[type="submit"]'), async () => {
      errorAt('collection-error');
      try {
        await api.createCollection(input);
        $('collection-dialog').close();
        form.reset();
        state.view = 'collections';
        state.page = 0;
        syncView();
        const refreshed = await load();
        if (refreshed) notify('Coleta registrada! Seu impacto foi atualizado.');
        else if (api.session()) notify('Coleta registrada. Não foi possível recarregar o histórico; tente novamente pelo botão abaixo.', true);
      } catch (error) { errorAt('collection-error', error.message); }
    });
  });
  void load();
})();

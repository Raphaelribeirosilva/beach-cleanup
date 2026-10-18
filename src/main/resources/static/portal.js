/* global createPortalApi, createAdminPortal */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const { node, action, busy } = window.PortalUI;
  const accountLink = new URLSearchParams(window.location.hash.slice(1));
  const initialAccountMode = accountLink.has('reset') ? 'reset' : accountLink.has('verify') ? 'verify' : null;
  let accountToken = initialAccountMode ? accountLink.get(initialAccountMode) : null;
  let accountMode = null;
  if (initialAccountMode) window.history.replaceState(null, '', window.location.pathname + window.location.search);
  const api = createPortalApi(window.fetch.bind(window), () => {
    resetAccount();
    if ($('collection-dialog').open) $('collection-dialog').close();
    notify('Sua sessão expirou. Entre novamente para continuar.', true);
    openAuth('login');
  });
  const state = { view: 'events', page: 0, pages: 0, generation: 0, signup: false,
    profile: null, totalRegistrations: 0, registrations: [], selectedEvent: null, editingCollection: null, profileVersion: null, loading: false };
  const kg = value => value == null ? '—' : new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 3 }).format(value);
  const date = value => {
    if (!value) return 'Data não informada';
    // API event times represent local wall time; date-only values must not shift to the previous day.
    const parsed = new Date(value.includes('T') ? value : `${value}T12:00:00`);
    return Number.isNaN(parsed.getTime()) ? 'Data não informada' : new Intl.DateTimeFormat('pt-BR',
      value.includes('T') ? { dateStyle: 'medium', timeStyle: 'short' } : { dateStyle: 'medium' }).format(parsed);
  };
  const activeEvent = event => ['Agendado', 'Em andamento'].includes(event.status);
  const loadReport = window.createImpactReports(api, { node, kg, catalogChoices, isCurrent: generation => generation === state.generation });
  const admin = createAdminPortal(api, { node, action, busy, kg, date });
  function catalogChoices(select, entries, title) {
    const previous = select.value;
    const placeholder = node('option', '', title);
    placeholder.value = '';
    select.replaceChildren(placeholder, ...entries.map(entry => {
      const option = node('option', '', entry.name);
      option.value = entry.id;
      return option;
    }));
    select.value = entries.some(entry => String(entry.id) === previous) ? previous : '';
  }
  function notify(message, error = false) {
    $('notice').textContent = message;
    $('notice').className = error ? 'notice error' : 'notice';
    $('notice').hidden = !message;
  }
  function errorAt(id, message = '') { window.PortalUI.errorAt($(id), message); }
  function syncAccount() {
    const signed = Boolean(api.session());
    $('account').hidden = signed;
    $('signed-account').hidden = !signed;
    $('impact').hidden = !signed || ['admin', 'reports'].includes(state.view);
    $('admin-tab').hidden = api.session()?.role !== 'ADMIN';
    $('account-name').textContent = state.profile?.name || 'Minha conta';
    $('total-kg').textContent = state.profile ? kg(state.profile.totalCollectedKg) : '—';
    $('total-events').textContent = state.totalRegistrations;
  }
  function resetAccount() {
    admin.reset();
    state.generation++;
    state.profile = null;
    state.profileVersion = null;
    state.editingCollection = null;
    $('profile-form').reset();
    $('password-form').reset();
    $('report-results').replaceChildren();
    if ($('collection-dialog').open) $('collection-dialog').close();
    state.registrations = [];
    state.totalRegistrations = 0;
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
    const reports = state.view === 'reports';
    const profile = state.view === 'profile';
    document.querySelector('.hero').hidden = administrative || reports || profile;
    $('impact').hidden = administrative || reports || !api.session();
    admin.setActive(administrative);
    $('reports-workspace').hidden = !reports;
    $('profile-workspace').hidden = !profile;
    $('results').hidden = administrative || reports || profile;
    $('collection-open').hidden = administrative || reports || profile;
    if (administrative || reports || profile) $('pagination').hidden = true;
    $('filters').hidden = !events;
    $('workspace-title').textContent = events ? 'Encontre sua próxima ação'
      : administrative ? 'Administração dos mutirões'
      : reports ? 'Resultados das ações de limpeza' : profile ? 'Meu perfil' : state.view === 'mine' ? 'Seu lugar na próxima mudança' : 'Cada coleta conta';
  }
  function empty(title, description) {
    const box = node('div', 'empty');
    box.append(node('strong', '', title), node('span', '', description));
    $('results').replaceChildren(box);
  }
  async function registrationAction(event, registered) {
    if (!api.session()) { openAuth('login'); return; }
    try {
      if (registered) await api.cancel(event.id);
      else await api.register(event.id);
      const refreshed = await load();
      if (refreshed) window.PortalUI.focus($('results'));
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
    const actions = node('div', 'card-actions');
    actions.append(action('Editar coleta', () => openCollection(collection.event, collection)),
      action('Excluir coleta', async () => {
        if (!window.confirm(`Excluir a coleta #${collection.id}? Esta ação não pode ser desfeita.`)) return;
        try { await api.deleteCollection(collection.id, collection.version); if (await load()) { window.PortalUI.focus($('results')); notify('Coleta excluída. Totais atualizados.'); } }
        catch (error) { notify(error.message, true); }
      }));
    body.append(actions);
    card.append(top, body);
    return card;
  }
  async function load() {
    const generation = ++state.generation;
    if (state.view === 'admin') { state.loading = false; return admin.load(); }
    if (state.view === 'reports') return loadReport(generation);
    if (state.view === 'profile') {
      $('profile-workspace').setAttribute('aria-busy', 'true');
      $('profile-form').querySelector('[type=submit]').disabled = true;
      try {
        const profile = await api.profile();
        if (generation !== state.generation) return false;
        state.profile = profile;
        state.profileVersion = profile.version;
        $('profile-form').elements.name.value = profile.name;
        $('profile-form').elements.email.value = profile.email;
        $('profile-verification').textContent = profile.emailVerified ? 'E-mail confirmado.' : 'E-mail ainda não confirmado.';
        $('profile-resend').hidden = profile.emailVerified;
        syncAccount();
        return true;
      } catch (error) { if (generation === state.generation) notify(error.message, true); return false; }
      finally { if (generation === state.generation) { $('profile-workspace').setAttribute('aria-busy', 'false'); $('profile-form').querySelector('[type=submit]').disabled = false; } }
    }
    const view = state.view;
    state.loading = true;
    $('results').setAttribute('aria-busy', 'true');
    $('pagination').hidden = true;
    empty('Carregando…', 'Estamos buscando as informações.');
    try {
      const signed = Boolean(api.session());
      const resultPromise = view === 'events' ? api.events({ page: state.page,
        beachId: $('location-filter').value, status: $('status-filter').value })
        : view === 'collections' ? api.collections({ page: state.page }) : Promise.resolve(null);
      const [result, profile, beaches] = await Promise.all([
        resultPromise, signed ? api.profile() : null, api.catalog('beaches')
      ]);
      const registrationPage = signed ? await api.registrations({ page: view === 'mine' ? state.page : 0, size: view === 'mine' ? 6 : 1,
        eventIds: view === 'events' ? result.content.map(event => event.id).join(',') : undefined })
        : { content: [], totalElements: 0, registeredEventIds: [] };
      const registrations = registrationPage.content;
      if (generation !== state.generation) return false;
      catalogChoices($('location-filter'), beaches, 'Todas as praias');
      state.profile = profile;
      state.registrations = view === 'events' ? registrationPage.registeredEventIds.map(id => ({ id })) : registrations;
      state.totalRegistrations = registrationPage.totalElements;
      syncAccount();
      const records = view === 'mine' ? registrations : result.content;
      if (!records.length && state.page > 0) { state.page--; return load(); }
      if (!records.length) empty(view === 'mine' ? 'Sua próxima ação está por vir.'
        : view === 'collections' ? 'Sua primeira coleta começa aqui.' : 'Nenhum evento encontrado.',
        view === 'mine' ? 'Explore os eventos e escolha um mutirão para participar.'
          : view === 'collections' ? 'Registre o que você retirou da praia para acompanhar seu impacto.'
          : 'Tente outro local ou status. Novos mutirões aparecerão por aqui.');
      else $('results').replaceChildren(...records.map(view === 'collections' ? collectionCard : eventCard));
      state.pages = (view === 'mine' ? registrationPage.totalPages : result?.totalPages) || 0;
      $('pagination').hidden = state.pages < 2;
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
    errorAt('auth-status');
    $('name-label').hidden = !state.signup;
    $('auth-form').elements.name.required = state.signup;
    $('auth-form').elements.password.minLength = state.signup ? 15 : 1;
    $('password-help').textContent = state.signup ? 'Use pelo menos 15 caracteres, até 72 bytes UTF-8 e evite senhas comuns. Você precisará confirmar o e-mail.' : 'Confira a senha e confirme seu e-mail para entrar. A sessão pode ser renovada por até sete dias.';
    $('auth-form').elements.password.autocomplete = state.signup ? 'new-password' : 'current-password';
    $('auth-title').textContent = state.signup ? 'Comece a fazer a diferença.' : 'Que bom ter você aqui.';
    $('auth-description').textContent = state.signup ? 'Crie sua conta para cuidar das praias com a gente.'
      : 'Entre para participar dos eventos e acompanhar suas coletas.';
    $('auth-submit').textContent = state.signup ? 'Criar minha conta' : 'Entrar';
    $('auth-switch').textContent = state.signup ? 'Já tenho uma conta' : 'Ainda não tenho uma conta';
    if (!$('auth-dialog').open) window.PortalUI.openDialog($('auth-dialog'));
  }
  async function openCollection(event = null, collection = null) {
    if (!api.session()) { openAuth('login'); return; }
    const session = api.session();
    let beaches, materials;
    try {
      [beaches, materials, collection] = await Promise.all([
        api.catalog('beaches'), api.catalog('materials'), collection ? api.collection(collection.id) : null
      ]);
      if (collection) event = collection.event;
    }
    catch (error) { notify(error.message, true); return; }
    if (api.session() !== session) return;
    state.selectedEvent = event;
    state.editingCollection = collection;
    $('collection-form').reset();
    catalogChoices($('collection-form').elements.beach, beaches, 'Selecione a praia');
    catalogChoices($('collection-form').elements.materialType, materials, 'Selecione o material');
    $('collection-form').elements.registrationDate.value = api.dataRules.today();
    if (event) $('collection-form').elements.beach.value = event.beachId ?? '';
    if (collection) {
      const form = $('collection-form');
      // Keep inactive catalog values available when editing historical records.
      for (const [field, id, label] of [['beach', collection.beachId, collection.beach], ['materialType', collection.materialId, collection.materialType]]) {
        const select = form.elements[field];
        if (id && ![...select.options].some(option => option.value === String(id))) {
          const option = node('option', '', label); option.value = id; select.append(option);
        }
        select.value = id ?? '';
      }
      form.elements.weightKg.value = collection.weightKg;
      form.elements.registrationDate.value = collection.registrationDate;
    }
    $('collection-title').textContent = collection ? `Editar coleta #${collection.id}` : 'Registrar coleta';
    syncCollectionEvent();
    errorAt('collection-error');
    window.PortalUI.openDialog($('collection-dialog'));
  }
  function syncCollectionEvent() {
    const form = $('collection-form');
    const event = state.selectedEvent;
    form.elements.beach.disabled = Boolean(event);
    form.elements.registrationDate.readOnly = Boolean(event);
    form.elements.registrationDate.min = '1900-01-01';
    form.elements.registrationDate.max = api.dataRules.today();
    if (event) {
      form.elements.beach.value = event.beachId ?? '';
      form.elements.registrationDate.value = event.dateTime?.slice(0, 10) || '';
    }
    $('collection-event').textContent = event ? `Evento: ${event.title}. A coleta usa a praia e a data do evento; datas futuras não são permitidas.` : 'Coleta independente, sem evento.';
    $('unlink-event').hidden = !state.selectedEvent;
  }
  $('login-open').addEventListener('click', () => openAuth('login'));
  $('signup-open').addEventListener('click', () => openAuth('signup'));
  $('auth-switch').addEventListener('click', () => openAuth(state.signup ? 'login' : 'signup'));
  async function endSession() {
    const pending = api.logout();
    resetAccount();
    notify('Encerrando a sessão no servidor…');
    try { await pending; notify('Sessão encerrada no servidor. Você saiu da sua conta.'); }
    catch (error) {
      notify(error.message, true);
      $('notice').append(action('Tentar encerrar no servidor', endSession));
    }
  }
  $('logout').addEventListener('click', () => void busy($('logout'), endSession));
  $('collection-open').addEventListener('click', () => openCollection());
  $('unlink-event').addEventListener('click', () => { state.selectedEvent = null; syncCollectionEvent(); });
  document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
  document.querySelectorAll('[data-view]').forEach(button => button.addEventListener('click', () => {
    if (button.dataset.view === 'admin' && api.session()?.role !== 'ADMIN') {
      notify('Esta área é exclusiva de administradores.', true); return;
    }
    if (!['events', 'reports'].includes(button.dataset.view) && !api.session()) { openAuth('login'); return; }
    state.view = button.dataset.view;
    state.page = 0;
    syncView();
    notify('');
    const requested = state.view;
    void load().then(loaded => { if (loaded && requested === state.view) window.PortalUI.focus($('workspace-title')); });
  }));
  $('filters').addEventListener('submit', event => { event.preventDefault(); state.page = 0; notify(''); void load().then(() => window.PortalUI.focus($('results'))); });
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
          openAuth('login');
          errorAt('auth-status', 'Conta criada. Confira seu e-mail e confirme o endereço antes de entrar. Se necessário, use Reenviar confirmação.');
          return;
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
    const input = { beachId: Number(form.elements.beach.value), materialId: Number(data.get('materialType')),
      weightKg: Number(data.get('weightKg')), registrationDate: data.get('registrationDate') };
    if (state.selectedEvent) input.event = { id: state.selectedEvent.id };
    if (!input.beachId || !input.materialId || !api.dataRules.weightValid(data.get('weightKg'))
        || !api.dataRules.dateValid(input.registrationDate, api.dataRules.today())) {
      errorAt('collection-error', 'Selecione praia/material, peso de 0,001 a 100.000 kg com até 3 casas decimais e data entre 1900 e hoje (São Paulo).'); return;
    }
    void busy(form.querySelector('[type="submit"]'), async () => {
      errorAt('collection-error');
      try {
        const editing = state.editingCollection;
        if (editing) await api.updateCollection(editing.id, { ...input, version: editing.version });
        else await api.createCollection(input);
        $('collection-dialog').close();
        form.reset();
        state.view = 'collections';
        state.page = 0;
        syncView();
        const refreshed = await load();
        if (refreshed) notify('Coleta salva! Seu impacto foi atualizado.');
        else if (api.session()) notify('Coleta salva. Não foi possível recarregar o histórico; tente novamente pelo botão abaixo.', true);
      } catch (error) { errorAt('collection-error', error.status === 409
        ? 'A coleta mudou desde que foi aberta. Seus campos foram preservados. Feche e reabra a edição para conferir os dados atuais; nenhuma alteração foi sobrescrita.' : error.message); }
    });
  });

  $('report-filters').addEventListener('submit', event => { event.preventDefault(); void load(); });
  $('profile-form').addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    const previousEmail = state.profile?.email;
    const input = { name: form.elements.name.value.trim(), email: form.elements.email.value.trim(), version: state.profileVersion, currentPassword: form.elements.currentPassword.value };
    void busy(form.querySelector('[type="submit"]'), async () => {
      try {
        state.profile = await api.updateProfile(input);
        state.profileVersion = state.profile.version;
        if (state.profile.email !== previousEmail) {
          await api.logout(); resetAccount(); notify('Perfil atualizado. Confirme o novo e-mail antes de entrar novamente.'); openAuth('login');
        } else { syncAccount(); notify('Perfil atualizado.'); }
      } catch (error) { notify(error.status === 409 ? 'O perfil mudou ou o e-mail já está em uso. Seus campos foram preservados. Reabra Meu perfil para conferir a versão atual.' : error.message, true); }
      finally { form.elements.currentPassword.value = ''; }
    });
  });
  $('password-form').addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    const input = { currentPassword: form.elements.currentPassword.value, newPassword: form.elements.newPassword.value };
    if (input.newPassword !== form.elements.confirmation.value) { notify('A confirmação da nova senha não confere.', true); return; }
    if (Object.values(input).some(value => new TextEncoder().encode(value).length > 72)) { notify('Cada senha deve ter no máximo 72 bytes em UTF-8.', true); return; }
    void busy(form.querySelector('[type="submit"]'), async () => {
      try { await api.changePassword(input); await api.logout(); resetAccount(); notify('Senha alterada. Entre novamente.'); openAuth('login'); }
      catch (error) { notify(error.message, true); }
      finally { form.reset(); }
    });
  });


  function openAccountHelp(mode) {
    accountMode = mode;
    const reset = mode === 'reset', verify = mode === 'verify';
    const request = !reset && !verify;
    if (request) accountToken = null;
    if ($('auth-dialog').open) $('auth-dialog').close();
    $('account-help-form').reset(); errorAt('account-help-error');
    $('help-email-label').hidden = !request;
    $('help-password-label').hidden = !reset; $('help-confirmation-label').hidden = !reset;
    $('account-help-form').elements.email.required = request;
    $('account-help-form').elements.newPassword.required = reset;
    $('account-help-form').elements.confirmation.required = reset;
    $('account-help-title').textContent = reset ? 'Escolher nova senha' : verify ? 'Confirmar seu e-mail' : mode === 'verification' ? 'Reenviar confirmação' : 'Recuperar acesso';
    $('account-help-description').textContent = reset ? 'Use pelo menos 15 caracteres, até 72 bytes UTF-8. A redefinição encerra todas as sessões da conta.'
      : verify ? 'Clique para confirmar que este endereço de e-mail é seu.' : 'Informe seu e-mail. Se o endereço estiver apto, enviaremos as instruções. Confira também o spam.';
    $('account-help-submit').textContent = reset ? 'Redefinir senha' : verify ? 'Confirmar e-mail' : 'Enviar instruções';
    if (!$('account-help-dialog').open) window.PortalUI.openDialog($('account-help-dialog'));
  }
  $('recovery-open').addEventListener('click', () => openAccountHelp('recovery'));
  $('verification-open').addEventListener('click', () => openAccountHelp('verification'));
  $('profile-resend').addEventListener('click', () => { openAccountHelp('verification'); $('account-help-form').elements.email.value = state.profile?.email || ''; });
  $('account-help-dialog').addEventListener('close', () => { accountToken = null; $('account-help-form').reset(); });
  window.addEventListener('hashchange', () => {
    const link = new URLSearchParams(window.location.hash.slice(1));
    const mode = link.has('reset') ? 'reset' : link.has('verify') ? 'verify' : null;
    if (!mode) return;
    accountToken = link.get(mode);
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
    openAccountHelp(mode);
  });
  $('account-help-form').addEventListener('submit', event => {
    event.preventDefault(); const form = event.currentTarget;
    const mode = accountMode, token = accountToken;
    const email = form.elements.email.value.trim(), newPassword = form.elements.newPassword.value;
    if (mode === 'reset' && (newPassword !== form.elements.confirmation.value || new TextEncoder().encode(newPassword).length > 72)) {
      errorAt('account-help-error', 'Confira a confirmação e o limite de 72 bytes da nova senha.'); return;
    }
    void busy($('account-help-submit'), async () => {
      errorAt('account-help-error');
      try {
        if (mode === 'recovery' || mode === 'verification') {
          const result = mode === 'recovery' ? await api.requestRecovery(email) : await api.requestVerification(email);
          $('account-help-dialog').close(); notify(result.message);
        } else {
          if (mode === 'verify') await api.verifyEmail(token);
          else { await api.resetPassword({ token, newPassword }); await api.logout(); resetAccount(); }
          $('account-help-dialog').close(); notify(mode === 'verify' ? 'E-mail confirmado. Entre na sua conta.' : 'Senha redefinida. Todas as sessões foram encerradas. Entre com a nova senha.'); openAuth('login');
        }
      } catch (error) { errorAt('account-help-error', error.message); }
      finally { form.elements.newPassword.value = ''; form.elements.confirmation.value = ''; }
    });
  });

  // Restored credentials still go through the API before private data is rendered.
  syncAccount();
  syncView();
  window.addEventListener('focus', () => { api.session(); });
  void load();
  if (initialAccountMode) openAccountHelp(initialAccountMode);
})();

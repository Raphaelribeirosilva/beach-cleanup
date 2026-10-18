/* global createAdminPortal */
(() => {
  'use strict';
  const transitions = {
    Agendado: ['Agendado', 'Em andamento', 'Pausado', 'Cancelado'],
    'Em andamento': ['Em andamento', 'Pausado', 'Concluido', 'Cancelado'],
    Pausado: ['Pausado', 'Agendado', 'Em andamento', 'Cancelado'],
    Concluido: ['Concluido'], Cancelado: ['Cancelado']
  };
  const statuses = Object.keys(transitions);
  const auditResources = { COLLECTION: 'Coleta', EVENT: 'Evento', USER: 'Usuário', BEACH: 'Praia', MATERIAL: 'Material' };
  const auditActions = { CREATE: 'Criação', TRANSFER: 'Transferência', STATE_CHANGE: 'Alteração de estado', MERGE: 'Unificação',
    REGISTER: 'Inscrição', UNREGISTER: 'Cancelamento de inscrição', DELETE: 'Exclusão' };
  window.createAdminPortal = (api, { node, action, busy, kg, date }) => {
    const $ = id => document.getElementById(id);
    const state = { active: false, view: 'events', page: 0, pages: 0, generation: 0, loading: false, editing: null,
      catalogs: { beaches: [], materials: [] } };
    const isCatalog = view => ['beaches', 'materials'].includes(view);
    const isReview = view => ['quality', 'rounding'].includes(view);
    const choices = kind => [{ id: '', name: 'Selecione' }, ...state.catalogs[kind]];
    async function refreshCatalogs() {
      const [beaches, materials] = await Promise.all([api.catalog('beaches'), api.catalog('materials')]);
      return { beaches, materials };
    }
    const authorized = () => state.active && api.session()?.role === 'ADMIN';
    const current = session => authorized() && api.session() === session;
    function message(text = '', error = false) {
      $('admin-message').textContent = text;
      $('admin-message').className = error ? 'notice error' : 'notice';
      $('admin-message').hidden = !text;
    }
    function field(form, name, title, { type = 'text', value = '', required = false, choices, ...attributes } = {}) {
      const label = node('label', '', title);
      const input = node(choices ? 'select' : 'input');
      input.name = name;
      if (choices) choices.forEach(choice => {
        const option = node('option', '', typeof choice === 'object' ? choice.name
          : choice === 'Concluido' ? 'Concluído' : choice || 'Todos');
        option.value = typeof choice === 'object' ? choice.id : choice;
        input.append(option);
      });
      else input.type = type;
      for (const [key, val] of Object.entries(attributes)) input.setAttribute(key, val);
      input.required = required;
      input.value = value;
      label.append(input);
      form.append(label);
      return input;
    }
    function filters() {
      const form = $('admin-filters');
      form.replaceChildren();
      form.hidden = isCatalog(state.view) || isReview(state.view);
      if (form.hidden) return;
      if (state.view === 'audit') {
        field(form, 'resource', 'Recurso', { choices: ['', ...Object.entries(auditResources).map(([id, name]) => ({ id, name }))] });
        field(form, 'resourceId', 'ID do recurso', { type: 'number', min: 1 });
        field(form, 'actorId', 'ID do autor', { type: 'number', min: 1 });
        field(form, 'action', 'Ação', { choices: ['', ...Object.entries(auditActions).map(([id, name]) => ({ id, name }))] });
        field(form, 'from', 'Desde (UTC)', { type: 'datetime-local' });
        field(form, 'to', 'Até (UTC)', { type: 'datetime-local' });
      } else if (state.view === 'events') {
        field(form, 'location', 'Praia', { choices: choices('beaches') });
        field(form, 'status', 'Status', { choices: ['', ...statuses] });
      } else if (state.view === 'users') {
        field(form, 'name', 'Nome', { maxlength: 255 });
        field(form, 'email', 'E-mail contém', { maxlength: 255 });
      } else {
        field(form, 'beach', 'Praia', { choices: choices('beaches') });
        field(form, 'materialType', 'Material', { choices: choices('materials') });
        field(form, 'volunteerId', 'ID do usuário', { type: 'number', min: 1, step: 1 });
        field(form, 'eventId', 'ID do evento', { type: 'number', min: 1, step: 1 });
        field(form, 'from', 'De', { type: 'date' });
        field(form, 'to', 'Até', { type: 'date' });
      }
      const search = node('button', 'button outline', 'Buscar');
      search.type = 'submit';
      form.append(search);
    }
    function configure() {
      document.querySelectorAll('[data-admin-view]').forEach(button => {
        const active = state.view === button.dataset.adminView;
        button.classList.toggle('active', active);
        if (active) button.setAttribute('aria-current', 'page');
        else button.removeAttribute('aria-current');
      });
      $('admin-title').textContent = state.view === 'audit' ? 'Histórico de alterações' : state.view === 'quality' ? 'Pendências de qualidade'
        : state.view === 'rounding' ? 'Conversão dos pesos antigos' : state.view === 'users' ? 'Consultar usuários'
        : state.view === 'events' ? 'Gerenciar eventos' : state.view === 'beaches' ? 'Gerenciar praias'
          : state.view === 'materials' ? 'Gerenciar materiais' : 'Gerenciar coletas';
      $('admin-create').hidden = state.view === 'audit' || state.view === 'users' || isReview(state.view);
      $('admin-create').textContent = isCatalog(state.view) ? '+ Cadastrar item'
        : state.view === 'events' ? '+ Criar evento' : '+ Registrar coleta';
      filters();
    }
    function empty(text) { $('admin-results').replaceChildren(node('p', 'empty', text)); }
    async function load() {
      if (!authorized()) return false;
      const session = api.session();
      const generation = ++state.generation;
      const view = state.view;
      state.loading = true;
      $('admin-results').setAttribute('aria-busy', 'true');
      $('admin-pagination').hidden = true;
      empty('Carregando…');
      try {
        const catalogs = await refreshCatalogs();
        if (!current(session) || generation !== state.generation) return false;
        state.catalogs = catalogs;
        const previousFilters = Object.fromEntries(new FormData($('admin-filters')));
        filters();
        for (const [name, value] of Object.entries(previousFilters)) {
          if ($('admin-filters').elements[name]) $('admin-filters').elements[name].value = value;
        }
        if (isCatalog(view)) {
          state.pages = 1;
          const entries = catalogs[view];
          if (!entries.length) empty('Nenhum item cadastrado. Cadastre o primeiro item para disponibilizá-lo nos formulários.');
          else $('admin-results').replaceChildren(...entries.map(record => catalogCard(record, view)));
          return true;
        }
        const values = Object.fromEntries(new FormData($('admin-filters')));
        for (const [name, target] of [['location', 'beachId'], ['beach', 'beachId'], ['materialType', 'materialId']]) {
          if (name in values) { values[target] = values[name]; delete values[name]; }
        }
        if (view === 'audit') for (const key of ['from', 'to']) if (values[key]) values[key] += ':00Z';
        const result = await api.admin[view]({ ...values, page: state.page });
        if (!current(session) || generation !== state.generation) return false;
        // A deletion can remove the last record of the current page.
        if (state.page > 0 && state.page >= result.totalPages) {
          state.page = Math.max(0, result.totalPages - 1);
          return load();
        }
        state.pages = result.totalPages;
        if (!result.content.length) empty(isReview(view) ? 'Nenhum registro para revisar nesta lista.' : 'Nenhum registro encontrado. Revise os filtros ou cadastre um registro.');
        else $('admin-results').replaceChildren(...result.content.map(record => view === 'audit' ? auditCard(record) : isReview(view) ? reviewCard(record, view) : card(record, view)));
        $('admin-pagination').hidden = state.pages < 2;
        $('admin-page-info').textContent = `Página ${state.page + 1} de ${state.pages} · ${result.totalElements} registros`;
        $('admin-previous').disabled = state.page === 0;
        $('admin-next').disabled = state.page + 1 >= state.pages;
        return true;
      } catch (error) {
        if (!current(session) || generation !== state.generation) return false;
        empty('Não foi possível carregar os registros.');
        $('admin-results').firstChild.append(action('Tentar novamente', load));
        message(error.message, true);
        return false;
      } finally {
        if (generation === state.generation) {
          state.loading = false;
          $('admin-results').setAttribute('aria-busy', 'false');
        }
      }
    }
    function auditCard(record) {
      const card = node('article', 'card');
      const body = node('div', 'card-body');
      body.append(node('h3', '', `${auditActions[record.action] ?? record.action} · ${auditResources[record.resource] ?? record.resource} #${record.resourceId}`),
        node('p', '', `${new Date(record.occurredAt).toLocaleString('pt-BR')} · Autor: ${record.actorId ?? 'sistema'} (${({ ADMIN: 'administrador', VOLUNTEER: 'voluntário', SYSTEM: 'sistema' })[record.actorRole] ?? record.actorRole})`));
      for (const [label, before, after] of [
        ['Usuário', record.previousUserId, record.nextUserId],
        ['Evento', record.previousEventId, record.nextEventId],
        ['Estado', record.previousState, record.nextState]]) {
        if (before != null || after != null) body.append(node('p', '', `${label}: ${before ?? 'sem vínculo'} → ${after ?? 'sem vínculo'}`));
      }
      if (record.targetId != null) body.append(node('p', '', `Destino da unificação: #${record.targetId}`));
      card.append(body);
      return card;
    }
    function catalogCard(record, view) {
      const card = node('article', 'card');
      const body = node('div', 'card-body');
      body.append(node('span', 'card-id', `#${record.id}`), node('h3', '', record.name),
        node('p', '', `Nomes reconhecidos: ${record.aliases.join(', ')}`));
      const actions = node('div', 'card-actions');
      actions.append(action('Editar nomes', () => edit(view, record.id)),
        action('Unificar com outro item', () => edit(view, record.id, true)));
      body.append(actions);
      card.append(body);
      return card;
    }
    function reviewCard(record, view) {
      const result = node('article', 'card');
      const body = node('div', 'card-body');
      if (view === 'quality') {
        body.append(node('h3', '', `${record.resource === 'event' ? 'Evento' : 'Coleta'} #${record.id}`),
          node('p', '', record.message), action('Revisar registro', () => edit(record.resource === 'event' ? 'events' : 'collections', record.id)));
      } else {
        body.append(node('h3', '', `Coleta #${record.collectionId}`),
          node('p', '', `Peso anterior: ${record.originalWeight ?? 'não informado'} kg`),
          node('p', '', `Peso convertido: ${record.roundedWeight ?? 'não informado'} kg · Diferença: ${record.deltaWeight ?? 'não calculável'} kg`),
          node('p', 'hint', 'Registro histórico da conversão. Edições posteriores não alteram esta conferência.'));
      }
      result.append(body);
      return result;
    }
    function card(record, view) {
      const card = node('article', 'card');
      const top = node('div', 'card-top');
      top.append(node('span', 'card-id', `#${record.id}`),
        node('span', 'badge', view === 'events' ? record.status : view === 'collections' ? record.materialType : 'Usuário'));
      const body = node('div', 'card-body');
      body.append(node('h3', '', view === 'events' ? record.title : view === 'users' ? record.name : `${kg(record.weightKg)} kg · ${record.beach}`));
      const lines = view === 'events' ? [record.location, date(record.dateTime)]
        : view === 'users' ? [record.email, `${kg(record.totalCollectedKg)} kg coletados`]
        : [date(record.registrationDate), record.volunteer ? `Usuário #${record.volunteer.id}: ${record.volunteer.name}` : 'Sem proprietário',
          record.event ? `Evento #${record.event.id}: ${record.event.title}` : 'Sem evento'];
      lines.forEach(line => body.append(node('p', '', line)));
      if (view !== 'users') {
        const actions = node('div', 'card-actions');
        actions.append(action(view === 'events' ? 'Editar / status' : 'Editar', () => edit(view, record.id)),
          action('Excluir', () => remove(view, record)));
        body.append(actions);
      }
      card.append(top, body);
      return card;
    }
    async function edit(view, id = null, merge = false) {
      if (!authorized()) return;
      const session = api.session();
      const generation = state.generation;
      try {
        const catalogs = await refreshCatalogs();
        const record = id == null ? {} : isCatalog(view) ? catalogs[view].find(item => item.id === id)
          : await api.admin[view === 'events' ? 'event' : 'collection'](id);
        if (!current(session) || generation !== state.generation) return;
        if (!record) { message('O item foi unificado. Recarregue a lista.', true); return; }
        state.catalogs = catalogs;
        state.editing = { view, id, version: record.version, merge, targets: isCatalog(view) ? catalogs[view] : [] };
        const editingContext = state.editing;
        const form = $('admin-form');
        form.replaceChildren();
        $('admin-editor-title').textContent = `${merge ? 'Unificar' : id == null ? 'Criar' : 'Editar'} ${isCatalog(view) ? 'item do catálogo' : view === 'events' ? 'evento' : 'coleta'}${id == null ? '' : ` #${id}`}`;
        if (isCatalog(view)) {
          if (merge) {
            form.append(node('p', '', `Unificar ${record.name} em outro item move seus eventos, coletas e nomes alternativos para o destino. Confira se representam o mesmo local ou material.`));
            field(form, 'targetId', 'Manter como cadastro principal', {
              choices: [{ id: '', name: 'Selecione o destino' }, ...catalogs[view].filter(item => item.id !== id)], required: true });
          } else {
            field(form, 'name', 'Nome principal', { value: record.name || '', required: true, maxlength: 255 });
            field(form, 'aliases', 'Outros nomes (separados por vírgula)', { value: record.aliases?.join(', ') || '', maxlength: 12800 });
            form.append(node('p', 'hint', 'Inclua a cidade no nome de praias homônimas. Nomes antigos continuam reconhecidos após a edição.'));
          }
        } else if (view === 'events') {
          field(form, 'title', 'Título', { value: record.title || '', required: true, maxlength: 255 });
          field(form, 'location', 'Praia', { value: record.beachId ?? '', required: true, choices: choices('beaches') });
          field(form, 'dateTime', 'Data e hora', { type: 'datetime-local', value: record.dateTime || '', required: true, step: 1, min: '1900-01-01T00:00', max: '9999-12-31T23:59:59' });
          field(form, 'status', 'Status', { value: record.status || 'Agendado', choices: id == null ? ['Agendado'] : transitions[record.status] });
          form.append(node('p', 'hint', 'Novos eventos começam como Agendado. Datas passadas são permitidas para histórico. Praia/data não podem conflitar com coletas vinculadas.'));
        } else {
          field(form, 'beach', 'Praia', { value: record.beachId ?? '', required: true, choices: choices('beaches') });
          field(form, 'materialType', 'Material', { value: record.materialId ?? '', required: true, choices: choices('materials') });
          field(form, 'weightKg', 'Peso (kg)', { type: 'number', value: record.weightKg ?? '', required: true, min: '0.001', max: '100000', step: '0.001', inputmode: 'decimal' });
          field(form, 'registrationDate', 'Data da coleta', { type: 'date', value: record.registrationDate || '', required: true, min: '1900-01-01', max: api.dataRules.today() });
          field(form, 'volunteerId', 'ID do proprietário (opcional)', { type: 'number', value: record.volunteer?.id ?? '', min: 1, step: 1 });
          const eventInput = field(form, 'eventId', 'ID do evento (opcional)', { type: 'number', value: record.event?.id ?? '', min: 1, step: 1 });
          eventInput.addEventListener('change', async () => {
            const eventId = eventInput.value;
            if (!eventId) return;
            try {
              const linked = await api.admin.event(Number(eventId));
              if (!current(session) || state.editing !== editingContext || eventInput.value !== eventId || !form.isConnected) return;
              form.elements.beach.value = linked.beachId ?? '';
              form.elements.registrationDate.value = linked.dateTime?.slice(0, 10) || '';
            } catch (error) { if (current(session)) message(error.message, true); }
          });
          form.append(node('p', 'hint', 'Peso: 0,001 a 100.000 kg, até 3 casas decimais. Data: de 1900 a hoje em São Paulo. Ao escolher um evento, confira sua praia e data. Para corrigir legado, selecione referências válidas ou remova o vínculo.'));
        }
        const error = node('p', 'error');
        error.id = 'admin-form-error';
        error.setAttribute('role', 'alert');
        error.hidden = true;
        const submit = node('button', 'button primary full', 'Salvar');
        submit.type = 'submit';
        form.append(error, submit);
        if (id != null) {
          form.append(action('Recarregar registro atual', () => {
            if (window.confirm('Descartar o formulário e carregar os dados atuais?')) return edit(view, id, merge);
          }));
          form.append(node('p', 'hint', 'Após um conflito, recarregue e revise os dados antes de salvar. Alterações salvas desde a abertura do formulário impedem o envio até você recarregar e revisar.'));
        }
        if (!$('admin-dialog').open) $('admin-dialog').showModal();
      } catch (error) { if (current(session)) message(error.message, true); }
    }
    async function remove(view, record) {
      if (!authorized() || !window.confirm(`Excluir ${view === 'events' ? 'evento' : 'coleta'} #${record.id}? Esta ação não pode ser desfeita.`)) return;
      const session = api.session();
      try {
        await api.admin[view === 'events' ? 'deleteEvent' : 'deleteCollection'](record.id, record.version);
        if (!current(session)) return;
        const refreshed = await load();
        if (current(session)) message(refreshed ? 'Registro excluído.' : 'Registro excluído, mas a lista não foi atualizada. Tente recarregar.', !refreshed);
      } catch (error) { if (current(session)) message(error.message, true); }
    }
    $('admin-form').addEventListener('submit', event => {
      event.preventDefault();
      if (!authorized() || !state.editing) return;
      const session = api.session();
      const editing = state.editing;
      const form = event.currentTarget;
      const input = Object.fromEntries(new FormData(form));
      if (editing.id != null) input.version = editing.version;
      for (const key of ['title', 'location', 'beach', 'materialType']) if (key in input) input[key] = input[key].trim();
      const errorBox = $('admin-form-error');
      const showError = text => { errorBox.textContent = text; errorBox.hidden = !text; };
      if (isCatalog(editing.view)) {
        if (editing.merge) {
          const target = editing.targets.find(item => item.id === Number(input.targetId));
          if (!target) { showError('Selecione o destino.'); return; }
          input.targetId = target.id;
          input.targetVersion = target.version;
          if (!window.confirm('Confirmar a unificação dos registros e de seus vínculos?')) return;
        } else {
          input.name = input.name.trim();
          input.aliases = input.aliases.split(',').map(value => value.trim()).filter(Boolean);
        }
      } else {
        for (const [name, target] of [['location', 'beachId'], ['beach', 'beachId'], ['materialType', 'materialId']]) {
          if (name in input) { input[target] = Number(input[name]); delete input[name]; }
        }
        if (!input.beachId || (editing.view === 'collections' && !input.materialId)) {
          showError('Selecione praia e material nos catálogos.'); return;
        }
      }
      if (['title', 'location', 'beach', 'materialType'].some(key => key in input && !input[key])) {
        showError('Preencha os campos obrigatórios com texto.'); return;
      }
      if (editing.view === 'collections') {
        if (!api.dataRules.weightValid(input.weightKg) || !api.dataRules.dateValid(input.registrationDate, api.dataRules.today())) {
          showError('Informe peso de 0,001 a 100.000 kg com até 3 casas e data entre 1900 e hoje (São Paulo).'); return;
        }
        input.weightKg = Number(input.weightKg);
        if (!Number.isFinite(input.weightKg) || input.weightKg <= 0) { showError('Informe um peso finito maior que zero.'); return; }
        for (const [key, target] of [['volunteerId', 'volunteer'], ['eventId', 'event']]) {
          const id = input[key] === '' ? null : Number(input[key]);
          if (id !== null && (!Number.isSafeInteger(id) || id <= 0)) { showError('Informe IDs inteiros positivos.'); return; }
          input[target] = id === null ? null : { id };
          delete input[key];
        }
      }
      void busy(form.querySelector('[type=submit]'), async () => {
        showError('');
        try {
          if (isCatalog(editing.view)) {
            if (editing.merge) await api.admin.mergeCatalog(editing.view, editing.id, input);
            else await api.admin.saveCatalog(editing.view, editing.id, input);
          } else {
            if (editing.view === 'collections' && input.event) {
              const linked = await api.admin.event(input.event.id);
              if (!current(session) || state.editing !== editing) return;
              if (input.beachId !== linked.beachId || input.registrationDate !== linked.dateTime?.slice(0, 10)) {
                showError('A coleta deve usar a mesma praia e data do evento. Revise os campos ou remova o vínculo.'); return;
              }
            }
            await api.admin[editing.view === 'events' ? 'saveEvent' : 'saveCollection'](editing.id, input);
          }
          if (!current(session)) return;
          if (state.editing === editing) $('admin-dialog').close();
          const refreshed = await load();
          if (current(session)) message(refreshed ? 'Registro salvo. Os totais são calculados a partir das coletas atuais.'
            : 'Registro salvo, mas a lista não foi atualizada. Tente recarregar.', !refreshed);
        } catch (error) { if (current(session) && state.editing === editing) showError(error.message); }
      });
    });
    $('admin-dialog').addEventListener('close', () => { state.editing = null; $('admin-form').replaceChildren(); });
    document.querySelectorAll('[data-admin-view]').forEach(button => button.addEventListener('click', () => {
      if (!authorized()) return;
      state.view = button.dataset.adminView;
      state.page = 0;
      configure();
      message();
      void load();
    }));
    $('admin-create').addEventListener('click', () => void edit(state.view));
    $('admin-filters').addEventListener('submit', event => { event.preventDefault(); state.page = 0; message(); void load(); });
    $('admin-previous').addEventListener('click', () => { if (!state.loading && state.page > 0) { state.page--; void load(); } });
    $('admin-next').addEventListener('click', () => { if (!state.loading && state.page + 1 < state.pages) { state.page++; void load(); } });
    function reset() {
      state.generation++;
      state.active = false;
      state.loading = false;
      state.page = 0;
      state.pages = 0;
      state.view = 'events';
      state.editing = null;
      $('admin-dialog').close();
      $('admin-form').replaceChildren();
      $('admin-results').replaceChildren();
      $('admin-pagination').hidden = true;
      $('admin-workspace').hidden = true;
      message();
      configure();
    }
    configure();
    return { load, reset, setActive(active) {
      if (!active && state.active) reset();
      state.active = active;
      $('admin-workspace').hidden = !active;
    } };
  };
})();

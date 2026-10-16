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
  window.createAdminPortal = (api, { node, action, busy, kg, date }) => {
    const $ = id => document.getElementById(id);
    const state = { active: false, view: 'events', page: 0, pages: 0, generation: 0, loading: false, editing: null };
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
        const option = node('option', '', choice === 'Concluido' ? 'Concluído' : choice || 'Todos');
        option.value = choice;
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
      if (state.view === 'events') {
        field(form, 'location', 'Praia ou local', { maxlength: 255 });
        field(form, 'status', 'Status', { choices: ['', ...statuses] });
      } else if (state.view === 'users') {
        field(form, 'name', 'Nome', { maxlength: 255 });
        field(form, 'email', 'E-mail contém', { maxlength: 255 });
      } else {
        field(form, 'beach', 'Praia', { maxlength: 255 });
        field(form, 'materialType', 'Material', { maxlength: 255 });
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
      $('admin-title').textContent = state.view === 'users' ? 'Consultar usuários'
        : state.view === 'events' ? 'Gerenciar eventos' : 'Gerenciar coletas';
      $('admin-create').hidden = state.view === 'users';
      $('admin-create').textContent = state.view === 'events' ? '+ Criar evento' : '+ Registrar coleta';
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
        const values = Object.fromEntries(new FormData($('admin-filters')));
        const result = await api.admin[view]({ ...values, page: state.page });
        if (!current(session) || generation !== state.generation) return false;
        // A deletion can remove the last record of the current page.
        if (state.page > 0 && state.page >= result.totalPages) {
          state.page = Math.max(0, result.totalPages - 1);
          return load();
        }
        state.pages = result.totalPages;
        if (!result.content.length) empty('Nenhum registro encontrado. Revise os filtros ou cadastre um registro.');
        else $('admin-results').replaceChildren(...result.content.map(record => card(record, view)));
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
    async function edit(view, id = null) {
      if (!authorized()) return;
      const session = api.session();
      const generation = state.generation;
      try {
        const record = id == null ? {} : await api.admin[view === 'events' ? 'event' : 'collection'](id);
        if (!current(session) || generation !== state.generation) return;
        state.editing = { view, id, version: record.version };
        const form = $('admin-form');
        form.replaceChildren();
        $('admin-editor-title').textContent = `${id == null ? 'Criar' : 'Editar'} ${view === 'events' ? 'evento' : 'coleta'}${id == null ? '' : ` #${id}`}`;
        if (view === 'events') {
          field(form, 'title', 'Título', { value: record.title || '', required: true, maxlength: 255 });
          field(form, 'location', 'Local', { value: record.location || '', required: true, maxlength: 255 });
          field(form, 'dateTime', 'Data e hora', { type: 'datetime-local', value: record.dateTime || '', required: true, step: 1 });
          field(form, 'status', 'Status', { value: record.status || 'Agendado', choices: id == null ? ['Agendado'] : transitions[record.status] });
          form.append(node('p', 'hint', 'Novos eventos começam como Agendado. As opções seguem as transições permitidas pela API.'));
        } else {
          field(form, 'beach', 'Praia', { value: record.beach || '', required: true, maxlength: 255 });
          field(form, 'materialType', 'Material', { value: record.materialType || '', required: true, maxlength: 255 });
          field(form, 'weightKg', 'Peso (kg)', { type: 'number', value: record.weightKg ?? '', required: true, min: 0, step: 'any', inputmode: 'decimal' });
          field(form, 'registrationDate', 'Data da coleta', { type: 'date', value: record.registrationDate || '', required: true });
          field(form, 'volunteerId', 'ID do proprietário (opcional)', { type: 'number', value: record.volunteer?.id ?? '', min: 1, step: 1 });
          field(form, 'eventId', 'ID do evento (opcional)', { type: 'number', value: record.event?.id ?? '', min: 1, step: 1 });
          form.append(node('p', 'hint', 'Consulte os IDs nas abas Usuários e Eventos. Deixe vazio para remover o vínculo. Novos vínculos exigem evento Agendado ou Em andamento.'));
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
            if (window.confirm('Descartar o formulário e carregar os dados atuais?')) return edit(view, id);
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
        await api.admin[view === 'events' ? 'deleteEvent' : 'deleteCollection'](record.id);
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
      if (['title', 'location', 'beach', 'materialType'].some(key => key in input && !input[key])) {
        showError('Preencha os campos obrigatórios com texto.'); return;
      }
      if (editing.view === 'collections') {
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
          await api.admin[editing.view === 'events' ? 'saveEvent' : 'saveCollection'](editing.id, input);
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

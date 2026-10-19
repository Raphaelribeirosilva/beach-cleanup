(() => {
  'use strict';
  let states = [];
  let loading;
  const { node } = window.PortalUI;
  function ready() {
    if (!loading) loading = fetch('/locations.json').then(response => {
      if (!response.ok) throw new Error('Não foi possível carregar Estados e cidades. Tente novamente.');
      return response.json();
    }).then(data => { states = data; }).catch(error => { loading = null; throw error; });
    return loading;
  }
  function options(select, entries, title, value = select.value) {
    select.replaceChildren(...[{ id: '', name: title }, ...entries].map(entry => {
      const option = node('option', '', entry.name);
      option.value = String(entry.id);
      return option;
    }));
    select.value = entries.some(entry => String(entry.id) === String(value)) ? String(value) : '';
  }
  function field(form, name, title, required) {
    const label = node('label', '', title);
    const select = node('select');
    select.name = name;
    select.required = required;
    label.append(select);
    form.append(label);
    return select;
  }
  function cities(stateSelect, citySelect, value = '') {
    const values = states.find(state => state.uf === stateSelect.value)?.cities || [];
    options(citySelect, values.map(name => ({ id: name, name })), stateSelect.value ? 'Todas as cidades' : 'Selecione o Estado primeiro', value);
    citySelect.disabled = !stateSelect.value;
  }
  function geography(form, { required = false, state = '', city = '' } = {}) {
    const stateSelect = field(form, 'state', 'Estado', required);
    const citySelect = field(form, 'city', 'Cidade', required);
    options(stateSelect, states.map(item => ({ id: item.uf, name: `${item.name} (${item.uf})` })), required ? 'Selecione o Estado' : 'Todos os Estados', state);
    const sync = value => {
      cities(stateSelect, citySelect, value);
      if (required && stateSelect.value) citySelect.options[0].textContent = 'Selecione a cidade';
    };
    sync(city);
    stateSelect.addEventListener('change', () => sync(''));
    return { state: stateSelect, city: citySelect, set(uf, name) { stateSelect.value = uf || ''; sync(name); } };
  }
  function beaches(form, entries, { required = false, name = 'beachId', value = '', existing = false } = {}) {
    const selected = entries.find(entry => String(entry.id) === String(value));
    const geo = geography(form, { required: false, state: selected?.state, city: selected?.city });
    const beach = field(form, name, 'Praia', required);
    const hint = node('p', 'hint');
    form.append(hint);
    function sync(next = '') {
      const filtered = entries.filter(entry => (!geo.state.value || entry.state === geo.state.value)
        && (!geo.city.value || entry.city === geo.city.value));
      options(beach, filtered, required ? 'Selecione a praia' : 'Todas as praias', next);
      hint.textContent = filtered.length ? '' : 'Nenhuma praia cadastrada nesta localização. O administrador pode cadastrá-la na aba Praias.';
      hint.hidden = Boolean(filtered.length);
    }
    // Existing beaches without geography remain editable; no location is guessed.
    geo.state.required = required && !(existing && selected && !selected.state);
    geo.city.required = geo.state.required;
    geo.state.addEventListener('change', () => sync());
    geo.city.addEventListener('change', () => sync());
    sync(value);
    return { ...geo, beach, set(uf, city, id) { geo.set(uf, city); sync(id); } };
  }
  window.PortalLocations = { ready, geography, beaches };
})();

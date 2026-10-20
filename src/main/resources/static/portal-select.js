/* Searchable progressive enhancement: native selects remain the form's source of truth. */
(() => {
  'use strict';
  const widgets = new WeakMap();
  const { node } = window.PortalUI;
  let sequence = 0, active;
  const key = text => text.normalize('NFD').replace(/\p{M}/gu, '').toLocaleLowerCase('pt-BR');
  function enhance(select) {
    if (widgets.has(select) || !select.isConnected) return;
    const id = `choice-${++sequence}`;
    const label = select.labels?.[0];
    const title = [...(label?.childNodes || [])].filter(item => item.nodeType === Node.TEXT_NODE).map(item => item.textContent.trim()).join(' ') || select.name;
    const wrap = node('span', 'choice');
    const trigger = node('button', 'choice-trigger');
    trigger.type = 'button';
    trigger.setAttribute('aria-haspopup', 'listbox');
    trigger.setAttribute('aria-expanded', 'false');
    trigger.setAttribute('aria-controls', `${id}-list`);
    const text = node('span', 'choice-value');
    const arrow = node('span', 'choice-arrow', '⌄');
    arrow.setAttribute('aria-hidden', 'true');
    trigger.append(text, arrow);
    const popup = node('span', 'choice-popup');
    popup.setAttribute('popover', 'manual');
    const search = node('input', 'choice-search');
    search.type = 'search';
    search.placeholder = 'Digite para encontrar…';
    search.setAttribute('aria-label', `Pesquisar opções de ${title}`);
    search.setAttribute('role', 'combobox');
    search.setAttribute('aria-autocomplete', 'list');
    search.setAttribute('aria-expanded', 'true');
    search.setAttribute('aria-controls', `${id}-list`);
    const list = node('span', 'choice-list');
    list.id = `${id}-list`;
    list.setAttribute('role', 'listbox');
    list.setAttribute('aria-label', title);
    const count = node('span', 'choice-count');
    count.setAttribute('role', 'status');
    popup.append(search, list, count);
    select.after(wrap);
    wrap.append(trigger, popup);
    select.classList.add('choice-native');
    select.tabIndex = -1;
    select.setAttribute('aria-hidden', 'true');
    let matches = [], cursor = 0, opened = false;
    function position() {
      const rect = trigger.getBoundingClientRect();
      const below = innerHeight - rect.bottom - 12;
      const above = rect.top - 12;
      const height = Math.min(360, Math.max(below, above), innerHeight - 32);
      const width = Math.min(Math.max(rect.width, 280), innerWidth - 32);
      popup.style.width = `${width}px`;
      popup.style.maxHeight = `${height}px`;
      popup.style.left = `${Math.max(16, Math.min(rect.left, innerWidth - width - 16))}px`;
      popup.style.top = `${below >= Math.min(300, above) ? rect.bottom + 7 : Math.max(12, rect.top - height - 7)}px`;
    }
    function close(restore = false) {
      if (!opened) return;
      opened = false;
      if (popup.isConnected && popup.matches(':popover-open')) popup.hidePopover();
      trigger.setAttribute('aria-expanded', 'false');
      if (active?.select === select) active = null;
      if (restore && trigger.isConnected) trigger.focus();
    }
    function highlight() {
      [...list.children].forEach((option, index) => option.classList.toggle('is-focused', index === cursor));
      const current = list.children[cursor];
      if (current) { search.setAttribute('aria-activedescendant', current.id); current.scrollIntoView({ block: 'nearest' }); }
      else search.removeAttribute('aria-activedescendant');
    }
    function choose(option) {
      select.value = option.value;
      select.dispatchEvent(new Event('input', { bubbles: true }));
      select.dispatchEvent(new Event('change', { bubbles: true }));
      close(true);
    }
    function render() {
      const all = [...select.options].filter(option => !option.disabled && key(option.textContent).includes(key(search.value)));
      matches = all.slice(0, 80);
      cursor = Math.max(0, matches.findIndex(option => option.selected));
      list.replaceChildren(...matches.map((option, index) => {
        const row = node('span', 'choice-option');
        row.id = `${id}-${index}`;
        row.setAttribute('role', 'option');
        row.setAttribute('aria-selected', String(option.selected));
        row.append(node('span', '', option.textContent), node('span', 'choice-check', option.selected ? '✓' : ''));
        row.addEventListener('pointerdown', event => event.preventDefault());
        row.addEventListener('click', event => { event.preventDefault(); choose(option); });
        return row;
      }));
      count.textContent = !all.length ? 'Nenhuma opção encontrada. Tente outro nome.' : all.length > 80
        ? `${all.length} opções · Digite para refinar a busca` : `${all.length} ${all.length === 1 ? 'opção disponível' : 'opções disponíveis'}`;
      highlight();
    }
    function refresh() {
      text.textContent = select.selectedOptions[0]?.textContent || 'Selecione';
      trigger.disabled = select.disabled;
      trigger.setAttribute('aria-label', `${title}: ${text.textContent}`);
      trigger.classList.toggle('is-placeholder', !select.value);
      if (select.disabled) close();
      else if (opened) render();
    }
    function open() {
      if (select.disabled || opened) return;
      active?.close();
      opened = true;
      active = { select, wrap, close, position };
      search.value = '';
      popup.showPopover();
      trigger.setAttribute('aria-expanded', 'true');
      position(); render(); search.focus({ preventScroll: true });
    }
    trigger.addEventListener('click', event => { event.preventDefault(); opened ? close() : open(); });
    trigger.addEventListener('keydown', event => {
      if (['ArrowDown', 'ArrowUp'].includes(event.key)) { event.preventDefault(); open(); }
    });
    search.addEventListener('input', render);
    popup.addEventListener('keydown', event => {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(true); }
      if (event.key === 'Tab') close();
      if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
        event.preventDefault();
        cursor = matches.length ? (cursor + (event.key === 'ArrowDown' ? 1 : -1) + matches.length) % matches.length : 0;
        highlight();
      }
      if (event.key === 'Enter') { event.preventDefault(); if (matches[cursor]) choose(matches[cursor]); }
    });
    select.addEventListener('change', refresh);
    select.addEventListener('focus', () => trigger.focus());
    select.addEventListener('invalid', event => { event.preventDefault(); trigger.focus(); open(); });
    const descriptor = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
    Object.defineProperty(select, 'value', { configurable: true, get() { return descriptor.get.call(this); },
      set(value) { descriptor.set.call(this, value); refresh(); } });
    widgets.set(select, { refresh });
    refresh();
  }
  function scan() {
    if (active && !active.select.isConnected) active.close();
    document.querySelectorAll('select').forEach(select => {
      if (!widgets.has(select)) enhance(select);
      else widgets.get(select).refresh();
    });
  }
  let queued = false;
  const observer = new MutationObserver(records => {
    if (!records.some(record => record.target instanceof HTMLSelectElement || record.target instanceof HTMLOptionElement
      || [...record.addedNodes].some(item => item.nodeType === 1 && (item.matches('select') || item.querySelector('select'))))) return;
    if (!queued) { queued = true; queueMicrotask(() => { queued = false; scan(); }); }
  });
  observer.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['disabled', 'required'] });
  document.addEventListener('pointerdown', event => { if (active && !active.wrap.contains(event.target)) active.close(); });
  document.addEventListener('scroll', () => active?.position(), true);
  window.addEventListener('resize', () => active?.position());
  document.addEventListener('reset', () => queueMicrotask(scan));
  scan();
})();

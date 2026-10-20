/* Shared DOM, async controls and accessible dialog behavior. Native dialogs contain keyboard focus. */
(() => {
  'use strict';
  const openers = new WeakMap();
  function node(tag, className, content) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (content !== undefined) element.textContent = content;
    return element;
  }
  function focus(element) {
    if (!element?.isConnected || !element.getClientRects().length) return;
    if (!element.matches('button,input,select,a[href]')) element.tabIndex = -1;
    element.focus({ preventScroll: true });
    element.scrollIntoView({ block: 'nearest' });
  }
  function errorAt(element, message = '') {
    element.textContent = message;
    element.hidden = !message;
    if (message) focus(element);
  }
  async function busy(button, operation) {
    if (button.disabled) return;
    const hadFocus = document.activeElement === button;
    button.disabled = true;
    button.setAttribute('aria-busy', 'true');
    const previous = button.textContent;
    button.textContent = 'Aguarde…';
    try { return await operation(); }
    finally {
      button.disabled = false;
      button.removeAttribute('aria-busy');
      if (button.textContent === 'Aguarde…') button.textContent = previous;
      if (hadFocus && document.activeElement === document.body) focus(button);
    }
  }
  function action(label, handler, primary = false) {
    const button = node('button', `button ${primary ? 'primary' : 'outline'}`, label);
    button.type = 'button';
    button.addEventListener('click', () => void busy(button, handler));
    return button;
  }
  function openDialog(dialog) {
    if (dialog.open) return;
    openers.set(dialog, document.activeElement);
    dialog.showModal();
    const first = [...dialog.querySelectorAll('form input,form select,form button')]
      .find(element => !element.disabled && element.type !== 'hidden' && element.getClientRects().length);
    focus(first || dialog.querySelector('button'));
  }
  document.querySelectorAll('dialog').forEach(dialog => dialog.addEventListener('close', () => {
    const previous = openers.get(dialog);
    focus(previous?.isConnected && previous.getClientRects().length ? previous : document.getElementById('workspace-title'));
  }));
  function materials(form, entries) {
    const group = node('fieldset', 'material-options');
    group.append(node('legend', '', 'Materiais coletados'),
      node('p', 'hint', 'Marque um ou mais materiais e informe o peso de cada um em kg.'));
    if (!entries.length) group.append(node('p', 'notice', 'Nenhum material disponível. Solicite o cadastro na administração.'));
    entries.forEach(entry => {
      const row = node('div', 'material-row');
      const label = node('label', 'material-check');
      const check = node('input');
      check.type = 'checkbox'; check.name = 'materialType'; check.value = entry.id;
      label.append(check, node('span', '', entry.name));
      const weightLabel = node('label', '', `Peso de ${entry.name} (kg)`);
      const weight = node('input');
      Object.assign(weight, { type: 'number', name: `weight-${entry.id}`, min: '0.001', max: '100000', step: '0.001', disabled: true });
      weight.dataset.materialWeight = '';
      weight.setAttribute('inputmode', 'decimal');
      weightLabel.append(weight);
      check.addEventListener('change', () => {
        weight.disabled = !check.checked;
        weight.required = check.checked;
        weight.name = check.checked ? 'weightKg' : `weight-${entry.id}`;
      });
      row.append(label, weightLabel); group.append(row);
    });
    form.append(group);
    return group;
  }
  function materialValues(form) {
    return [...form.querySelectorAll('.material-row')].filter(row => row.querySelector('[type=checkbox]').checked)
      .map(row => ({ materialId: Number(row.querySelector('[type=checkbox]').value), weightKg: row.querySelector('[data-material-weight]').value }));
  }
  const eventStatusLabel = value => ['concluido', 'concluído', 'finalizado'].includes(String(value).trim().toLowerCase()) ? 'Finalizado' : value;
  window.PortalUI = { node, busy, action, focus, errorAt, openDialog, materials, materialValues, eventStatusLabel };
})();

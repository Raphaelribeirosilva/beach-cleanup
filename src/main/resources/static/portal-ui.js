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
  window.PortalUI = { node, busy, action, focus, errorAt, openDialog };
})();

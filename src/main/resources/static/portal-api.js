(function (root) {
  'use strict';
  const SESSION_KEY = 'beachcleanup.session.v1';
  function browserStorage() {
    try { return root.sessionStorage; } catch (_) { return null; }
  }
  function createPortalApi(fetcher, onSessionExpired = () => {}, {
    storage = browserStorage(), now = () => Date.now(),
    schedule = setTimeout, cancel = clearTimeout
  } = {}) {
    let session = null;
    let expiresAt = 0;
    let expiryTimer;
    let loginGeneration = 0;
    function clearSession() {
      loginGeneration++;
      session = null;
      expiresAt = 0;
      cancel(expiryTimer);
      try { storage?.removeItem(SESSION_KEY); } catch (_) { /* storage may be disabled */ }
    }
    function expire() {
      if (!session) return;
      clearSession();
      onSessionExpired();
    }
    function currentSession() {
      if (session && now() >= expiresAt) expire();
      return session;
    }
    function armExpiry() {
      cancel(expiryTimer);
      expiryTimer = schedule(() => {
        if (now() >= expiresAt) expire();
        else armExpiry();
      }, Math.min(Math.max(expiresAt - now(), 1), 2147483647));
      expiryTimer?.unref?.();
    }
    function validSession(value) {
      return value && typeof value.accessToken === 'string' && value.accessToken.length > 0
        && value.tokenType === 'Bearer' && Number.isSafeInteger(value.userId) && value.userId > 0
        && ['ADMIN', 'VOLUNTEER'].includes(value.role)
        && Number.isFinite(value.expiresIn) && value.expiresIn > 0 && value.expiresIn <= 1800;
    }
    try {
      const saved = JSON.parse(storage?.getItem(SESSION_KEY) || 'null');
      if (validSession(saved?.session) && Number.isFinite(saved.expiresAt)
          && saved.expiresAt > now() && saved.expiresAt <= now() + 1800000) {
        session = saved.session;
        expiresAt = saved.expiresAt;
        armExpiry();
      } else clearSession();
    } catch (_) { clearSession(); }
    async function request(path, { method = 'GET', body, authenticated = false } = {}) {
      const current = currentSession();
      if (authenticated && !current) throw new Error('Entre na sua conta para continuar.');
      const headers = { Accept: 'application/json' };
      if (body !== undefined) headers['Content-Type'] = 'application/json';
      if (authenticated) headers.Authorization = `Bearer ${current.accessToken}`;
      let response;
      try {
        response = await fetcher(path, { method, headers, cache: 'no-store', credentials: 'omit',
          ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
      } catch (_) {
        throw new Error('Não foi possível conectar. Verifique sua conexão e tente novamente.');
      }
      const data = response.status === 204 ? null : await response.json().catch(() => null);
      if (!response.ok) {
        if (response.status === 401 && authenticated && session === current) {
          expire();
        }
        const messages = {
          401: authenticated ? 'Sua sessão expirou. Entre novamente para continuar.' : 'E-mail ou senha inválidos.',
          403: 'Você não tem permissão para realizar esta ação.',
          404: 'Este registro não está mais disponível.',
          409: 'Os dados mudaram ou a operação conflita com registros existentes. Atualize e tente novamente.'
        };
        const fields = Array.isArray(data?.errors) ? data.errors.map(error => error.message).filter(Boolean) : [];
        const message = fields.length ? fields.join(' ') : response.status >= 500
          ? 'O serviço está indisponível no momento. Tente novamente em instantes.'
          : response.status === 401 ? messages[401] : data?.message || messages[response.status] || 'Não foi possível concluir a ação.';
        const error = new Error(message);
        error.status = response.status;
        throw error;
      }
      // An older response must not restore data belonging to a session that has ended.
      if (authenticated && currentSession() !== current) throw new Error('A sessão mudou. Entre novamente para continuar.');
      return data;
    }
    function query(path, values) {
      const params = new URLSearchParams();
      for (const [key, value] of Object.entries(values)) {
        if (value !== '' && value !== null && value !== undefined) params.set(key, String(value));
      }
      return `${path}?${params}`;
    }
    function adminRequest(path, options = {}) {
      if (currentSession()?.role !== 'ADMIN') {
        const error = new Error('Esta área é exclusiva de administradores.');
        error.status = 403;
        return Promise.reject(error);
      }
      return request(path, { ...options, authenticated: true });
    }
    return {
      admin: {
        events: filters => adminRequest(query('/cleanupEvent/search', { size: 6, ...filters })),
        users: filters => adminRequest(query('/users/search', { size: 6, ...filters })),
        collections: filters => adminRequest(query('/trashCollection/search', { size: 6, ...filters })),
        event: id => adminRequest(`/cleanupEvent/${id}`),
        collection: id => adminRequest(`/trashCollection/${id}`),
        saveEvent: (id, input) => adminRequest(id == null ? '/cleanupEvent' : `/cleanupEvent/${id}`,
          { method: id == null ? 'POST' : 'PUT', body: input }),
        deleteEvent: id => adminRequest(`/cleanupEvent/${id}`, { method: 'DELETE' }),
        saveCollection: (id, input) => adminRequest(id == null ? '/trashCollection' : `/trashCollection/${id}`,
          { method: id == null ? 'POST' : 'PUT', body: input }),
        deleteCollection: id => adminRequest(`/trashCollection/${id}`, { method: 'DELETE' })
      },
      session: currentSession,
      logout: clearSession,
      signup: input => request('/users', { method: 'POST', body: input }),
      async login(input) {
        const generation = ++loginGeneration;
        const result = await request('/auth/login', { method: 'POST', body: input });
        if (generation !== loginGeneration) throw new Error('A sessão mudou. Entre novamente para continuar.');
        if (!validSession(result)) throw new Error('Não foi possível iniciar a sessão. Tente entrar novamente.');
        session = result;
        expiresAt = now() + result.expiresIn * 1000;
        try {
          storage?.removeItem(SESSION_KEY);
          storage?.setItem(SESSION_KEY, JSON.stringify({ session, expiresAt }));
        }
        catch (_) { /* login remains available in memory when storage is unavailable */ }
        armExpiry();
        return result;
      },
      profile: () => request(`/users/${session?.userId}`, { authenticated: true }),
      events: filters => request(query('/cleanupEvent/search', { size: 6, sortBy: 'dateTime', direction: 'asc', ...filters })),
      registrations: () => request('/users/me/events', { authenticated: true }),
      register: id => request(`/users/me/events/${id}`, { method: 'PUT', authenticated: true }),
      cancel: id => request(`/users/me/events/${id}`, { method: 'DELETE', authenticated: true }),
      collections: filters => request(query('/trashCollection/search', {
        size: 6, sortBy: 'registrationDate', direction: 'desc', ...filters,
        volunteerId: session?.userId
      }), { authenticated: true }),
      createCollection: input => request('/trashCollection', { method: 'POST', body: input, authenticated: true })
    };
  }
  if (typeof module !== 'undefined' && module.exports) module.exports = { createPortalApi };
  else root.createPortalApi = createPortalApi;
})(globalThis);

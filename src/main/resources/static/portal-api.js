(function (root) {
  'use strict';
  const SESSION_KEY = 'beachcleanup.session.v2';
  const MAX_SESSION_MS = 7 * 24 * 60 * 60 * 1000;
  function browserStorage() {
    try { return root.sessionStorage; } catch (_) { return null; }
  }
  function createPortalApi(fetcher, onSessionExpired = () => {}, {
    storage = browserStorage(), now = () => Date.now(), schedule = setTimeout, cancel = clearTimeout
  } = {}) {
    let session = null, expiresAt = 0, refreshExpiresAt = 0, expiryTimer;
    let loginGeneration = 0, refreshing = null;
    const pendingRevocations = new Set();
    function clearSession() {
      loginGeneration++;
      session = null; expiresAt = 0; refreshExpiresAt = 0;
      cancel(expiryTimer);
      try { storage?.removeItem(SESSION_KEY); } catch (_) { /* disabled storage */ }
    }
    function expire() {
      if (!session) return;
      clearSession(); onSessionExpired();
    }
    function currentSession() {
      if (session && now() >= refreshExpiresAt) expire();
      return session;
    }
    function validSession(value) {
      return value && typeof value.accessToken === 'string' && value.accessToken.length > 0
        && value.tokenType === 'Bearer' && Number.isSafeInteger(value.userId) && value.userId > 0
        && ['ADMIN', 'VOLUNTEER'].includes(value.role)
        && Number.isFinite(value.expiresIn) && value.expiresIn > 0 && value.expiresIn <= 1800
        && typeof value.refreshToken === 'string' && /^[A-Za-z0-9_-]{43}$/.test(value.refreshToken)
        && Number.isFinite(value.refreshExpiresIn) && value.refreshExpiresIn > 0
        && value.refreshExpiresIn <= MAX_SESSION_MS / 1000;
    }
    function persist() {
      try { storage?.setItem(SESSION_KEY, JSON.stringify({ session, expiresAt, refreshExpiresAt })); }
      catch (_) { /* credentials remain in memory */ }
    }
    function armExpiry() {
      cancel(expiryTimer);
      const lastAccess = expiresAt >= refreshExpiresAt - 1000;
      expiryTimer = schedule(() => {
        if (!currentSession()) return;
        void renew().catch(() => {});
      }, Math.max((lastAccess ? refreshExpiresAt : expiresAt - 30000) - now(), 1000));
      expiryTimer?.unref?.();
    }
    function accept(result, deadline = now() + result.refreshExpiresIn * 1000) {
      session = result;
      refreshExpiresAt = Math.min(deadline, now() + result.refreshExpiresIn * 1000);
      expiresAt = Math.min(now() + result.expiresIn * 1000, refreshExpiresAt);
      persist(); armExpiry();
    }
    try {
      // Old non-renewable sessions must authenticate again after this upgrade.
      storage?.removeItem('beachcleanup.session.v1');
      const saved = JSON.parse(storage?.getItem(SESSION_KEY) || 'null');
      if (validSession(saved?.session) && Number.isFinite(saved.expiresAt)
          && Number.isFinite(saved.refreshExpiresAt) && saved.expiresAt <= now() + 1800000
          && saved.expiresAt > 0 && saved.refreshExpiresAt > now()
          && saved.refreshExpiresAt <= now() + MAX_SESSION_MS && saved.expiresAt <= saved.refreshExpiresAt) {
        session = saved.session; expiresAt = saved.expiresAt; refreshExpiresAt = saved.refreshExpiresAt; armExpiry();
      } else clearSession();
    } catch (_) { clearSession(); }
    async function revoke(refreshToken) {
      pendingRevocations.add(refreshToken);
      await request('/auth/logout', { method: 'POST', body: { refreshToken } });
      pendingRevocations.delete(refreshToken);
    }
    async function logout() {
      const refreshToken = session?.refreshToken;
      if (refreshToken) pendingRevocations.add(refreshToken);
      clearSession();
      const results = await Promise.allSettled([...pendingRevocations].map(revoke));
      if (results.some(result => result.status === 'rejected'))
        throw new Error('Sua sessão local foi removida, mas não foi possível encerrar no servidor. Tente encerrar novamente.');
    }
    function renew() {
      if (refreshing?.generation === loginGeneration) return refreshing.promise;
      const current = currentSession();
      if (!current) return Promise.reject(new Error('Entre na sua conta para continuar.'));
      const generation = loginGeneration, deadline = refreshExpiresAt;
      const task = { generation };
      task.promise = (async () => {
        try {
          const result = await request('/auth/refresh', { method: 'POST', body: { refreshToken: current.refreshToken } });
          if (generation !== loginGeneration) {
            // Logout can revoke by the old token too; this also covers a cancelled login/refresh response.
            if (validSession(result)) void revoke(result.refreshToken).catch(() => {});
            throw new Error('A sessão mudou. Entre novamente para continuar.');
          }
          if (!validSession(result) || result.userId !== current.userId) throw new Error('Resposta de renovação inválida.');
          accept(result, deadline);
          return session;
        } catch (error) {
          if (generation === loginGeneration) expire();
          throw error;
        } finally { if (refreshing === task) refreshing = null; }
      })();
      refreshing = task;
      return task.promise;
    }
    async function request(path, { method = 'GET', body, authenticated = false } = {}) {
      let current = currentSession();
      const generation = loginGeneration;
      if (authenticated && !current) throw new Error('Entre na sua conta para continuar.');
      if (authenticated && (now() >= expiresAt || (now() >= expiresAt - 30000 && expiresAt < refreshExpiresAt - 1000))) current = await renew();
      if (authenticated && generation !== loginGeneration) throw new Error('A sessão mudou. Entre novamente para continuar.');
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
        if (response.status === 401 && authenticated && generation === loginGeneration && session === current) expire();
        const messages = {
          401: authenticated || path === '/auth/refresh' ? 'Sua sessão expirou ou foi encerrada. Entre novamente para continuar.' : 'E-mail ou senha inválidos.',
          403: 'Você não tem permissão para realizar esta ação.',
          404: 'Este registro não está mais disponível.',
          409: 'Os dados mudaram ou a operação conflita com registros existentes. Atualize e tente novamente.'
        };
        const fields = Array.isArray(data?.errors) ? data.errors.map(error => error.message).filter(Boolean) : [];
        const message = fields.length ? fields.join(' ') : response.status >= 500
          ? 'O serviço está indisponível no momento. Tente novamente em instantes.'
          : response.status === 401 ? messages[401] : data?.message || messages[response.status] || 'Não foi possível concluir a ação.';
        const error = new Error(message); error.status = response.status; throw error;
      }
      if (authenticated && (generation !== loginGeneration || !currentSession())) throw new Error('A sessão mudou. Entre novamente para continuar.');
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
        quality: filters => adminRequest(query('/admin/data-quality', { size: 6, ...filters })),
        rounding: filters => adminRequest(query('/admin/data-quality/rounding', { size: 6, ...filters })),
        saveCatalog: (kind, id, input) => adminRequest(`/catalog/${kind}${id == null ? '' : `/${id}`}`,
          { method: id == null ? 'POST' : 'PUT', body: input }),
        mergeCatalog: (kind, id, input) => adminRequest(`/catalog/${kind}/${id}/merge`, { method: 'POST', body: input }),
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
      dataRules: {
        today: (instant = new Date()) => {
          const parts = Object.fromEntries(new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Sao_Paulo',
            year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(instant).map(part => [part.type, part.value]));
          return `${parts.year}-${parts.month}-${parts.day}`;
        },
        weightValid: value => /^(?:\d+|\d*\.\d{1,3}0*)$/.test(String(value)) && Number(value) >= 0.001 && Number(value) <= 100000,
        dateValid: (value, today) => /^\d{4}-\d{2}-\d{2}$/.test(value) && value >= '1900-01-01' && value <= today
      },
      catalog: kind => request(`/catalog/${kind}`),
      logout,
      renew,
      signup: input => request('/users', { method: 'POST', body: input }),
      async login(input) {
        const generation = ++loginGeneration;
        const result = await request('/auth/login', { method: 'POST', body: input });
        if (generation !== loginGeneration) {
          if (validSession(result)) void revoke(result.refreshToken).catch(() => {});
          throw new Error('A sessão mudou. Entre novamente para continuar.');
        }
        if (!validSession(result)) throw new Error('Não foi possível iniciar a sessão. Tente entrar novamente.');
        accept(result);
        return result;
      },
      report: filters => request(query('/reports/impact', filters)),
      participation: filters => adminRequest(query('/reports/participation', filters)),
      updateProfile: input => request(`/users/${session?.userId}`, { method: 'PUT', body: input, authenticated: true }),
      changePassword: input => request('/auth/password', { method: 'PUT', body: input, authenticated: true }),
      updateCollection: (id, input) => request(`/trashCollection/${id}`, { method: 'PUT', body: input, authenticated: true }),
      collection: id => request(`/trashCollection/${id}`, { authenticated: true }),
      deleteCollection: id => request(`/trashCollection/${id}`, { method: 'DELETE', authenticated: true }),
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

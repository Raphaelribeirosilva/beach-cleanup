/* Impact report rendering and request lifecycle. */
(() => {
  'use strict';
  window.createImpactReports = (api, { node, kg, catalogChoices, isCurrent }) => {
    const $ = id => document.getElementById(id);
    let sequence = 0;
    async function eventChoices(generation) {
      const entries = [];
      let page = 0, pages = 1;
      while (page < pages) {
        const result = await api.events({ page, size: 100, sortBy: 'id', direction: 'asc' });
        if (!isCurrent(generation)) return null;
        entries.push(...result.content.map(event => ({ id: event.id,
          name: `${event.title} · ${event.dateTime ? event.dateTime.slice(0, 10).split('-').reverse().join('/') : 'Data não informada'} · ${event.location || 'Praia não informada'} (#${event.id})` })));
        pages = result.totalPages;
        page++;
      }
      return entries;
    }
    return async function loadReport(generation) {
      const request = ++sequence;
      const form = $('report-filters');
      const submit = form.querySelector('[type=submit]');
      submit.disabled = true;
      $('report-summary').setAttribute('aria-busy', 'true');
      $('report-summary').textContent = 'Carregando resultados…';
      $('report-results').setAttribute('aria-busy', 'true');
      const filters = Object.fromEntries(new FormData(form));
      $('report-results').textContent = 'Carregando relatório…';
      try {
        const [report, beaches, materials, events] = await Promise.all([
          api.report(filters), api.catalog('beaches'), api.catalog('materials'), eventChoices(generation)
        ]);
        if (!isCurrent(generation)) return false;
        catalogChoices(form.elements.beachId, beaches, 'Todas as praias');
        catalogChoices(form.elements.materialId, materials, 'Todos os materiais');
        catalogChoices(form.elements.eventId, events, 'Todos os eventos');
        const summary = $('report-summary');
        summary.replaceChildren();
        for (const [value, label] of [[`${kg(report.totals.weightKg)} kg`, 'Peso coletado'],
          [report.totals.collections, 'Coletas registradas'], [report.totals.collectors, 'Voluntários com coleta registrada']]) {
          const metric = node('div', 'report-metric');
          metric.append(node('strong', '', value), node('span', '', label));
          summary.append(metric);
        }
        const box = $('report-results');
        box.replaceChildren();
        const table = node('table', 'report-table');
        const caption = node('caption', '', `Coletas por ${form.elements.groupBy.selectedOptions[0].textContent.toLocaleLowerCase('pt-BR')}`);
        const head = node('thead'); const heading = node('tr');
        for (const label of ['Grupo', 'Quilos', 'Coletas', 'Voluntários com coleta']) { const th = node('th', '', label); th.scope = 'col'; heading.append(th); }
        head.append(heading); const body = node('tbody');
        const chart = node('div', 'report-chart');
        chart.append(node('h3', '', 'Quilos por grupo'));
        const max = report.rows.reduce((largest, row) => Math.max(largest, Number(row.weightKg)), 1);
        for (const row of report.rows) {
          const tr = node('tr');
          for (const value of [row.label, kg(row.weightKg), row.collections, row.collectors]) tr.append(node('td', '', value));
          body.append(tr);
          const label = node('label', 'report-bar', `${row.label}: ${kg(row.weightKg)} kg`);
          const meter = node('meter'); meter.min = 0; meter.max = max; meter.value = Number(row.weightKg);
          meter.setAttribute('aria-label', `${row.label}: ${kg(row.weightKg)} kg`); label.append(meter); chart.append(label);
        }
        table.append(caption, head, body);
        if (report.rows.length) {
          const scroll = node('div', 'report-table-scroll');
          scroll.tabIndex = 0;
          scroll.setAttribute('role', 'region');
          scroll.setAttribute('aria-label', 'Detalhamento das coletas; role horizontalmente se necessário');
          scroll.append(table);
          const detail = node('div', 'report-detail');
          detail.append(scroll, chart);
          box.append(detail);
        } else {
          const empty = node('div', 'empty');
          empty.append(node('strong', '', 'Nenhuma coleta para os filtros selecionados.'),
            node('p', '', 'Altere o período, a praia, o material ou o evento e consulte novamente.'));
          box.append(empty);
        }
        box.append(node('p', 'hint', 'Voluntários podem aparecer em vários grupos; o total conta cada pessoa uma única vez. Coletas sem responsável entram nos quilos e coletas, sem aumentar voluntários.'));
        if (api.session()?.role === 'ADMIN') {
          const section = node('section'); box.append(section);
          try {
            const { materialId, groupBy, ...eventFilters } = filters;
            const rows = await api.participation(eventFilters);
            if (!isCurrent(generation)) return false;
            section.append(node('h3', '', 'Inscrições por evento · acesso administrativo'),
              node('p', 'hint', 'Usa a data do evento, praia e evento selecionados. O filtro de material não se aplica às inscrições. Não representa presença nem pessoas com coleta.'));
            for (const row of rows) section.append(node('p', '', `#${row.eventId} ${row.title}: ${row.registeredParticipants} inscritos`));
            if (!rows.length) section.append(node('p', '', 'Nenhum evento no período.'));
          } catch (error) { if (isCurrent(generation)) section.textContent = error.message; }
        }
        return true;
      } catch (error) {
        if (isCurrent(generation)) {
          $('report-summary').replaceChildren();
          $('report-results').textContent = `${error.message} Use Consultar impacto para tentar novamente.`;
        }
        return false;
      } finally {
        if (request === sequence) { submit.disabled = false; $('report-results').setAttribute('aria-busy', 'false'); $('report-summary').setAttribute('aria-busy', 'false'); }
    }
  };
  };
})();

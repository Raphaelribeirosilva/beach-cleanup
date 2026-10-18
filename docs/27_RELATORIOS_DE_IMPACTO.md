# Relatórios de impacto

O portal oferece a aba **Relatórios de impacto**, acessível sem login. Os filtros combinam período inclusivo, praia e material pelos IDs dos catálogos e ID do evento. O agrupamento pode ser por praia, material, evento ou dia da coleta. Indicadores, tabela e gráfico de barras mostram os resultados, inclusive em celular.

## Consultas e regras

- `GET /reports/impact?from=2026-01-01&to=2026-01-31&beachId=1&groupBy=material`: consulta pública agregada. Parâmetros opcionais: `from`, `to`, `beachId`, `materialId`, `eventId`, `groupBy` (`beach`, `material`, `event`, `period`; padrão `beach`).
- `totals` contém `weightKg`, `collections` e `collectors`. `rows` contém os mesmos indicadores por grupo, sua `key` e seu `label`. As chaves são IDs convertidos para string ou datas ISO, nunca o texto usado como identificação do catálogo.
- A data considerada é `registrationDate`, sem conversão de fuso. Limites inicial e final são inclusivos. Sem período, considera todas as coletas. Intervalos invertidos, IDs não positivos, datas inválidas e agrupamentos desconhecidos retornam HTTP 400. Filtros sem correspondência retornam zeros e lista vazia.
- Quilos usam `BigDecimal`, preservando os valores decimais armazenados. As duas consultas agregadas executam na mesma transação com isolamento `REPEATABLE_READ`; o servidor não carrega todas as coletas para somar em Java.
- `collectors` conta responsáveis distintos com coleta registrada. Uma pessoa aparece uma única vez no total, mesmo quando possui coletas em vários grupos. **Não se somam os voluntários dos grupos para obter o total.**
- Coletas sem responsável contribuem para peso e quantidade de coletas, sem aumentar o número de voluntários. Coletas independentes aparecem no grupo **Sem evento**. Registros legados sem catálogo/data usam chave nula e rótulo explícito; pesos nulos não acrescentam quilos, mas o registro entra na contagem de coletas.
- Edições e exclusões são refletidas na próxima consulta; não existe total de relatório persistido ou cache de aplicação.

## Inscrições e permissões

`GET /reports/participation` é exclusivo de administradores. Aceita `from`, `to`, `beachId` e `eventId` e retorna `eventId`, `title` e `registeredParticipants` por evento, incluindo eventos sem inscritos ou coletas. Sem autenticação retorna 401; voluntário recebe 403.

Esta consulta usa a **data do evento**, pois uma inscrição não possui data de coleta nem material. O painel identifica essa diferença e informa que o filtro de material não se aplica às inscrições. Os números representam inscrições atuais, não presença comprovada nem pessoas que registraram coleta. O mesmo voluntário inscrito em eventos diferentes aparece em cada evento; não se oferece uma soma como se fossem pessoas distintas.

A consulta pública não retorna usuários, e-mails, IDs de responsáveis ou listas de participantes. Dados individuais permanecem nos endpoints existentes com suas permissões. O contrato está em [openapi.json](openapi.json).

## Verificação e limites

`ImpactReportIntegrationTests` concilia peso e quantidade para os quatro agrupamentos, testa filtros combinados e limites de datas, voluntários distintos, responsável ausente, coletas independentes, inscrições sem coleta, eventos sem inscritos, permissões e atualização após edição/exclusão. O teste de navegador cobre acesso público, filtros, gráfico, tela de celular e seção administrativa.

Validação em 18/10/2026: a regressão Java existente passou com 106 testes; os cinco novos testes de integração passaram em H2. O novo caso `MySqlDatabaseIT#impactReportsReconcileDecimalsCatalogIdsAndRegistrationsOnMySql` passou em MySQL 8.0 temporário, verificando decimais, agrupamentos, IDs, inscrições e atualização após edição/exclusão. Não foi reexecutada toda a suíte MySQL nesta entrega. Também passaram 18 testes JavaScript, o teste de navegador em Edge e a conferência OpenAPI (20 caminhos, 31 operações).

Não foi identificada necessidade específica de exportação nesta entrega; CSV fica fora do escopo. O painel apresenta todos os grupos do filtro, sem paginação; em bases extensas, prefira limitar o período. Não há mudança de esquema ou migração nesta entrega.

# Etapa 11 — Buscas com filtros e paginação

## Contrato HTTP

| Rota GET | Acesso | Filtros opcionais | Campos de ordenação (`sortBy`) |
|---|---|---|---|
| `/users/search` | Administrador autenticado | `name`, `email` | `id`, `name`, `email` |
| `/cleanupEvent/search` | Público | `location`, `status`, `from`, `to` | `id`, `title`, `location`, `dateTime`, `status` |
| `/trashCollection/search` | Autenticado; voluntário vê somente suas coletas | `beach`, `materialType`, `from`, `to`, `volunteerId`, `eventId` | `id`, `beach`, `materialType`, `weightKg`, `registrationDate` |

As listagens anteriores continuam retornando arrays; as rotas `/search` retornam `PageDTO`:

```json
{
  "content": [],
  "number": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

`page` começa em zero; `size` deve estar entre 1 e 100 e tem padrão 20. `sortBy` tem padrão `id`; `direction` aceita `asc` ou `desc` e tem padrão `asc`. Ordenações por outro campo usam também ID crescente para desempatar. Página fora dos resultados retorna conteúdo vazio, mantendo a contagem do filtro. Deslocamentos superiores ao limite inteiro da consulta são rejeitados.

Filtros são combinados com AND. Textos são pesquisados por trecho sem diferenciar caixa, ignorando espaços nas extremidades; texto vazio não filtra. `%`, `_` e `!` são caracteres literais, não permitem ampliar a busca como curingas SQL. Textos têm limite de 255 caracteres.

`status` usa os estados da etapa 9. `from`/`to` são inclusivos: data e hora local em formato ISO para eventos (`2026-10-15T08:00:00`), data ISO para coletas (`2026-10-15`). Eventos continuam usando `LocalDateTime`, sem conversão de fuso horário. Um período invertido retorna 400. IDs de filtro devem ser positivos; ID válido sem registros retorna uma página vazia.

Um voluntário não pode buscar coletas de outra conta: `volunteerId` alheio retorna 403. Sem esse parâmetro, a consulta restringe o proprietário à conta autenticada. Isso também vale para `totalElements`/`totalPages`; os metadados não contam registros alheios. Administradores podem filtrar qualquer proprietário.

`totalCollectedKg` nas respostas de contas e nas contas aninhadas das coletas considera todas as coletas do usuário, independentemente do filtro/página atual. Dados de senha e credenciais internas não são retornados.

## Organização do código

- `SearchResource`: recebe os parâmetros de query, converte datas ISO, expõe as três rotas e protege a busca de usuários com `@PreAuthorize`.
- `SearchPagination`: valida página/tamanho, limita campos de ordenação, valida direção e cria `PageRequest` com desempate estável.
- `SearchService`: valida filtros, aplica o isolamento das coletas e usa `Specification` para construir predicados JPA. Executa a consulta e a montagem dos DTOs dentro de transação somente de leitura, inclusive com OSIV desligado.
- `UserRepository`, `CleanupEventRepository` e `TrashCollectionRepository`: implementam `JpaSpecificationExecutor`, permitindo filtragem/paginação no banco. A consulta paginada de coletas usa `EntityGraph` para carregar usuário/evento junto aos registros.
- `PageDTO`: define o envelope JSON de forma explícita, sem depender da serialização interna de `Page`.
- `CollectedTotalsService`: calcula em lote os totais dos usuários da página usando os registros completos de coleta.

Nenhuma migração de esquema foi necessária. Os índices da etapa 10 permanecem. Busca por trecho com `%texto%` pode exigir varredura do texto em bases grandes; esta etapa não implementa pesquisa textual especializada.

## Verificação

`SearchIntegrationTests` usa a cadeia de segurança real com identidades simuladas e OSIV desligado. Verifica combinação de filtros, limites inclusivos de período, ordenação, páginas vazias, compatibilidade da listagem antiga, total completo do usuário, ausência de senha, isolamento de registros/contagens, bloqueio de filtros alheios, caracteres literais e rejeição de parâmetros inválidos.

Os testes anteriores de autenticação continuam usando login/JWT reais. Consulte também os [exemplos de uso](11_EXEMPLOS_DE_BUSCA.md).

Suíte completa executada em 10/10/2026: **BUILD SUCCESS — 58 testes, zero falhas, zero erros e zero ignorados**. Cinco testes pertencem à nova suíte de buscas.

# Proteção de formulários antigos

Data: 16/10/2026.

## Problema e comportamento

O `@Version` já detectava alterações concorrentes durante uma transação, mas o servidor carregava a versão atual ao receber uma nova requisição. Assim, um formulário aberto antes de outra edição podia sobrescrever dados já salvos.

As respostas de usuários, eventos e coletas agora incluem `version`, também nas listagens, buscas paginadas e objetos aninhados. Cada edição precisa enviar a versão do próprio registro obtida quando o formulário foi carregado:

```http
GET /cleanupEvent/10
```

```json
{
  "id": 10,
  "title": "Mutirão",
  "location": "Praia A",
  "dateTime": "2026-11-15T09:00:00",
  "status": "Agendado",
  "version": 0
}
```

```http
PUT /cleanupEvent/10
Content-Type: application/json
```

```json
{
  "title": "Mutirão revisado",
  "location": "Praia A",
  "dateTime": "2026-11-15T09:00:00",
  "status": "Agendado",
  "version": 0
}
```

Se a edição for aceita, a resposta inclui a versão resultante. O cliente deve guardar esse valor para sua próxima edição, sem incrementá-lo por conta própria. Uma gravação sem mudança efetiva pode manter a versão.

| Operação | Contrato |
| --- | --- |
| `PUT /users/{id}` | `version` obrigatória junto de nome e e-mail |
| `PUT /cleanupEvent/{id}` | `version` obrigatória junto dos campos do evento |
| `PUT /trashCollection/{id}` | `version` obrigatória junto dos campos da coleta |
| Versão ausente, nula, negativa ou inválida | HTTP 400; nenhuma alteração |
| Versão diferente da atual | HTTP 409, `error: "Concurrent update"`; nenhuma alteração |
| Criação por POST | Continua dispensando versão; o servidor define a versão inicial |

As permissões continuam sendo verificadas. Um conflito não concede acesso a registros de outra conta. A versão enviada é comparada na transação antes de copiar os campos; o `@Version` continua protegendo a janela entre essa comparação e a gravação. O valor enviado nunca é atribuído à versão da entidade gerenciada. As atualizações fazem flush antes de montar a resposta, para devolver a versão efetivamente persistida.

## Portal

Os formulários administrativos de eventos e coletas guardam a versão recebida ao abrir o editor e a enviam ao salvar. Um conflito mantém o diálogo e os campos digitados. **Recarregar registro atual** pede confirmação, carrega os dados e a versão atuais e permite revisar antes de um novo envio. Não há repetição automática com a versão mais recente.

## Persistência e compatibilidade

Usuários e coletas reutilizam suas colunas `row_version`. Eventos ganharam `@Version` e a migração Flyway `V4__add_event_row_version.sql`, que adiciona `row_version BIGINT NOT NULL DEFAULT 0` e preserva registros e vínculos existentes. H2 de testes cria a coluna pelo Hibernate; o teste de migração usa H2 persistente em modo MySQL e valida os dados anteriores.

Esta mudança exige atualizar clientes externos: PUTs antigos sem `version` passam a receber 400. O contrato [OpenAPI](openapi.json) distingue os corpos de criação e atualização, exigindo versão somente na edição. `rowVersion` e a versão de credenciais permanecem fora do JSON; o campo público chama-se `version`.

O controle cobre os três PUTs de edição. Exclusões, inscrições e troca de senha mantêm seus contratos específicos. A versão de um recurso não é uma versão agregada dos recursos vinculados: alterações em coletas podem mudar o total calculado do usuário sem alterar a versão do perfil. O contrato utiliza versão no JSON e não acrescenta ETag/If-Match.

## Verificações

Os testes de integração novos simulam formulários carregados em requisições separadas, edição intermediária já concluída, rejeição do formulário antigo, preservação integral do registro e novo envio após recarregar. Cobrem usuários, eventos e coletas, versões ausentes/nulas/negativas/inválidas/futuras e autorização. Os testes existentes de concorrência, transferência de proprietário, totais e estados foram adaptados ao contrato.

O roteiro de navegador foi ampliado para editar eventos e coletas por outra requisição enquanto o formulário permanece aberto, verificar a rejeição, preservar os campos e salvar após recarregar. Inclui a conferência dos totais após o conflito.

- Maven offline com o repositório local: `package` aprovado; **78 testes, zero falhas, zero erros e zero testes ignorados**, incluindo os sete novos cenários parametrizados/de autorização.
- Após simplificar os corpos de teste com versões explícitas, os 12 testes de totais, concorrência de coletas e estados foram executados novamente e passaram. Todos os 17 arquivos de testes Java foram recompilados, inclusive o teste MySQL.
- `node --test tools/portal-api.test.cjs`: **14 testes aprovados**.
- `node --check` aprovado para o JavaScript administrativo e o roteiro de navegador.
- `python tools/validate_openapi.py`: estrutura e cobertura aprovadas, com 13 caminhos e 23 operações.
- `node tools/verify_portal_browser.cjs`: aprovado no Edge headless com aplicação H2 descartável, incluindo os conflitos de formulários antigos de evento e coleta, preservação dos campos e salvamento após recarregar. As regressões anteriores de desktop/celular, permissões, sessão, totais e CSP também passaram. A verificação móvel usa emulação.

A suíte MySQL real e o CI remoto não foram executados nesta tarefa. Os testes MySQL foram adaptados às quatro migrações e à nova assinatura de edição e compilados com os demais testes. As verificações Java foram feitas na árvore de trabalho atual, preservando as alterações locais anteriores.

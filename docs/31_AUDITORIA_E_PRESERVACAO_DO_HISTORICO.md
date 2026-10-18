# Auditoria e preservação do histórico

A trilha registra autor por ID, papel exercido, instante UTC do servidor, recurso, ID e ação. Ela é gravada na mesma transação da operação: falhas e conflitos não deixam uma alteração administrativa aparentemente concluída. Não há gravação de DTOs, nomes, e-mails, senhas, hashes de senha, tokens, cabeçalhos ou textos livres enviados pelo cliente.

## Operações registradas

| Recurso | Ação | Evidência |
| --- | --- | --- |
| COLLECTION | CREATE | Usuário e evento iniciais |
| COLLECTION | TRANSFER | Usuário e evento anteriores e posteriores, inclusive desvinculação |
| COLLECTION | DELETE | Usuário e evento anteriores à exclusão |
| EVENT | STATE_CHANGE | Estado anterior e posterior, com as regras de transição existentes |
| BEACH / MATERIAL | MERGE | ID da origem e `targetId` do destino canônico |
| EVENT | REGISTER / UNREGISTER | Usuário e evento, somente quando o vínculo muda |
| USER / EVENT | DELETE | Exclusão de um registro elegível, mantendo a identificação do recurso excluído |

O autor vem do contexto de autenticação validado pelo servidor. Operações internas sem autenticação usam `SYSTEM`, sem autor numérico. A tabela não depende de relações JPA com os objetos originais, permitindo conservar a evidência depois de excluir um registro vazio. Não existe endpoint para editar ou excluir a trilha. Isso protege o fluxo da aplicação; operadores com permissão de escrita direta no banco continuam capazes de modificar tabelas. Restrinja essas credenciais e preserve os backups.

## Política de preservação

- Usuários com inscrições, coletas ou referências na auditoria (inclusive autoria e vínculos anteriores) não podem ser excluídos. O retorno é **409** com explicação. Encerrar uma inscrição ou transferir/excluir uma coleta não apaga a evidência nem libera automaticamente a exclusão do usuário.
- Eventos com inscrições, coletas ou histórico auditado também retornam **409**. Eventos em estado diferente de `Agendado` são preservados mesmo quando seu estado antecede esta implantação. Quando permitido pela máquina de estados, use `Cancelado` para encerrar o evento.
- Usuários sem vínculos ou histórico e eventos ainda agendados sem vínculos ou histórico podem ser excluídos fisicamente. A exclusão é auditada. As regras existentes de chaves estrangeiras permanecem como proteção adicional.
- Coletas podem ser corrigidas ou explicitamente excluídas por quem tem permissão. A exclusão atualiza os totais derivados, conserva os IDs dos vínculos na auditoria e não exclui o usuário ou o evento. A trilha não é uma cópia integral da coleta e não fornece restauração de peso ou de todos os campos excluídos.
- A unificação conserva o catálogo de origem com referência ao destino e preserva seus aliases. A trilha conserva ambos os IDs; os relatórios continuam usando os IDs canônicos.
- Não há anonimização automática nesta entrega. Uma solicitação de remoção de dados pessoais com histórico exige procedimento administrativo específico, com política de retenção definida pelo responsável pelo serviço. Não substitua dados pessoais por valores arbitrários nem remova a trilha para liberar exclusões.

A retenção da trilha é indefinida nesta implementação; não existe expurgo automático. O responsável pelo serviço deve definir o prazo e a base de retenção antes de executar qualquer expurgo ou procedimento de anonimização. A trilha começa nesta entrega: ações anteriores não são reconstruídas e nenhum autor ou horário antigo é inventado. Vínculos atuais e o estado de eventos legados continuam protegidos.

## Exclusões e telas antigas

`DELETE /users/{id}`, `/cleanupEvent/{id}` e `/trashCollection/{id}` exigem `?version=N`, obtido no GET/listagem que originou a confirmação. O portal envia a versão exibida, inclusive para coletas próprias. Ausência ou valor negativo retorna **400**; versão antiga retorna **409**. A autorização continua sendo verificada antes da exclusão, e o DELETE JPA também verifica a versão no banco. Uma tela antiga sem versão deve ser atualizada. Nunca busque uma versão nova para repetir silenciosamente a exclusão após um conflito: recarregue, revise e confirme novamente.

As inscrições mantêm o cancelamento idempotente: não são exclusões físicas do evento ou da conta. Catálogos continuam usando as versões de origem e destino na unificação.

## Investigação

Na área administrativa, abra **Auditoria**. É possível filtrar recurso, ID do recurso, ID do autor, ação e período. Os campos do filtro de período são explicitamente UTC; os resultados mostram o horário local do navegador. Voluntários e visitantes não recebem acesso à trilha.

Também é possível consultar `GET /admin/audit`, com JWT de administrador:

```text
/admin/audit?resource=COLLECTION&resourceId=42&action=TRANSFER&page=0&size=20
/admin/audit?actorId=7&from=2026-10-01T00:00:00Z&to=2026-10-31T23:59:59Z
```

Filtros combinam por AND. O intervalo é inclusivo, com instantes ISO 8601. Ordenação: horário decrescente e ID decrescente como desempate. `size` aceita 1 a 100; `page` aceita 0 a 1.000.000. Campos e respostas estão no [OpenAPI](openapi.json).

## Banco e validação

A migração MySQL `V10__administrative_audit.sql` cria `tb_audit_entry` e índices de recurso, horário, autor e vínculos. H2 usa o mapeamento equivalente da entidade. A trilha participa das transações dos serviços; consultas de preservação usam leitura bloqueante para enxergar evidências já confirmadas mesmo sob um snapshot anterior do MySQL.

`AuditIntegrationTests` verifica autoria, vínculos anteriores e posteriores, estados, unificação, filtros, ausência de segredos, permissões, rollback, rejeição de versão ausente ou antiga, preservação depois de excluir coletas e cancelamento de inscrições. As suítes existentes continuam conciliando totais e exercitando transferências concorrentes e acesso por outras contas. Os testes do cliente verificam que a versão confirmada acompanha a exclusão.

Validação desta entrega: 134 testes Java nos relatórios finais, 14 testes em MySQL descartável, 27 testes do cliente e 3 verificações Python aprovados. O OpenAPI passou com 27 caminhos e 38 operações. A verificação em Edge passou com filtros de auditoria, preservação de eventos após remoção das coletas, exclusões com versão, regressões de permissões e sessões, CSP e telas móveis e desktop.

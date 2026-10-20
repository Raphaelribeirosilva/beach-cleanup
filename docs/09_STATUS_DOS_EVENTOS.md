# Etapa 9 — Estados dos eventos

Novos eventos devem começar como `Agendado`. O campo JSON continua sendo `status`, com nomes em português; `Concluído`, `Finalizado`, diferenças de caixa e espaços nas extremidades são normalizados. O estado existente `COMPLETED` mantém o valor canônico `Concluido` na API e no banco, mas é exibido como **Finalizado** em toda a interface. Não há um segundo estado de encerramento. Status desconhecido retorna 400 com erro no campo `status`; criação com outro estado conhecido e transição não permitida retornam 409.

| Estado atual | Próximos estados permitidos |
|---|---|
| Agendado | Em andamento, Pausado, Cancelado |
| Em andamento | Pausado, Finalizado (`Concluido`), Cancelado |
| Pausado | Agendado, Em andamento, Cancelado |
| Finalizado (`Concluido`) | Nenhum |
| Cancelado | Nenhum |

Manter o estado atual permite corrigir metadados. Eventos concluídos/cancelados não podem ser reabertos. A mudança usa o PUT existente de `/cleanupEvent/{id}` e continua exclusiva do administrador. Datas não provocam mudanças automáticas de estado.

Eventos `Agendado` e `Em andamento` aceitam novas inscrições e vínculos de coletas. `Pausado`, `Concluido` e `Cancelado` rejeitam novas inscrições/coletas com 409. Uma inscrição que já existe continua idempotente e pode ser cancelada em qualquer estado. Coletas sem evento continuam permitidas.

Encerrar/cancelar não remove inscrições nem coletas. Coletas já vinculadas podem ser corrigidas e excluídas; transferir uma coleta para outro evento exige que o destino aceite novos registros. A inscrição ainda não é requisito para coleta. Os totais seguem a soma das coletas existentes.

As escritas bloqueiam o registro do evento durante a validação, evitando que a mudança de estado passe entre a checagem e a gravação. O esquema e o seed permanecem; estados válidos existentes são interpretados pela regra, sem conversão da coluna para enum no banco. Dados externos com estados desconhecidos precisam ser corrigidos antes dessas operações.

`EventStateIntegrationTests` verifica a matriz completa de transições, criação/normalização, rejeição de estados desconhecidos, preservação dos dados após rejeição, restrições de novas inscrições/coletas e correção de registros históricos. Os testes anteriores continuam exercitando autenticação, autorização e concorrência das inscrições.

Registro histórico da etapa original: **BUILD SUCCESS — 52 testes, zero falhas e zero erros**, em 10/10/2026. A revisão do nome Finalizado e suas verificações estão em [Finalização de eventos](23_FINALIZACAO_DE_EVENTOS.md).

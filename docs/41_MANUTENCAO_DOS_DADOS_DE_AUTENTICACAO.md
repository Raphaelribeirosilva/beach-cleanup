# Manutenção periódica dos dados de autenticação

A aplicação executa a manutenção automaticamente, um minuto após iniciar e depois a cada hora, contando o intervalo a partir do término da execução anterior. O esquema vigente passa a **V1–V16**: V16 cria `idx_auth_rate_limit_window` para consultar contadores antigos pelo início da janela. A migração somente adiciona o índice; as exclusões ocorrem no processo periódico da aplicação. As migrações anteriores e os dados de negócio permanecem preservados.

## Retenção e garantias de segurança

| Dados | Quando remover, por padrão | O que permanece |
| --- | --- | --- |
| Sessões (`tb_auth_session`) | Vencimento absoluto há pelo menos sete dias | Sessões ainda válidas, sessões revogadas ainda não vencidas e sessões vencidas dentro da margem |
| Refresh tokens (`tb_refresh_token`) | Somente junto da exclusão da sessão, por `ON DELETE CASCADE` | Toda a família, incluindo tokens já consumidos, durante a validade da sessão e a margem de retenção |
| Confirmação/recuperação (`tb_account_action_token`) | Vencimento há pelo menos sete dias | Tokens válidos e tokens usados/vencidos dentro da margem, sem distinguir consumo para encurtar a retenção |
| Limitação de tentativas (`tb_auth_rate_limit`) | Início da janela há pelo menos 24 horas | Contadores recentes, inclusive janelas ainda ativas ou renovadas durante a limpeza |

Uma sessão dura no máximo sete dias desde o login; a rotação não estende esse prazo. A retenção começa **após o vencimento absoluto**, sem antecipar a exclusão por logout, revogação ou consumo. Assim, um refresh token antigo ainda permite detectar reutilização e revogar a família enquanto ela puder ser usada. Os tokens de acesso continuam exigindo uma sessão existente e válida; remover uma sessão vencida não reativa nenhuma credencial.

Os contadores usam janelas fixas de no máximo uma hora. A retenção padrão de 24 horas é medida por `window_start`, não pela última tentativa. O limitador recusa novas regras com janela superior a `AbuseLimiter.MAX_WINDOW_SECONDS`; ampliar esse limite exige revisar também a retenção mínima. Essa verificação impede que uma futura regra mantenha uma janela ativa por mais tempo do que a limpeza preserva.

A limpeza não altera senhas, geração de credenciais, usuários, eventos, coletas, catálogos, auditoria administrativa ou a fila de e-mail. A fila mantém sua [manutenção própria](40_FILA_PERSISTENTE_E_VALIDACAO_DE_EMAIL.md).

## Execução, concorrência e falhas

Cada tabela é processada em até dez lotes de duzentos registros por ciclo, por padrão. Cada lote usa uma transação independente com isolamento `READ_COMMITTED`. As consultas usam os índices de vencimento já existentes e o novo índice dos contadores. Os refresh tokens são removidos em cascata; a quantidade deles depende do histórico de rotações de cada sessão, e não do limite de sessões por lote.

A exclusão confere novamente a data mesmo depois de selecionar os registros. Se uma requisição renovar um contador antes da exclusão, o contador e sua nova contagem são preservados. Várias instâncias podem executar a manutenção: as condições são verificadas pelo banco e a execução é idempotente. Não há endpoint HTTP para disparar a limpeza.

Ao atingir o limite de lotes, o restante aguarda o próximo ciclo. Em caso de falha, o lote atual é revertido; lotes já concluídos continuam aplicados. O agendador registra aviso e tenta novamente no ciclo seguinte. Logs mostram somente contagens agregadas ou aviso fixo, sem IDs, hashes, e-mails, IPs, tokens ou conteúdo da exceção do banco.

## Configuração

| Propriedade | Padrão | Limites/efeito |
| --- | --- | --- |
| `security.maintenance.enabled` | `true` | `false` desativa somente o agendamento |
| `security.maintenance.initial-delay-ms` | `60000` | Espera inicial em milissegundos |
| `security.maintenance.fixed-delay-ms` | `3600000` | Espera após cada execução, em milissegundos |
| `security.maintenance.session-retention-days` | `7` | De 1 a 3650 dias após vencer |
| `security.maintenance.action-token-retention-days` | `7` | De 1 a 3650 dias após vencer |
| `security.maintenance.rate-limit-retention-hours` | `24` | De 1 a 87600 horas; nunca inferior à maior janela suportada |
| `security.maintenance.batch-size` | `200` | De 1 a 2000 registros por lote e tabela |
| `security.maintenance.max-batches` | `10` | De 1 a 100 lotes por tabela e ciclo |

Configurações inseguras de retenção ou tamanho dos lotes impedem a inicialização. Mudanças nas propriedades exigem reiniciar a aplicação. O relógio injetado e as comparações usam instantes UTC; os testes controlam esse relógio sem alterar o horário do computador. As propriedades são operacionais e não modificam os prazos de validade de sessões ou links.

## Validação

Os testes verificam os limites exatos de retenção, preservação de sessões revogadas ainda dentro do prazo, remoção em cascata, manutenção da detecção de reutilização, preservação dos limites ativos, renovação concorrente de contador, continuação de backlog e validade dos links após a limpeza. Também verificam disparo periódico, nova execução após falha, desativação do agendamento e ausência de dados sensíveis nos logs.

As mesmas verificações de preservação e concorrência rodam no MySQL descartável, com V16, conferência do índice e reinício da aplicação.

Resultado em 19/10/2026: suíte Maven completa com **Java 25.0.2**, **188 testes aprovados**, incluindo **21 com MySQL 8 descartável**, sem falhas, erros ou testes ignorados. Os nove testes novos locais verificam integração com H2, retenção, concorrência, configuração e agendamento; o novo cenário MySQL repete as garantias de preservação e renovação concorrente. Os relatórios desta execução confirmam Java 25 e o JAR foi conferido com bytecode de versão 69 para a manutenção.

Também passaram os **12 testes Python** das ferramentas e o OpenAPI com **29 caminhos, 40 operações e V1–V16**. A execução terminou às 23:45 (America/Sao_Paulo). Evidências locais: `target/validation-auth-maintenance-java25.log` e relatórios Surefire. Esta alteração não modifica o portal; os fluxos de navegador validados na [etapa anterior](40_FILA_PERSISTENTE_E_VALIDACAO_DE_EMAIL.md) não foram repetidos nesta etapa. Nenhuma rotina foi executada no banco persistente do usuário durante esses testes.

# Finalização de eventos

## Estado reaproveitado e contrato

O domínio já tinha `EventStatus.COMPLETED`, com valor persistido e retornado `Concluido`. Esse estado foi reaproveitado. A interface exibe **Finalizado** nos cartões públicos, inscrições, listagem administrativa, edição do evento e filtros. Valores enviados pelos seletores continuam sendo `Concluido`.

A API aceita também `Finalizado`, incluindo caixa diferente e espaços nas extremidades, em entradas de evento e no filtro `status` de `/cleanupEvent/search`. A resposta continua canônica: `Concluido`. O alias anterior `Concluído` permanece aceito. `docs/openapi.json` descreve essa compatibilidade. Não foi criada migração: não há alteração de esquema nem conversão dos registros existentes, e as migrações anteriores foram preservadas.

## Transições e permissões

Somente o administrador pode finalizar, usando **Administração → Eventos → Editar / status → Finalizado → Salvar**. A operação usa o `PUT /cleanupEvent/{id}` existente com a versão atual do registro. A transição permitida permanece **Em andamento → Finalizado**. Agendado e Pausado não podem ser finalizados diretamente; Finalizado é terminal e não pode ser reaberto. Manter o mesmo estado permite corrigir metadados. Novos eventos continuam obrigatoriamente Agendado.

Não há finalização automática pela data, endpoint paralelo ou nova permissão. Autenticação, controle de versão, auditoria e bloqueio transacional existentes permanecem em vigor.

## Inscrições, coletas e relatórios

- Finalizar não apaga o evento, suas inscrições ou coletas. Consulta pública, busca, inscrições pessoais e relatórios continuam disponíveis.
- Novas inscrições retornam 409, com mensagem indicando que o evento foi finalizado. Repetir uma inscrição já existente continua idempotente; cancelá-la continua permitido.
- Novas coletas vinculadas e transferências de coletas para o evento finalizado continuam bloqueadas por 409, inclusive para administradores. Não foi introduzida exceção de registro tardio.
- Coletas já vinculadas podem continuar sendo corrigidas ou excluídas conforme as permissões existentes. Coletas independentes continuam permitidas. A finalização não altera pesos ou totais.
- Os cartões informam o encerramento, não oferecem Inscrever-se nem Registrar coleta para o evento finalizado, e mantêm Cancelar inscrição quando a pessoa já estiver inscrita.

## Verificações

`EventStateIntegrationTests` cobre a matriz existente de transições e foi ampliado para verificar o alias Finalizado, proibição de criação nesse estado, finalização por administrador, rejeição de anônimo e voluntário, busca compatível por três nomes, consulta pública, mensagem de inscrição encerrada, bloqueio de novas coletas e impossibilidade de reabertura. A verificação de preservação passou a finalizar pelo alias e conferir o peso e a quantidade de coletas no relatório público após uma correção histórica.

O roteiro `tools/verify_portal_browser.cjs` foi ampliado para finalizar um evento pelo formulário administrativo, conferir o valor canônico salvo, filtrar por Finalizado na administração e na busca pública, conferir o seletor terminal e a ausência de ações de inscrição/coleta no cartão encerrado. Usa apenas uma API local e dados descartáveis.

Resultados em 20/10/2026:

- Maven offline, `-Dtest=EventStateIntegrationTests,ImpactReportIntegrationTests,SecurityIntegrationTests test`: **17 testes, zero falhas/erros, BUILD SUCCESS**.
- Maven offline, `-DskipTests package`: **BUILD SUCCESS**, para empacotar a versão examinada no navegador.
- `node tools/verify_portal_browser.cjs`: **PASS**, incluindo finalização pelo formulário, filtro administrativo e público, seletor terminal, mensagem de encerramento e bloqueio das ações; também passou a regressão existente em computador/celular.
- `python tools/validate_openapi.py`: estrutura e cobertura válidas, **29 caminhos / 40 operações**. É um verificador estrutural local, não uma validação completa do padrão OpenAPI.
- `node --check` nos três scripts JavaScript alterados: sem erros de sintaxe.
- `git diff --check`: sem erros de whitespace.

[Captura do evento finalizado e filtro público](capturas/23/evento-finalizado.png), com dados de teste descartáveis. A foto e o restante do sistema visual não foram alterados.

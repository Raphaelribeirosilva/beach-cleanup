# Eventos fictícios para a demonstração no Render

## Comportamento

A variável **`DEMO_DATA_ENABLED=true`** habilita uma carga na inicialização. Sem a variável, ou com `false`, nenhum evento de demonstração é criado. A variável não é secreta. Não a configure no ambiente local que usa seus dados pessoais. Mantenha os perfis de publicação `prod,render`; não ative o perfil `test` para popular o site.

Todos os títulos começam com `[DEMONSTRAÇÃO FICTÍCIA]`. Os cartões mostram uma descrição com o aviso: **não há ação real agendada, as inscrições são para testar o site e ninguém deve comparecer ao local**.

As datas são calculadas uma única vez, considerando o dia da primeira execução em `America/Sao_Paulo`, sempre às 08h00:

| Tema | Praia | Município | Data relativa | Estado final |
| --- | --- | --- | --- | --- |
| Cuidado com a areia | Praia do Morro | Guarapari/ES | +7 dias | Agendado, inscrições abertas |
| Conhecendo os resíduos | Praia de Setiba | Guarapari/ES | +14 dias | Agendado, inscrições abertas |
| Orla consciente | Praia da Costa | Vila Velha/ES | +21 dias | Agendado, inscrições abertas |
| Memória de um mutirão | Praia de Itaparica | Vila Velha/ES | −7 dias | Concluído, exibido como Finalizado |

Exemplo: uma primeira execução em 21/10/2026 gera datas 28/10, 04/11, 11/11 e 14/10/2026, respectivamente. Não são anúncios de atividades reais. As datas não são renovadas em reinícios. Se a demonstração continuar por muito tempo, um administrador existente poderá revisar os eventos pelas regras normais do portal.

## Regras e preservação

- O modelo exige título (até 255 caracteres), praia cadastrada, data/hora válida e status. A descrição opcional tem até 2.000 caracteres e é retornada pela API e exibida como texto no portal. Nesta implementação ela é preenchida pela carga, não pelo formulário administrativo; edições normais preservam a descrição.
- Novos eventos começam como `Agendado`. O exemplo finalizado passa pelo serviço existente nas transições `Agendado → Em andamento → Concluido`. As duas alterações ficam na auditoria com ator `SYSTEM`, sem usuário administrador.
- Os eventos futuros aceitam inscrição pelas regras existentes. O finalizado não aceita novas inscrições ou coletas. Nenhum participante, coleta, peso, conta ou e-mail é gerado pela carga. Confirmação de conta, recuperação de senha, permissões e envio de e-mails continuam com as mesmas exigências.
- Praias existentes com nome e município correspondentes são reutilizadas. Quando necessário, são acrescentadas praias qualificadas pelo município. Nenhum catálogo preexistente é renomeado ou removido.
- A migração **V17** acrescenta a coluna opcional `description` e a tabela de controle `tb_demo_data`. A migração roda normalmente mesmo com a carga desabilitada: apenas prepara a estrutura e o marcador, sem criar os quatro eventos. Migrações anteriores não foram modificadas.
- O marcador `coastal-events-v1` é bloqueado durante a execução. Os quatro eventos, eventuais praias adicionais, auditoria e conclusão do marcador são gravados na mesma transação. Uma falha reverte a carga inteira; reiniciar permite nova tentativa.
- Depois do sucesso, outra execução é uma operação sem alterações, inclusive com inicializações concorrentes. Não depende do título para identificar a carga. Alterações ou exclusões posteriores não provocam recriação. **Não apague nem reinicialize o marcador para tentar atualizar as datas.**
- A atualização de evento faz `flush` da entidade já gerenciada, evitando um merge redundante que falhava no Hibernate ao encadear transições de um evento recém-criado.

## Ativar no Render, uma etapa por vez

1. **Disponibilizar o código.** Esta implementação foi preparada localmente, sem push. Revise o commit e faça o envio ao GitHub, ou autorize esse envio em uma próxima etapa. O Render precisa executar uma revisão que contenha V17 e `DemoDataConfig`. Conectar o repositório não envia os commits locais. O guia [Render/Aiven](43_DEMONSTRACAO_RENDER_AIVEN.md) continua descrevendo Docker, Java 25, TLS, CA e e-mail.
2. **Configurar o serviço.** No Web Service do Render, abra **Environment** e adicione `DEMO_DATA_ENABLED` com valor `true`. Mantenha as demais variáveis, o CA e as credenciais já configurados. Não precisa criar administrador, fornecer senha nova, mudar o banco ou habilitar SMTP. A credencial Aiven existente precisa permitir a aplicação da migração V17, além das anteriores.
3. **Implantar a revisão.** Salve e execute o deploy/redeploy do serviço com a nova configuração. Aguarde a aplicação das migrações e a mensagem `Carga de demonstração aplicada: quatro eventos fictícios.` nos logs. Em execuções posteriores, a mensagem será `Carga de demonstração já aplicada; eventos preservados.` Se houver erro de inicialização, resolva a causa sem apagar dados nem executar `flyway clean`.
4. **Conferir o site.** Abra a URL pública do Render e a listagem de eventos, limpe filtros antigos e confira os três agendados e o finalizado. Use os filtros de ES/Guarapari e ES/Vila Velha se houver outros registros. Confira títulos, descrição de ficção e datas. Inscrições continuam exigindo uma conta válida conforme a política existente; valide confirmação e recuperação com um endereço seu e o provedor de e-mail já configurado. A carga não comprova entrega de e-mail.
5. **Desativar após conferir.** Em Environment, altere `DEMO_DATA_ENABLED` para `false` ou remova a variável. Salve e aplique o redeploy. Os eventos permanecem no MySQL do Aiven e continuam visíveis. Reativar posteriormente não duplica, não restaura exclusões e não altera datas.

Não houve push, deploy remoto ou acesso ao Aiven nesta tarefa. A validação do link público só poderá ser concluída depois dessas etapas no seu ambiente.

## Verificações desta implementação — 21/10/2026

Os testes usam H2 temporário e uma instância MySQL descartável, iniciada com `--no-defaults`, diretório temporário e porta própria pelo script `tools/run_mysql_tests.ps1`. O banco local do usuário não é usado.

| Verificação | Resultado |
| --- | --- |
| Java 25.0.2, suíte completa com MySQL 8.0.46 descartável | 199 testes executados: 197 aprovados inicialmente; os dois restantes esperavam a lista antiga V1–V16. Após corrigir a expectativa para V17, ambos foram repetidos e aprovados. A repetição executou `verify`, gerou o JAR e terminou com `BUILD SUCCESS`. A suíte inteira não foi repetida após essa alteração apenas no teste. |
| Carga de demonstração | Quatro testes aprovados (dois H2 e dois MySQL): ausência/desativação da opção, cadastro das quatro praias/datas/status, descrições, auditoria SYSTEM, ausência de usuários/coletas/e-mails, preservação de evento existente e de edição posterior, reinícios, reversão integral, concorrência e não recriação após exclusão deliberada no banco descartável. |
| Migrações e regressões | V1–V17 validadas; testes de preservação de dados legados aprovados após a correção da expectativa. A suíte inclui CRUD, permissões, transições, inscrições, confirmação/recuperação, fila de e-mail, configuração Render e transporte Brevo simulado. |
| OpenAPI | 29 caminhos e 40 operações; versão de migração atualizada para V17 e descrição de evento documentada como opcional/somente leitura. |
| Python | 12 testes aprovados. |
| JavaScript | 29 testes aprovados; sintaxe de `portal.js` validada com `node --check`. |
| Navegador | `node tools/verify_portal_browser.cjs`: PASS no Edge headless com JAR final e H2 descartável. Cobriu fluxos públicos/privados, formulários de confirmação/recuperação, sessões, CRUD, permissões e acessibilidade desktop/mobile. Esse roteiro usa os dados de teste existentes; a conferência visual dos quatro cartões de demonstração no Render permanece pendente. |

A primeira execução isolada dos dois testes novos revelou o problema de merge da entidade no Hibernate. Depois da correção para `flush`, os quatro cenários H2/MySQL passaram na execução completa. Os avisos de envio desativado nos testes são esperados: nenhum e-mail externo é enviado.

Comandos: Maven offline com `-Pmysql-it verify` pelo script de banco descartável; repetição com `-Dtest=MySqlDatabaseIT#catalogUpgradePreservesEveryLegacyColumnAndCredentialsOnMySql`; `python tools/validate_openapi.py`; `python -m unittest discover -s tools -p 'test_*.py'`; `node --test tools/portal-api.test.cjs`; `node --check src/main/resources/static/portal.js`.

Evidências locais ignoradas pelo Git: `target/demo-initial-tests.log`, `target/demo-full-validation.log`, `target/demo-final-validation.log`, `target/demo-browser-validation.log` e relatórios de `target/surefire-reports`. A repetição sobrescreve o relatório da classe MySQL; o log completo anterior conserva o resultado dos seus 23 cenários. As alterações locais preexistentes participaram da execução, mas foram preservadas e não incluídas no commit desta tarefa.

Ainda dependem do ambiente hospedado: build Docker no Render, aplicação V17 no Aiven real com TLS/CA, logs da carga, exibição pela URL HTTPS, persistência após desativar a variável e entrega real de confirmação/recuperação. Testes locais não comprovam esses pontos.

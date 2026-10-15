# Interface administrativa — entrega 1 do planejamento

Data: 15/10/2026.

Esta entrega implementa somente a primeira prioridade de [19_PROXIMOS_PASSOS.md](19_PROXIMOS_PASSOS.md). O número 20 identifica este documento na pasta, não uma etapa adicional do planejamento. Não houve implantação, commit ou push.

## Análise e integração com o portal

O portal existente é servido pelo Spring Boot, com HTML, CSS e JavaScript sem dependências npm. O cliente `portal-api.js` já centralizava JWT em memória, requisições, mensagens de validação/conflito e descarte de respostas de uma sessão encerrada. A API já oferecia CRUD de eventos/coletas, buscas paginadas, transições de status e autorização por perfil/propriedade.

A nova aba **Administração** reutiliza o login, a navegação, os cartões, os diálogos, os controles e o tratamento de erros do portal. O arquivo `portal-admin.js` concentra os formulários e as listas administrativas. A apresentação inicial e o painel de impacto pessoal ficam ocultos enquanto a administração está aberta, dando espaço às operações de gestão.

Nenhuma rota de negócio, regra de autorização, entidade ou migração foi alterada. O ajuste Java permite servir o novo arquivo estático com GET/HEAD pela mesma cadeia de segurança do portal, mantendo a política de conteúdo e o bloqueio de outros métodos. O JavaScript é público como os demais arquivos do portal e não contém credenciais; operações e dados privados exigem JWT e autorização no servidor.

## O que foi implementado

| Área | Operações disponíveis |
|---|---|
| Eventos | Busca paginada por local/status, cadastro, edição dos dados e do status, exclusão com confirmação. |
| Usuários | Consulta paginada por nome/e-mail, exibindo ID, nome, e-mail e total coletado em kg. |
| Coletas | Busca paginada por praia, material, proprietário, evento e período; cadastro, edição e exclusão com confirmação. |

Novos eventos oferecem apenas `Agendado`. Ao editar, o seletor mostra o estado atual e as transições permitidas pelo backend. Eventos concluídos ou cancelados não podem mudar de status; a API continua sendo a autoridade para validar a transição no momento da gravação.

Nas coletas, o administrador pode informar ou remover os IDs do proprietário e do evento, transferir a coleta e alterar peso, praia, material e data. Os IDs podem ser consultados nas abas Usuários e Eventos. Campo de vínculo vazio envia `null`, conforme o contrato existente: a coleta pode ficar sem proprietário ou sem evento. Novos vínculos com eventos pausados, concluídos ou cancelados são rejeitados pelo backend.

Todas as listas usam as rotas `/search`, com seis registros por página. Buscar reinicia a paginação. Ao excluir o último registro de uma página, a interface retorna a uma página válida. Os textos recebidos da API são inseridos com `textContent`, incluindo nomes, materiais e locais.

Os formulários validam campos obrigatórios, limites de texto, peso finito maior que zero e IDs inteiros positivos. As mensagens retornadas pela API aparecem no formulário ou na área administrativa. Falhas de carregamento oferecem nova tentativa; uma falha de atualização da lista após uma gravação informa que a gravação já foi concluída.

Conflitos preservam o formulário para revisão. Na edição, **Recarregar registro atual** pede confirmação antes de descartar o conteúdo digitado. Exclusões também exigem confirmação; conflitos de integridade mantêm o registro visível. Logout ou token revogado limpam os registros e formulários administrativos; uma resposta antiga não repõe dados de outra sessão.

## Como acessar

1. Empacote e inicie a aplicação a partir da raiz do projeto:

   ```powershell
   .\mvnw.cmd -B package
   java -jar target/beachcleanup-0.0.1-SNAPSHOT.jar --spring.profiles.active=test
   ```

2. Abra `http://localhost:8080/` e entre com `admin@example.com` / `admin123`, disponíveis somente no perfil de demonstração `test`.
3. Abra **Administração** e escolha **Eventos**, **Usuários** ou **Coletas**.
4. Para conferir o perfil de voluntário, saia e entre com `ana@example.com` / `ana123`. A aba administrativa não aparece, e as operações administrativas continuam bloqueadas pela API.

O perfil `test` usa H2 em memória e recria os dados ao reiniciar. Em MySQL, use uma conta administradora criada pelo procedimento controlado da [etapa 10](10_PERSISTENCIA_E_MIGRACOES.md); o cadastro público continua criando voluntários. O JWT permanece apenas em memória: recarregar a página exige novo login.

## Verificações e resultados

- **Java e empacotamento:** `package` aprovado, com 71 testes, nenhuma falha, erro ou teste ignorado. Inclui segurança, estados dos eventos, coletas concorrentes, totais, buscas e entrega dos arquivos estáticos. O teste do portal também verifica o novo arquivo JavaScript e o bloqueio de POST nesse caminho.
- **JavaScript:** `node --test tools/portal-api.test.cjs`, com oito testes aprovados. A cobertura adicional verifica bloqueio no cliente para anônimos/voluntários, consultas administrativas sem limitar o proprietário à conta atual, métodos e corpos de CRUD, envio do JWT, conflitos, revogação e descarte de respostas após logout.
- **OpenAPI:** `python tools/validate_openapi.py` aprovado, com 13 caminhos e 23 operações. O contrato não precisou mudar.
- **Navegador:** `node tools/verify_portal_browser.cjs` aprovado no Edge em modo headless, usando aplicação e banco H2 temporários. O script preserva os fluxos anteriores do voluntário e acrescenta os cenários administrativos descritos abaixo.

No navegador foram verificados cadastro/edição/exclusão de eventos, mudança de status, busca de usuários e paginação nas três áreas; cadastro/edição/exclusão de coletas, transferência de proprietário e remoção de vínculo; recálculo dos totais das contas envolvidas; validação de peso; referências inexistentes (404); vínculo com evento pausado e exclusão de evento com coleta (409); preservação do formulário; cancelamento de exclusão; retorno à página anterior após excluir o último item; limpeza dos dados no logout; isolamento entre sessões; texto com aparência de HTML; ausência de exceções JavaScript e violações de CSP. Também foi verificada a limpeza da área e a solicitação de novo login após revogar o token por troca de senha.

Com JWT real de voluntário, o roteiro chama diretamente a API e exige 403 para criação/edição/exclusão de eventos, listagem/busca de usuários, acesso ao perfil de outra conta, leitura/edição/exclusão de coleta de outra conta, cadastro de coleta para terceiro e busca por coletas de outro proprietário. Exibir manualmente o botão oculto no DOM também não abre a administração. O voluntário preserva as operações autorizadas sobre seus próprios registros.

As verificações visuais usam viewports de 1280 × 960 e 390 × 844, com teste de ausência de rolagem horizontal na página. As capturas ficam em `target/admin-desktop.png` e `target/admin-mobile.png`. A validação móvel foi feita por emulação, sem dispositivo físico.

Para repetir as verificações:

```powershell
.\mvnw.cmd -B package
node --test tools/portal-api.test.cjs
python tools/validate_openapi.py
node tools/verify_portal_browser.cjs
```

O roteiro de navegador exige Node.js 24+ e Edge/Chromium instalado. `PORTAL_BROWSER` permite informar outro executável compatível. Ele inicia a aplicação em uma porta livre, cria somente dados descartáveis e encerra os processos criados ao terminar. Nesta entrega, o Maven instalado com o NetBeans foi usado em modo offline com o repositório local, em lugar do wrapper.

## Limites e pendências

- A API mantém sua versão de coletas interna. Conflitos concorrentes detectados pelo servidor são tratados, mas um formulário antigo enviado depois de outra gravação pode sobrescrever os campos. Não há ETag/`If-Match` nem promessa de detecção de formulários desatualizados; a evolução desse contrato continua prevista no planejamento.
- A consulta de usuários não oferece edição de contas, alteração de perfis ou recuperação de senha nesta área. A etapa solicitada prevê consulta, e esses fluxos não foram acrescentados.
- Os vínculos de coleta usam IDs opcionais, sem seletor com busca de nomes. Praias e materiais continuam como texto livre, conforme o contrato atual.
- A suíte MySQL real e o CI remoto não foram executados nesta entrega. Não houve alteração de esquema ou persistência; a validação desta interface utilizou H2. Nenhuma hospedagem, homologação, proteção contra abuso ou relatório foi implementado.
- As alterações locais anteriores à tarefa, incluindo o planejamento, o resumo anterior e arquivos já modificados ou preparados no Git, foram preservadas. Não foi feito commit nem push.

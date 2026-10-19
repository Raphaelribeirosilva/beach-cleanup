# Etapa 18 — Portal do voluntário

Data: 15/10/2026.

## Objetivo e entrega

Interface web em português para completar o fluxo do voluntário: criar conta, entrar, encontrar um evento, inscrever-se, registrar uma coleta e acompanhar o total coletado. HTML, CSS e JavaScript são servidos pelo Spring Boot na raiz `/`, dentro do mesmo JAR e da mesma origem da API. Não há instalação npm, servidor frontend separado, CDN ou alteração de esquema.

O visual usa cores de mar e areia, ilustração SVG local e layout adaptado a desktop e celular. Há navegação por teclado, rótulos de formulário, link para pular ao conteúdo, diálogos nativos, mensagens de carregamento/erro e estados vazios. São apresentados somente dados retornados pela API.

## Funcionalidades

- Exploração pública dos eventos com filtros por local e todos os status, paginação de seis registros e indicação de data/local.
- Cadastro público de voluntário e login automático após cadastro bem-sucedido; mensagem de recuperação caso o cadastro funcione e o login falhe.
- Login, saída e tratamento de expiração da sessão.
- Inscrição/cancelamento conforme as permissões e estados existentes; eventos pausados, concluídos e cancelados não oferecem nova inscrição/coleta.
- Área de inscrições da própria conta, incluindo eventos encerrados, com cancelamento permitido pela API.
- Registro de coleta independente pelo botão geral ou vinculada a um evento pelo cartão dele. O vínculo pode ser removido antes de salvar; inscrição não é requisito para coleta, conforme a regra existente.
- Histórico paginado de coletas e painel do total coletado em kg e quantidade de inscrições. O total vem da agregação já implementada no backend.

O histórico sempre envia `volunteerId` da conta autenticada, inclusive quando um administrador usa o portal. Assim, esta interface mantém o foco nas próprias coletas. A API continua aplicando suas regras de autorização.

## Como usar

```powershell
.\mvnw.cmd -B package
java -jar target/beachcleanup-0.0.1-SNAPSHOT.jar --spring.profiles.active=test
```

Abra `http://localhost:8080/`. No perfil de teste, use `ana@example.com` / `ana123` ou cadastre uma conta. O evento inicial é pausado, portanto só permite consulta. Para ensaiar inscrição e coleta vinculada, cadastre um evento `Agendado` pela API com uma conta administradora. O portal não cria eventos fictícios.

Para MySQL ou produção, use as variáveis e perfis descritos no README e na etapa 17. O portal fica disponível no mesmo endereço do backend; não publique o perfil `test` com as contas de exemplo.

## Integração e segurança

`VolunteerPortalSecurityConfig` libera somente GET/HEAD dos cinco caminhos estáticos: `/`, `/index.html`, `/portal.css`, `/portal.js` e `/portal-api.js`. A cadeia tem ordem posterior à segurança operacional e anterior à cadeia geral; não altera os arquivos de segurança preexistentes nem abre as rotas privadas da API.

As respostas do portal enviam Content Security Policy com scripts, estilos e conexões restritos à mesma origem, sem scripts inline, objetos ou incorporação em frames. Dados da API são inseridos com `textContent`, sem interpretação como HTML.

Atualização da sessão: o portal guarda o access token e o refresh token em `sessionStorage`, renova o acesso por até sete dias desde o login e revoga a sessão no servidor ao sair. A rotação não amplia o prazo absoluto. A troca de senha revoga todas as sessões. Sem armazenamento disponível, o login continua em memória. Não há cookie de autenticação. Consulte a [etapa 29](29_RENOVACAO_E_ENCERRAMENTO_DE_SESSAO.md) para expiração, concorrência, proteção das credenciais e falha de conexão no logout.

Campos obrigatórios e peso recebem validação no navegador, mas o backend permanece a fonte de validação e autorização. O limite de senha é conferido em bytes UTF-8. Rascunhos não são persistidos.

## Verificação reproduzível

```powershell
.\mvnw.cmd -B package
node --test tools/portal-api.test.cjs
python tools/validate_openapi.py
node tools/verify_portal_browser.cjs
```

Os testes JavaScript e de navegador exigem Node.js 24 ou superior e usam apenas módulos nativos. O teste de navegador usa Microsoft Edge instalado no caminho padrão do Windows; alternativamente, configure `PORTAL_BROWSER` com o caminho de um executável Chromium compatível. Não requer instalação de Playwright.

O script de navegador inicia seu próprio JAR com H2 em memória e porta temporária. Cria eventos/conta de teste nessa instância, verifica cadastro/login, busca/paginação, inscrição/cancelamento, coletas vinculada/independente, totais, isolamento entre contas, escape de texto, saída/recarregamento e ausência de exceções JavaScript/violações CSP. Verifica largura de celular e salva capturas em `target/portal-desktop.png` e `target/portal-mobile.png`. Ao terminar, encerra os processos criados e remove somente o perfil temporário do navegador. Não usa o MySQL do usuário.

`VolunteerPortalIntegrationTests` verifica a entrada pública e seus assets, os cabeçalhos de segurança e a manutenção da proteção da API e de caminhos não liberados. Os cinco testes Node cobrem o cliente HTTP, JWT, filtros, isolamento de conta, expiração, respostas atrasadas, erros e falhas de rede. O CI passa a executar os testes Node além das verificações já existentes; o ensaio de navegador é local.

## Resultado verificado

- Suíte Java completa: **71 testes aprovados, zero falhas, erros ou testes ignorados**, com empacotamento aprovado.
- Cliente JavaScript: **5 testes aprovados**, sem dependências npm.
- OpenAPI: estrutura e cobertura aprovadas, mantendo **13 caminhos e 23 operações**.
- Ensaio no Microsoft Edge sem janela: **aprovado** para o fluxo completo descrito acima, com total de 3,75 kg após duas coletas, isolamento entre contas e layout sem transbordamento horizontal em largura de 390 px. Capturas de desktop/celular também foram inspecionadas visualmente.

A primeira execução completa no sandbox falhou apenas na remoção de dois diretórios temporários do JUnit por acesso negado. A repetição fora dessa restrição passou integralmente. Não foram repetidos os testes em MySQL real, pois não há alteração de persistência nesta etapa. Não houve publicação nem execução de CI remoto.

## Limites e próximas etapas

Esta entrega não inclui interface administrativa, alteração/exclusão de coletas pelo portal, recuperação de senha ou relatórios agregados por evento/praia/material/período. Essas operações e evoluções permanecem nas etapas seguintes. A interface não acrescenta controle de versão do formulário; mantém o contrato de concorrência já documentado para a API.

## Git

O commit desta etapa contém apenas o portal, sua configuração de acesso, testes, CI e documentação. Alterações locais preexistentes, inclusive arquivos já preparados no índice, são preservadas e excluídas deste commit. Nenhum push ou deploy faz parte desta entrega.

## Login e cadastro: senha e erros nos campos (19/10/2026)

O formulário oferece Mostrar/Ocultar senha, inclusive por teclado. A senha volta a ficar oculta ao fechar ou alternar entre login e cadastro. Os erros de nome, e-mail e senha aparecem junto ao respectivo campo, com destaque visual, `aria-invalid`, descrição associada e foco no primeiro campo inválido. Os valores digitados são preservados e o erro do campo é removido ao editá-lo.

O cliente preserva `errors[].fieldName` das respostas da API. E-mail duplicado no cadastro destaca o e-mail; credenciais inválidas no login destacam e-mail e senha com a mesma mensagem genérica. Falhas sem campo específico, como indisponibilidade e conexão, continuam na mensagem geral. Não há mudança nos endpoints ou nos payloads de autenticação.

Verificação: testes do cliente e fluxos reais de navegador cobrem exibição da senha, campos obrigatórios, política de senha retornada pelo servidor, limite UTF-8, e-mail duplicado, credenciais inválidas, foco e limpeza ao editar/alternar formulário.

### Ajuste da apresentação e do mínimo de senha (19/10/2026)

Mostrar senha agora é uma checkbox acessível por teclado; ela é desmarcada ao fechar ou alternar o formulário. Os links de conta, recuperação e confirmação ficam em linhas separadas com espaçamento. O texto permanente de requisitos abaixo da checkbox foi removido; erros continuam aparecendo junto ao campo quando necessário.

Senhas novas passam a exigir pelo menos 7 caracteres Unicode, em cadastro, troca e redefinição, tanto na interface quanto no backend e no OpenAPI. O limite de 72 bytes UTF-8 e as rejeições de senhas comuns/repetidas foram preservados. O login continua aceitando as credenciais de contas existentes.

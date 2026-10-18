# Renovação e encerramento de sessão

O portal renova o login durante uma sessão permitida de até **sete dias**, sem pedir novamente a senha a cada 30 minutos. A renovação usa refresh tokens opacos com rotação e revogação persistidas. Esta entrega conclui o lembrete de refresh token que estava pendente na [etapa 22](22_EXPERIENCIA_DE_SESSAO.md).

## Validade e contrato

- `POST /auth/login` retorna os campos anteriores e acrescenta `refreshToken` e `refreshExpiresIn`. Cada login cria uma sessão independente, identificada pelo claim `sid` do JWT.
- O access token vale até **1.800 segundos**, limitado pelo prazo restante da sessão. Sua expiração é conferida sem tolerância adicional; atingir `exp` já o invalida.
- O refresh token tem 32 bytes aleatórios, codificados em Base64 URL sem padding (43 caracteres). O prazo absoluto é de **604.800 segundos a partir do login**. Rotação e recarga da página não estendem esse limite. O banco guarda somente SHA-256, a sessão associada e a indicação de consumo, sem guardar o token original ou uma cópia recuperável.
- `POST /auth/refresh`, com `Content-Type: application/json` e corpo `{"refreshToken":"..."}`, consome o token e retorna outro par. Não exige access token válido. Respostas de login e refresh usam `Cache-Control: no-store`.
- `POST /auth/logout`, com o mesmo formato JSON, revoga a sessão inteira. Um token antigo já consumido também identifica e encerra sua família. O logout é idempotente: uma credencial de formato válido desconhecida ou já revogada retorna 204.
- Refresh token desconhecido, expirado, reutilizado ou de sessão revogada recebe 401. Formato ou JSON inválido recebe 400; conteúdo de formulário recebe 415. Nenhuma mensagem de erro devolve a credencial.

O [OpenAPI](openapi.json) descreve o contrato completo, inclusive a validade menor do access token no final dos sete dias.

## Rotação, concorrência e revogação

Um refresh token só pode ser consumido uma vez. Seus hashes anteriores ficam vinculados à família, permitindo detectar reutilização. A reutilização revoga **todos os refresh tokens e access tokens daquela sessão**, inclusive um par emitido pela renovação anterior. Outras sessões de login continuam válidas.

As transações bloqueiam primeiro a conta e depois a sessão/token. Leituras com bloqueio evitam consultar uma versão antiga depois de aguardar uma rotação concorrente no MySQL `REPEATABLE READ`. A revogação por reutilização é confirmada no banco mesmo quando a resposta é 401; a exceção de credenciais não desfaz essa transação.

Duas renovações simultâneas com o mesmo token podem produzir uma resposta 200 e uma 401. A segunda detecta reutilização e encerra a família, portanto o par da primeira também deixa de ser válido. Não existe janela de tolerância que permita reutilizar a credencial. O portal compartilha uma única renovação em andamento entre as requisições da mesma aba. Duplicar uma aba pode copiar `sessionStorage`: não use o mesmo refresh token em clientes independentes; faça login independente em cada um. Uma resposta de renovação perdida não deve ser repetida automaticamente, pois o servidor pode ter consumido o token.

O logout revoga também access tokens já copiados. O servidor confere assinatura, validade, conta, versão das credenciais e sessão persistida nas requisições protegidas. Após confirmar a revogação, esses access tokens recebem 401. Uma operação autorizada antes da revogação pode terminar; o logout não desfaz uma operação de negócio já aceita. Na troca de senha, a atualização da senha, o incremento de `credentialVersion` e a revogação de **todas** as sessões da conta ocorrem na mesma transação. Excluir a conta elimina também suas sessões e hashes por chave estrangeira com cascata.

## Portal, armazenamento e encerramento

O portal guarda o par e seus prazos absolutos em `sessionStorage`, na chave `beachcleanup.session.v2`. O access token é renovado aproximadamente 30 segundos antes de expirar ou antes de uma requisição privada quando necessário. Recarregar a mesma aba pode renovar um access token vencido se a sessão ainda permitir. A rotação atualiza o armazenamento sem aumentar o prazo absoluto inicial. Quando o armazenamento está indisponível, as credenciais ficam somente em memória.

Respostas atrasadas de login/renovação não restauram uma sessão encerrada. Requisições de escrita não são repetidas automaticamente após 401. Falha na renovação exige novo login; o cliente não repete a credencial de uso único.

**Sair** limpa imediatamente as credenciais locais e os dados privados e solicita a revogação no servidor. A confirmação de encerramento só aparece após a resposta do servidor. Em falha de conexão, o aviso informa que o encerramento remoto não foi confirmado e oferece **Tentar encerrar no servidor**. A credencial necessária para essa tentativa permanece apenas em memória; recarregar/fechar a aba perde essa tentativa. Fechar a aba sem sair não garante revogação no servidor. O limite de sete dias e a troca de senha continuam aplicáveis.

Não se armazenam senha ou perfil no armazenamento persistente. `sessionStorage` continua acessível a scripts da mesma origem; é uma escolha compatível com o portal atual, não uma proteção contra XSS. A CSP restrita à mesma origem e a renderização por `textContent` continuam necessárias. Use HTTPS no ambiente hospedado e não registre corpos de login/refresh/logout, tokens ou cabeçalhos de autorização nos logs.

## Cookies e CSRF

Este fluxo **não usa cookies de autenticação**: não há `Set-Cookie`, o cliente usa `credentials: omit` e os endpoints ignoram cookies como credenciais. Refresh/logout exigem um segredo explícito no JSON, com tipo de conteúdo que requer preflight em chamadas de outra origem; não foi habilitado CORS. Formulários e cookies isolados não autenticam essas rotas. A desativação de CSRF dessas duas rotas se apoia nessa ausência de credenciais enviadas automaticamente pelo navegador.

Uma futura troca para cookie exige nova revisão de CSRF e de `HttpOnly`, `Secure`, `SameSite`, `Path` e domínio antes de habilitar o cookie. Essa alteração não pode simplesmente reutilizar a configuração atual.

## Migração e operação

A migração **V8** cria `tb_auth_session` e `tb_refresh_token`, índices e chaves estrangeiras com cascata. Atualize pelo Flyway e mantenha o procedimento de backup/restauração do projeto. Tokens antigos sem `sid` deixam de autenticar após a atualização; o portal remove a chave v1 e solicita novo login. Não há conversão insegura de access tokens antigos em sessões renováveis.

Os hashes consumidos precisam permanecer até a sessão expirar, para detectar reutilização. A entrega não agenda descarte automático: na manutenção do banco, sessões expiradas podem ser removidas com `DELETE FROM tb_auth_session WHERE expires_at <= CURRENT_TIMESTAMP(6)`; os hashes associados são removidos em cascata. Não remova hashes de uma sessão ainda válida. Os prazos e a revogação não dependem dessa limpeza.

## Verificações

- `RefreshSessionIntegrationTests`: hash, rotação, prazo absoluto, limite exato de expiração, access token vencido, reutilização com revogação confirmada, preservação de outras sessões, logout idempotente por token antigo, troca de senha, renovações simultâneas, corrida entre logout/refresh, JSON obrigatório e ausência de cookie.
- `MySqlDatabaseIT`: sessões persistentes após reinício, rotação concorrente sob isolamento real, revogação do par vencedor por reutilização, logout, expiração, troca de senha e cascata ao excluir a conta. A suíte também valida as migrações V1–V8 e backup/restauração.
- `tools/portal-api.test.cjs`: uma renovação para requisições concorrentes, rotação do armazenamento sem estender prazo, recarga, renovação antecipada, logout durante renovação, respostas atrasadas, falha de rede/repetição explícita do logout e ausência de repetição automática de escritas.
- `tools/verify_portal_browser.cjs`: renovação após recarga sem novo login, rotação de ambas as credenciais, prazo máximo preservado, logout no servidor, rejeição do JWT copiado e descarte de sessão totalmente expirada, junto das regressões do portal.

Referência para rotação e detecção de reutilização: [RFC 9700, seção 4.14.2](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14.2). A política de sete dias e a revogação imediata de access tokens são decisões desta aplicação.

## Resultados da entrega

Verificado em 18/10/2026:

- Maven `-Pmysql-it verify`: **131 testes aprovados**, sendo 12 em MySQL 8.0 real, zero falhas, erros ou testes ignorados; JAR empacotado. O script utilizou servidor e bancos descartáveis, sem acessar o banco de uso da aplicação.
- Cliente Node: **25 testes aprovados**. Auditoria decimal Python: **3 testes aprovados**.
- OpenAPI: estrutura, referências e cobertura aprovadas para **22 caminhos e 33 operações**.
- Edge headless: aprovado em computador/celular, incluindo renovação silenciosa, rotação, prazo absoluto, ausência de cookies, falha de conexão no logout com aviso correto, nova tentativa explícita e rejeição do access token já copiado. As regressões de perfil, senha, coletas, relatórios, administração e CSP também passaram.
- Conferidos os 54 links locais de README, portal, etapa 22 e deste documento. As verificações usam a árvore de trabalho atual; alterações anteriores foram preservadas e ficam fora do commit desta entrega.

O lembrete de refresh token está concluído por esta entrega verificada. Não houve implantação nem push.

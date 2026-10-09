# Etapa 4 — Autenticação e autorização

## Objetivo

Adicionar login por e-mail/senha, tokens JWT e permissões para voluntários e administradores. Impedir que uma conta consulte ou altere os dados privados de outra, mantendo o contrato de erro da etapa 3.

Etapa implementada em 09/10/2026. Este documento atualiza as regras de acesso dos endpoints existentes; exemplos anteriores agora precisam de token quando a rota é protegida.

## O que mudou

| Área | Antes | Agora |
|---|---|---|
| Login | Não existia | `POST /auth/login`, com e-mail e senha |
| Acesso aos dados | Endpoints sem autenticação | Filtros do Spring Security e regras de acesso por recurso |
| Perfil do usuário | Não existia | `VOLUNTEER` ou `ADMIN`, persistido no banco |
| Cadastro público | Criava voluntário | Continua público e sempre cria `VOLUNTEER` |
| E-mail | Podia se repetir | Normalizado para minúsculas, com verificação e restrição de unicidade |
| Coletas de voluntário | Qualquer cliente podia indicar um vínculo | Usuário comum registra e gerencia somente coletas ligadas à própria conta |
| Listagem de coletas | Todos os registros | Voluntário vê as próprias; administrador vê todas |
| Erros de segurança | Não existiam | 401/403 no mesmo formato de `StandardError` |
| Console H2 | Habilitado no perfil de teste | Acesso HTTP bloqueado pela cadeia de segurança |

## Matriz de permissões

| Operação | Sem token | VOLUNTEER | ADMIN |
|---|---|---|---|
| POST `/volunteers` | Permitido | Permitido | Permitido; ainda cria VOLUNTEER |
| POST `/auth/login` | Permitido | Permitido | Permitido |
| GET `/cleanupEvent` e `/cleanupEvent/{id}` | Permitido | Permitido | Permitido |
| POST/PUT/DELETE de eventos | 401 | 403 | Permitido |
| GET `/volunteers` | 401 | 403 | Lista todos |
| GET/PUT/DELETE `/volunteers/{id}` | 401 | Somente o próprio ID | Qualquer ID |
| GET `/trashCollection` | 401 | Lista somente coletas próprias | Lista todas |
| GET/PUT/DELETE `/trashCollection/{id}` | 401 | Somente coletas próprias | Qualquer coleta |
| POST `/trashCollection` | 401 | Cria coleta vinculada à própria conta | Pode escolher vínculos ou deixá-los nulos |
| `/h2-console/**` | 401 | 403 | 403 |

Uma rota não declarada pública exige autenticação. Assim, chamadas anônimas podem receber 401 antes do 404/405 do MVC. Após a autenticação, a validação e o tratamento de erros da etapa 3 continuam ativos.

O administrador é um perfil de conta, sem endpoint público de promoção. Enviar `role` no cadastro ou no PUT não altera permissões. O service também força `VOLUNTEER` no cadastro. O perfil persistido é consultado a cada requisição autenticada; a API não confia em uma claim de papel enviada no token.

## Como usar

### 1. Cadastrar um voluntário

```http
POST /volunteers
Content-Type: application/json
```

```json
{
  "name": "Ana",
  "email": "ana@example.com",
  "password": "senha-de-exemplo"
}
```

O cadastro continua retornando 201, `Location` e `VolunteerDTO`, sem senha/hash. O e-mail é armazenado em minúsculas. Tentativa de cadastrar ou atualizar para um e-mail ocupado retorna 409; a atualização rejeitada preserva os dados anteriores.

`VolunteerService.normalizeEmail` remove espaços nas extremidades e usa `Locale.ROOT`. Os DTOs continuam exigindo o formato de e-mail da etapa 3: clientes devem enviar um endereço válido sem espaços externos. O login também procura pelo e-mail normalizado.

### 2. Fazer login

```http
POST /auth/login
Content-Type: application/json
```

```json
{
  "email": "ana@example.com",
  "password": "senha-de-exemplo"
}
```

Resposta 200, com `Cache-Control: no-store`:

```json
{
  "accessToken": "<token-JWT-retornado-pela-API>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "volunteerId": 2,
  "role": "VOLUNTEER"
}
```

`expiresIn` é medido em segundos: o token vale por 15 minutos. O login não cria uma sessão HTTP. E-mail não cadastrado e senha incorreta retornam a mesma mensagem 401. Para contas inexistentes, a verificação usa um hash BCrypt auxiliar, evitando pular o trabalho de comparação. Senha de login acima de 72 bytes retorna 401; campos ausentes/em branco ou e-mail malformado retornam 400 com `errors`.

### 3. Enviar o token

```http
GET /volunteers/2
Authorization: Bearer <accessToken>
```

Envie esse cabeçalho em cada chamada protegida. A autenticação aceita Bearer no cabeçalho, sem cookie de sessão ou login Basic. Após expirar, faça login novamente. Se o cliente enviar um Bearer inválido em uma rota pública, o filtro também o rejeita: remova o cabeçalho ou use um token válido.

## Regras para coletas

Para `VOLUNTEER`, o servidor define `volunteer` como a conta autenticada no POST e no PUT. O cliente pode omitir o vínculo, enviá-lo como `null` ou indicar seu próprio ID; o resultado permanece vinculado ao usuário. Indicar outro ID positivo retorna 403 e impede a operação. Objetos de vínculo sem ID positivo continuam retornando 400 pela validação.

Exemplo de criação por um voluntário:

```json
{
  "beach": "Praia do Morro",
  "materialType": "Plástico",
  "weithKg": 2.5,
  "regitrationDate": "2026-10-15",
  "event": { "id": 2 }
}
```

Os campos `weithKg` e `regitrationDate` foram preservados. O vínculo com evento permanece opcional e deve apontar para um evento existente quando enviado. Inscrição no evento ainda não é uma exigência de negócio nesta etapa.

`ADMIN` mantém a capacidade da etapa 1 de criar coletas sem voluntário/evento, trocar vínculos e removê-los com `null` ou omissão. Coletas sem voluntário são acessíveis somente a administradores. Usuários comuns recebem 403 ao buscar uma coleta de outra conta ou um ID de coleta inexistente, pois a propriedade não pode ser confirmada. Administradores recebem 404 para registros inexistentes.

## Tokens e configuração

O JWT usa assinatura HS256. O `sub` é o ID estável do voluntário; mudar o e-mail não invalida essa identidade. O token inclui `iss`, `aud`, `iat`, `nbf`, `exp` e `jti`, sem senha, hash ou e-mail. O emissor fixo é `https://beachcleanup.example` e a audiência é `beachcleanup-api`; essa URL identifica o emissor e não é consultada na rede. Emissão e validação ocorrem na própria aplicação.

O decoder verifica assinatura, algoritmo, emissor, audiência, expiração obrigatória e instante de início de validade. A tolerância temporal é zero. Após validar o JWT, o filtro consulta a conta no banco e usa seu perfil atual. Conta excluída recebe 401 mesmo com um token ainda não expirado; mudança de perfil passa a valer na próxima requisição.

Essa configuração usa o suporte oficial de [Resource Server JWT do Spring Security](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html) e [NimbusJwtEncoder](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/jwt/NimbusJwtEncoder.html). As permissões dos métodos HTTP utilizam [Method Security](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html), com `@EnableMethodSecurity` e `@PreAuthorize`. Não foi criado um servidor OAuth2 completo.

### Chave de assinatura

`security.jwt.secret` lê a variável de ambiente `JWT_SECRET`. O valor deve ser Base64 de pelo menos 32 bytes aleatórios. A chave não foi gravada no repositório.

No perfil `test`, se a variável estiver vazia, a aplicação gera uma chave aleatória em memória na inicialização. Reiniciar essa instância invalida seus tokens. Se a variável estiver definida, a chave fornecida é usada e deve ser válida mesmo no perfil de teste.

Fora de `test`, chave ausente, Base64 inválido ou menos de 32 bytes impedem a inicialização. Para gerar uma chave para a sessão atual do PowerShell:

```powershell
$jwtBytes = New-Object byte[] 32
$jwtRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$jwtRng.GetBytes($jwtBytes)
$jwtRng.Dispose()
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
```

Em um ambiente persistente, mantenha a chave no gerenciador de segredos do ambiente para que reinícios não a substituam. Todos os processos que compartilham a autenticação precisam da mesma chave. Alterar a chave invalida os tokens anteriores.

### Administrador local

O perfil `test`, já ativo por padrão no projeto, mantém os dados H2 em memória e o seed existente. A conta `admin-original@example.com`, senha `demo-original7!`, agora é `ADMIN`. Ela serve para testar gestão de eventos e operações administrativas localmente. Fora desse perfil, o seed não é executado.

Não existe criação automática de administrador fora de `test`. O perfil administrativo precisa ser provisionado por uma operação controlada no banco, junto com a configuração do ambiente persistente. O cadastro público nunca promove usuários.

O console H2 está bloqueado por HTTP, inclusive para administradores. A aplicação continua usando H2 no perfil de teste; migrações e configuração de produção ainda não foram implementadas.

## Erros de segurança

Os filtros, que executam antes dos controllers, escrevem `StandardError` por meio de `SecurityErrorHandler`. O advice também trata exceções de autenticação/autorização disparadas no login e nos métodos protegidos, evitando convertê-las em 500.

```json
{
  "timestamp": "2026-10-09T21:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Autenticação necessária ou credenciais inválidas.",
  "path": "/volunteers"
}
```

401 inclui `WWW-Authenticate: Bearer`. Para usuário autenticado sem permissão, a resposta é 403, `error` igual a `Forbidden` e mensagem `Você não tem permissão para acessar este recurso.`. As respostas não incluem o token inválido, a senha ou detalhes da assinatura.

A unicidade é verificada no service e no banco. A verificação prévia retorna 409 com `Email conflict`; se requisições concorrentes ultrapassarem essa verificação, a restrição do banco é traduzida pelo handler existente de conflito de integridade.

## Verificação

Os testes anteriores de CRUD passam pela cadeia real do Spring Security com usuário administrativo simulado. Os novos testes de segurança não simulam o login: usam o endpoint, o token emitido, o filtro real, os controllers e o banco H2 próprio (`securitytests`).

São verificados:

- Cadastro público, normalização de e-mail e tentativa de promoção por `role` no JSON.
- Login válido, duração/claims do token, `no-store`, ausência de cookie de sessão e acesso com Bearer.
- 401 sem token, com senha errada ou conta inexistente; validação de campos de login.
- 403 para acesso ao perfil/coletas de outro usuário e gestão de eventos por voluntário.
- Proprietário definido no servidor, listagem filtrada e impedimento de transferência de coleta.
- Operações administrativas e perda imediata de privilégios após alteração do perfil no banco.
- Token malformado, assinatura de outra chave, expiração, validade futura, emissor/audiência incorretos, claims obrigatórias ausentes e conta inexistente.
- Exclusão da conta invalidando seu acesso e mudança de e-mail preservando a identidade do token.
- Tentativa de obter privilégios por claim `role`, ignorada em favor do perfil persistido.
- E-mail duplicado em cadastro/PUT, preservando dados na rejeição.
- Chave obrigatória fora de teste, validação de Base64/tamanho, preservação de chave fornecida e geração de chave temporária.

Comando neste ambiente:

```powershell
& 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository' -o -B test
```

Resultado: **BUILD SUCCESS — 29 testes, 0 falhas, 0 erros e 0 ignorados**: 1 teste de contexto/seed, 18 de CRUD/validação, 7 de integração de segurança e 3 de configuração da chave. As dependências novas foram baixadas em uma execução inicial online; a execução final foi offline com o cache preenchido.

## Arquivos desta etapa

Sob `src/main/java/com/raphael/beachcleanup/`:

- Criados `config/JwtConfig.java`, `config/SecurityConfig.java`, `security/AccessPolicy.java` e `security/SecurityErrorHandler.java`.
- Criados `entities/VolunteerRole.java`, `dto/LoginDTO.java`, `dto/TokenDTO.java`, `services/AuthService.java`, `resources/AuthResource.java` e `exceptions/EmailConflictException.java`.
- Alterados `entities/Volunteer.java`, `services/VolunteerService.java`, os repositories de voluntários/coletas, os três controllers, `config/TestConfig.java` e `exceptions/ResourceExceptionHandler.java`.

Também foram alterados `pom.xml`, `src/main/resources/application.properties`, o teste de CRUD e o índice de documentos. Foram criados `SecurityIntegrationTests.java` e `JwtConfigTests.java`. A alteração local preexistente em `ValidationError.java` permanece fora desta etapa e do commit.

## Continuação e limites atuais

Troca/redefinição de senha é o próximo fluxo a implementar, com verificação da identidade e política de invalidação de tokens. Esta etapa não inclui refresh token, logout/revogação individual de JWT, confirmação de e-mail, recuperação de senha, bloqueio de conta ou limitação de tentativas de login. Encerrar o uso do token no cliente não o revoga no servidor; ele permanece válido até expirar, salvo exclusão da conta ou troca da chave.

O acesso é stateless por Bearer enviado explicitamente; CSRF está desativado para esse contrato. Uma futura autenticação por cookies precisa rever essa decisão. CORS para um frontend em outro domínio ainda não foi configurado. Transporte HTTPS e proteção de segredos devem ser configurados no ambiente de execução.

Para banco persistente já existente, antes de usar esta versão será necessário normalizar e deduplicar e-mails, preencher `role` e aplicar as restrições por migração. A alteração nas anotações JPA foi verificada no H2 recriado pelos testes e não substitui uma migração de dados de produção.

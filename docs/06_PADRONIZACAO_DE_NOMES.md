# Etapa 6 — Padronização de nomes

## Objetivo

Refatoração realizada em 09/10/2026 para representar contas como `User`, distinguir a conta do papel de voluntário e corrigir os nomes de peso e data. Este documento descreve o contrato vigente da API. Os documentos das etapas 1 a 5 e a análise inicial permanecem como histórico; seus exemplos usam nomes anteriores.

`CleanupEvent` e `TrashCollection` mantêm seus nomes. BCrypt, CRUD, validações, JWT, autorização, troca de senha, invalidação dos tokens e controle de concorrência mantêm o comportamento existente.

## Mapeamento dos nomes

| Nome anterior | Nome atual |
|---|---|
| `Volunteer` | `User` |
| `VolunteerRole` | `UserRole` |
| `VolunteerService` | `UserService` |
| `VolunteerRepository` | `UserRepository` |
| `VolunteerResource` | `UserResource` |
| `VolunteerDTO` | `UserDTO` |
| `VolunteerInsertDTO` | `UserInsertDTO` |
| `VolunteerUpdateDTO` | `UserUpdateDTO` |
| `/volunteers` e `/volunteers/{id}` | `/users` e `/users/{id}` |
| `tb_volunteer` | `tb_user` |
| `weithKg` | `weightKg` |
| `regitrationDate` | `registrationDate` |
| `totalTrashCollecting` | `totalCollectedKg` |
| `volunteerId` na resposta de login | `userId` |
| `AccessPolicy.canAccessVolunteer` | `AccessPolicy.canAccessUser` |
| `CleanupEvent.volunteers` / `getVolunteers()` | `CleanupEvent.users` / `getUsers()` |

Arquivos, imports, construtores, getters/setters, tipos de repositórios, DTOs, políticas de acesso, dados iniciais e testes foram atualizados. `UserRole` conserva exatamente os valores `VOLUNTEER` e `ADMIN`; as authorities continuam `ROLE_VOLUNTEER` e `ROLE_ADMIN`.

### Unidade do total coletado

Antes da renomeação foram conferidos o modelo, os services, o seed e a documentação. A etapa 1 documenta o peso das coletas em kg; a análise inicial descreve o total como acumulado coletado e compara seu valor à coleta em kg. Essa é a unidade de domínio adotada para `totalCollectedKg`; não houve conversão ou recálculo dos valores.

O total continua sendo um valor armazenado: começa em `0.0` no cadastro, não pode ser alterado pelo PUT de perfil e não é recalculado ao criar, editar ou excluir coletas. O administrador original do seed conserva total `20.0` e coleta de `15.5` kg. A divergência preexistente e a ausência de agregação permanecem pendentes, sem mudar o comportamento nesta refatoração.

### Vínculo da coleta

`TrashCollection.volunteer`, seus getters/setters e o campo JSON `volunteer` continuam representando quem realizou a coleta. O tipo da associação JPA agora é `User`, e a resposta aninhada usa `UserDTO`. A consulta derivada `findAllByVolunteerId` permanece porque percorre `volunteer.id`.

`User.events` e `CleanupEvent.users` preservam o relacionamento de inscrições. O relacionamento inverso `User.collection` continua com `mappedBy = "volunteer"`.

## Atualização dos clientes

Atualize URLs, corpos de POST/PUT, leitura de respostas, campos de erro, testes e coleções do Postman:

- Contas: todas as chamadas CRUD passam a usar `/users`; o header `Location` de cadastro também aponta para `/users/{id}`.
- Login: leia `userId` em vez de `volunteerId`. Use esse ID em `/users/{id}`.
- Coletas: envie/leia `weightKg` e `registrationDate`; os nomes de validação também usam esses campos.
- Perfis: leia `totalCollectedKg`, inclusive dentro de `volunteer` na resposta de uma coleta.
- Mantenha o corpo do vínculo como `"volunteer": {"id": ...}`. Não o substitua por `user` ou `userId`.

Não foram mantidas rotas ou aliases JSON antigos. As rotas antigas deixam de atender o CRUD; requisições autenticadas recebem 404, e requisições anônimas podem receber 401 antes do roteamento. Campos desconhecidos seguem o tratamento anterior da aplicação. Enviar apenas os nomes antigos do peso/data não preenche os novos campos obrigatórios e resulta em 400. O total é somente leitura no contrato de cadastro/edição do perfil.

### Rotas e permissões vigentes

| Operação | Sem token | VOLUNTEER | ADMIN |
|---|---|---|---|
| POST `/users` | Permitido | Permitido | Permitido; sempre cria VOLUNTEER |
| POST `/auth/login` | Permitido | Permitido | Permitido |
| GET `/users` | 401 | 403 | Permitido |
| GET/PUT/DELETE `/users/{id}` | 401 | Somente a própria conta | Qualquer conta |
| GET `/cleanupEvent` e `/cleanupEvent/{id}` | Permitido | Permitido | Permitido |
| POST/PUT/DELETE de eventos | 401 | 403 | Permitido |
| GET `/trashCollection` | 401 | Somente as próprias coletas | Todas as coletas |
| GET/PUT/DELETE `/trashCollection/{id}` | 401 | Somente as próprias coletas | Qualquer coleta |
| POST `/trashCollection` | 401 | Vinculado à própria conta | Pode escolher vínculos ou deixá-los nulos |
| PUT `/auth/password` | 401 | Troca a própria senha | Troca a própria senha |

Cadastro/edição não permitem escolher ou elevar o perfil. E-mails continuam normalizados e únicos. As respostas não expõem senha, hash BCrypt, `credentialVersion` ou `rowVersion`. Erros mantêm o contrato `StandardError`/`ValidationError`.

JWT continua com duração de **900 segundos (15 minutos)**, mesma chave, emissor, audiência e claim `credentialVersion`. A identidade continua no `sub`, sem renomear claims. A troca de senha incrementa a versão das credenciais e invalida os tokens anteriores; `User.rowVersion` mantém `@Version` para impedir sobrescritas concorrentes. Não foi implementado refresh token.

## Exemplos para o Postman

Use `http://localhost:8080` como base. Nos endpoints protegidos, configure `Authorization: Bearer <accessToken>`; para os corpos JSON, use `Content-Type: application/json`.

### Cadastro

```http
POST /users
Content-Type: application/json
```

```json
{
  "name": "Marina Silva",
  "email": "marina@example.com",
  "password": "senha-inicial"
}
```

Sucesso: 201, `Location: http://localhost:8080/users/<id>`, com corpo neste formato (ID ilustrativo):

```json
{
  "id": 6,
  "name": "Marina Silva",
  "email": "marina@example.com",
  "totalCollectedKg": 0.0
}
```

### Login e consulta/edição do perfil

```http
POST /auth/login
Content-Type: application/json
```

```json
{
  "email": "marina@example.com",
  "password": "senha-inicial"
}
```

```json
{
  "accessToken": "<JWT>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "userId": 6,
  "role": "VOLUNTEER"
}
```

Consulte `GET /users/<userId>` com o token. Para editar:

```http
PUT /users/<userId>
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "name": "Marina Souza",
  "email": "marina.souza@example.com"
}
```

O PUT exige nome e e-mail e preserva senha, perfil, total e inscrições. Para listar todas as contas, use `GET /users` com token de administrador. Para excluir, use `DELETE /users/<userId>` respeitando as permissões e os vínculos existentes; sucesso retorna 204.

### Coleta

```http
POST /trashCollection
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "beach": "Praia do Morro",
  "materialType": "Plástico",
  "weightKg": 2.5,
  "registrationDate": "2026-10-15"
}
```

Com token de voluntário, `volunteer` é vinculado automaticamente à própria conta. Administradores podem acrescentar `"volunteer": {"id": <userId>}`. Ambos podem acrescentar `"event": {"id": <eventId>}` com um evento existente. Os IDs devem ser positivos e resolvidos pelo servidor; não envie nome/senha do usuário no vínculo.

Sucesso retorna 201 com `weightKg`, `registrationDate`, `volunteer` (um `UserDTO`) e `event`. Para atualizar, use o mesmo formato em `PUT /trashCollection/<id>`; para consultar/excluir, use GET/DELETE nessa rota. As regras de propriedade, de vínculos nulos e de validação permanecem as anteriores.

### Troca de senha

```http
PUT /auth/password
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "currentPassword": "senha-inicial",
  "newPassword": "senha-nova"
}
```

Sucesso retorna 204 sem token novo. Faça login novamente com a senha nova; os tokens anteriores da conta passam a receber 401. Nenhum ID no corpo altera a conta alvo.

## Impacto no banco

Antes das alterações foram verificadas as configurações versionadas, arquivos locais de configuração/dados e as variáveis `SPRING_DATASOURCE`, `SPRING_PROFILES` e `SPRING_JPA` disponíveis na sessão. O perfil padrão é `test`; a URL é `jdbc:h2:mem:testdb`. Não foi encontrada configuração de banco persistente nem arquivo de dados persistente no projeto. A dependência MySQL, por si só, não configura uma conexão MySQL.

Não foi necessária migração de registros persistentes nesse ambiente. Nenhum comando de exclusão de dados foi executado para realizar a refatoração e as configurações de datasource/geração de esquema não foram alteradas. O ciclo normal do H2 em memória recria os dados ao reiniciar a aplicação; o seed foi atualizado para usar os novos tipos sem mudar contas, senhas ou valores.

| Objeto anterior | Objeto atual |
|---|---|
| Tabela `tb_volunteer` | Tabela `tb_user` |
| Coluna `total_trash_collecting` | Coluna `total_collected_kg` em `tb_user` |
| Coluna `weith_kg` | Coluna `weight_kg` em `tb_trash_collection` |
| Coluna `regitration_date` | Coluna `registration_date` em `tb_trash_collection` |

As colunas corrigidas têm nomes explícitos em `@Column`. `tb_trash_collection.volunteer_id` permanece e referencia `tb_user`. A tabela de inscrições `tb_event_volunteer` e suas colunas `volunteer_id`/`event_id` permanecem; a associação agora referencia `User`. IDs, tipo de geração, restrições de e-mail, papéis e versões de credenciais/concorrência foram preservados. As tabelas de eventos e coletas mantêm seus nomes.

Se outro ambiente usar configuração externa com banco persistente, será necessária uma migração específica para seu SGBD antes de iniciar esta versão: renomear a tabela/colunas existentes, preservar registros, IDs, hashes, papéis, versões e chaves estrangeiras dos dois vínculos. Geração automática de esquema não substitui essa migração; nenhum script foi aplicado a banco externo não identificado nesta sessão.

### Conferir no console H2

Com a aplicação reiniciada e rodando no perfil `test`, abra `http://localhost:8080/h2-console`, use JDBC URL `jdbc:h2:mem:testdb`, usuário `sa` e senha vazia. O nome `h2consoletests` pertence aos testes automatizados e não deve ser usado no console da aplicação.

```sql
SELECT id, name, email, role, total_collected_kg FROM tb_user;
SELECT id, weight_kg, registration_date, volunteer_id, event_id FROM tb_trash_collection;
```

O console continua liberado somente no perfil `test`, com frames na mesma origem. As rotas protegidas da API continuam exigindo token.

## Resultado dos testes

A suíte completa foi executada após limpar os artefatos compilados antigos da renomeação:

```powershell
& 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository' -o -B test
```

Resultado: **BUILD SUCCESS — 38 testes, 0 falhas, 0 erros e 0 ignorados**.

| Suíte | Testes |
|---|---|
| `BeachcleanupApplicationTests` | 1 |
| `CrudIntegrationTests` | 19 |
| `H2ConsoleIntegrationTests` | 2 |
| `JwtConfigTests` | 3 |
| `PasswordChangeIntegrationTests` | 6 |
| `SecurityIntegrationTests` | 7 |

Foram verificados CRUD em `/users`, header `Location`, ausência de mapeamento nas rotas antigas, nomes JSON corrigidos (incluindo a ausência dos anteriores nas respostas), `userId` no login, persistência nas novas colunas e o vínculo `volunteer` com uma conta `User`. O seed mantém suas cinco contas, senhas BCrypt, perfis, totais e associações de evento/coleta.

Os testes também cobrem validações e integridade referencial, proteção de senhas, login, expiração e validação do JWT, isolamento por propriedade, permissões de administrador/voluntário, troca de senha, invalidação dos tokens e alterações concorrentes. O console H2 segue acessível no perfil `test`, enquanto a API mantém sua proteção.

## Pendências que permaneceram

- Calcular/manter o total coletado de forma consistente ao criar, editar ou excluir coletas, incluindo a divergência do seed original.
- Preparar migração específica caso seja configurado um banco persistente externo; não há um configurado no projeto verificado.
- Recuperação de senha por e-mail, refresh token, logout/revogação individual de JWT, confirmação de e-mail e limitação de tentativas continuam fora desta tarefa.
- Inscrição em eventos por endpoint próprio e regras de transição de status permanecem fora da refatoração.
- Atualizar clientes e coleções do Postman para o contrato acima; os exemplos históricos não foram reescritos.

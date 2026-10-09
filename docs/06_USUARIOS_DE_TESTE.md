# Usuários de exemplo — perfil test

## Objetivo

Disponibilizar contas prontas no `TestConfig` para testes manuais de login, permissões, coletas e troca de senha. Foram adicionadas quatro contas e preservado o administrador original.

## Contas disponíveis

| Nome | E-mail | Senha inicial | Perfil |
|---|---|---|---|
| Raphael | `admin-original@example.com` | `demo-original7!` | ADMIN |
| Administrador de teste | `admin@example.com` | `admin123` | ADMIN |
| Ana Silva | `ana@example.com` | `ana123` | VOLUNTEER |
| Bruno Costa | `bruno@example.com` | `bruno123` | VOLUNTEER |
| Carla Souza | `carla@example.com` | `carla123` | VOLUNTEER |

As senhas são gravadas com BCrypt pelo bean `PasswordEncoder`. Os novos usuários começam com total de coleta zero, sem inscrições/coletas e com versão das credenciais zero. O evento, a coleta e os vínculos do administrador original foram preservados.

O seed executa somente com o perfil `test`, já ativo por padrão. Reinicie a aplicação para carregar as contas adicionadas. O H2 é em memória: ao reiniciar, os dados e as senhas iniciais são recriados. Consulte o `userId` retornado pelo login para identificar cada conta, sem depender de IDs fixos.

## Login

```http
POST /auth/login
Content-Type: application/json
```

```json
{
  "email": "ana@example.com",
  "password": "ana123"
}
```

A resposta informa `accessToken`, `tokenType`, `expiresIn`, `userId` e `role`. Use `Authorization: Bearer <accessToken>` nas chamadas protegidas.

## Cenários para testar

- **Administrador:** entre com `admin@example.com`, liste usuários em `GET /users` e crie/edite eventos.
- **Voluntário:** entre com Ana e consulte ou edite somente o perfil em `GET/PUT /users/{userId}`, usando o `userId` retornado pelo login.
- **Isolamento:** tente consultar o perfil de Bruno com o token de Ana; a resposta deve ser 403.
- **Coletas:** crie uma coleta com Ana, omitindo `volunteer`; ela será vinculada à própria conta. Bruno não deve conseguir acessá-la e sua listagem não deve incluir essa coleta.
- **Permissões:** tente cadastrar um evento com Carla; a resposta deve ser 403.
- **Troca de senha:** use `PUT /auth/password` com `currentPassword: ana123` e uma nova senha. Os tokens anteriores de Ana devem receber 401; faça login com a nova senha. Os tokens de Bruno e Carla continuam válidos.

Os exemplos e o contrato vigente estão na [padronização de nomes](06_PADRONIZACAO_DE_NOMES.md). Os documentos de autenticação e troca de senha registram as etapas históricas e seus nomes antigos.

## Arquivos e verificação

- `src/main/java/com/raphael/beachcleanup/config/TestConfig.java`: novas contas com hashes BCrypt e perfis.
- `src/test/java/com/raphael/beachcleanup/BeachcleanupApplicationTests.java`: teste existente ajustado para conferir as cinco contas, senhas, perfis e versões iniciais.
- `docs/README.md`: link para este documento.

Comando usado para verificar a inicialização e o seed neste ambiente:

```powershell
& 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository' -o -B '-Dtest=BeachcleanupApplicationTests' test
```

Resultado: **BUILD SUCCESS — 1 teste, 0 falhas, 0 erros e 0 ignorados**. A verificação confirmou as cinco contas, as respectivas senhas BCrypt, os perfis e a versão inicial das credenciais.

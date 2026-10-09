# Etapa 5 — Troca de senha e invalidação dos tokens

## Objetivo

Permitir que um usuário autenticado troque a própria senha, comprovando a senha atual, e invalidar todos os tokens de acesso anteriores dessa conta após o sucesso.

Etapa implementada em 09/10/2026. O fluxo é uma troca autenticada. Recuperação de conta por e-mail permanece para a próxima etapa, pois exige configurar o envio de mensagens e tokens temporários de uso único.

## O que mudou

| Área | Antes | Agora |
|---|---|---|
| Troca de senha | Sem endpoint próprio | `PUT /auth/password`, com Bearer, senha atual e nova senha |
| Identificação da conta | Perfil podia ser editado pelo ID | A troca usa exclusivamente o `sub` do token autenticado |
| Verificação de identidade | Login verificava a senha | A troca também exige a senha atual |
| Nova senha | Não existia fluxo de alteração | Obrigatória, diferente da atual e com máximo de 72 bytes em UTF-8 |
| Tokens após alteração | Válidos até expirar | Todos os tokens anteriores da conta são rejeitados |
| Concorrência | Sem controle de versão da entidade | `@Version` impede sobrescrita por alteração concorrente |
| Erro de concorrência | Sem tratamento específico | 409 com `StandardError` e `Concurrent update` |

## Endpoint

```http
PUT /auth/password
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "currentPassword": "senha-atual",
  "newPassword": "nova-senha"
}
```

Sucesso: **204 No Content**, sem corpo e sem novo token. O cliente deve descartar os tokens antigos e fazer `POST /auth/login` novamente com a nova senha para continuar usando as rotas protegidas.

Não existe ID de voluntário no caminho ou como campo utilizado pelo endpoint. Enviar `volunteerId`, `id`, `role` ou `credentialVersion` no JSON não muda a conta alvo nem suas permissões. Administradores seguem a mesma regra: esse fluxo altera somente a própria senha e não é uma ferramenta de redefinição de senha de terceiros.

Nome, e-mail, perfil, total de coleta e associações permanecem preservados. `PUT /volunteers/{id}` continua sem alterar senha.

## Validações e erros

| Situação | Resposta |
|---|---|
| Sem Bearer, token inválido, expirado ou revogado | 401 |
| Campo obrigatório ausente, nulo ou com apenas espaços | 400 com `errors` |
| JSON malformado ou corpo ausente | 400 no contrato padrão |
| Senha atual incorreta ou acima de 72 bytes em UTF-8 | 401 com mensagem genérica |
| Nova senha acima de 72 bytes em UTF-8 | 400, campo `newPassword` |
| Nova senha igual à atual | 400, campo `newPassword` |
| Alteração concorrente do mesmo registro | 409; operação rejeitada sem sobrescrever a vencedora |

O limite é medido em bytes, como no cadastro, sem truncar a entrada. Uma senha de 70 letras ASCII e um `é` ocupa 72 bytes e é aceita. Não foi adicionada uma regra de tamanho mínimo ou de composição diferente da utilizada no cadastro.

Exemplo de erro por campo:

```json
{
  "timestamp": "2026-10-09T21:00:00Z",
  "status": 400,
  "error": "Validation error",
  "message": "Verifique os campos informados.",
  "path": "/auth/password",
  "errors": [
    {
      "fieldName": "newPassword",
      "message": "A nova senha deve ser diferente da senha atual"
    }
  ]
}
```

Falhas de autenticação mantêm `Unauthorized`, a mensagem `Autenticação necessária ou credenciais inválidas.` e `WWW-Authenticate: Bearer`. As respostas não reproduzem nenhuma das senhas, o hash ou o token enviado.

## Como funciona

1. A cadeia de segurança valida o JWT e consulta a conta no banco.
2. O controller recebe `ChangePasswordDTO` com `@Valid` e o `Jwt` autenticado por `@AuthenticationPrincipal`.
3. `AuthService.changePassword` busca a conta pelo `sub` e confere novamente a versão das credenciais.
4. BCrypt verifica a senha atual com `matches`; a nova senha é validada e comparada com a senha salva para impedir reutilização imediata.
5. O service gera um novo hash BCrypt e incrementa `credentialVersion` na mesma transação.
6. `saveAndFlush` força a gravação e a verificação da versão da entidade. O commit transacional conclui a alteração antes da resposta 204.

Senhas recebidas não são normalizadas: espaços internos e caracteres Unicode fazem parte da senha. O DTO marca os dois campos como `WRITE_ONLY`, para que também não apareçam se o objeto for serializado diretamente.

A conferência da senha atual e a invalidação dos tokens seguem as orientações de [troca de senha da OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#change-password-feature) e de [gestão de sessões](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html).

## Invalidação por versão das credenciais

Cada voluntário possui `credentialVersion`, inicialmente zero. O login inclui esse número na claim homônima do JWT. A autenticação só aceita o token quando a claim é um inteiro e corresponde ao valor atual no banco.

Após uma troca de senha, o número passa de 0 para 1. Todos os tokens emitidos com 0 deixam de autenticar essa conta, inclusive o token utilizado para solicitar a troca. Um novo login emite tokens com 1. Uma segunda troca avança para 2 e invalida a geração anterior novamente.

Essa regra é por usuário: os tokens de outras contas continuam funcionando. A chave de assinatura, a duração de 15 minutos, o emissor e a audiência da etapa 4 continuam os mesmos. O número é um mecanismo de revogação, não um segredo; aparece no JWT, mas não nas respostas de perfil/coleta ou na serialização da entidade.

Tokens emitidos pela etapa 4, sem a claim `credentialVersion`, passam a receber 401 nesta versão. Todos os clientes precisam fazer login novamente após atualizar a aplicação. Tokens com claim ausente, inválida ou diferente do valor persistido também recebem 401.

A revogação é conferida na autenticação das próximas requisições. Ela não cancela retroativamente uma requisição já autenticada e em execução. A troca ainda verifica a versão dentro do service e protege a gravação contra concorrência.

## Concorrência

`Volunteer.rowVersion`, anotado com `@Version`, é separado da versão das credenciais. Ele controla alterações concorrentes da entidade inteira; editar o perfil pode avançar `rowVersion`, mas não revoga tokens porque não altera `credentialVersion`.

Se duas trocas partirem da mesma versão, apenas uma consegue gravar o novo hash. A outra é rejeitada por token revogado/senha desatualizada (401) ou conflito de versão (409), conforme o momento em que começa e tenta gravar. O registro nunca recebe as duas alterações sucessivas com o mesmo token antigo.

O handler traduz `OptimisticLockingFailureException` para 409, sem detalhes internos do banco. Consulte o estado atual e autentique-se novamente antes de repetir uma operação que encontrou conflito.

## Banco e compatibilidade

Foram adicionados os campos `credential_version` e `row_version` à entidade `Volunteer`. O H2 em memória é recriado e recebe essas colunas automaticamente nos testes.

Em um banco persistente existente, será necessário aplicar uma migração e preencher as versões dos registros anteriores antes de usar esta implementação. A etapa não inclui uma migração de produção nem uma conversão de dados automática.

## Verificação

`PasswordChangeIntegrationTests` usa MockMvc com a cadeia real de segurança, login real, BCrypt e repositories H2 em um banco próprio (`passwordtests`). Foram acrescentados seis testes com múltiplos cenários:

- Sucesso 204, novo hash, preservação do perfil e rejeição de todas as sessões anteriores da conta.
- Tokens e senha de outra conta preservados, mesmo enviando campos extras para tentar alterar o alvo.
- Senha atual incorreta, campos ausentes/em branco, nova senha repetida ou acima do limite: hash e versão mantidos, sem revogação do token válido.
- Limite de 72 bytes com caracteres multibyte e duas trocas sucessivas, conferindo cada geração de tokens.
- Exigência de autenticação e rejeição da versão das credenciais ausente, inválida ou incorreta.
- Administrador limitado à própria senha e DTO sem serialização dos segredos.
- Duas requisições simultâneas: exatamente uma alteração bem-sucedida e apenas uma geração nova de credenciais.

Os testes da etapa 4 que constroem JWTs diretamente foram ajustados para incluir a claim de versão nos tokens que representam uma conta válida. Os testes anteriores de CRUD, validação, login, permissões e configuração da chave continuam na suíte.

Comando neste ambiente:

```powershell
& 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository' -o -B test
```

Resultado: **BUILD SUCCESS — 35 testes, 0 falhas, 0 erros e 0 ignorados**: 1 de contexto/seed, 18 de CRUD/validação, 3 de configuração JWT, 7 de segurança e 6 de troca de senha. O teste concorrente confirma que exatamente uma das duas requisições consegue alterar a senha.

## Arquivos desta etapa

Sob `src/main/java/com/raphael/beachcleanup/`:

- Criado `dto/ChangePasswordDTO.java`.
- Alterado `resources/AuthResource.java` para o endpoint de troca.
- Alterado `services/AuthService.java` para trocar a senha e incluir a versão nos tokens de login.
- Alterados `config/JwtConfig.java` e `config/SecurityConfig.java` para leitura e conferência da claim.
- Alterados `entities/Volunteer.java` e `services/VolunteerService.java` para versões e inicialização segura no cadastro.
- Alterado `exceptions/ResourceExceptionHandler.java` para o conflito de concorrência.

Também foram criados `PasswordChangeIntegrationTests.java` e este documento, e atualizados `SecurityIntegrationTests.java` e `docs/README.md`. A alteração local preexistente em `ValidationError.java` permanece preservada e fora do commit.

## Próxima etapa

Configurar envio de e-mails e implementar redefinição de senha para quem perdeu a senha atual, usando token temporário de uso único, expiração e invalidação das credenciais após o sucesso. Refresh token, revogação individual/logout, confirmação de e-mail e limitação de tentativas permanecem pendentes.

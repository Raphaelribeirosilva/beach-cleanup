# Etapa 3 — Validações e padronização dos erros da API

## Objetivo

Completar as validações HTTP de cadastro e atualização de voluntários, eventos e coletas, rejeitando dados inválidos antes da persistência. Centralizar as respostas de erro no contrato já utilizado por `StandardError` e incluir mensagens por campo com `ValidationError`.

Etapa implementada e verificada em 09/10/2026. Este documento descreve o comportamento atual; os documentos das etapas 1 e 2 permanecem como registros históricos.

## O que mudou

| Área | Antes | Agora |
|---|---|---|
| Cadastro de voluntário | Campos obrigatórios e formato de e-mail | Também limita nome/e-mail a 255 caracteres |
| Atualização de voluntário | Recebia a entidade sem `@Valid` | Recebe `VolunteerUpdateDTO` validado, com nome e e-mail obrigatórios |
| Cadastro/atualização de evento | Recebia a entidade sem validação | Recebe `CleanupEventInputDTO` validado |
| Cadastro/atualização de coleta | Recebia a entidade sem validação | Recebe `TrashCollectionInputDTO` validado, com peso positivo e finito |
| Vínculos enviados na coleta | Verificação de ID ausente no service | Validação por campo de ID obrigatório e positivo, além da resolução no banco |
| Erros de validação | Formato padrão do framework | Status 400 com contrato próprio e lista `errors` |
| Senha acima de 72 bytes | `ResponseStatusException` | `FieldValidationException`, com erro no campo `password` |
| JSON inválido, tipos e corpo ausente | Sem padronização própria | Status 400 no formato `StandardError` |
| Método/conteúdo incompatível | Sem padronização própria | Status 405/415 no mesmo formato, preservando cabeçalhos HTTP |
| Falha inesperada | Sem handler próprio | Status 500 com mensagem genérica e registro da exceção no servidor |

As rotas, os nomes dos campos JSON e os DTOs de resposta foram preservados. Campos extras, como `id` no corpo e `password` na edição de perfil, continuam sem alterar os campos controlados pelo servidor.

## Regras de entrada

As validações ficam nos DTOs de entrada, acionadas por `@Valid` nos controllers. As entidades relacionadas não são validadas como um cadastro completo: `ResourceReferenceDTO` recebe somente o ID do vínculo.

| Recurso | Campo | Regra |
|---|---|---|
| Voluntário: POST e PUT | `name` | Obrigatório, não pode conter apenas espaços, máximo de 255 caracteres |
| Voluntário: POST e PUT | `email` | Obrigatório, formato válido, máximo de 255 caracteres |
| Voluntário: somente POST | `password` | Obrigatória, não pode conter apenas espaços, máximo de 72 bytes em UTF-8 |
| Evento: POST e PUT | `title`, `location`, `status` | Obrigatórios, não podem conter apenas espaços, máximo de 255 caracteres cada |
| Evento: POST e PUT | `dateTime` | Obrigatória e desserializável como `LocalDateTime` |
| Coleta: POST e PUT | `beach`, `materialType` | Obrigatórios, não podem conter apenas espaços, máximo de 255 caracteres cada |
| Coleta: POST e PUT | `weithKg` | Obrigatório, maior que zero e finito |
| Coleta: POST e PUT | `regitrationDate` | Obrigatória e desserializável como `LocalDate` |
| Coleta: POST e PUT | `volunteer`, `event` | Opcionais; se enviados como objeto, devem ter `id` obrigatório e positivo |

Os limites de texto acompanham as colunas `varchar(255)` geradas pelo mapeamento atual. Valores numéricos não finitos, incluindo NaN, infinito e estouro de `Double`, são rejeitados por validação ou leitura do JSON.

Datas passadas e futuras são aceitas nesta etapa. `status` e `materialType` continuam sendo textos livres, sujeitos à obrigatoriedade e ao limite de tamanho. Não foi definida uma lista de status ou uma regra de transição. E-mail duplicado continua permitido: unicidade exige uma regra e uma restrição de banco próprias.

## Cadastro e atualização

Os JSONs de sucesso documentados nas etapas anteriores continuam válidos. PUT exige todos os campos editáveis obrigatórios do recurso. Por exemplo:

```http
PUT /volunteers/2
Content-Type: application/json
```

```json
{
  "name": "Ana Silva",
  "email": "ana.silva@example.com"
}
```

Enviar apenas `name` agora retorna 400 pelo campo `email`, preservando o registro anterior. A edição de perfil mantém senha, total de coleta e inscrições. Não foi implementado PATCH ou troca de senha.

Na coleta, os nomes `weithKg` e `regitrationDate` permanecem no contrato. Os vínculos continuam opcionais: omiti-los ou enviar `null` no PUT remove as associações existentes, conforme a etapa 1. Quando um vínculo é enviado, seus campos extras são ignorados e o service consulta o registro pelo ID.

- `"volunteer": {}` retorna 400 com `fieldName` igual a `volunteer.id`.
- `"event": {"id": 0}` retorna 400 com `fieldName` igual a `event.id`.
- Um ID positivo que não existe retorna 404 e impede a gravação.

A validação do corpo acontece antes da chamada ao service. Portanto, PUT com corpo inválido e ID inexistente retorna 400; com corpo válido e ID inexistente, retorna 404.

## Contrato de erro

Todas as respostas de erro tratadas pelo advice têm `timestamp`, `status`, `error`, `message` e `path`. Erros de campo acrescentam `errors`, contendo `fieldName` e `message`.

Exemplo de peso inválido:

```json
{
  "timestamp": "2026-10-09T19:00:00Z",
  "status": 400,
  "error": "Validation error",
  "message": "Verifique os campos informados.",
  "path": "/trashCollection",
  "errors": [
    {
      "fieldName": "weithKg",
      "message": "O peso deve ser maior que zero"
    }
  ]
}
```

Os erros produzidos por Bean Validation são ordenados pelo nome do campo e pela mensagem. Um campo pode ter mais de uma mensagem quando viola mais de uma restrição. As respostas não incluem o valor rejeitado, a senha recebida, o SQL ou a pilha da exceção.

Exemplo de JSON malformado ou tipo incompatível:

```json
{
  "timestamp": "2026-10-09T19:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Requisição inválida. Verifique o JSON, os tipos e os parâmetros enviados.",
  "path": "/cleanupEvent"
}
```

| Status | Situação |
|---|---|
| 400 | Campos inválidos, senha acima do limite, vínculo sem ID positivo, JSON malformado, corpo ausente/nulo ou tipos incorretos |
| 404 | Registro/vínculo inexistente ou rota sem recurso |
| 405 | Método HTTP não suportado; mantém o cabeçalho `Allow` |
| 406 | Formato de resposta solicitado não suportado |
| 409 | Conflito de integridade, incluindo exclusão bloqueada por registros vinculados |
| 415 | Tipo de conteúdo não suportado |
| 500 | Falha inesperada, com mensagem genérica |

O handler estende `ResponseEntityExceptionHandler` para aproveitar a classificação dos erros MVC e preservar seus cabeçalhos. Os handlers específicos de 404 e 409 mantêm seus nomes de erro anteriores (`Resource not found` e `Integrity conflict`). `FieldValidationException` permite que validações feitas no service, como o limite BCrypt, usem a mesma lista de erros por campo.

O contrato se aplica aos erros que passam pelo Spring MVC e pelo advice. Falhas anteriores a essa camada, como erros do container ou de futuros filtros de autenticação, precisam de tratamento no componente responsável. Uma resposta 406 também depende de o cliente aceitar um formato no qual o corpo de erro possa ser serializado.

## Verificação

Os testes usam MockMvc com contexto Spring e repositories reais, além de H2 em memória. Foram mantidos os testes de CRUD e de proteção de credenciais e acrescentados seis testes, com múltiplos cenários cada:

- Campos obrigatórios ausentes/em branco e e-mail inválido, sem criação de registros.
- PUT inválido nos três recursos, preservando dados existentes; e-mail inválido preservando perfil e hash.
- Peso nulo, zero, negativo, infinito, NaN e estouro numérico.
- Referências sem ID, com zero ou ID negativo, conferindo os caminhos `volunteer.id` e `event.id`.
- Textos acima de 255 caracteres rejeitados antes da persistência.
- Corpo ausente/nulo, JSON malformado, data inválida, ID de rota não numérico, 405 com `Allow`, 415 e rota inexistente.

O teste do limite BCrypt também passou a conferir o campo `password` na resposta. Os cenários de ID inexistente no PUT foram ajustados para enviar corpos válidos, permitindo exercitar o 404 após a validação. Os cenários existentes continuam verificando 409, vínculos opcionais e rollback quando uma referência não existe.

Os caminhos 406 e 500 foram implementados, mas não tiveram falhas artificiais adicionadas à suíte nesta etapa.

Comando executado neste ambiente, com o Maven instalado e dependências no cache:

```powershell
& 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository' -o -B test
```

Resultado: **BUILD SUCCESS — 19 testes, 0 falhas, 0 erros e 0 ignorados** (1 teste de contexto/seed e 18 testes de integração). Os avisos de integridade nos logs correspondem aos testes de exclusão que esperam 409.

## Arquivos desta etapa

Sob `src/main/java/com/raphael/beachcleanup/`:

- Criados `dto/VolunteerUpdateDTO.java`, `dto/CleanupEventInputDTO.java`, `dto/TrashCollectionInputDTO.java` e `dto/ResourceReferenceDTO.java`.
- Alterado `dto/VolunteerInsertDTO.java` para limitar os textos.
- Alterados os três controllers em `resources/` para receber DTOs validados.
- Criado `exceptions/FieldValidationException.java` e ampliado `exceptions/ResourceExceptionHandler.java`.
- Alterados `services/VolunteerService.java` e `services/TrashCollectionService.java` para os erros por campo.

Também foram atualizados `src/test/java/com/raphael/beachcleanup/CrudIntegrationTests.java` e `docs/README.md`, além deste documento. A alteração local preexistente em `ValidationError.java` foi preservada.

## Próximas etapas

Definir autenticação e autorização, incluindo identificação do usuário e permissões para cadastro, edição e exclusão. Troca/redefinição de senha deve acompanhar esse fluxo. Regras de domínio como unicidade de e-mail, status permitidos, inscrição em eventos e cálculo automático dos totais ainda precisam de definição e implementação; migrações de banco continuam pendentes.

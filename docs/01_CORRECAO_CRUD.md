# Etapa 1 — Correção do CRUD

## Objetivo

Corrigir criação, consulta, atualização e exclusão de voluntários, eventos e coletas. O documento descreve o comportamento após esta etapa. A análise inicial em `docs/ANALISE_PROJETO.md` fica nesta pasta como registro do diagnóstico anterior.

## O que foi corrigido

| Área | Antes | Agora |
|---|---|---|
| Cadastro de evento/coleta | Entidade anotada como `@PathVariable` sem variável na rota | JSON recebido por `@RequestBody` |
| Resposta de cadastro | Evento/coleta retornavam 200 e não utilizavam a URI criada | Os três recursos retornam 201 e cabeçalho `Location` |
| Atualização | `POST /{id}` | `PUT /{id}` |
| Atualização do voluntário | E-mail era gravado no nome | Nome e e-mail são gravados nos campos corretos |
| Atualização de evento | Título não era atualizado | Título, local, data/hora e status são atualizados |
| Atualização de coleta | Data e vínculos não eram atualizados | Praia, material, peso, data, voluntário e evento são atualizados |
| ID inexistente | Tratamento diferente entre recursos/operações | Consulta, atualização e exclusão retornam 404 nos três recursos |
| ID recebido na criação | Poderia fazer `save` atualizar um registro existente | O ID é zerado antes de salvar, criando um novo registro |
| ID recebido na atualização | Contrato pouco claro | O ID do caminho determina o registro; o ID do corpo não o substitui |
| Vínculos da coleta | Entidades recebidas eram salvas sem resolução explícita | Voluntário/evento são buscados no banco pelos IDs |
| Exclusão com conflito de integridade | Sem tradução própria | Retorna 409, sem excluir os dados que causaram o conflito |
| Senha no DTO de cadastro | Setter `setPassoword` | Setter corrigido para `setPassword` |
| Transações | Sem fronteira explícita de serviço | Escritas transacionais; consultas com `readOnly = true` |

As rotas-base foram mantidas: `/volunteers`, `/cleanupEvent` e `/trashCollection`. Clientes que atualizavam por POST precisam passar a usar PUT; o mapeamento antigo de atualização foi substituído.

## Endpoints

A tabela vale para cada uma das três rotas-base.

| Método | Caminho | Função | Resposta |
|---|---|---|---|
| GET | `/{recurso}` | Listar todos | 200 com lista; lista vazia quando não há registros |
| GET | `/{recurso}/{id}` | Consultar um | 200 com registro ou 404 |
| POST | `/{recurso}` | Criar | 201 com registro e `Location` |
| PUT | `/{recurso}/{id}` | Atualizar os campos editáveis | 200 com registro ou 404 |
| DELETE | `/{recurso}/{id}` | Excluir | 204 sem corpo; 404 se ausente; 409 se houver conflito de integridade |

Exemplo de cabeçalho de cadastro: `Location: http://localhost:8080/cleanupEvent/2`. O host e a porta são derivados da requisição.

Os corpos JSON devem ser enviados com `Content-Type: application/json`. Os exemplos usam `http://localhost:8080`, a configuração convencional de execução local, sem alteração de porta.

## Como funciona por dentro

O controller recebe a requisição, o service executa a operação em uma transação e o repository acessa o banco por JPA.

1. **Criação:** o service define o ID como nulo para que a persistência gere um novo identificador. No cadastro de voluntário, o total começa em zero e não são cadastradas inscrições por esse fluxo.
2. **Consulta individual:** `findById` usa `orElseThrow` com `ResourceNotFoundException`, que o handler transforma em 404.
3. **Atualização:** primeiro busca o registro existente, depois copia os campos editáveis e salva. Um PUT para um ID inexistente não cria outro registro.
4. **Coleta:** os vínculos são resolvidos antes de alterar/salvar a entidade. Campos extras enviados dentro de `volunteer` e `event` não atualizam essas entidades.
5. **Exclusão:** busca o registro e solicita a exclusão. `flush()` força a verificação das restrições antes da conclusão. Se houver conflito, a transação é revertida e o handler devolve 409 com mensagem sem detalhes internos do SQL.

## Voluntários

### Criar

`POST /volunteers`

```json
{
  "name": "Ana",
  "email": "ana@example.com",
  "password": "senha-de-exemplo"
}
```

O cadastro continua usando `VolunteerInsertDTO` com `@Valid`: nome, e-mail e senha são obrigatórios e o e-mail deve ter formato válido. A resposta usa `VolunteerDTO`, com ID, nome, e-mail e total inicial de coleta igual a `0.0`.

### Atualizar

`PUT /volunteers/2`

```json
{
  "name": "Ana Silva",
  "email": "ana.silva@example.com"
}
```

Esta operação edita o perfil: apenas nome e e-mail. Senha, total coletado e inscrições existentes são preservados, mesmo que o corpo envie valores para eles. Alteração de senha e inscrição em eventos precisam de fluxos próprios nas próximas etapas.

A listagem retorna DTOs; a consulta individual e a atualização ainda retornam `Volunteer`. A proteção completa das respostas e o hash das senhas pertencem à etapa 2 e continuam pendentes.

## Eventos

### Criar

`POST /cleanupEvent`

```json
{
  "title": "Mutirão de limpeza",
  "location": "Praia do Morro",
  "dateTime": "2026-10-15T08:00:00",
  "status": "Agendado"
}
```

### Atualizar

`PUT /cleanupEvent/2`

```json
{
  "title": "Mutirão de limpeza — novo horário",
  "location": "Praia do Morro",
  "dateTime": "2026-10-15T09:00:00",
  "status": "Agendado"
}
```

Todos os campos escalares editáveis são copiados. Participações de voluntários não são alteradas por esse endpoint. A data/hora usa `LocalDateTime`, sem informação de fuso.

## Coletas

### Criar

Cadastre ou consulte um voluntário e um evento antes de usar seus IDs.

`POST /trashCollection`

```json
{
  "beach": "Praia do Morro",
  "materialType": "Plástico",
  "weithKg": 2.5,
  "regitrationDate": "2026-10-15",
  "volunteer": { "id": 2 },
  "event": { "id": 2 }
}
```

Os nomes `weithKg` e `regitrationDate` foram mantidos nesta etapa para preservar o contrato e o mapeamento existentes. Apesar da grafia, representam peso em kg e data de registro.

- Se um ID relacionado não existir, a resposta é 404 e a coleta não é salva.
- Se for enviado um objeto de vínculo sem ID, como `"volunteer": {}`, a resposta é 400.
- Se o vínculo estiver ausente ou for `null`, ele permanece nulo. O modelo atual permite coleta sem voluntário/evento; torná-los obrigatórios depende da definição de regra de negócio.
- Os atributos extras de entidades relacionadas são ignorados para atualização: apenas o ID identifica o vínculo.

### Atualizar

`PUT /trashCollection/2`

```json
{
  "beach": "Praia do Morro",
  "materialType": "Vidro",
  "weithKg": 3.0,
  "regitrationDate": "2026-10-16",
  "volunteer": { "id": 2 },
  "event": { "id": 3 }
}
```

A atualização inclui a data e permite trocar voluntário/evento. Um vínculo inexistente impede a operação inteira, preservando o registro anterior. `null` ou omissão de uma associação remove o vínculo nessa atualização.

## Semântica de atualização

PUT recebe o conjunto de campos editáveis do recurso. Envie todos esses campos: valores omitidos são desserializados como nulos e podem substituir os valores existentes. Não foi implementado PATCH.

- Voluntário: `name` e `email`.
- Evento: `title`, `location`, `dateTime` e `status`.
- Coleta: `beach`, `materialType`, `weithKg`, `regitrationDate`, `volunteer` e `event`.

As validações completas dos corpos de atualização e dos cadastros de evento/coleta serão tratadas na etapa 3. O funcionamento do CRUD não estabelece, por si só, regras como peso positivo ou status permitido.

## Exclusão e erros

Uma coleta pode impedir a exclusão do voluntário ou evento associado por restrição de chave estrangeira. Nessa situação, a API retorna 409 e preserva os registros. Exclua ou desvincule a coleta antes de tentar novamente.

Participações na tabela `tb_event_volunteer` também seguem os mapeamentos JPA existentes: excluir o voluntário remove suas linhas de associação; excluir um evento que ainda tenha participantes pode encontrar conflito. Não foi adicionada exclusão em cascata de coletas.

Exemplo de resposta própria para ID inexistente:

```json
{
  "timestamp": "2026-10-07T22:00:00Z",
  "status": 404,
  "error": "Resource not found",
  "message": "Resource not found. Id 999999",
  "path": "/cleanupEvent/999999"
}
```

Conflitos de integridade usam esse mesmo formato, com status 409 e `error` igual a `Integrity conflict`. O tratamento global de validações e outros erros não foi completado nesta etapa.

## Arquivos alterados

- `resources/VolunteerResource.java`: atualização por PUT.
- `resources/CleanupEventResource.java` e `resources/TrashCollectionResource.java`: corpo do cadastro, 201 com Location e atualização por PUT.
- `services/VolunteerService.java`: cópia correta de nome/e-mail, inicialização segura do novo ID/total, busca para atualização/exclusão e transações.
- `services/CleanupEventService.java`: atualização do título, novo ID na criação, 404 e transações.
- `services/TrashCollectionService.java`: novo ID, resolução de vínculos, atualização de data/associações, 404 e transações.
- `dto/VolunteerInsertDTO.java`: correção da grafia de password e do setter.
- `exceptions/ResourceExceptionHandler.java`: resposta 409 para conflitos de integridade.
- `src/test/java/com/raphael/beachcleanup/CrudIntegrationTests.java`: testes de integração HTTP/JPA com MockMvc e H2.

Os caminhos Java abreviados acima ficam sob `src/main/java/com/raphael/beachcleanup/`.

## Verificação

A suíte de integração exercita controllers, services e repositories reais, com banco H2 próprio (`crudtests`). O MockMvc simula requisições no contexto Spring, sem abrir uma porta HTTP. Cada teste limpa os registros desse banco; o teste de contexto original usa o banco configurado em `testdb`.

São verificados:

- Ciclo de criação, listagem, consulta, atualização e exclusão de cada recurso.
- 201 com Location nos cadastros e 204 sem corpo nas exclusões.
- Correção do nome/e-mail e preservação de senha e total na edição do perfil.
- Atualização do título do evento e da data/associações da coleta.
- ID da criação não sobrescrevendo registro existente e ID do corpo não alterando o registro alvo.
- 404 para consulta, atualização e exclusão com ID inexistente.
- Referências inexistentes ou sem ID e preservação dos dados quando a atualização falha.
- 409 nas exclusões bloqueadas por coletas e exclusão bem-sucedida após remover a dependência.
- Coletas com vínculos nulos e remoção de vínculos por PUT.

Comando convencional: `mvnw.cmd test`.

Neste ambiente, o wrapper apresentou `Cannot index into a null array` antes de iniciar o Maven. A validação usa o Maven já instalado no NetBeans, sem modificar o wrapper ou o POM:

```powershell
& 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository' -o -B test
```

O modo offline (`-o`) pressupõe dependências já disponíveis no cache. Resultado da execução: **BUILD SUCCESS — 10 testes, 0 falhas, 0 erros e 0 ignorados** (1 teste de contexto existente e 9 testes de integração novos). As violações de integridade exibidas nos logs correspondem aos cenários que verificam a resposta 409.

## Continuação

A próxima etapa recomendada é proteger credenciais: hash da senha e DTOs em todas as respostas, inclusive nos voluntários aninhados em coletas. Depois, completar validações, contrato de erros e regras de domínio. Cálculo automático de totais, autenticação, unicidade de e-mail, status e inscrição em eventos continuam pendentes.

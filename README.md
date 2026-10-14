# Beach Cleanup

API para organizar mutirões de limpeza de praias, cadastrar usuários, gerenciar inscrições e registrar o peso dos resíduos coletados. Implementada com Java 25, Spring Boot 4.1.1, Spring MVC, Spring Security, JPA e Flyway.

O backend oferece CRUD com DTOs e validações, autenticação JWT, permissões de administrador/voluntário, troca de senha, estados dos eventos, totais calculados e buscas paginadas. A interface web e os relatórios por evento, praia, material e período são etapas futuras.

## Requisitos

- JDK 25 ou superior, com `JAVA_HOME` apontando para o JDK e `java` disponível no terminal.
- Maven Wrapper incluído no repositório; ele usa Maven 3.9.16. A primeira execução precisa de rede para baixar Maven e dependências. Também é possível usar uma instalação Maven compatível substituindo `mvnw.cmd` por `mvn`.
- Python 3 para validar o contrato OpenAPI.
- MySQL para persistência. A suíte em banco real foi verificada em MySQL 8.0.46; o modo H2 dispensa a instalação de um banco.

Clone o repositório e execute os comandos a partir da raiz:

```powershell
git clone git@github.com:Raphaelribeirosilva/beach-cleanup.git
cd beach-cleanup
java -version
.\mvnw.cmd -v
```

Em Linux/macOS, use `sh mvnw` no lugar de `.\mvnw.cmd`.

## Execução rápida com H2

```powershell
.\mvnw.cmd -B package
java -jar target/beachcleanup-0.0.1-SNAPSHOT.jar --spring.profiles.active=test
```

A API usa `http://localhost:8080`, salvo configuração externa de porta. O perfil `test` é o padrão quando nenhum perfil ativo é informado. Ele cria um H2 em memória, exibe SQL e carrega [contas de exemplo](docs/06_USUARIOS_DE_TESTE.md). Os dados são recriados após o encerramento da aplicação. Sem `JWT_SECRET`, esse perfil gera uma chave temporária, portanto tokens anteriores também deixam de valer após reinício.

Para iniciar pela IDE, importe o projeto como Maven, selecione um JDK compatível e execute `BeachcleanupApplication`. Configure o perfil e as variáveis de ambiente na configuração de execução.

## Execução com MySQL persistente

O servidor, o banco e a conta de acesso devem existir antes de iniciar a aplicação. O MySQL local já configurado pelo usuário está registrado na [etapa 12](docs/12_MYSQL_LOCAL_VALIDACAO_MANUAL.md).

| Variável | Valor necessário |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `mysql` |
| `DB_URL` | URL JDBC do banco, por exemplo `jdbc:mysql://localhost:3306/beachcleanup` |
| `DB_USER` | Conta com acesso ao banco e permissão para aplicar as migrações |
| `DB_PASSWORD` | Senha dessa conta |
| `JWT_SECRET` | Chave em Base64 com pelo menos 32 bytes aleatórios; preserve-a entre reinícios |

Defina essas variáveis na configuração de execução do Eclipse/STS ou no ambiente do terminal. Com elas configuradas:

```powershell
java -jar target/beachcleanup-0.0.1-SNAPSHOT.jar --spring.profiles.active=mysql
```

O perfil MySQL usa Flyway, `ddl-auto=validate`, `open-in-view=false` e desabilita console H2 e logs SQL. Não carrega contas de exemplo. O cadastro público cria contas `VOLUNTEER`; a definição do primeiro administrador é uma operação controlada no banco, descrita na [etapa 10](docs/10_PERSISTENCIA_E_MIGRACOES.md).

As migrações atuais são V1 (esquema), V2 (índices) e V3 (versão das coletas). Em banco já gerenciado pelo Flyway, a inicialização aplica somente as migrações pendentes. Preserve as versões aplicadas e crie uma nova migração para mudar o esquema; não use recriação de tabelas para atualizar um banco com dados existentes. V1 inicializa um banco vazio; um banco legado sem histórico Flyway exige uma migração específica.

## Preparação para implantação

Para preparar a implantação, use o perfil `prod`, que inclui `mysql`, as mesmas variáveis DB/JWT e os [modelos e procedimentos da etapa 17](docs/17_IMPLANTACAO_E_OPERACAO.md):

```powershell
java -jar target/beachcleanup-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
```

`prod` vincula HTTP a `127.0.0.1` por padrão para uso atrás de proxy, habilita logs JSON e encerramento gracioso e rejeita configurações de banco incompatíveis. As rotas de saúde são públicas e não expõem detalhes internos. O destino de hospedagem, proxy HTTPS, backup e restauração devem ser configurados antes de publicar.

## Primeiras requisições

Com o perfil `test`, faça login com Ana e registre uma coleta sem evento:

```powershell
$api = 'http://localhost:8080'
$login = Invoke-RestMethod -Method Post -Uri "$api/auth/login" -ContentType 'application/json' -Body '{"email":"ana@example.com","password":"ana123"}'
$headers = @{ Authorization = "Bearer $($login.accessToken)" }
$coleta = Invoke-RestMethod -Method Post -Uri "$api/trashCollection" -Headers $headers -ContentType 'application/json' -Body '{"beach":"Praia A","materialType":"Vidro","weightKg":2.5,"registrationDate":"2026-10-15"}'
Invoke-RestMethod -Uri "$api/users/$($login.userId)" -Headers $headers
Invoke-RestMethod -Uri "$api/trashCollection/search?page=0&size=10" -Headers $headers
```

A coleta será vinculada automaticamente à Ana. Seu `totalCollectedKg` será calculado a partir das coletas existentes. No perfil MySQL, cadastre sua própria conta por `POST /users` com `name`, `email` e `password`, e substitua as credenciais de exemplo no login.

O token dura 15 minutos. Use `Authorization: Bearer <accessToken>` nas rotas protegidas. A troca de senha por `PUT /auth/password` revoga os tokens anteriores da conta; faça novo login. Senhas aceitam no máximo 72 bytes UTF-8.

## Rotas e regras principais

| Recurso | Operações e acesso |
|---|---|
| `/auth/login` | POST público; retorna token, `userId` e perfil |
| `/auth/password` | PUT autenticado, com `currentPassword` e `newPassword` |
| `/users` e `/users/{id}` | Cadastro público; listagem exclusiva do administrador; leitura, edição e exclusão da própria conta ou por administrador |
| `/cleanupEvent` e `/cleanupEvent/{id}` | Leitura pública; cadastro, edição e exclusão por administrador |
| `/users/me/events` | GET dos eventos da própria conta; PUT/DELETE em `/{eventId}` para inscrição/cancelamento |
| `/trashCollection` e `/trashCollection/{id}` | Voluntário acessa suas coletas; administrador gerencia todas e pode transferir/remover proprietário |
| `/users/search`, `/cleanupEvent/search`, `/trashCollection/search` | Buscas paginadas; preservam as permissões dos respectivos recursos |

Novos eventos começam como `Agendado`. Eventos `Agendado` e `Em andamento` aceitam novas inscrições e vínculos de coletas. Inscrição não é requisito para coleta, e uma coleta pode existir sem evento. Veja a matriz de transições na [etapa 9](docs/09_STATUS_DOS_EVENTOS.md).

As buscas usam páginas a partir de zero, tamanho padrão 20 e limite 100, com filtros e ordenação. Prefira essas rotas às listagens originais, que retornam arrays completos. Veja [exemplos de busca](docs/11_EXEMPLOS_DE_BUSCA.md).

Entradas inválidas retornam 400; autenticação ausente/inválida, 401; acesso negado, 403; recurso não encontrado, 404; conflitos de integridade, estado ou versão concorrente, 409. Para voluntários, IDs de coletas inexistentes ou inacessíveis retornam 403. Após conflito concorrente, consulte o registro atual antes de tentar novamente. O controle de versão é interno ao servidor; não há precondição `If-Match` para detectar formulários antigos no cliente.

## Testes, contrato e CI

```powershell
.\mvnw.cmd -B test
python tools/validate_openapi.py
```

A suíte padrão usa H2 e não exige MySQL. O contrato [OpenAPI](docs/openapi.json) pode ser importado em ferramentas de API; descreve 13 caminhos e 23 operações. É um arquivo estático, sem Swagger UI ou `/v3/api-docs` servido pela aplicação.

Para executar a suíte completa em uma instância MySQL temporária no Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/run_mysql_tests.ps1
```

O script exige os binários MySQL 8.0 e executa `verify` com o perfil Maven `mysql-it`, usando diretório/porta próprios e removendo a instância ao terminar. Esse perfil Maven seleciona testes; ele é distinto do perfil Spring `mysql`, que configura a aplicação. Variáveis de teste e alternativas de execução estão na [etapa 15](docs/15_TESTES_MYSQL_E_CI.md).

Última verificação de código, em 14/10/2026: **73 testes aprovados**, incluindo quatro em MySQL real com perfil `prod`, com empacotamento e probes HTTP aprovados na [etapa 17](docs/17_IMPLANTACAO_E_OPERACAO.md). O [workflow CI](.github/workflows/ci.yml) executa OpenAPI, testes H2/MySQL e empacotamento em push para `main`, pull requests e acionamento manual. Os relatórios são guardados mesmo em caso de falha; o resultado remoto deve ser consultado no GitHub Actions após cada envio.

## Documentação atual e próximos passos

| Tema | Documento |
|---|---|
| Nomes, campos e contrato de CRUD | [Etapa 6](docs/06_PADRONIZACAO_DE_NOMES.md) |
| Total derivado das coletas | [Etapa 7](docs/07_TOTAL_COLETADO.md) |
| Inscrições | [Etapa 8](docs/08_INSCRICAO_EM_EVENTOS.md) |
| Estados dos eventos | [Etapa 9](docs/09_STATUS_DOS_EVENTOS.md) |
| Banco e migrações | [Etapa 10](docs/10_PERSISTENCIA_E_MIGRACOES.md) |
| Paginação e filtros | [Etapa 11](docs/11_BUSCAS_PAGINADAS.md) |
| Contrato OpenAPI | [Etapa 13](docs/13_CONTRATO_OPENAPI.md) |
| Concorrência e autorização das coletas | [Etapa 14](docs/14_CONCORRENCIA_E_AUTORIZACAO_DAS_COLETAS.md) |
| Testes em MySQL real e CI | [Etapa 15](docs/15_TESTES_MYSQL_E_CI.md) |
| Revisão documental e prioridades atuais | [Etapa 16](docs/16_DOCUMENTACAO_DE_ENTRADA.md) |
| Preparação para implantação, saúde, logs e backup/restauração | [Etapa 17](docs/17_IMPLANTACAO_E_OPERACAO.md) |

As análises `ANALISE_PROJETO.md` e `07_ANALISE_ATUAL_E_PROXIMO_PASSO.md` são diagnósticos anteriores. Totais, inscrições, estados, persistência, buscas e OpenAPI já foram implementados; eles não devem ser tratados como funcionalidades pendentes.

O perfil e os procedimentos de operação estão preparados na [etapa 17](docs/17_IMPLANTACAO_E_OPERACAO.md); a hospedagem ainda precisa ser escolhida/configurada. As próximas etapas de produto são construir a interface de uso e acrescentar relatórios de impacto.

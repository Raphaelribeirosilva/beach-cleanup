# Beach Cleanup

API para organizar mutirões de limpeza de praias, cadastrar usuários, gerenciar inscrições e registrar o peso dos resíduos coletados. Implementada com Java 25, Spring Boot 4.1.1, Spring MVC, Spring Security, JPA e Flyway.

O backend oferece CRUD com DTOs e validações, autenticação JWT, permissões de administrador/voluntário, troca de senha, estados dos eventos, totais calculados, catálogos de praias/materiais e buscas paginadas. O portal web do voluntário permite cadastro, login, consulta de eventos, inscrições e registro/acompanhamento de coletas. A área administrativa permite gerenciar eventos, coletas e catálogos e consultar usuários. Os relatórios por evento, praia, material e período são etapas futuras.

A [consolidação](docs/25_CONSOLIDACAO_DA_BASE.md) reúne instalação e atualização anteriores aos catálogos. A [etapa de qualidade dos dados](docs/26_QUALIDADE_DOS_DADOS.md) acrescenta pesos decimais, V7, regras de datas/praias e revisão de legados. Documentos de etapas anteriores registram resultados históricos; para o contrato atual, consulte este README e o [OpenAPI](docs/openapi.json).

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

## Portal do voluntário

Com a aplicação iniciada, abra **http://localhost:8080/** no navegador. O portal é servido pelo próprio Spring Boot e não exige servidor frontend, instalação npm ou build JavaScript.

É possível explorar eventos e filtrar por local/status sem login. Crie uma conta ou entre para inscrever-se, cancelar inscrições, registrar coletas independentes ou vinculadas a um evento e consultar suas coletas e seu total em kg. No perfil `test`, pode usar `ana@example.com` / `ana123`. O evento inicial está pausado; novos eventos são cadastrados por administrador pela API.

O portal preserva a sessão da aba em `sessionStorage` e renova access tokens de até 30 minutos por até sete dias desde o login. Cada renovação gira o refresh token; o servidor guarda somente hashes. Sair revoga a sessão no servidor, incluindo access tokens já emitidos; trocar a senha revoga todas as sessões da conta. Não há cookie de autenticação. Detalhes na [renovação e encerramento de sessão](docs/29_RENOVACAO_E_ENCERRAMENTO_DE_SESSAO.md).

Para administrar, entre com uma conta `ADMIN` e abra a aba **Administração**. No perfil `test`, use `admin@example.com` / `admin123`. As abas Eventos, Usuários e Coletas oferecem buscas paginadas; eventos e coletas possuem formulários e exclusão com confirmação. Voluntários não têm acesso à área administrativa, e o backend continua protegendo cada operação. Veja o uso, os testes e os limites na [entrega da interface administrativa](docs/20_INTERFACE_ADMINISTRATIVA.md).

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

As migrações atuais são V1 (esquema), V2 (índices), V3 (versão das coletas), V4 (versão dos eventos), V5 (tabelas e referências dos catálogos), V6 (associação dos textos antigos aos catálogos) V7 (pesos `DECIMAL(38,3)` e auditoria dos arredondamentos) e V8 (sessões renováveis e hashes de refresh tokens). V6 e V7 são migrações Java incluídas no JAR. Em banco já gerenciado pelo Flyway, a inicialização aplica somente as migrações pendentes. Preserve as versões aplicadas e crie uma nova migração para mudar o esquema; não use recriação de tabelas para atualizar um banco com dados existentes. V1 inicializa um banco vazio; um banco legado sem histórico Flyway exige uma migração específica. Siga o [procedimento de instalação e atualização](docs/25_CONSOLIDACAO_DA_BASE.md) e a [conferência da V7](docs/26_QUALIDADE_DOS_DADOS.md) para conferir histórico, backup e dados após a atualização.

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
$praias = Invoke-RestMethod -Uri "$api/catalog/beaches"
$materiais = Invoke-RestMethod -Uri "$api/catalog/materials"
$praia = $praias | Where-Object name -eq 'Praia do Morro' | Select-Object -First 1
$material = $materiais | Where-Object name -eq 'Vidro' | Select-Object -First 1
$body = @{ beachId = $praia.id; materialId = $material.id; weightKg = 2.5; registrationDate = '2026-10-17' } | ConvertTo-Json
$coleta = Invoke-RestMethod -Method Post -Uri "$api/trashCollection" -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod -Uri "$api/users/$($login.userId)" -Headers $headers
Invoke-RestMethod -Uri "$api/trashCollection/search?page=0&size=10" -Headers $headers
```

A coleta será vinculada automaticamente à Ana. Seu `totalCollectedKg` será calculado a partir das coletas existentes. No perfil MySQL, cadastre sua própria conta por `POST /users` com `name`, `email` e `password`, e substitua as credenciais de exemplo no login. O banco novo não possui catálogos demonstrativos: um administrador precisa cadastrar praia e material antes de registrar eventos/coletas. Nomes livres desconhecidos retornam 400; use IDs obtidos nas listas. Nomes principais e aliases cadastrados continuam aceitos nos campos antigos.

O token dura 30 minutos (`expiresIn: 1800`). Use `Authorization: Bearer <accessToken>` nas rotas protegidas. A troca de senha por `PUT /auth/password` revoga os tokens anteriores da conta; faça novo login. Senhas aceitam no máximo 72 bytes UTF-8.

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
| `/catalog/beaches` e `/catalog/materials` | GET público; POST somente administrador |
| `/catalog/{kind}/{id}` | PUT somente administrador, com `version`; `kind` aceita `beaches` ou `materials` |
| `/catalog/{kind}/{id}/merge` | POST somente administrador; recebe `targetId`, `version` da origem e `targetVersion` do destino |
| `/admin/data-quality` | GET somente administrador; pendências de praia/material, peso, data e compatibilidade com evento, paginadas |
| `/admin/data-quality/rounding` | GET somente administrador; snapshot paginado dos pesos convertidos na V7, originais e diferenças exatas |

Novos eventos começam como `Agendado`. Eventos `Agendado` e `Em andamento` aceitam novas inscrições e vínculos de coletas. Inscrição não é requisito para coleta, e uma coleta pode existir sem evento. Veja a matriz de transições na [etapa 9](docs/09_STATUS_DOS_EVENTOS.md).

Pesos novos devem estar entre **0,001 e 100.000 kg**, com até **3 casas decimais**. O backend usa `BigDecimal` e somas decimais exatas. Entradas com precisão excedente retornam 400; somente a migração dos dados antigos usa arredondamento `HALF_UP`, registrando original e diferença. A data da coleta deve estar entre 01/01/1900 e hoje em São Paulo. Com evento, **praia e dia da coleta devem coincidir com os do evento**; mudar a praia/dia do evento conflitando com coletas retorna 409. Datas passadas de eventos são permitidas para histórico. As abas administrativas **Qualidade dos dados** e **Conversão de pesos** permitem revisar pendências e conferir arredondamentos; a [etapa 26](docs/26_QUALIDADE_DOS_DADOS.md) explica as exceções e o procedimento de revisão.

As buscas usam páginas a partir de zero, tamanho padrão 20 e limite 100, com filtros e ordenação. Prefira essas rotas às listagens originais, que retornam arrays completos. Veja [exemplos de busca](docs/11_EXEMPLOS_DE_BUSCA.md).

Entradas inválidas retornam 400; autenticação ausente/inválida, 401; acesso negado, 403; recurso não encontrado, 404; conflitos de integridade, estado ou versão concorrente, 409. Para voluntários, IDs de coletas inexistentes ou inacessíveis retornam 403. Filtros `beachId` (eventos e coletas) e `materialId` (coletas) selecionam identidades exatas; os filtros textuais também reconhecem aliases.

## Campo `version` nas edições

Respostas de usuários, eventos, coletas e catálogos incluem `version`. Nos PUTs, envie a versão do próprio registro recebida quando abriu o formulário. Ausente/nula/negativa/inválida retorna 400; diferente da atual retorna 409 (`Concurrent update`) sem salvar. Após conflito, recarregue e revise os campos antes de reenviar. O servidor devolve a versão resultante; não a incremente no cliente. Uma edição sem mudança pode mantê-la.

Exemplo com a coleta criada acima, preservando seus vínculos opcionais:

```powershell
$atual = Invoke-RestMethod -Uri "$api/trashCollection/$($coleta.id)" -Headers $headers
$body = @{
    beachId = $atual.beachId; materialId = $atual.materialId
    weightKg = 3.0; registrationDate = $atual.registrationDate; version = $atual.version
    volunteer = $(if ($null -ne $atual.volunteer) { @{ id = $atual.volunteer.id } } else { $null })
    event = $(if ($null -ne $atual.event) { @{ id = $atual.event.id } } else { $null })
} | ConvertTo-Json -Depth 3
$coleta = Invoke-RestMethod -Method Put -Uri "$api/trashCollection/$($atual.id)" -Headers $headers -ContentType 'application/json' -Body $body
```

PUT substitui os campos de entrada; omitir `event` remove o vínculo e administrador também pode remover `volunteer`. POSTs de criação dispensam versão. A unificação exige versões da origem e do destino. Exclusões, inscrições e troca de senha mantêm seus contratos próprios. `version` não é `credentialVersion`, não representa a soma das versões dos vínculos e não usa ETag/If-Match. Mudanças de catálogos podem invalidar formulários de eventos/coletas relacionados. Veja [as regras completas](docs/23_PROTECAO_DE_FORMULARIOS_ANTIGOS.md).

## Testes, contrato e CI

```powershell
.\mvnw.cmd -B test
python tools/validate_openapi.py
python -m unittest discover -s tools -p 'test_*.py'
node --test tools/portal-api.test.cjs
```

A suíte padrão usa H2 e não exige MySQL. Os testes do cliente do portal usam Node.js 24 ou superior, sem pacotes npm; Node não é necessário para executar a aplicação. O contrato [OpenAPI](docs/openapi.json) pode ser importado em ferramentas de API; descreve 18 caminhos e 29 operações. É um arquivo estático, sem Swagger UI ou `/v3/api-docs` servido pela aplicação.

Para executar a suíte completa em uma instância MySQL temporária no Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/run_mysql_tests.ps1
```

O script exige os binários MySQL 8.0 e executa `verify` com o perfil Maven `mysql-it`, usando diretório/porta próprios e removendo a instância ao terminar. Esse perfil Maven seleciona testes; ele é distinto do perfil Spring `mysql`, que configura a aplicação. Variáveis de teste e alternativas de execução estão na [etapa 15](docs/15_TESTES_MYSQL_E_CI.md).

Os resultados da revisão de qualidade, instalação V1–V7, atualização de bancos anteriores e conversão dos pesos em MySQL real estão na [etapa 26](docs/26_QUALIDADE_DOS_DADOS.md). O [workflow CI](.github/workflows/ci.yml) usa JDK 25 e executa OpenAPI, conferência decimal em Python, cliente JavaScript, testes H2/MySQL e empacotamento em push para `main`, pull requests e acionamento manual. Os relatórios são guardados mesmo em caso de falha; o resultado remoto deve ser consultado no GitHub Actions para o SHA enviado.

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
| Portal web do voluntário, uso e verificações | [Etapa 18](docs/18_PORTAL_DO_VOLUNTARIO.md) |
| Interface administrativa — entrega 1 do planejamento | [Entrega administrativa](docs/20_INTERFACE_ADMINISTRATIVA.md) |
| Proteção contra formulários desatualizados | [Etapa 23](docs/23_PROTECAO_DE_FORMULARIOS_ANTIGOS.md) |
| Catálogos, aliases e unificação | [Etapa 24](docs/24_CATALOGOS_DE_PRAIAS_E_MATERIAIS.md) |
| Consolidação, instalação, atualização e verificações atuais | [Etapa 25](docs/25_CONSOLIDACAO_DA_BASE.md) |
| Qualidade dos dados, pesos decimais, V7 e revisão dos legados | [Etapa 26](docs/26_QUALIDADE_DOS_DADOS.md) |
| Relatórios de impacto | [Etapa 27](docs/27_RELATORIOS_DE_IMPACTO.md) |
| Área do voluntário: perfil, senha e manutenção das coletas | [Etapa 28](docs/28_AREA_DO_VOLUNTARIO.md) |
| Renovação e encerramento de sessão | [Etapa 29](docs/29_RENOVACAO_E_ENCERRAMENTO_DE_SESSAO.md) |

As análises `ANALISE_PROJETO.md` e `07_ANALISE_ATUAL_E_PROXIMO_PASSO.md` são diagnósticos anteriores. Totais, inscrições, estados, persistência, buscas e OpenAPI já foram implementados; eles não devem ser tratados como funcionalidades pendentes.

O perfil e os procedimentos de operação estão preparados na [etapa 17](docs/17_IMPLANTACAO_E_OPERACAO.md). A [etapa 21](docs/21_HOMOLOGACAO_RESTRITA.md) registra a preparação da homologação e suas pendências de validação hospedada. Interface administrativa, proteção de formulários, catálogos, relatórios, área do voluntário e renovação de sessão já foram implementados.

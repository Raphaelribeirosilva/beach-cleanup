# Consolidação da base atual

Referência histórica de 17/10/2026. Para a versão vigente, migrações **V1–V14**, materiais predefinidos e coleta em lote, consulte a [consolidação atual](38_VERSAO_ATUAL_COLETAS_EM_LOTE_E_V14.md).

Data: 17/10/2026. Base funcional: `e5096f2` (catálogos de praias e materiais), acrescida dos testes e da documentação desta consolidação.

## Contrato vigente

Use o [README principal](../README.md) para executar a aplicação e o [OpenAPI](openapi.json) para importar requisições: **16 caminhos e 27 operações**. As etapas antigas registram o comportamento e os resultados na data de cada entrega.

- JWT dura 1800 segundos (30 minutos). A troca de senha invalida os tokens anteriores da conta.
- Usuários, eventos, coletas e catálogos expõem `version`. PUT exige a versão lida ao abrir o formulário. Ausente, nula, negativa ou inválida: 400; desatualizada: 409, sem salvar. Recarregue e revise antes de reenviar. Não incremente a versão no cliente. POST de criação dispensa versão. A unificação de catálogos exige `version` da origem e `targetVersion` do destino. Exclusões, inscrições e troca de senha mantêm seus contratos próprios; não há ETag/If-Match.
- `version` não é a versão de credenciais. O total do usuário é calculado a partir das coletas e pode mudar sem alterar a versão do perfil. Renomear/unificar catálogos altera versões dos eventos/coletas afetados, invalidando formulários antigos.
- Eventos usam `beachId`; coletas usam `beachId` e `materialId`. Consulte os IDs em `GET /catalog/beaches` e `GET /catalog/materials`, públicos. POST/PUT/unificação de catálogos são exclusivos do administrador. Não existe exclusão física de catálogo pela API.
- Nomes principais e aliases cadastrados continuam aceitos em `location`, `beach` e `materialType`. Nomes desconhecidos retornam 400; nome e ID enviados juntos devem corresponder. IDs inexistentes retornam 404 e IDs unificados retornam 409. Os filtros por ID são exatos; os textuais também reconhecem aliases, caixa e acentos.
- PUT substitui os campos de entrada. Preserve os vínculos desejados: omitir `event` remove o vínculo; um administrador também pode remover/transferir `volunteer`. A versão enviada é sempre a do recurso editado.

Detalhes: [versões](23_PROTECAO_DE_FORMULARIOS_ANTIGOS.md), [catálogos](24_CATALOGOS_DE_PRAIAS_E_MATERIAIS.md), [exemplos de busca](11_EXEMPLOS_DE_BUSCA.md).

## Preparar JDK 25 e compilar

Instale um JDK 25 e configure seu caminho real. Os comandos abaixo são PowerShell, executados na raiz do checkout:

```powershell
$env:JAVA_HOME = 'C:\caminho\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
.\mvnw.cmd -v
.\mvnw.cmd -B package
python tools/validate_openapi.py
node --test tools/portal-api.test.cjs
```

Confira que **Java e Maven mostram Java 25**. O Wrapper usa Maven 3.9.16; a primeira execução requer rede. Em Linux/macOS, use `sh mvnw`. Python 3 valida o OpenAPI; Node.js 24 executa os testes JavaScript. Nenhum pacote npm é necessário. O JAR é `target/beachcleanup-0.0.1-SNAPSHOT.jar`. Sem `-Pmysql-it`, a suíte usa H2.

## Instalação nova no MySQL

Use MySQL 8.0 e seus clientes `mysql`/`mysqldump` (a validação abaixo usou 8.0.46). Não use o `mysql` do XAMPP quando ele for MariaDB. Confira o executável e a versão:

```powershell
$mysqlBin = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
& "$mysqlBin\mysql.exe" --version
& "$mysqlBin\mysql.exe" -h 127.0.0.1 -P 3306 -u root -p
```

No cliente, crie um banco vazio e uma conta própria. Substitua a senha ilustrativa antes de executar:

```sql
CREATE DATABASE beachcleanup CHARACTER SET utf8mb4;
CREATE USER 'beachcleanup_app'@'localhost' IDENTIFIED BY 'SUBSTITUA_POR_SENHA_PROPRIA';
GRANT ALL PRIVILEGES ON beachcleanup.* TO 'beachcleanup_app'@'localhost';
```

No terminal da aplicação, configure as mesmas credenciais e uma chave JWT persistente. O exemplo de URL abaixo é para desenvolvimento local:

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/beachcleanup?allowPublicKeyRetrieval=true&useSSL=false'
$env:DB_USER = 'beachcleanup_app'
$env:DB_PASSWORD = 'SUBSTITUA_POR_SENHA_PROPRIA'
$keyBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($keyBytes)
$rng.Dispose()
$env:JWT_SECRET = [Convert]::ToBase64String($keyBytes)
java -jar target/beachcleanup-0.0.1-SNAPSHOT.jar --spring.profiles.active=mysql
```

Guarde a chave em configuração externa e reutilize-a nos reinícios. Para ambiente hospedado, use `prod` e os procedimentos de [implantação](17_IMPLANTACAO_E_OPERACAO.md) e [homologação restrita](21_HOMOLOGACAO_RESTRITA.md).

A inicialização aplica **V1–V6** antes de `ddl-auto=validate`. O perfil persistente não insere usuários nem catálogos demonstrativos. Cadastre a primeira conta por `POST /users` com `name`, `email` e `password`. Um operador deve verificar a identidade e promover somente essa conta:

```sql
UPDATE tb_user SET role='ADMIN' WHERE id=<ID_VERIFICADO>;
```

Faça login novamente com essa conta; cadastre praia e material com seu token administrativo. IDs são ilustrativos nos exemplos HTTP; utilize os retornados no seu banco:

```http
POST /catalog/beaches
Authorization: Bearer <accessToken>
Content-Type: application/json

{"name":"Praia do Morro","aliases":["Morro"]}
```

```http
POST /catalog/materials
Authorization: Bearer <accessToken>
Content-Type: application/json

{"name":"Vidro","aliases":[]}
```

O fluxo completo de criação/edição de coleta com IDs reais está no [README](../README.md#primeiras-requisições). Para criar um evento como administrador:

```http
POST /cleanupEvent
Authorization: Bearer <accessToken>
Content-Type: application/json

{"title":"Mutirão","beachId":10,"dateTime":"2026-11-15T08:00:00","status":"Agendado"}
```

Na edição, envie todos os campos desejados mais `version` lida em `GET /cleanupEvent/{id}`. A API não cria praias implicitamente.

## Atualização de banco com dados anteriores aos catálogos

Este procedimento pressupõe histórico Flyway válido e compatível. A suíte cobre bases V2 e V4. Banco não vazio sem `flyway_schema_history` exige um plano específico de adoção; `baseline-on-migrate` e `clean` permanecem desabilitados.

1. Interrompa as escritas e encerre a versão antiga da aplicação. Registre a revisão do JAR, as configurações externas e o estado do banco. Consulte `SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;`. Não edite versões/checksums já aplicados.
2. Faça backup consistente do banco anterior, incluindo histórico Flyway. Com o caminho MySQL configurado, o exemplo PowerShell grava diretamente pelo cliente, sem conversão de encoding pelo shell:

   ```powershell
   New-Item -ItemType Directory -Path backups -Force | Out-Null
   & "$mysqlBin\mysqldump.exe" -h 127.0.0.1 -P 3306 -u root -p --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --routines --events --triggers --result-file=backups/pre-catalogos.sql beachcleanup
   if ($LASTEXITCODE -ne 0) { throw 'Backup falhou' }
   Get-FileHash backups/pre-catalogos.sql -Algorithm SHA256
   ```

3. Ensaie a restauração em **outro banco** antes de atualizar o original. Crie um banco vazio com nome distinto, restaure com `mysql -u root -p NOME_DO_BANCO_SEPARADO` e, dentro do cliente, `source C:/caminho/absoluto/backups/pre-catalogos.sql;`. Aponte `DB_URL` da nova aplicação para essa cópia, com usuário autorizado nesse banco, e execute o novo JAR no perfil `mysql`.
4. A inicialização aplica apenas versões pendentes: V3/V4 acrescentam versões zero; V5 cria catálogos e referências; V6 associa os textos existentes aos catálogos. V6 é Java e está no JAR compilado; copiar somente os SQLs não reproduz a atualização.
5. Compare todas as colunas antigas das quatro tabelas de domínio (`tb_user`, `tb_cleanup_event`, `tb_event_volunteer`, `tb_trash_collection`) com o backup: IDs, nomes/textos originais, e-mails, hashes BCrypt, perfis, versões de credenciais/linha já existentes, datas, pesos e vínculos. Confira contagens, soma dos pesos, inscrições, login com contas existentes e totais calculados. A suíte reproduz essa comparação integral automaticamente em MySQL descartável.
6. Confira que existem seis versões bem-sucedidas no histórico e que o Hibernate valida o esquema. Revise os novos IDs de praia/material: caixa/acentos/espaços equivalentes compartilham ID; `Morro` e `Praia do Morro` permanecem separados até decisão administrativa. Textos originais não são reescritos pela migração. Campos nulos/vazios permanecem sem referência para revisão; não há inferência de localização/material.
7. Reinicie o novo JAR na mesma cópia. Não deve haver migração reaplicada nem registros duplicados. Teste leitura, login, filtros e edição usando os IDs e `version` atuais. Após o ensaio, repita o processo controlado no banco de destino com um backup atualizado.

Para conferir rapidamente no cliente SQL, antes e depois da atualização:

```sql
SELECT COUNT(*) AS users FROM tb_user;
SELECT COUNT(*) AS events FROM tb_cleanup_event;
SELECT COUNT(*) AS registrations FROM tb_event_volunteer;
SELECT COUNT(*) AS collections, SUM(weight_kg) AS kg FROM tb_trash_collection;
SELECT volunteer_id, COUNT(*) AS collections, SUM(weight_kg) AS kg
FROM tb_trash_collection GROUP BY volunteer_id ORDER BY volunteer_id;
```

Depois de V6, revise referências ausentes sem excluir dados:

```sql
SELECT id, location FROM tb_cleanup_event WHERE beach_id IS NULL;
SELECT id, beach, material_type FROM tb_trash_collection
WHERE beach_id IS NULL OR material_id IS NULL;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

Se for necessário retornar à versão anterior, interrompa a aplicação nova e restaure o backup pré-atualização em banco separado, apontando o JAR antigo para ele. As migrações não oferecem downgrade. Preserve o banco atualizado para análise; não remova histórico/colunas para simular retorno.

## Repetir a validação em MySQL descartável

Com JDK 25 ativo e os binários MySQL 8.0 disponíveis:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/run_mysql_tests.ps1
node --test tools/portal-api.test.cjs
python tools/validate_openapi.py
```

O script escolhe porta livre, cria diretório de dados próprio, inicia somente em loopback e executa `verify -Pmysql-it`. Cada teste cria/remove seu banco com UUID; o script encerra a instância que criou e remove seu diretório ao terminar. Não acessa o serviço/banco MySQL de uso normal. Logs de inicialização ficam em `target/mysql-test-logs/` e os relatórios em `target/surefire-reports/`. Para outros caminhos de Maven/MySQL ou servidor descartável já iniciado, consulte a [etapa 15](15_TESTES_MYSQL_E_CI.md).

## Resultados locais registrados

Execução em 17/10/2026:

| Item | Resultado |
| --- | --- |
| JDK | Eclipse Temurin 25.0.4.1+1 LTS, Windows x64; SHA-256 do pacote conferido com a API oficial Adoptium |
| MySQL | Community Server 8.0.46 real, instância descartável, porta dinâmica |
| Maven | 3.9.12 instalado, cache local, modo offline; `-B -Pmysql-it verify` |
| Suíte Java | **95 testes, 0 falhas, 0 erros, 0 ignorados**; 88 H2/demais e 7 casos MySQL |
| Instalação | V1–V6, esquema validado, contas ausentes antes do cadastro, reinício, autenticação e probes HTTP aprovados |
| Atualização | V2→V6 e V4→V6 com comparação de todas as colunas antigas, login BCrypt e reinício aprovados |
| Backup/restauração | Comparação das quatro tabelas de domínio, dois catálogos e histórico Flyway aprovada; aplicação inicia e autentica após restauração |
| JavaScript | Node.js 24.21.0; **15 testes aprovados** |
| OpenAPI | Python 3.14.1; **16 caminhos e 27 operações**, estrutura e cobertura aprovadas |
| Empacotamento | **BUILD SUCCESS**, JAR Spring Boot executável |
| Wrapper | Maven 3.9.16 com JDK 25 confirmado por `mvnw.cmd -v`; `-o -B -DskipTests package` aprovado sobre as classes já testadas |
| Exemplos HTTP do README | Executados contra o JAR com H2 após readiness UP: login, listas de catálogos, criação e edição de coleta, avanço de `version`, rejeição do formulário antigo com 409, total e filtros por IDs aprovados |

Os casos de atualização usam duas contas (ADMIN/VOLUNTEER), três eventos, três inscrições e cinco coletas: peso total **12,25 kg**; totais por conta **2,75 kg** e **8,25 kg**; coleta sem proprietário **1,25 kg**. Incluem grafias equivalentes de `Plástico`, praias semanticamente distintas, vínculos ausentes e textos incompletos. Preservam hashes, perfis, versões, inscrições e todos os valores antigos, inclusive o total legado armazenado; a resposta da API continua usando o total calculado.

A execução usou uma cópia isolada do commit base mais a classe de testes desta entrega. Alterações locais preexistentes de segurança/H2 e documentação foram preservadas e ficaram fora da base testada e deste commit. Nenhum dado do banco MySQL habitual foi acessado. Relatórios locais desta execução ficam em `target/consolidation-source/target/surefire-reports/` e o log em `target/consolidation-tools/mysql-verify.log`; esses caminhos não são versionados. Os resultados e as verificações ficam registrados neste documento para revisão.

## CI remoto e estado da entrega

O [workflow](../.github/workflows/ci.yml) usa Temurin 25, MySQL 8.0, Python e Node 24. Executa testes Java/MySQL, OpenAPI, JavaScript e empacotamento; guarda relatórios Surefire por sete dias. Após envio autorizado, confira a execução **do SHA enviado**, não apenas uma execução anterior:

```powershell
gh run list --repo Raphaelribeirosilva/beach-cleanup --workflow ci.yml --commit (git rev-parse HEAD)
gh run watch <RUN_ID> --repo Raphaelribeirosilva/beach-cleanup --exit-status
gh run view <RUN_ID> --repo Raphaelribeirosilva/beach-cleanup
```

CI remoto ainda pendente nesta consolidação: não houve autorização de envio deste novo commit. A consulta sem autenticação retornou HTTP 404; não há `gh` autenticado nem token GitHub configurado neste ambiente. O acesso SSH usado pelo Git não autentica a API de Actions. Para concluir a conferência remota, é necessário acesso autenticado e resultado/link da execução correspondente. Os resultados locais acima não são apresentados como aprovação remota.

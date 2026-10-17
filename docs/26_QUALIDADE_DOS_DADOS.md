# Qualidade dos dados, pesos decimais e revisão de legados

Data: 17/10/2026. Esta etapa sucede a [consolidação V1–V6](25_CONSOLIDACAO_DA_BASE.md).

## Regras de gravação

| Campo/relação | Regra |
| --- | --- |
| Peso de uma coleta | De **0,001 a 100.000 kg**, inclusive; resolução de **0,001 kg (1 g)** |
| Precisão de entrada | Até três casas decimais efetivas; `1.2300` equivale a `1.230`. `1.2345` retorna 400, sem arredondar silenciosamente |
| Tipo numérico | `BigDecimal` em entidades/DTOs/somas; `DECIMAL(38,3)` em `tb_trash_collection.weight_kg` |
| Data da coleta | Entre **01/01/1900 e hoje em America/Sao_Paulo**, inclusive; datas futuras retornam 400 |
| Praia da coleta com evento | Mesmo **ID** da praia do evento. Grafias/aliases não definem identidade |
| Data da coleta com evento | Mesmo dia civil de `event.dateTime`, sem conversão de fuso do horário local do evento |
| Coleta independente | Permitida, sem evento e sem necessidade de inscrição; exige praia/material cadastrados e peso/data válidos |
| Data/hora de evento | De 01/01/1900 a 31/12/9999; datas passadas e futuras são permitidas para histórico/planejamento |
| Mudança de praia/dia do evento | Retorna 409 se conflitar com praia/dia já conhecidos de qualquer coleta vinculada. Campos nulos de legado permanecem pendentes; não há propagação silenciosa |

O limite de 100.000 kg é por registro, não por usuário/evento; totais podem excedê-lo. O armazenamento comporta pesos legados maiores para permitir revisão sem perda. O total é a soma decimal das coletas atuais, por proprietário, incluindo registros legados ainda pendentes; não se exclui um peso da soma por estar sinalizado. Sem coletas, retorna `0.000`. Coletas sem proprietário não entram no total de contas.

`User.totalCollectedKg` armazenado em `DOUBLE` é um campo **legado sem participação nos cálculos** e permanece intacto. O campo de resposta homônimo usa `BigDecimal` calculado no banco a partir de `weight_kg`. A migração não recalcula nem substitui o valor legado do perfil.

POSTs e PUTs passam pelas mesmas regras no service, inclusive fora do HTTP. Validações do DTO também recusam peso ausente/fora da faixa. O JSON continua usando números para pesos e totais correntes; clientes que somam dados devem usar aritmética decimal. PUT continua exigindo a `version` lida no registro.

## Exceções permitidas e limites

- Correções de coletas já vinculadas a evento pausado/concluído/cancelado continuam permitidas, desde que a praia, data e peso sejam válidos. Novo vínculo com esses eventos continua rejeitado com 409.
- É possível corrigir título, horário dentro do mesmo dia ou status de um evento sem mudar sua praia/dia. Pendências antigas não impedem essas correções por si só. Mudar a praia/dia exige compatibilidade com os campos já conhecidos das coletas que continuam vinculadas.
- Um evento histórico pode ser criado como `Agendado`, respeitando as transições existentes. Criar uma coleta de evento futuro continua proibido enquanto sua data for futura.
- Referências nulas de legado não recebem uma praia/material presumido. Na próxima edição da coleta, selecione referências válidas e corrija os demais campos. Caso o evento não tenha praia/data identificada e suas coletas também tenham campos ausentes, é permitido corrigir primeiro o evento: valores nulos nas coletas continuam pendências e não são preenchidos automaticamente. Depois corrija as coletas, preservando seus vínculos inclusive com evento concluído. Campos já conhecidos conflitantes continuam impedindo alterar o evento. Desvincular e vincular novamente continua sujeito à regra de novos vínculos do estado do evento.
- Há exceção para **preservar dados na migração**, não uma permissão para gravar novas coletas incoerentes. Peso negativo, zero, ausente ou acima do limite que já existia permanece identificável no relatório e exige decisão administrativa.

## Interface

O formulário do voluntário, ao abrir a partir de um evento, fixa a praia e a data desse evento. **Remover vínculo** libera os campos para uma coleta independente. Peso tem mínimo/máximo e passo de 0,001; data usa o dia de São Paulo. O backend permanece a autoridade da validação.

No editor administrativo, selecionar o ID de um evento carrega sua praia e data. Antes de salvar, a interface confere a compatibilidade; o backend a confere novamente dentro da transação, com bloqueio do evento. A edição de evento usa leitura bloqueante das coletas para não ignorar uma gravação recente sob o isolamento REPEATABLE READ do MySQL.

As novas abas administrativas são:

- **Qualidade dos dados**: lista uma linha por pendência, com ação **Revisar registro**. Um registro pode ter várias pendências. Após correção válida, a consulta deixa de apontá-las; não há botão para apenas ocultar uma inconsistência.
- **Conversão de pesos**: mostra o peso anterior, o convertido e a diferença da V7. É um histórico de conferência, mantido mesmo após editar, transferir ou excluir a coleta.

Ambas são exclusivas de `ADMIN`; voluntários recebem 403, e chamadas sem autenticação recebem 401. Não expõem credenciais nem hashes.

## V7: conversão e rastreabilidade

`src/main/java/db/migration/mysql/V7__decimal_collection_weights.java` acrescenta uma migração Java, sem modificar V1–V6. Ela é incluída no JAR compilado e executada pelo Flyway antes de `ddl-auto=validate`.

1. Lê os pesos antigos usando a representação decimal canônica de cada `DOUBLE` (`BigDecimal.valueOf`). Antes de qualquer alteração de domínio/DDL, confere se todos os valores finitos arredondados cabem em `DECIMAL(38,3)`. Um peso não representável interrompe a atualização, identificando a coleta para revisão. Não há truncamento/saturação.
2. Cria `tb_weight_conversion_audit`: ID da coleta, proprietário naquele momento, valor original em texto, valor convertido decimal e diferença exata em texto. Também registra pesos nulos e valores que não mudam.
3. Converte a coluna e grava explicitamente cada peso arredondado com **`HALF_UP`, três casas**. Não depende do arredondamento implícito do MySQL/H2. Quando o valor numérico muda, incrementa `row_version`, invalidando formulários anteriores.
4. Preserva IDs, usuários, e-mails, BCrypt, papéis, versões de credenciais, inscrições, datas, textos, referências e vínculos. A V7 não adivinha referências nem corrige datas antigas.

Exemplos: `2.3455 → 2.346` (+0,0005 kg), `0.0004 → 0.000` (−0,0004 kg, sinalizado como peso inválido), `-2.3455 → -2.346` (−0,0005 kg, sinalizado), `0.30000000000000004 → 0.300` (−0,00000000000000004 kg). Cada diferença está na auditoria. Um peso nulo continua nulo; não vira zero.

O arredondamento é **por coleta**. A soma convertida pode diferir do arredondamento da soma anterior: duas coletas de `1.2345` somavam `2.4690`; convertidas somam `2.470`, diferença de `0.001 kg`. Essa diferença deve conferir com a soma das diferenças individuais, inclusive por proprietário histórico.

O total anterior da auditoria significa a **soma decimal das representações canônicas dos pesos individuais**, não a reprodução de `SUM(DOUBLE)`. Somas antigas em ponto flutuante também podiam exibir ruído binário: pesos `0.1` e `0.2` passam a totalizar exatamente `0.300`, mesmo que a API antiga exibisse `0.30000000000000004`. Nesse exemplo, nenhum peso individual mudou e a diferença por coleta é zero; apenas o erro de acumulação foi eliminado.

A tabela de auditoria não tem FK para a coleta, pois deve sobreviver à exclusão desta. Ela não representa os totais atuais depois de futuras edições/transferências. Inclua-a nos backups; o teste de backup/restauração compara também essa tabela.

## Aplicação e revisão em um banco existente

Siga o [procedimento de backup e ensaio em banco separado](25_CONSOLIDACAO_DA_BASE.md#atualização-de-banco-com-dados-anteriores-aos-catálogos), agora esperando **sete migrações**. Encerre todas as instâncias/escritas da aplicação antiga antes de migrar. Teste a nova versão com o backup restaurado antes de atualizar o banco habitual.

MySQL realiza commits implícitos em DDL: a precondição de capacidade é verificada antes do DDL, mas não se promete rollback transacional de uma interrupção de infraestrutura durante V7. Nesse caso, restaure o backup e investigue a falha; não apague o histórico Flyway ou use `repair` para aceitar uma atualização parcial.

Após iniciar o novo JAR com perfil `mysql`/`prod`, confira:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SHOW COLUMNS FROM tb_trash_collection LIKE 'weight_kg';
SELECT COUNT(*) FROM tb_weight_conversion_audit;
```

Faça login com administrador e consulte as duas abas ou a API paginada:

```http
GET /admin/data-quality?page=0&size=100
Authorization: Bearer <accessToken>

GET /admin/data-quality/rounding?page=0&size=100
Authorization: Bearer <accessToken>
```

Pendências têm `resource` (`event`/`collection`), `id`, `code` e `message`. Os códigos identificam praia/material ausente, peso/data inválidos e divergência de praia/data com evento. Referências a catálogo de tipo incorreto ou já unificado também são sinalizadas. A conferência é dinâmica; não altera o banco.

Na auditoria, `originalWeight`, `roundedWeight` e `deltaWeight` são **strings decimais** (ou nulos) para que o navegador preserve até valores legados extremos. Para exportar todas as páginas e conferir os totais com Python `Decimal`:

```powershell
$api = 'http://localhost:8080'
# $headers contém o Bearer obtido no login administrativo; não salve o token no arquivo.
$records = @()
$pageNumber = 0
do {
    $page = Invoke-RestMethod -Uri "$api/admin/data-quality/rounding?page=$pageNumber&size=100" -Headers $headers
    $records += @($page.content)
    $pageNumber++
} while ($pageNumber -lt $page.totalPages)
New-Item -ItemType Directory -Path backups -Force | Out-Null
ConvertTo-Json -InputObject @($records) -Depth 5 | Set-Content -Encoding UTF8 backups/conversao-v7.json
python tools/summarize_weight_audit.py backups/conversao-v7.json
```

O resumo mostra total anterior, convertido e diferença, quantidade de pesos ausentes e agrupamento pelo proprietário na migração. Rejeita exportações duplicadas ou diferenças inconsistentes e mantém cálculos exatos, inclusive para valores muito pequenos/grandes. Para conferir os totais **atuais**, consulte usuários/coletas; não os compare diretamente ao snapshot depois de editar os dados.

Revise cada pendência com evidência do registro original. Cadastre/unifique praias e materiais quando houver identificação confirmada; não associe homônimos automaticamente. Corrija a coleta/evento pelo editor, mantendo os pesos quando a correção for apenas de referência. Quando o peso precisar mudar por informação de domínio, registre a justificativa operacional junto da exportação de conferência. A exportação e o relatório não contêm senha/JWT e ficam fora do Git em `backups/`.

## Verificação desta entrega

Execução local em 17/10/2026, sobre cópia isolada de `5ddc516` acrescida dos arquivos desta etapa:

| Verificação | Resultado |
| --- | --- |
| Ambiente | Temurin **25.0.4.1+1 LTS**, Maven 3.9.12 instalado, cache local em modo offline, Windows x64 |
| Java completo, `-B -Pmysql-it verify` | **115 testes aprovados**, 0 falhas, 0 erros, 0 ignorados; 106 H2/demais e **9 casos MySQL** |
| Banco real | MySQL Community Server **8.0.46**, processo/diretório/porta próprios; encerramento e limpeza confirmados |
| Migrações | Instalação V1–V7 e atualizações V2/V4/V6→V7 aprovadas; reinício sem reaplicar migrações; Hibernate valida o esquema |
| Preservação | Contas, BCrypt/login, papéis, versões de credenciais, datas, inscrições e vínculos conferidos; backup/restauração também compara a auditoria V7 |
| Conversão V6→V7 | Sete registros antigos (seis pesos numéricos e um nulo); original **100000.42430000000000004 kg**, convertido **100000.424 kg**, diferença **−0.00030000000000004 kg**, integralmente conciliada na auditoria |
| Capacidade | Em H2, peso `1e100` impede a migração antes de alterar coluna/dados ou criar a tabela de auditoria; peso/version originais preservados |
| Precisão | `0.1 + 0.2 = 0.300`; edição, transferência e exclusão mantêm somas decimais exatas; zeros sem coletas |
| Concorrência MySQL | Edição de evento não ignora coleta gravada após um snapshot anterior; inconsistência de praia é rejeitada sem alteração parcial |
| Legado incompleto | Correção de evento e coleta com campos nulos preserva peso e vínculo com evento concluído; pendências desaparecem após correção válida |
| JavaScript | **17 testes aprovados**, Node 24.21.0; limites, data de São Paulo, permissões e consultas de qualidade incluídos |
| Conferência decimal | **3 testes Python aprovados**, Python 3.14.1; totais por proprietário, ausências, extremos e rejeição de exportação inconsistente/duplicada |
| Contrato/arquivos | OpenAPI aprovado: **18 caminhos/29 operações**; sintaxe JS/PowerShell e links locais Markdown conferidos |
| CI local | Workflow conferido com **actionlint 1.7.12**, verificadores opcionais shellcheck/pyflakes desativados |
| Empacotamento | **BUILD SUCCESS**, JAR Spring Boot executável |
| Navegador | Edge headless aprovado com JAR final/H2 descartável: limites dos formulários, praia/data fixadas no vínculo, revisão de registro legado incompleto e auditoria preservada após correção; regressões de permissões, conflitos, totais, CSP e desktop/celular aprovadas. Celular usa emulação |

Os testes também cobrem mínimo/máximo de peso inclusivos, precisão excedente, datas de 1900/9999, ano 10000 rejeitado em eventos, coleta futura rejeitada, virada de dia de São Paulo, divergências de praia/dia e correção de coleta no mesmo evento encerrado. Pesos negativos/zero/nulos/acima da faixa existentes não são apagados nem corrigidos sem evidência: a migração os preserva e o relatório os identifica.

Para repetir com JDK 25 ativo e MySQL 8.0 instalado:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/run_mysql_tests.ps1
node --test tools/portal-api.test.cjs
node tools/verify_portal_browser.cjs
```

O executor MySQL também verifica OpenAPI e executa os testes Python. Nesta máquina foi usado `-MavenCommand 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd'` com `-MavenArguments @('-Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository','-o')`; o comando padrão usa o Wrapper. Logs locais: `target/consolidation-tools/quality-mysql.log`, relatórios em `target/quality-source/target/surefire-reports/` e logs MySQL em `target/quality-source/target/mysql-test-logs/`.

O roteiro de navegador cria um registro incompleto e um snapshot de auditoria em SQL apenas no H2 descartável antes da inicialização do seed. Confere a correção administrativa sem alterar o snapshot. Também fecha o navegador pelo CDP antes da remoção do perfil temporário, evitando deixar processos filhos do Edge bloqueando a limpeza no Windows. A execução final terminou com código zero e perfil removido; log em `target/consolidation-tools/quality-browser.log`.

A revisão usa dados legados controlados em bancos descartáveis e oferece o relatório para a base de uso. Não foi fornecida uma conexão para leitura da base habitual nesta tarefa; nenhum registro desse banco foi consultado ou alterado. A ausência de pendências nessa base só poderá ser afirmada depois de executar o roteiro nela ou em uma cópia correspondente.

Alterações locais anteriores de segurança/H2 e documentação são preservadas, excluídas da cópia isolada de validação e do commit desta etapa. Push depende de autorização específica para este novo commit.

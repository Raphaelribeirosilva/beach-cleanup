# Etapa 15 — Testes em MySQL real e integração contínua

Entrega inicial: 14/10/2026. Contrato de testes atualizado em 17/10/2026; resultados atuais na [consolidação](25_CONSOLIDACAO_DA_BASE.md).

## Objetivo

Verificar o comportamento no banco utilizado pelo projeto e automatizar testes, validação OpenAPI e empacotamento. Os testes H2 continuam disponíveis; a nova suíte usa MySQL real, sem substituir o servidor pelos modos de compatibilidade do H2.

## Testes MySQL

`MySqlDatabaseIT` é ativado pelo perfil Maven `mysql-it`. O perfil acrescenta `*IT.java` aos padrões de seleção do Surefire e mantém os padrões dos testes existentes. Não adiciona dependências Java novas.

Cada teste cria um banco com nome aleatório `beachcleanup_ci_<UUID>` e remove somente esse banco ao terminar. A conexão exige variáveis específicas de teste, servidor em loopback, porta explícita e URL sem nome de banco. Não lê `DB_URL`, `DB_USER`, `DB_PASSWORD` ou `JWT_SECRET` do ambiente de uso da aplicação. A ausência de configuração falha a execução, em vez de ignorar silenciosamente os testes.

Os nove casos atuais verificam:

1. Aplicação de V1–V7, validação do esquema pelo Hibernate, ausência de contas demonstrativas, autenticação e preservação de conta, evento, inscrição, coleta e total após reinício da aplicação. Conferem também unicidade de e-mail, duplicidade de inscrição, chave estrangeira inválida e probes HTTP com o perfil `prod`.
2. Atualização de um banco com V1/V2 e registros existentes para V7, preservando IDs, vínculos, data, peso, hash de senha e versão de credenciais; as novas versões da coleta e do evento iniciam em zero quando o peso não exige arredondamento.
3. Duas transações que carregam a mesma coleta: somente uma edição conclui, e a outra recebe conflito otimista. O peso e o total correspondem à edição vencedora.
4. Transferência por administrador enquanto o antigo proprietário tem uma coleta carregada: atualização e exclusão desatualizadas falham, preservando a transferência; uma nova operação do antigo proprietário é negada.
5. Backup/restauração em outro banco, comparando todas as colunas de usuários, eventos, inscrições, coletas, catálogos, aliases, auditoria V7 e histórico Flyway; login e reinício após restaurar.
6. Atualização V2 → V7 com usuários ADMIN/VOLUNTEER, BCrypt válido, versões de credenciais, três eventos, três inscrições e cinco coletas, inclusive sem proprietário/evento e com textos incompletos.
7. Atualização V4 → V7 com os mesmos dados e versões não zero em eventos/coletas. Os casos 6 e 7 comparam **todas as colunas antigas**, conferem login, IDs de catálogo, normalização de acentos/caixa/espaços, separação de nomes semanticamente distintos, totais e reinício sem reaplicar migrações. A comparação numérica desconsidera apenas zeros de escala adicionais da coluna decimal. Esses dois casos não chamam o seed auxiliar de catálogos dos demais testes.
8. Conversão V6 → V7 com pesos que exigem arredondamento, nulos, negativos, pequenos demais e acima do limite novo. Confere os valores originais, convertidos, diferenças exatas, totais, versões, login e relatório de pendências. Valores fora da faixa nova permanecem para revisão.
9. Uma transação de edição de evento abre um snapshot antes da inserção de uma coleta por outra transação. A leitura bloqueante deve enxergar a nova coleta e impedir mudar o evento para outra praia, sem deixar dados incompatíveis. Veja [qualidade dos dados](26_QUALIDADE_DOS_DADOS.md).

As transações concorrentes usam `CountDownLatch` com limites de tempo. A aplicação inicia com o perfil `prod`, que inclui `mysql`, driver MySQL e configurações reais de Flyway/Hibernate, incluindo `open-in-view=false`. A chave JWT utilizada é exclusiva dos testes.

## Execução local no Windows

O script `tools/run_mysql_tests.ps1` usa os executáveis MySQL 8.0 instalados, cria um diretório temporário e inicia um processo próprio em porta disponível, ligado somente a `127.0.0.1`. Usa `--no-defaults` para não ler configurações do servidor existente e `--no-monitor` para impedir a criação de um processo filho fora do controle do script. Desativa MySQL X e não instala nem altera serviços Windows.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/run_mysql_tests.ps1
```

O caminho padrão dos binários é `C:\Program Files\MySQL\MySQL Server 8.0\bin`. Para outro local ou instalação Maven, use os parâmetros:

```powershell
./tools/run_mysql_tests.ps1 -MySqlBin 'C:\caminho\MySQL\bin' -MavenCommand 'C:\caminho\mvn.cmd'
```

`-MavenArguments` aceita argumentos adicionais, como o caminho do cache Maven. O script executa `mvn -B -Pmysql-it verify`, `python tools/validate_openapi.py` e `python -m unittest discover -s tools -p 'test_*.py'` para conferir os cálculos da auditoria decimal. Exige JDK 25 ou superior, Python 3 e MySQL 8.0; a execução rejeita MariaDB. Na primeira execução, o Maven precisa de rede para baixar sua distribuição ou dependências ausentes. Os resultados atuais estão na [etapa 26](26_QUALIDADE_DOS_DADOS.md).

Ao terminar, inclusive em caso de falha, o script restaura as variáveis de ambiente, encerra somente o processo MySQL que iniciou e remove o diretório temporário após conferir seu caminho absoluto e prefixo gerado. Guarda logs em `target/mysql-test-logs/`, que não é versionado. A conta/senha literal de teste pertence apenas à instância descartável.

Uma política de execução que bloqueie scripts pode exigir a opção `-ExecutionPolicy Bypass` para esse processo; ela não altera a política permanente da máquina.

## Execução com servidor descartável já iniciado

Configure uma instância destinada exclusivamente a testes e uma conta com permissão para criar/remover bancos. Exemplo de variáveis para uma instância descartável na porta 13306:

```powershell
$env:MYSQL_TEST_SERVER_URL = 'jdbc:mysql://127.0.0.1:13306/?allowPublicKeyRetrieval=true&useSSL=false'
$env:MYSQL_TEST_USER = 'root'
$env:MYSQL_TEST_PASSWORD = 'senha-da-instancia-descartavel'
$env:MYSQL_TEST_CLIENT_BIN = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
./mvnw.cmd -B -Pmysql-it verify
```

O script de execução local é a opção que provisiona automaticamente o servidor separado. Não aponte as variáveis de teste para o serviço MySQL que contém dados reais. `MYSQL_TEST_CLIENT_BIN` localiza `mysql`/`mysqldump` para o ensaio de restauração; se omitido, os clientes devem estar no PATH. SSL e recuperação de chave pública nos exemplos são configurações da conexão de teste local, não uma configuração nova do perfil de produção.

`mvn test`, sem o perfil, mantém a suíte H2 existente e não exige MySQL. Para selecionar somente a nova classe em um servidor descartável já configurado:

```text
mvn -B -Pmysql-it -Dtest=MySqlDatabaseIT test
```

## Pipeline GitHub Actions

`.github/workflows/ci.yml` executa em push para `main`, pull requests e acionamento manual. Usa Ubuntu 24.04, Temurin Java 25, Python 3.14, cache Maven e um serviço `mysql:8.0` com porta atribuída dinamicamente e verificação de saúde.

O pipeline valida OpenAPI e executa `sh mvnw -B -Pmysql-it verify`, incluindo testes H2, testes MySQL e empacotamento. Guarda relatórios Surefire por sete dias mesmo quando houver falha. O token possui somente `contents: read`; não há deploy, escrita no repositório, acesso ao MySQL local ou necessidade de segredos de produção.

O workflow está versionado no GitHub. Um resultado local não equivale a uma execução aprovada do GitHub Actions: confira o SHA da execução após cada envio autorizado. A política de exigir CI aprovado antes de merge depende das configurações de proteção da branch no GitHub e não é alterada por esta etapa.

## Verificação histórica da entrega inicial

- A suíte com o perfil `mysql-it` executou **67 testes, zero falhas, zero erros e zero testes ignorados**, incluindo **quatro testes em MySQL Community Server 8.0.46 real** em instância temporária.
- Workflow validado com **actionlint 1.7.12**, sem erros, com verificadores opcionais shellcheck/pyflakes desativados. O executável foi baixado da release oficial e seu SHA-256 foi conferido contra o arquivo de checksums publicado.
- **BUILD SUCCESS** no `verify`, com JAR executável empacotado pelo Spring Boot. OpenAPI aprovado: **13 caminhos e 23 operações**.
- Após o ajuste de encerramento no Windows, o script foi verificado novamente com seleção da classe MySQL: quatro testes aprovados, empacotamento e OpenAPI aprovados, código de saída zero e diretório temporário removido. As instâncias e os diretórios temporários das execuções anteriores também foram encerrados/removidos.
- Revisão das alterações aprovada em `git diff --check`. O workflow ainda não foi executado no GitHub, pois não houve push nesta etapa.

Não houve acesso ao servidor MySQL que contém o banco `beachcleanup`, nem alteração em V1, V2, V3 ou na configuração de produção. A [validação manual da etapa 12](12_MYSQL_LOCAL_VALIDACAO_MANUAL.md) continua sendo o relato do usuário; os testes desta etapa foram executados pelo Codex em outra instância.

## Referências

- [Serviços em contêiner no GitHub Actions](https://docs.github.com/en/actions/tutorials/use-containerized-services/use-docker-service-containers).
- [Inicialização de um diretório MySQL](https://dev.mysql.com/doc/refman/8.0/en/data-directory-initialization.html).
- [Execução do MySQL no Windows](https://dev.mysql.com/doc/refman/8.0/en/windows-start-command-line.html).
- [Opção MySQL `--no-monitor`](https://dev.mysql.com/doc/refman/8.0/en/server-options.html#option_mysqld_no-monitor).
- [actionlint 1.7.12](https://github.com/rhysd/actionlint/releases/tag/v1.7.12).

## Git

O commit inclui somente o perfil Maven, workflow, testes MySQL, script local e este documento. Alterações locais preexistentes são preservadas. Push depende de autorização explícita.

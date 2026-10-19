# Etapa 17 — Preparação para implantação e operação

> Atualização de 18/10/2026: seguir [Homologação e recuperação](33_HOMOLOGACAO_E_RECUPERACAO.md) para migração separada, permissões, timers, retenção e aceitação. Os registros anteriores abaixo permanecem históricos.

Data: 14/10/2026.

## Entrega e limites

Foi criado o perfil Spring `prod`, com MySQL explícito, encerramento gracioso e logs JSON. O Actuator fornece verificações de saúde com acesso restrito às três rotas documentadas abaixo. Há modelos de ambiente e serviço Linux, além de procedimentos de atualização, backup e restauração.

Esta etapa prepara os artefatos. Não define um provedor, domínio ou servidor real e não publica a aplicação. O serviço systemd é um modelo para uma VM Linux com proxy HTTPS; sua instalação depende do destino escolhido. Nenhum serviço da máquina do usuário ou dado do banco `beachcleanup` foi alterado.

## Perfil de produção

Em `application.properties`, o grupo `prod` ativa `mysql`. Execute com as variáveis DB/JWT já configuradas:

```powershell
java -jar target/beachcleanup-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

O modelo [prod.env.example](../deploy/prod.env.example) contém somente placeholders. Configure os valores reais no ambiente do processo ou no gerenciador de serviços, fora do Git. Java não carrega um arquivo `.env` automaticamente. No Eclipse/STS, essas variáveis ficam na configuração de execução.

| Variável | Uso |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod`; o comando/modelo também força esse perfil |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Conexão MySQL persistente, com acesso às migrações |
| `JWT_SECRET` | Chave Base64 com pelo menos 32 bytes aleatórios, preservada entre reinícios |
| `SERVER_ADDRESS` | Padrão `127.0.0.1`, para uso atrás de proxy na mesma máquina |
| `SERVER_PORT` | Porta HTTP; padrão 8080 |

`ProductionConfig` rejeita `prod` combinado com `test`, ausência de `mysql`, URL/driver de banco temporário, `ddl-auto` diferente de `validate`, Flyway desativado, permissão de Flyway clean ou console H2 habilitado. A validação existente da chave JWT continua obrigatória. Os erros de configuração não incluem os valores das credenciais.

O perfil mantém SQL desabilitado e não carrega seed. O encerramento é gracioso, com até 30 segundos por fase do ciclo de vida. A execução padrão de desenvolvimento continua usando `test`; uma implantação deve usar explicitamente o comando `prod`.

V1/V2/V3 permanecem intactas. Esta etapa não cria migração nova.

## Saúde e monitoramento

| GET | O que verifica | Resultado |
|---|---|---|
| `/actuator/health` | Saúde agregada | 200 quando UP; 503 quando DOWN |
| `/actuator/health/liveness` | Estado do processo | Deve orientar reinício do processo |
| `/actuator/health/readiness` | Estado de atendimento e conexão ao banco | Deve orientar envio de tráfego |

As três rotas dispensam JWT e omitem componentes/detalhes internos, inclusive para administradores. A resposta pode incluir os nomes dos grupos de saúde. As demais rotas de gerenciamento e métodos de escrita são bloqueados. Somente `health` é exposto por HTTP; a exposição por JMX está desabilitada. As regras de autenticação da API permanecem nas configurações existentes.

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health/readiness
Invoke-RestMethod http://localhost:8080/actuator/health/liveness
```

Uma falha de banco deixa readiness em 503 sem tornar liveness DOWN. Configure o balanceador para encaminhar tráfego somente após readiness 200. Configure alertas para indisponibilidade persistente, erros da aplicação e falhas de backup. Uma indisponibilidade do banco deve ser investigada como dependência, sem reiniciar continuamente a API por esse único motivo.

Estas são rotas operacionais do Actuator; o contrato OpenAPI dos recursos de negócio mantém 13 caminhos e 23 operações.

## Logs

O perfil usa `logging.structured.format.console=ecs`, produzindo JSON no console com campos de tempo, nível, logger e mensagem. Use a captura de stdout do gerenciador da aplicação; o modelo Linux permite consulta por `journalctl -u beachcleanup`.

Esta configuração não ativa registro de corpos HTTP, cabeçalhos de autenticação ou parâmetros de query. Evite adicionar esses dados ao log. Preserve o acesso aos logs como acesso operacional e defina sua retenção no coletor ou journald, conforme o destino de hospedagem. Não há arquivo de log gravado pela aplicação em uma pasta do repositório.

## Modelo de execução em Linux

O arquivo [beachcleanup.service](../deploy/beachcleanup.service) considera:

- JDK 25 ou superior em `/usr/bin/java`.
- Conta de sistema `beachcleanup`, sem privilégios administrativos.
- JAR da versão em `/opt/beachcleanup/beachcleanup.jar`.
- Variáveis reais em `/etc/beachcleanup/prod.env`, com permissão de leitura restrita ao operador/gerenciador de serviços.
- Proxy reverso HTTPS encaminhando para `127.0.0.1:8080`. O proxy, certificado e domínio ainda precisam ser configurados no destino.

Após adaptar e instalar o modelo no host escolhido, o operador usará:

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now beachcleanup
sudo systemctl status beachcleanup
curl --fail http://127.0.0.1:8080/actuator/health/readiness
```

O modelo reinicia o processo quando ele falha e reserva 45 segundos para parada. Não instala MySQL, proxy ou certificados. Em contêiner ou plataforma gerenciada, passe as mesmas variáveis e use as mesmas rotas de saúde, ajustando `SERVER_ADDRESS` à rede do ambiente.

## Atualização e retorno à versão anterior

1. Gere o artefato com `mvnw -B -Pmysql-it verify` em servidor de testes descartável, ou utilize o artefato verificado da mesma revisão. Identifique a versão pelo commit e guarde seu checksum.
2. Guarde o JAR anterior e obtenha um backup verificável do banco. Não execute migrações/DDL durante a obtenção do dump transacional.
3. Pare a instância, substitua o JAR e inicie explicitamente com `prod`. Flyway aplica somente versões pendentes; Hibernate valida o esquema.
4. Confirme readiness e faça um teste de login, leitura de evento e consulta de coleta com contas adequadas. Confirme os logs e a preservação dos dados antes de liberar tráfego.
5. Se precisar voltar, use o JAR anterior somente quando o esquema continuar compatível. Migração incompatível exige correção posterior ou restauração coordenada com a aplicação parada; não use Flyway clean nem recriação de tabelas como rollback.

## Backup MySQL

Use os clientes da instalação MySQL e uma conta de backup com as permissões necessárias às tabelas, rotinas e eventos. O comando pede a senha interativamente; não coloca a senha no histórico da linha de comando. Exemplo em PowerShell, com destino externo ao repositório:

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe' --no-defaults --host=127.0.0.1 --port=3306 --user=conta_backup --password --single-transaction --quick --no-tablespaces --set-gtid-purged=OFF --routines --events --triggers --result-file=C:\Backups\beachcleanup-20261014.sql beachcleanup
if ($LASTEXITCODE -ne 0) { throw 'Backup falhou; não trate o arquivo como backup válido.' }
Get-FileHash -Algorithm SHA256 -LiteralPath C:\Backups\beachcleanup-20261014.sql
```

Crie o diretório de destino antes. Use `--result-file` em vez de redirecionar a saída no PowerShell, preservando a codificação produzida pelo cliente. `--single-transaction` oferece uma visão consistente das tabelas InnoDB quando não há alteração concorrente de esquema. O dump inclui o histórico Flyway e deve preservar contas, hashes, perfis, versões, eventos, inscrições, coletas e vínculos.

Defina frequência e retenção conforme a perda de dados e o tempo de recuperação aceitáveis para o projeto. Armazene cópias protegidas fora do servidor da aplicação; um dump contém dados pessoais e hashes de senha. Confirme o código de saída, tamanho, checksum e resultado de restauração, não apenas a existência do arquivo.

`backups/` e `deploy/prod.env` foram adicionados ao `.gitignore`. Isso não substitui um destino de armazenamento externo nem remove arquivos já versionados.

## Ensaio de restauração

Restaure primeiro em **outro banco**, criado vazio para o ensaio. O dump acima não usa `--databases`, portanto não contém o comando de seleção/criação do banco de origem gerado por essa opção. Exemplo, executado por um operador com permissões apropriadas:

```powershell
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --no-defaults --host=127.0.0.1 --port=3306 --user=operador_restore --password --execute='CREATE DATABASE beachcleanup_restore_20261014 CHARACTER SET utf8mb4'
if ($LASTEXITCODE -ne 0) { throw 'Não foi possível criar o banco de ensaio.' }
& 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe' --no-defaults --host=127.0.0.1 --port=3306 --user=operador_restore --password --database=beachcleanup_restore_20261014 --execute='source C:/Backups/beachcleanup-20261014.sql'
if ($LASTEXITCODE -ne 0) { throw 'A restauração apresentou erro.' }
```

Antes de restaurar, confira o checksum e examine referências explícitas a schemas/DEFINER caso o banco passe a ter views, rotinas ou eventos que dependam do schema de origem. Confirme as contagens de tabelas e registros, histórico Flyway, vínculos e totais calculados. Inicie uma instância separada da aplicação com `DB_URL` do banco restaurado e outra porta, mantendo a chave JWT apropriada em armazenamento protegido. Verifique readiness e login com uma conta de teste conhecida; não altere a configuração da aplicação em uso para fazer o ensaio.

O ensaio é um procedimento documentado nesta etapa, não uma restauração executada sobre os dados do usuário. Uma recuperação real deve coordenar a parada das escritas, o banco escolhido, a versão do JAR e a chave JWT. A restauração de um backup antigo também restaura versões de credenciais daquele momento; o operador deve decidir sobre a rotação da chave JWT para invalidar tokens que não devam continuar válidos.

## Testes e verificação

`OperationsIntegrationTests` cobre rotas públicas sem detalhes internos, readiness DOWN com liveness UP quando o indicador de banco falha, bloqueio das demais rotas operacionais e manutenção da proteção de `/users`.

`ProductionConfigTests` cobre configuração válida, rejeição de `prod,test`, ausência de MySQL e opções incompatíveis. A suíte `MySqlDatabaseIT` agora inicia com `prod` em servidor MySQL temporário e verifica também o grupo `prod,mysql` e as três probes por HTTP real, mantendo os cenários de migração, reinício, integridade e concorrência.

Resultado final: **BUILD SUCCESS — 73 testes, zero falhas, zero erros e zero testes ignorados**, incluindo os seis novos testes e os quatro testes em MySQL Community Server 8.0.46 temporário com perfil `prod`. As probes foram consultadas por HTTP real nesse perfil. O JAR foi empacotado e a instância temporária foi encerrada/removida pelo script da etapa 15.

OpenAPI aprovado: **13 caminhos e 23 operações**. Foram conferidos **24 links locais**, sintaxe dos exemplos PowerShell, regras de exclusão de arquivos de backup/ambiente e diferenças de whitespace. Não foi realizado deploy, backup ou restore no servidor MySQL do usuário; os modelos systemd e comandos de backup/restore não foram executados em um host de produção.

## Referências

- [Actuator: endpoints e probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html).
- [Logs estruturados do Spring Boot](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured).
- [mysqldump e opções de backup](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html).

## Git

O commit contém somente esta etapa. Alterações locais preexistentes, inclusive `SecurityConfig`, propriedades/testes do console H2 e o índice de `docs`, são preservadas e não incluídas. A segurança operacional foi criada em uma cadeia separada para manter essa separação. Push exige autorização explícita.

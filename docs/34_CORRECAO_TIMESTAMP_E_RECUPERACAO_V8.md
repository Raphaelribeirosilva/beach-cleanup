# Correção de TIMESTAMP e recuperação da V8

## Causa e alcance

O erro informado é `42000 / 1067 / Invalid default value for 'revoked_at'`, no primeiro `CREATE TABLE` da V8. Os erros de `entityManagerFactory`, `authRateLimitRepository`, `AbuseLimiter`, filtro e Tomcat são consequências da migração interrompida.

Com `explicit_defaults_for_timestamp=OFF`, o servidor pode interpretar `TIMESTAMP(6)` sem `NULL` explícito como obrigatório, com padrão zero. `NO_ZERO_DATE` e modo estrito tornam esse padrão inválido. Além disso, a primeira coluna TIMESTAMP (`expires_at`) pode ganhar `DEFAULT CURRENT_TIMESTAMP` e `ON UPDATE CURRENT_TIMESTAMP`: uma atualização comum poderia mudar o vencimento. São comportamentos documentados por [MySQL](https://dev.mysql.com/doc/refman/8.4/en/timestamp-initialization.html) e [MariaDB](https://mariadb.com/docs/server/reference/data-types/date-and-time-data-types/timestamp).

`AuthSession` usa `Instant expiresAt` obrigatório e `Instant revokedAt` opcional. `active(now)` exige revogação ausente e expiração futura; `revoke(now)` preenche a data uma única vez. Portanto, `TIMESTAMP(6) NULL DEFAULT NULL` é a definição correta de `revoked_at`. Não há motivo para preencher automaticamente a expiração, que é calculada pela aplicação. `AccountActionToken`, `AuthRateLimit` e `AuditEntry` também fornecem seus instantes explicitamente.

O perfil `mysql` usa MySQL Connector/J, Flyway e Hibernate `ddl-auto=validate`; não usa criação automática de schema pelo Hibernate. O rótulo “MySQL 5.5” do log não basta para identificar o produto real. Foram encontrados binários MySQL 8.0.46 e MariaDB 10.4.32 nesta máquina, mas a tentativa de conexão a `127.0.0.1:3306` sem senha foi negada. **Não foi possível consultar a versão nem o histórico do banco `beachcleanup` do usuário.** A versão dos binários instalados não comprova qual serviço atende a aplicação.

Confirmar com `SELECT VERSION(), @@version_comment` na mesma conexão usada pelo Eclipse. Se for realmente MySQL 5.5, esta correção não representa suporte a essa versão: o projeto usa recursos posteriores, como microssegundos, e deve usar um servidor compatível. A instância MySQL usada nos testes do projeto é 8.0.46. Não alterar o dialeto para ocultar o aviso de versão.

## Alterações implementadas

A V8 já foi aplicada com sucesso em testes MySQL anteriores e faz parte dos commits entregues. Não há acesso aos demais ambientes para inventariá-los. Foi avaliada uma solução sem alterar a V8, configurando `explicit_defaults_for_timestamp` por conexão. O teste no MariaDB 10.4.32 demonstrou que essa variável é global/somente leitura nessa versão: essa alternativa não corrige o caso legado sem mudar a configuração e reiniciar o servidor.

Por isso, a entrega corrige as duas declarações da V8 e exige alinhamento manual e revisado do checksum nos ambientes que já aplicaram sua versão anterior. V9 e V10 permanecem inalteradas. **Não existe repair automático nem realinhamento silencioso de histórico.**

- V8: `revoked_at TIMESTAMP(6) NULL DEFAULT NULL`; sessões ativas continuam sem revogação. `expires_at DATETIME(6) NOT NULL` não recebe default nem atualização automática, mesmo com os padrões legados do servidor. Não se usa `DEFAULT CURRENT_TIMESTAMP` para uma expiração que deve ser informada pela aplicação.
- V11: normaliza revogação e converte os quatro campos obrigatórios para `DATETIME(6) NOT NULL`: `tb_auth_session.expires_at`, `tb_account_action_token.expires_at`, `tb_auth_rate_limit.window_start` e `tb_audit_entry.occurred_at`. Esses campos das V9/V10 também podiam ganhar defaults/atualizações automáticos. A conversão é feita com `time_zone=+00:00`, preservando o instante UTC; a zona anterior da conexão é restaurada ao final. Não há `UPDATE`, `DELETE`, `DROP` ou recriação de tabelas.
- Perfil `mysql`: Hibernate e Connector/J usam UTC explicitamente (`hibernate.jdbc.time_zone`, `connectionTimeZone` e `forceConnectionTimeZoneToSession`). Assim, os `Instant` continuam representando datas UTC embora DATETIME não faça conversão de fuso no servidor. Não sobrescrever essas opções com parâmetros conflitantes na URL JDBC. Integrações SQL externas também devem ler/escrever esses DATETIME em UTC.
- `MySqlVendorDialectResolver`: quando o metadado do Connector/J identifica produto MySQL, mas a versão completa contém, por exemplo, `5.5.5-10.4.32-MariaDB`, escolhe o dialeto MariaDB com a versão **10.4.32 real**. MySQL nativo mantém a resolução padrão. Isso corrige o SQL de bloqueio `FOR UPDATE OF alias`, rejeitado pelo MariaDB observado nos ensaios. Não se inventa versão mais recente para esconder avisos do Hibernate; um aviso de versão mínima continua relevante e os ensaios desta correção não equivalem à homologação integral de MariaDB antigo.
- Perfil Maven `flyway-maintenance`: permite `info`, `repair` e `validate` manuais com a mesma versão Flyway do projeto, mesmos scripts e credenciais fornecidas pelas variáveis `DB_URL`, `DB_USER`, `DB_PASSWORD`. Não é executado pelo build ou pela inicialização, não habilita baseline automático e mantém `clean` desabilitado.

Mudar a V8 não faz o Flyway reaplicá-la em um banco onde consta sucesso. Nesse caso, após conferir o checksum original e a diferença SQL esperada, o repair alinha somente o metadado da V8; a V11 faz a alteração real do esquema. Em banco com V8 falhada sem objetos parciais, o repair remove o registro falhado e a próxima inicialização executa a V8 corrigida.

Checksums Flyway desta revisão: V8 original `-1798703937`; V8 corrigida `1609259866`. Se a V8 aplicada tiver outro checksum, não alinhar automaticamente: investigar a versão SQL que criou aquele ambiente. V9/V10 não devem apresentar divergência. O ensaio de alinhamento registrou `Checksum: 1609259866` e preservou todas as linhas de domínio.

## Diagnóstico do seu banco: executar antes de qualquer repair

1. Parar a aplicação no Eclipse e garantir backup recuperável do banco e do histórico antes de manutenção. Usar o mesmo host, porta, schema e usuário do perfil `mysql`; não confiar no primeiro serviço MySQL listado na máquina.
2. No Workbench/cliente conectado ao schema `beachcleanup`, abrir e executar [tools/diagnose_v8.sql](../tools/diagnose_v8.sql). O script é somente leitura: mostra versão/produto, modos, histórico completo, tabelas presentes e definições de colunas, sem ler senhas ou tokens.
3. Se as tabelas da V8 aparecerem, executar individualmente os `SHOW CREATE TABLE` e `COUNT(*)` indicados ao fim do arquivo. A ausência de dados não deve ser inferida de `information_schema.tables.table_rows`.
4. Conferir se somente a V8 está falhada, se não existem divergências de checksum fora da alteração esperada da V8 nem migrações ausentes e se nenhuma aplicação continua usando as tabelas. Não excluir linhas manualmente de `flyway_schema_history`.

| Estado observado | Procedimento |
|---|---|
| V8 ainda não aplicada, sem entrada falhada e sem tabelas da V8 | Não executar repair; iniciar a versão corrigida para aplicar V8–V11. |
| Apenas V8 falhada, sem `tb_auth_session` e sem `tb_refresh_token` | Após backup e revisão do histórico, executar o repair manual abaixo. A nova inicialização poderá reaplicar V8. |
| V8 aplicada com sucesso na versão anterior | Conferir o DDL, os dados e a diferença de checksum restrita à V8. Após backup e revisão, alinhar o checksum com repair e aplicar V11. Se V8 já usa o checksum corrigido, não fazer repair. |
| Há tabelas parciais, dados, outras falhas, checksum divergente fora da alteração esperada da V8 ou estrutura inesperada | Interromper o procedimento simples. Preservar backup e DDL, reconciliar o estado com o responsável pelo banco e definir a recuperação antes de repair. Não apagar, truncar ou renomear tabelas automaticamente. |

No ensaio descartável, a falha 1067 no primeiro `CREATE TABLE` não criou nenhuma das duas tabelas, mas registrou V8 com `success=0`. Isso é evidência do ensaio, **não diagnóstico do seu schema**. DDL MySQL/MariaDB não permite presumir rollback de todas as instruções de uma migração. `repair` altera metadados e pode também realinhar checksums; não corrige objetos parciais, conforme a [documentação do Flyway](https://documentation.red-gate.com/flyway/reference/commands/repair).

Se um ambiente antigo chegou a armazenar datas zero ou revogações incorretas, a V11 não tenta adivinhar valores corretos nem reativar sessões. Avaliar esses dados separadamente, preservando histórico; o modo estrito pode impedir alterar uma coluna que contém datas inválidas.

## Comandos para voltar a iniciar

No PowerShell, na raiz do projeto, configurar as mesmas variáveis da execução do Eclipse. Variáveis definidas apenas na configuração do Eclipse não são herdadas pelo terminal. Exemplo, **substituindo host/porta/usuário pelos reais**:

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/beachcleanup'
$env:DB_USER = 'SEU_USUARIO'
$databaseSecret = Read-Host 'Senha do banco' -AsSecureString
$env:DB_PASSWORD = [System.Net.NetworkCredential]::new('', $databaseSecret).Password
.\mvnw.cmd -Pflyway-maintenance compile flyway:info
```

Esse comando de manutenção exige conta com permissões adequadas no histórico, e a inicialização com migração exige DDL no schema. O plugin será baixado pelo Maven na primeira utilização. A fase `compile` é necessária: a location é `classpath:db/migration/mysql`, incluindo as migrações Java V6/V7 e os SQL atualizados; usar somente a pasta de arquivos SQL deixaria V6/V7 ausentes e tornaria repair perigoso. Não usar `-X`/debug para registrar credenciais, não passar senha por `-Dflyway.password` e não editar arquivos versionados com valores reais.

**Somente após diagnóstico e backup: V8 falhada sem as duas tabelas, ou V8 antiga aplicada com sucesso com divergência de checksum restrita às duas alterações documentadas:**

```powershell
.\mvnw.cmd -Pflyway-maintenance compile flyway:repair
.\mvnw.cmd -Pflyway-maintenance compile flyway:info
```

Não executar esses comandos preventivamente nem para esconder divergências inesperadas de checksum. Se V8 já estava aplicada, executar `flyway:validate` antes: a divergência deve estar restrita à V8 e corresponder à revisão entregue; parar diante de qualquer outra diferença. O arquivo de configuração mantém as mesmas `locations` da aplicação, como exigido pelo repair. Se houver tabelas parciais, os comandos acima isoladamente não resolvem e a inicialização deve aguardar reconciliação.

Com diagnóstico resolvido, preservar/configurar o `JWT_SECRET` já usado pela aplicação e iniciar:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=mysql'
```

Alternativa: atualizar os recursos do projeto no Eclipse/Maven e iniciar normalmente com o perfil `mysql` e as variáveis já configuradas. A inicialização deve aplicar as pendências até V11, validar o schema e chegar a `Started BeachcleanupApplication`. Depois:

```powershell
.\mvnw.cmd -Pflyway-maintenance compile flyway:validate
.\mvnw.cmd -Pflyway-maintenance compile flyway:info
```

Conferir V8–V11 com sucesso, `revoked_at` nullable/default NULL e ausência de `on update` em `expires_at`. Fazer login, renovar a sessão, verificar que uma atualização não estende o vencimento e confirmar que logout revoga a sessão. Não habilitar `clean`, `baseline-on-migrate` ou `ddl-auto=create/update` como atalho.

## Validação e limitações

Os resultados finais dos testes desta entrega estão registrados abaixo. Nenhum comando de escrita foi executado no banco local do usuário. A versão real, a existência de objetos parciais, o histórico e a retomada da aplicação nesse banco dependem do diagnóstico acima.

Resultados executados nesta entrega:

- 46 testes Java de regressão aprovados em H2 2.4.240 e MySQL 8.0.46 descartável: migração, preservação de dados, restauração, sessões, recuperação de conta e configurações de produção.
- No MariaDB **10.4.32 real**, em outra porta e datadir temporário: quatro cenários aprovados — reprodução/recuperação do 1067, alinhamento revisado da V8 já aplicada com preservação das linhas, inicialização/reinicialização com login e renovação concorrente com revogação por reutilização. O teste confirmou o rótulo “MySQL 5.5” no Flyway para esse MariaDB.
- O ensaio de conversão inicia em UTC, muda a conexão para `+03:00`, aplica V11, confere restauração de `+03:00` e compara as datas/linhas novamente em UTC. A expiração permanece igual após atualizar outro campo; a revogação da sessão ativa permanece NULL; inserir sessão sem expiração falha.
- Plugin Maven de manutenção validado em schema descartável MariaDB: `compile flyway:info flyway:migrate flyway:validate` descobriu e aplicou **todas as 11 migrações, inclusive V6/V7 Java**. As operações de repair foram testadas por API somente nos schemas descartáveis com estado previamente conferido.
- Após acrescentar a identificação do fornecedor, mais seis verificações aprovadas: duas unitárias do resolver e quatro cenários MySQL nativo (recuperação da falha, alinhamento da V8, inicialização/login e renovação concorrente). MySQL nativo mantém o dialeto padrão.
- 12 testes Python e estrutura/cobertura do OpenAPI aprovados: 28 caminhos, 39 operações. Não houve alteração de endpoints.

Esses resultados não comprovam a recuperação do seu banco `beachcleanup`: não houve autenticação nele, leitura de seu histórico, repair, exclusão ou modificação de seus dados. Execute o diagnóstico e escolha o procedimento pelo estado real, não pelo estado do ensaio.

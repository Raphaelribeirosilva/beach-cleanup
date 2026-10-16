# Homologação restrita — entrega 2 do planejamento

Data: 16/10/2026.

## Situação da entrega

A preparação local foi implementada. **A implantação hospedada continua pendente:** não foram informados provedor, servidor, domínio ou acesso operacional. Não existe URL de homologação criada por esta entrega. O critério de conclusão da etapa dois exige executar a instalação e os testes abaixo no destino escolhido; os testes locais não substituem essa aceitação.

## Análise do projeto e alterações

O portal do voluntário e a administração já utilizam a API no mesmo domínio. O perfil `prod` ativa MySQL, Flyway e validação do esquema, impede o seed de demonstração, produz logs JSON e oferece liveness/readiness. O modelo systemd existente mantém o Java em loopback e carrega segredos de `/etc/beachcleanup/prod.env`. Esses componentes foram reutilizados sem alterar regras de negócio ou migrações.

Foram acrescentados:

- [Proxy HTTPS restrito](../deploy/nginx-staging.conf.example): TLS 1.2/1.3, lista de IPs permitidos com bloqueio padrão IPv4/IPv6, encaminhamento para loopback, bloqueio externo do Actuator e log de acesso sem query string ou cabeçalhos.
- [Script de backup](../deploy/backup-mysql.sh): dump transacional com rotinas/eventos/triggers, arquivo privado, checksum SHA-256 e remoção de saída incompleta quando ocorre falha. Requer Bash, cliente MySQL 8.0, `mktemp` e `sha256sum` no Linux.
- [Modelo de credenciais do cliente MySQL](../deploy/backup.cnf.example), destinado a arquivo protegido fora do checkout. O script exige caminhos absolutos e valida o nome do banco.
- Ensaio em `MySqlDatabaseIT` que cria dados com acentos, exporta com `mysqldump`, restaura com `mysql` em banco separado e compara todas as colunas das cinco tabelas, incluindo hashes, perfis, versões, vínculos e histórico Flyway. A aplicação inicia com `prod` no banco restaurado e verifica login, inscrição e total coletado.
- Exclusão de `deploy/staging.env` e `deploy/backup.cnf` pelo Git. O executor MySQL local informa o diretório dos clientes; no CI Linux eles devem estar no `PATH`.

O ensaio utiliza somente credenciais de uma instância descartável, passadas ao ambiente dos processos filhos. Os dumps temporários são removidos ao finalizar. Nenhum banco existente do usuário é utilizado.

## Instalação no servidor de homologação

O destino previsto pelos modelos é uma VM Linux com systemd, Java 25, MySQL 8.0 e Nginx, alcançado diretamente, sem CDN ou outro proxy à frente. A escolha e o custo do provedor dependem do usuário. Reutilize os procedimentos da [implantação e operação](17_IMPLANTACAO_E_OPERACAO.md).

1. Configure DNS para o host, inclusive IPv6 se houver registro AAAA. No firewall, permita SSH apenas ao operador e HTTPS aos testadores ou à VPN. Mantenha 3306 e 8080 inacessíveis externamente. O MySQL e o Java devem escutar somente em loopback. Se usar banco gerenciado, adapte a rede e configure TLS com verificação do certificado na conexão JDBC.
2. Crie um banco **exclusivo e vazio** chamado `beachcleanup_staging`, em `utf8mb4`, e uma conta MySQL exclusiva com acesso a esse schema e permissões para executar V1/V2/V3. Use o cliente seguro do servidor para definir a senha, sem colocá-la no checkout ou no histórico. Não reutilize dados pessoais ou credenciais de produção.
3. Instale a conta de sistema e os diretórios do modelo [beachcleanup.service](../deploy/beachcleanup.service). Instale o JAR verificado em `/opt/beachcleanup/beachcleanup.jar`, mantenha a versão anterior e registre o commit e `sha256sum` do artefato em armazenamento operacional.
4. Copie os campos de [prod.env.example](../deploy/prod.env.example) para `/etc/beachcleanup/prod.env`, com proprietário `root` e modo `0600`. Configure `SPRING_PROFILES_ACTIVE=prod`, URL do banco de homologação, usuário/senha exclusivos, `SERVER_ADDRESS=127.0.0.1` e `SERVER_PORT=8080`. Gere `JWT_SECRET` com `openssl rand -base64 32` diretamente no ambiente protegido; preserve essa chave entre reinícios. Nunca envie o arquivo ao Git.
5. Instale o serviço e inicie com `sudo systemctl daemon-reload` e `sudo systemctl enable --now beachcleanup`. Confirme `curl --fail http://127.0.0.1:8080/actuator/health/readiness` e liveness. Confira `journalctl -u beachcleanup` sem copiar credenciais para registros da entrega.
6. Emita um certificado válido para o domínio por desafio DNS com o provedor de certificados escolhido. Instale a cadeia/chave nos caminhos do modelo Nginx, adapte domínio e insira `allow IP/32;` ou a rede VPN **antes** de `deny all;`. No celular fora da VPN, é necessário permitir também o IP público da rede móvel; prefira VPN para endereços variáveis.
7. Instale o arquivo Nginx no contexto `http` da distribuição, execute `sudo nginx -t` e só então recarregue o serviço. O modelo começa com todo acesso HTTPS bloqueado; não remova `deny all`. HTTP apenas redireciona para o domínio HTTPS fixo, sem servir o portal. Não existe exceção pública para emissão de certificado nem para saúde.
8. Cadastre contas exclusivas de teste pelo portal. Um operador deve promover somente a conta administradora verificada, conforme a [documentação MySQL](10_PERSISTENCIA_E_MIGRACOES.md). O cadastro público da API continua criando voluntários; a restrição do proxy cobre também essa rota.

O modelo preserva o cabeçalho Bearer usado pelo portal. A autorização da API continua obrigatória para os participantes liberados na rede. A lista de IPs usa a conexão efetiva ao Nginx; não configure confiança irrestrita em `X-Forwarded-For`. Se houver balanceador/CDN, adapte e teste a identificação do cliente antes de utilizar este modelo. Confira também o virtual host padrão do servidor para que nenhum outro host encaminhe à porta da aplicação.

Defina retenção dos logs Nginx e journald no servidor, proteja o acesso operacional e configure alertas para readiness indisponível, erros recorrentes e backups ausentes/falhos. O template usa `$uri`, sem argumentos da requisição; não ative log de corpos, senhas ou Authorization.

## Backup e restauração em banco separado

Instale o script em `/opt/beachcleanup/deploy/backup-mysql.sh`, de propriedade do operador e sem permissão de escrita para a aplicação. Instale `/etc/beachcleanup/backup.cnf` a partir do modelo, com modo `0600` e conta apropriada ao backup. Prepare um diretório privado de backup fora do checkout. Execute como o operador autorizado a ler esse arquivo. Exemplo Linux:

```sh
bash /opt/beachcleanup/deploy/backup-mysql.sh \
  /etc/beachcleanup/backup.cnf /var/backups/beachcleanup beachcleanup_staging
```

O script não aplica retenção nem envia cópias automaticamente. Agende a execução diária pelo gerenciador do servidor, alerte sobre código de saída diferente de zero e copie dump e checksum para armazenamento protegido fora da VM após cada sucesso. Defina retenção e janela de perda aceitável antes do uso. Não execute migrações/DDL durante o dump.

Para o ensaio, utilize uma conta de restauração diferente, em `/etc/beachcleanup/restore.cnf`, com acesso somente ao banco de ensaio. Substitua o caminho do dump e o nome datado abaixo. Execute cada comando somente após o anterior terminar com sucesso:

```sh
sha256sum --check /var/backups/beachcleanup/ARQUIVO.sql.sha256
mysql --defaults-file=/etc/beachcleanup/restore.cnf \
  --execute='CREATE DATABASE beachcleanup_restore_20261016 CHARACTER SET utf8mb4'
mysql --defaults-file=/etc/beachcleanup/restore.cnf --binary-mode=1 \
  beachcleanup_restore_20261016 < /var/backups/beachcleanup/ARQUIVO.sql
```

A criação deve falhar se o banco já existir; não use `IF NOT EXISTS` nem restaure sobre o banco em uso. O dump não inclui `--databases` e não seleciona o banco de origem. Se futuramente houver views/rotinas/eventos com schema explícito ou `DEFINER`, revise-os antes de importar. Não utilize dumps recebidos de fontes desconhecidas.

Configure uma segunda instância com o mesmo JAR, perfil `prod`, credenciais do banco restaurado e porta 8081 em loopback, mantendo os segredos fora do Git. Verifique readiness, login de conta conhecida, perfis, eventos, inscrições, coletas, versões e totais. Compare as tabelas e o histórico Flyway com o snapshot exportado. Encerre essa instância após o ensaio; mantenha o serviço principal e o banco de homologação intactos.

## Aceitação no ambiente hospedado

Registre data, commit/JAR, URL, executor e resultado de cada item, sem dados pessoais ou segredos:

| Verificação | Resultado exigido |
|---|---|
| Computador e celular físico na rede permitida/VPN | Certificado confiável, login e portal funcionais, sem rolagem horizontal nos formulários principais. |
| Rede não permitida, IPv4 e IPv6 quando disponível | HTTPS bloqueado com 403; acesso direto às portas 8080/3306 impossível. |
| Cadastro/login e permissões | Conta voluntária não consegue operações administrativas, inclusive chamando a API diretamente. |
| Administração | Cadastro/edição/status de eventos, consulta de usuários, gestão de coletas, paginação e mensagens de validação/conflito funcionais. |
| Voluntário | Inscrição, cadastro e consulta das próprias coletas, eventos e totais funcionais. |
| Reinício | IDs, hashes, perfis, inscrições, coletas e totais preservados após `systemctl restart beachcleanup`; readiness volta a UP. |
| Operação | Logs JSON disponíveis; probes locais UP e Actuator inacessível externamente. |
| Backup | Dump e checksum conferidos, cópia protegida fora da VM e restauração no banco separado aprovada. |
| Retorno | JAR anterior preservado, procedimento de rollback conferido com o esquema atual. |

Mantenha a restrição de rede durante toda a homologação. Os fluxos de recuperação de senha, verificação de e-mail e proteção contra abuso continuam previstos para outra entrega antes da abertura pública.

## Verificações locais

Resultados executados nesta entrega:

- Maven `-Pmysql-it verify` e empacotamento: **BUILD SUCCESS, 76 testes, zero falhas, erros ou ignorados**. Inclui cinco testes em MySQL Community Server 8.0.46 descartável, com o novo ensaio de restauração e o teste existente de persistência após reinício.
- JavaScript: **oito testes aprovados** em `portal-api.test.cjs`.
- OpenAPI: **13 caminhos e 23 operações**, contrato e cobertura aprovados.
- Navegador: roteiro completo aprovado no Edge headless, com fluxos de voluntário e administrador, bloqueio de operações administrativas por JWT de voluntário, paginação, validação/conflitos, totais, CSP e limpeza de sessão. Viewports de computador e celular emulado aprovados; capturas em `target/admin-desktop.png` e `target/admin-mobile.png`.
- Bash: sintaxe aprovada; simulação do cliente confirma geração/checksum, rejeição de nome inválido e remoção de saída incompleta após falha. A exportação/importação real foi validada pelo teste Java, sem executar o script Linux contra banco existente.
- Documentação: sete links locais conferidos, todos válidos; arquivos de segredos operacionais conferidos no `.gitignore`.

O Maven e o navegador exigiram execução fora do sandbox após bloqueios de acesso ao repositório de dependências e a recursos do Windows, respectivamente. As verificações utilizam a árvore de trabalho atual, que também contém alterações anteriores preservadas e excluídas deste commit. Não houve execução do CI remoto nem instalação/teste de configuração Nginx em servidor real.

Para repetir:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& '.\tools\run_mysql_tests.ps1' -MavenCommand 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd' -MavenArguments @('-Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository','-o')"
node --test tools/portal-api.test.cjs
python tools/validate_openapi.py
node tools/verify_portal_browser.cjs
```

Em outro ambiente com Maven disponível, use o executor MySQL com os caminhos apropriados ou configure `MYSQL_TEST_SERVER_URL`, `MYSQL_TEST_USER`, `MYSQL_TEST_PASSWORD` em um servidor descartável e execute `mvnw -B -Pmysql-it verify`. O ensaio adicional exige `mysql` e `mysqldump` do MySQL 8.0 no PATH, ou `MYSQL_TEST_CLIENT_BIN` apontando para seu diretório.

O Nginx, o certificado, o agendamento e o armazenamento externo precisam ser validados no servidor de destino. Testes de viewport móvel locais são emulação e não substituem o teste no celular físico hospedado.

## Git

Esta entrega deve incluir somente seus próprios arquivos no commit. As alterações preexistentes, inclusive as já preparadas no índice, foram preservadas. `19_PROXIMOS_PASSOS.md` e `RESUMO_ATUALIZACAO_PROXIMOS_PASSOS.md` permanecem fora do commit. Nenhum push foi solicitado.

## Referências

- [Nginx: restrição por endereço e ordem de allow/deny](https://nginx.org/en/docs/http/ngx_http_access_module.html).
- [Nginx: encaminhamento HTTP e cabeçalhos](https://nginx.org/en/docs/http/ngx_http_proxy_module.html).
- [MySQL 8.0: mysqldump e backup transacional](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html).

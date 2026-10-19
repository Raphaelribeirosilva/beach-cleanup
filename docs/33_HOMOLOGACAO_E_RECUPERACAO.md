# Homologação e recuperação

Preparação local em 18/10/2026. **Ambiente hospedado pendente**: não foram fornecidos servidor, domínio, acesso operacional ou participantes. Os modelos não comprovam implantação. Este roteiro complementa as etapas 17 e 21 e prevalece nos procedimentos abaixo.

## Destino e segredos

Usar VM Linux com systemd, Java compatível com o projeto, MySQL 8, Nginx, Python 3, Bash e utilitários `flock`, `mountpoint`, `sha256sum`. Confirmar versões e caminhos no destino. Os scripts Linux tiveram validação de sintaxe local; unidades systemd, permissões POSIX, proxy e armazenamento remoto ainda precisam ser exercitados no servidor.

Definir responsável operacional, host/alias SSH, domínio e DNS, IPs/VPN dos participantes (IPv4 e IPv6), armazenamento cifrado fora da VM e canal de alertas. Não enviar senhas ou chaves pelo chat. Não copiar dados pessoais de produção para testes sem política de anonimização.

Instalar o JAR verificado em `/opt/beachcleanup/beachcleanup.jar` e a pasta `deploy` em `/opt/beachcleanup/deploy`, propriedade root, sem escrita pelas contas de serviço. Criar contas de sistema sem login `beachcleanup` e `beachbackup`. Criar `/etc/beachcleanup` restrito; arquivos `prod.env`, `migration.env` e `operations.env` pertencem a root, modo 0600, lidos por systemd. Os clientes `backup.cnf` e `restore.cnf` devem ser legíveis somente por `beachbackup`, modo 0600, contendo seção `[client]`, host, usuário e senha. Não passar credenciais em argumentos. Não versionar arquivos reais.

Adaptar `database-permissions.sql.example` interativamente, substituindo todos os placeholders. Não executar o exemplo literalmente. A aplicação usa somente SELECT/INSERT/UPDATE/DELETE no schema de homologação; migrador tem DDL somente nesse schema. Backup não escreve na origem. SHOW_ROUTINE permite visibilidade global de metadados: o modelo pressupõe instância dedicada; adaptar com o provedor se compartilhada. Restauração só recebe permissões no prefixo `beachcleanup_restorecheck_`, com `partial_revokes=0`; verificar `SHOW GRANTS` e provar que essa conta não pode escrever no schema original. Nunca usar a conta administrativa no serviço.

## Implantação restrita

1. Gerar JWT persistente de pelo menos 32 bytes aleatórios e configurar `prod.env.example` fora do Git. Manter acesso público desabilitado. Configurar envio real de e-mail e verificar entrega antes de admitir usuários reais; modo disabled não entrega confirmação/recuperação.
2. Configurar firewall: SSH pela rede administrativa, MySQL e portas 8080/18080 somente locais. Adaptar `nginx-staging.conf.example`, substituir domínio/caminhos de certificado e liberar apenas IPs/VPN autorizados. Conferir IPv6. Emitir certificado por DNS conforme procedimento da etapa 21; validar renovação automática e executar `nginx -t` antes de recarregar. O proxy sobrescreve encaminhamento; não confiar em cabeçalhos enviados diretamente pelo cliente.
3. Instalar unidades `beachcleanup.service` e `beachcleanup-migrate.service`. Fazer backup validado antes de uma atualização com dados. Parar o serviço da aplicação; iniciar migrador com `systemctl start beachcleanup-migrate`. Ele executa migrações e continua ativo: aguardar `curl --fail http://127.0.0.1:18080/actuator/health/readiness`, conferir logs e **parar o migrador**. Não considerar `systemctl start` prova de sucesso. Falha impede seguir adiante.
4. Iniciar `beachcleanup.service`, verificar readiness local em 8080 e então liberar tráfego do proxy restrito. O ambiente de runtime usa `SPRING_FLYWAY_ENABLED=false` e `SECURITY_DATABASE_EXTERNAL_MIGRATIONS=true`; valida histórico, checksums e ausência de migrações pendentes sem executar DDL. Configurações contraditórias são recusadas. O modo automático anterior continua disponível para execução com conta de migrador, nunca no serviço com conta limitada.
5. Registrar commit, hash SHA-256 do JAR, versões, operador e horário. Não retornar JAR antigo sobre schema incompatível: restaurar em banco novo, validar e trocar a conexão conforme recuperação abaixo.

## Backup, retenção e restauração

Criar `/var/backups/beachcleanup` e subpasta `restore-reports`, propriedade `beachbackup`, modo 0700. Montar armazenamento protegido **fora da VM** em `/mnt/beachcleanup-offsite`, com escrita para essa conta e cifragem/gestão de chaves do provedor. Um diretório local ou disco da mesma VM não atende esse requisito. Confirmar a origem real da montagem e testar sobrevivência à perda da VM. Adaptar `ReadWritePaths` nas unidades se mudar os caminhos.

Instalar `operations.env.example`, unidades backup/restore-check/monitor/alert e timers em `/etc/systemd/system`; executar `systemctl daemon-reload`. Primeiro iniciar manualmente `beachcleanup-backup.service`, conferir resultado, checksum e cópia externa; depois `beachcleanup-restore-check.service` e seu relatório privado. Só habilitar timers após sucesso:

```sh
systemctl enable --now beachcleanup-backup.timer beachcleanup-restore-check.timer beachcleanup-monitor.timer
systemctl list-timers 'beachcleanup-*'
```

Backup diário às 02:00 UTC, com atraso aleatório de até cinco minutos; restauração de ensaio aos domingos às 03:00 UTC. Retenção padrão 14 dias em ambos os destinos, preservando pelo menos dois pares completos. Dumps e checksums antigos só são removidos depois de validar os pares e copiar para montagem externa. Arquivos desconhecidos são preservados; corrupção ou montagem ausente interrompem o ciclo. Há trava contra execução simultânea. O marcador de sucesso só é atualizado após todas as fases. Execução perdida é recuperada pelo timer persistente; acompanhar espaço e falhas, pois isso não garante RPO sozinho.

`restore-mysql.sh` aceita config, dump e pasta de relatórios absolutos; cria exclusivamente banco temporário com prefixo próprio e o remove ao terminar. Confere checksum, histórico Flyway sem falhas, referências de coletas e contagens; invalida sessões e links de conta na cópia. Seu relatório indica `applicationAcceptance=pending`: não inicia a aplicação e não prova aceitação funcional. `restore-latest.sh` usa o dump mais recente; corrupção não é ocultada escolhendo um mais antigo. Testar também a recuperação a partir do armazenamento externo.

Em incidente: restringir tráfego, preservar evidências e backup disponível, escolher ponto íntegro, restaurar em banco novo isolado e comparar contagens, quilos, vínculos e histórico. Antes de disponibilizar uma restauração, em transação revogar `tb_auth_session`, consumir `tb_account_action_token` e incrementar `tb_user.credential_version`; nunca reativar sessões/links antigos de um backup. Validar login novo e rejeição de access/refresh/links antigos, relatórios e permissões; só então trocar conexão e reabrir acesso restrito. Registrar tempo, perda de dados, responsável e decisão. Não sobrescrever a origem durante o ensaio. Proposta a aprovar pelo responsável: RPO nominal de 24h e RTO de 60min; nenhum deles está comprovado no servidor.

## Logs e alertas

Logs JSON da aplicação ficam no journal; Nginx registra URI sem query, sem Authorization ou corpo. Não habilitar debug de e-mail, SQL com parâmetros ou captura de tokens. Para retenção isolada instalar `journald-beachcleanup.conf.example` em `/etc/systemd/journald@beachcleanup.conf` e o drop-in `beachcleanup-logging.conf.example` em `/etc/systemd/system/beachcleanup.service.d/logging.conf`. Reiniciar o serviço conforme janela e verificar `journalctl --namespace=beachcleanup -u beachcleanup`. Limite sugerido de 256MB/14 dias implica retenção efetiva menor sob volume; definir coleta externa e acesso conforme política.

Monitor a cada cinco minutos verifica readiness local e backup com idade máxima de 26h; marcador no futuro também falha. Falhas de backup, ensaio e monitor acionam `beachcleanup-alert@.service`, que **apenas escreve evento crítico no journal**. Configurar coletor/alerta real para responsável e substituto, incluindo journal global (operações), namespace da aplicação, disco, MySQL, Nginx e renovação TLS. Validar recebimento e recuperação com falha controlada em homologação; não considerar logger prova de entrega de alerta. A indisponibilidade total da VM exige monitor externo.

## Aceitação e evidências

Executar de rede autorizada e depois de rede não autorizada, usando arquivos novos e domínio real:

```sh
python3 tools/check_staging.py https://DOMINIO_REAL --access allowed --output allowed.json
python3 tools/check_staging.py https://DOMINIO_REAL --access blocked --output blocked.json
```

O verificador exige TLS válido e pelo menos 14 dias restantes, portal acessível, recurso protegido com 401 e Actuator externo bloqueado. A perspectiva bloqueada comprova 403 do Nginx; timeout não conta como aprovação. Se o firewall bloqueia conexão inteira, registrar essa evidência de rede separadamente e manter prova positiva da rede autorizada. Não existe opção de ignorar TLS.

Preencher `docs/acceptance/staging-template.json` em registro restrito, sem nomes, e-mails, tokens ou senhas. Validar desktop e dispositivo móvel reais, teclado/foco e redes autorizada/bloqueada; cadastro/confirmação, login/refresh/logout, recuperação, perfil, coleta com conflito, exclusão, inscrição, relatórios conciliados e ações administrativas. Usar contas de voluntários distintos e administrador; provar negação entre contas. Registrar resultado, data, operador por identificador interno, navegador/dispositivo e evidência. Teste automatizado local não substitui aceite por usuários reais.

Evidência local: teste MySQL descarta/restaura banco separado, compara todas as linhas das 13 tabelas, inicia aplicação restaurada, confere login e total 2,5kg e rejeita tokens antigos após quarentena. Conta DML é testada sem DDL ou leitura de `mysql.user`; schema com migração pendente impede inicialização. CI publica `target/staging-recovery-proof.json`. Os testes Python verificam cópia, corrupção, retenção, falhas de montagem, monitor, HTTPS simulado e sintaxe Bash. Não houve implantação remota nem teste de dispositivo/usuário real nesta entrega.

Conclusão hospedada exige: URL restrita operacional, evidências TLS/restrição, backup externo e restauração completos no destino, alertas recebidos, metas de recuperação medidas e aceite funcional assinado. Esses itens permanecem pendentes até infraestrutura e participantes disponíveis, sem bloquear as etapas locais.

Validação desta entrega: 23 testes Java (16 MySQL, 3 operacionais e 4 de configuração), 12 Python e OpenAPI com 28 caminhos/39 operações aprovados. [Evidência local da restauração](acceptance/local-recovery-2026-10-18.json): 13 tabelas, ensaio de aproximadamente 4,22 segundos em banco descartável local; esse tempo não representa RTO do servidor.

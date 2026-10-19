# Recuperação de conta e proteção contra abuso

Esta entrega implementa recuperação de senha, confirmação de e-mail, política de senha e limites persistentes. O envio simulado é testado localmente e o adaptador SMTP está preparado. **A entrega real em uma caixa postal não foi validada: não há provedor nem endereço de teste configurados nesta sessão. A aplicação deve continuar com acesso restrito até essa validação.**

## Fluxos pelo portal

No cadastro, a senha deve ter pelo menos **7 caracteres Unicode**, respeitando o limite existente de **72 bytes UTF-8** do BCrypt. Há bloqueio básico de senhas comuns e de um único caractere repetido. Não se exigem combinações arbitrárias de símbolos/maiúsculas nem se impede colar senhas. A política vale para cadastro, troca e redefinição; não muda silenciosamente o hash das senhas legadas aceitas no login.

O cadastro cria `emailVerified=false` e agenda a confirmação após confirmar a transação. O portal orienta a conferir a mensagem antes de entrar. O login em ambiente real exige o endereço confirmado e responde genericamente a senha incorreta, conta ausente ou e-mail não confirmado. Em **Entrar**, há **Esqueci minha senha** e **Reenviar confirmação de e-mail**.

O pedido de recuperação sempre responde 202 com a mesma mensagem para endereços cadastrados, desconhecidos, não confirmados, limitados por destinatário ou envio indisponível. A busca da conta e o envio acontecem em executor separado: o SMTP não atrasa seletivamente a resposta de um e-mail existente. A resposta não garante envio. Recuperação só é enviada para endereço já confirmado; para cadastro ainda não confirmado, use o reenvio da confirmação.

O link de confirmação dura **24 horas**. Abrir o link apenas mostra a tela; a pessoa precisa clicar em **Confirmar e-mail**, enviando POST. O link de recuperação dura **15 minutos** e abre o formulário de nova senha e confirmação. O sucesso da redefinição encerra **todas as sessões** da conta, invalida seus access tokens e links anteriores de recuperação, notifica por e-mail sem incluir a senha e pede login normal com a nova senha. Não há login automático após redefinição.

Para mudar o próprio e-mail, o voluntário deve informar a senha atual em **Meu perfil**. Essa mudança remove a confirmação, invalida os links antigos pelo vínculo ao endereço/geração de credenciais, revoga as sessões e agenda confirmação do novo endereço. Administradores mantêm a permissão já existente de manutenção das contas. Editar apenas o nome não exige a senha.

## Tokens e privacidade

Os tokens de ação têm 32 bytes aleatórios, codificados em Base64 URL sem padding, e somente seu SHA-256 fica no banco. Cada token possui usuário, endereço, finalidade, expiração e geração de credenciais. O token de confirmação não serve para redefinição, nem o inverso. O mesmo token não pode ser consumido duas vezes, inclusive com requisições simultâneas: o servidor bloqueia a conta e faz leitura com bloqueio do token antes de alterar os dados. Validação de senha malsucedida não consome um link ainda válido.

Pedir outra mensagem não bloqueia a conta nem encerra suas sessões. Vários links de recuperação podem coexistir por até 15 minutos; usar um deles muda a geração de credenciais e invalida todos os demais. Trocar a senha autenticado também invalida os links antigos. Credenciais, senhas e destinatários não são registrados nos logs da aplicação; falhas do worker usam mensagem fixa, sem detalhes do provedor que possam incluir esses dados.

Os links usam `/#reset=...` e `/#verify=...` em uma origem configurada e confiável, sem usar o cabeçalho Host do pedido. O fragmento não é enviado ao servidor; o portal remove-o do histórico ao abrir o formulário. As páginas usam `no-referrer`, sem terceiros, e não persistem tokens de ação em `sessionStorage` ou `localStorage`. A confirmação continua sendo uma ação explícita por POST, protegendo contra consumo por uma simples visita de um verificador de links.

## Limites de tentativa

Os contadores ficam em `tb_auth_rate_limit`, com chaves HMAC usando o segredo JWT persistente. Não são reiniciados a cada processo nem dependem de memória de uma única instância. E-mails e IPs não são gravados em texto nesses contadores. Transações curtas independentes confirmam os contadores mesmo quando o login é recusado; bloqueios e repetição limitada tratam criação simultânea e deadlocks, com recusa conservadora sob contenção.

| Operação | Limite | Resposta ao atingir |
| --- | --- | --- |
| Login por endereço normalizado | 10 a cada 15 minutos | 429 + `Retry-After` |
| Login por IP | 30 a cada 15 minutos | 429 + `Retry-After` |
| Cadastro por IP | 10 por hora | 429 + `Retry-After` |
| Pedidos de recuperação/confirmação por IP, compartilhados | 20 por hora | 429 + `Retry-After` |
| Pedidos por endereço e finalidade | 3 por hora | 202 genérico; envio adicional suprimido |
| Consumo de links por IP, compartilhado | 20 a cada 15 minutos | 429 + `Retry-After` |
| Alteração de e-mail pelo voluntário, por conta | 5 a cada 15 minutos | 429 + `Retry-After` |

As janelas são fixas a partir da primeira tentativa; tentativas válidas também contam. Não se bloqueia permanentemente a conta. O filtro usa o endereço remoto resolvido pelo servidor, sem interpretar um `X-Forwarded-For` arbitrário. Com Nginx, mantenha o backend em loopback e o proxy sobrescrevendo os cabeçalhos; o [modelo de ambiente](../deploy/prod.env.example) configura o Tomcat para confiar apenas no proxy de loopback. Sem essa configuração, os pedidos pelo proxy compartilham o limite do endereço do proxy. Não configure confiança em qualquer remetente.

O executor de e-mail tem até duas threads e fila limitada a 100 pedidos. Sob saturação, o pedido pode ser descartado mantendo o mesmo 202, sem expor a existência da conta. A fila é de memória, não uma outbox durável: reinício antes do envio pode exigir nova solicitação. As restrições de destinatário/IP limitam o abuso do envio e do armazenamento; mantenha também os controles de rede e infraestrutura da implantação.

## Envio simulado e SMTP

No perfil `test`, o padrão é `account.mail.mode=memory`. As mensagens são capturadas em memória, com no máximo 100 entradas, e os testes as leem pelo bean `AccountMailGateway`; não existe endpoint HTTP para consultar mensagens ou tokens. O modo simulado é proibido fora de `test`. As contas de demonstração e a regressão antiga usam o perfil restrito, no qual a exigência de confirmação e os limites são desativados por padrão. Os novos testes ativam ambos explicitamente. Para testar essas exigências no perfil local, use `--security.accounts.require-verified-email=true --security.abuse.enabled=true`; o envio continua simulado.

Fora de `test`, confirmação e limites são ativos por padrão e o e-mail fica `disabled` até ser configurado. Isso permite preparar um ambiente restrito sem mandar mensagens reais; pedidos genéricos continuam sem prometer entrega. Use **SMTP com STARTTLS** (protocolo `smtp`, usualmente porta 587), remetente validado e `ACCOUNT_PUBLIC_BASE_URL` HTTPS. O Spring Mail executa o envio real quando `ACCOUNT_MAIL_MODE=smtp` e o host estiver configurado. A aplicação exige STARTTLS obrigatório e validação do nome do certificado; os timeouts de conexão/leitura/escrita são de cinco segundos. Não habilite debug de SMTP nem registre corpos de recuperação.

Configuração pelo [modelo](../deploy/prod.env.example), fora do Git: `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `ACCOUNT_MAIL_FROM`, `ACCOUNT_PUBLIC_BASE_URL` e `ACCOUNT_MAIL_MODE=smtp`. Não inclua credenciais reais nos arquivos de exemplo.

### Validação real obrigatória antes da abertura

Com um endereço de teste autorizado e o provedor configurado:

1. Mantenha a restrição de rede e `SECURITY_PUBLIC_ACCESS_ENABLED=false`, com confirmação e limites ativos.
2. Cadastre uma conta de teste e confira a **chegada na caixa postal**, o remetente e o domínio HTTPS do link. Confirme pelo portal e entre. O aceite pelo servidor SMTP sozinho não comprova entrega na caixa postal.
3. Peça recuperação, confira a chegada da nova mensagem, redefina a senha e confira a notificação. Teste senha nova, recusa da senha antiga, dos JWTs/refresh tokens anteriores e da reutilização do link. Confira também spam e eventuais bloqueios do provedor.
4. Registre data, provedor, resultado e responsável pela conferência, sem senhas, tokens ou conteúdo sensível. Só então defina `ACCOUNT_MAIL_DELIVERY_VALIDATED=true`.
5. A configuração `SECURITY_PUBLIC_ACCESS_ENABLED=true` recusa iniciar com perfil `test`, confirmação/limites desativados, modo simulado/desabilitado ou sem a declaração de entrega real validada. Esse sinalizador não remove as restrições de rede e não publica a aplicação automaticamente.

**Pendente nesta entrega:** não há provedor/endereço autorizados configurados para executar os passos reais. O adaptador e a validação simulada não substituem essa conferência. Não liberar acesso público enquanto a pendência existir.

## Persistência, manutenção e contrato

A migração **V9** acrescenta `email_verified=false` às contas existentes, encerra as sessões anteriores uma única vez e cria as tabelas de tokens e limites. Não presume que os e-mails legados já foram confirmados. Após atualizar, os usuários reais precisam confirmar o endereço e entrar novamente. IDs, senhas e dados de limpeza são preservados. As contas removidas têm seus tokens de ação eliminados em cascata.

Na manutenção, tokens de ação vencidos podem ser removidos por `expires_at`. Contadores com `window_start` anterior a um dia podem ser eliminados, pois todas as janelas são menores; não limpe contadores ativos. Não existe job automático de descarte nesta entrega. Faça backup antes da migração e preserve os procedimentos existentes de restauração.

Novos endpoints públicos, todos JSON e com `Cache-Control: no-store`: `POST /auth/recovery`, `/auth/verification`, `/auth/reset-password` e `/auth/verify-email`. Nenhuma rota usa cookie de autenticação. O [OpenAPI](openapi.json) descreve formatos, estados, limites, `emailVerified` e a senha atual para mudança de e-mail.

## Referências e verificações

A resposta genérica, o envio assíncrono e os tokens temporários seguem as orientações do [OWASP para recuperação de senha](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html). A escolha de 7 caracteres para novas senhas usa a recomendação para senha como fator único do [NIST SP 800-63B-4](https://pages.nist.gov/800-63-4/sp800-63b.html); o bloqueio local básico não representa uma lista completa de credenciais vazadas nem uma declaração de conformidade integral.

`AccountRecoveryIntegrationTests` cobre cadastro/confirmar, expiração de 15 minutos/24 horas, finalidade, uso único concorrente, resposta genérica, supressão silenciosa, limites HTTP, headers forjados, redefinição com revogação e mudança de e-mail. `AccountPolicyTests` cobre política, bloqueio da abertura pública e adaptação SMTP com remetente/destinatário/mensagem simulados. `MySqlDatabaseIT` verifica concorrência e persistência dos tokens/contadores em MySQL real. O roteiro de navegador cobre os formulários, fragmentos privados, feedback e regressões do portal.

### Resultados locais em 18/10/2026

- Suíte Maven completa com `-Pmysql-it verify`: **143 testes aprovados**, incluindo **13 em MySQL 8.0 real**, sem falhas, erros ou testes ignorados. Servidor/bancos temporários e descartáveis, sem tocar o banco de uso da aplicação.
- Após os ajustes finais de senha, troca de e-mail e configuração, **39 testes direcionados aprovados** e JAR novamente empacotado.
- **26 testes JavaScript** e **3 testes Python** de auditoria aprovados.
- OpenAPI: **26 caminhos e 37 operações**, com estrutura, referências e cobertura aprovadas.
- Edge headless: formulários de recuperação/confirmação, fragmento removido do endereço, ausência de consumo por simples abertura, cadastro e novo login, troca de e-mail com senha atual, regressões de conta/coletas/relatórios/administração, computador/celular e CSP aprovados.

O adaptador SMTP foi exercitado com remetente/destinatário/mensagem simulados. Isso **não valida conexão ao provedor nem chegada em caixa postal**. A verificação real permanece pendente conforme o roteiro acima. As alterações locais anteriores foram preservadas. Não houve publicação ou envio de e-mail real.

Configuração do remetente Gmail para caixas Gmail/Hotmail e ativação local: [etapa 35](35_ENVIO_DE_CONFIRMACAO_PELO_GMAIL.md). A entrega real depende da conta Google autorizada e ainda deve ser comprovada.

# Demonstração gratuita: Render + Aiven

Consulta às fontes oficiais: 21/10/2026. Este roteiro publica o frontend estático e a API no **mesmo Web Service Docker**, preservando chamadas relativas e a mesma origem. Não precisa de Static Site separado, CORS adicional ou domínio pago para o site.

## Estado do projeto e decisões

- Spring Boot 4.1.1, Java 25, Connector/J 9.7.0 e Flyway 12.4.0 gerenciados pelo BOM; MySQL com `ddl-auto=validate`, migrações V1–V16 e fila persistente de e-mail.
- `Dockerfile`: compilação em JDK 25, execução em JRE 25, usuário sem privilégios. O build empacota sem repetir testes; a verificação é separada. Tags Java 25 recebem atualizações, portanto não são um build imutável por digest.
- Perfil `prod,render`: `0.0.0.0`, `${PORT:10000}`, pool de quatro conexões e limites iniciais de JVM/threads para 512 MB. A adequação real da memória depende do teste hospedado.
- Banco novo no Aiven. **Não importar dados pessoais, usuários, senhas, tokens ou sessões do banco local. Não alterar o serviço MySQL local.** Os catálogos são criados pelas migrações; usuários e eventos locais não são copiados.
- Arquivos locais preexistentes não pertencem ao commit desta preparação. Não houve push ou criação de serviços por esta etapa.

## Limites consultados

| Serviço | Limites relevantes para esta demonstração |
| --- | --- |
| Render Web Service Free | 512 MB, 0,1 CPU; 750 horas/mês por workspace; dorme após 15 minutos sem tráfego e pode levar aproximadamente um minuto para voltar; disco efêmero; sem disco persistente, shell ou jobs avulsos; SMTP 25/465/587 bloqueado. |
| Render Hobby | 5 GB de tráfego de saída e 500 minutos de pipeline/mês. Acompanhar Billing; cartão cadastrado pode permitir cobrança de excedentes. Sem método de pagamento, excedentes podem suspender serviços/builds. |
| Aiven MySQL Free | Um nó, 1 CPU, 1 GB RAM, 1 GB disco, 76 conexões, backups; sem SLA, VPC, IP estático, fork ou integrações. Um serviço de cada tipo por organização. Pode ser desligado por inatividade; nuvem/região/configuração podem mudar. |
| Brevo Free | 300 envios/dia compartilhados pela conta. Conta transacional e remetente precisam estar habilitados. Aceite HTTP da API não prova entrega na caixa de entrada. |

Fontes: [Render Free](https://render.com/docs/free), [compute](https://render.com/docs/compute-plans), [franquias Hobby](https://render.com/articles/how-much-does-cloud-application-hosting-cost-for-small-businesses), [Aiven Free](https://aiven.io/docs/products/mysql/concepts/mysql-free-tier), [preços Aiven](https://aiven.io/pricing/mysql), [Brevo Free](https://help.brevo.com/hc/en-us/articles/208580669-FAQs-What-are-the-limits-of-the-Free-plan).

Não usar pings artificiais para evitar suspensão. A fila e a manutenção agendada param enquanto o Render dorme e retomam com a aplicação. O tráfego para banco/API também está sujeito às políticas de uso do Render. Não é hospedagem com garantia de disponibilidade para produção comercial.

## E-mail: requisitos antes da ativação

**Opção inicial implementada: Brevo por HTTPS na porta 443**, conservando SMTP para execução local. O adaptador usa `POST https://api.brevo.com/v3/smtp/email`, header `api-key`, timeout de conexão/leitura, validação TLS padrão do Java e não segue redirects. Respostas diferentes de 201 falham e são tratadas pela fila existente. O corpo de erro não é registrado. Confirmação, recuperação e aviso de alteração de senha mantêm a geração/validação de tokens existente. Há possibilidade de duplicação se o provedor aceitar e a conexão cair antes da resposta; a fila não promete entrega exatamente uma vez.

Antes de escolher o remetente:

1. Com domínio próprio e acesso DNS: autenticar no Brevo com os registros fornecidos (código Brevo, DKIM e DMARC) e cadastrar o remetente. O domínio do e-mail pode ser diferente de `onrender.com`. Não tentar autenticar `gmail.com` ou `onrender.com`, pois você não controla esses domínios.
2. Sem domínio: cadastrar e verificar um endereço seu, por exemplo Gmail. O Brevo documenta substituição do domínio de remetentes não autenticados por `@brevosend.com`, inclusive Gmail. É uma acomodação **temporária do provedor**, não garantia permanente; confirmar que a conta permite envio transacional e verificar como a mensagem aparece em caixas externas. Se a conta exigir domínio para habilitar envio, interromper essa etapa e reavaliar a opção gratuita.
3. Resend foi avaliado: 100 envios/dia no Free; o domínio de teste só envia para o próprio endereço da conta. Para visitantes do LinkedIn exige um domínio verificado. Por isso não é a opção inicial sem confirmação de domínio próprio.

Fontes: [Brevo remetentes/domínios](https://developers.brevo.com/docs/getting-started-with-senders-and-domains), [substituição e DNS](https://help.brevo.com/hc/en-us/articles/16045394674066-Troubleshooting-Domain-authentication), [API transacional](https://developers.brevo.com/docs/send-a-transactional-email), [Resend Free](https://resend.com/pricing), [restrição do domínio de teste](https://resend.com/docs/knowledge-base/403-error-resend-dev-domain).

## Etapa 1 — Aiven: criar um banco vazio

1. Entrar no [Console Aiven](https://console.aiven.io/), criar/selecionar um projeto e escolher **MySQL > Free ($0)**. Não selecionar trial ou plano pago. O plano Free pode definir a região automaticamente; anotar a região oferecida.
2. Preferir **MySQL 8.4**. A documentação atual encerrou a criação de 8.0 em 30/04/2026 e indica EOL em 31/10/2026. Conferir a versão realmente provisionada no Overview; não inferi-la pelo nome do plano.
3. Aguardar `Running`. Anotar em armazenamento privado host, porta, usuário, senha e database (`defaultdb`, salvo escolha diferente). Esses valores serão inseridos diretamente no Render, não no Git ou no chat.
4. Em Connection information/CA certificate, baixar a **CA do projeto**. Guardar fora do repositório. Não baixar/fornecer chave privada para esta conexão.
5. Quando o serviço Render existir, usar seus intervalos de saída em Networking para restringir o IP filter do Aiven. Se precisar conectar de seu computador para diagnóstico, adicionar apenas seu IP temporariamente. Não alterar a rede do seu MySQL local.

**Ponto de parada:** informar apenas `Running`, versão MySQL e região. Não compartilhar senha, URI completa ou chave. Validar esta etapa antes de avançar aos painéis seguintes.

## Compatibilidade e TLS

A análise das V1–V16 não encontrou tabela sem chave primária, FK para chave não única ou dependência de privilégios administrativos/SUPER. As migrações Java usam JDBC e tratam a alteração decimal de acordo com o fornecedor. V8/V11 já têm a correção de semântica de timestamps. Não se deve reescrever migrações publicadas para esta implantação nem executar `repair`, `clean` ou `baseline` para esconder falhas.

O CI passa a ter matriz MySQL 8.0/8.4. Isso **não equivale a um resultado de CI executado**: verificar a execução remota após o envio autorizado. A versão exata Aiven, seus privilégios e TLS precisam de confirmação no banco hospedado. Se o painel oferecer outra versão, validá-la antes de publicar.

O módulo `flyway-mysql` exigido pela [documentação Flyway](https://documentation.red-gate.com/fd/mysql-277579322.html) já está presente. A página consultada lista versões verificadas 5.7, 8.0 e 9.4, sem declarar 8.4 nessa lista; por isso a análise de SQL e a matriz preparada não são apresentadas como certificação em Aiven 8.4. O CI também passa a construir a imagem Docker, sem credenciais de implantação.

As pendências antigas de contagem de migrações não se reproduziram na inspeção: OpenAPI documenta V1–V16, testes de catálogo/persistência esperam 16, e o teste de pesos espera dez migrações de V7 a V16. Não foi necessário alterar esses contratos ou checksums.

No primeiro início, Flyway migra o banco vazio e Hibernate valida o esquema. O usuário configurado precisa das permissões DDL/DML exigidas pelas migrações; para a demonstração pode ser a conta fornecida pelo serviço. Separar usuário de migração/aplicação exige um fluxo adicional, pois Render Free não oferece pre-deploy command/shell. Não configurar credenciais Flyway separadas com uma URL sem TLS.

O entrypoint importa `/etc/secrets/aiven-ca.pem` em um truststore PKCS12 temporário. `changeit` protege **somente certificados públicos** no arquivo temporário; não é uma senha de banco ou segredo de produção. A URL é composta de host/porta/database e fixa `sslMode=VERIFY_IDENTITY`, CA explícita, `fallbackToSystemTrustStore=false` e `allowPublicKeyRetrieval=false`. Usuário/senha seguem separados. A CA do banco não substitui o truststore HTTPS do Java usado para Brevo.

Fontes: [versões Aiven](https://aiven.io/docs/products/mysql/reference/version-lifecycle), [chaves primárias](https://aiven.io/docs/products/mysql/howto/create-tables-without-primary-keys), [CA Aiven](https://aiven.io/docs/platform/concepts/tls-ssl-certificates), [Connector/J TLS](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-security.html).

## Etapa 2 — Brevo: habilitar o remetente

Depois da etapa 1, criar conta Free, completar as informações solicitadas e confirmar que o envio transacional está ativo. Em Settings > Senders, Domains, IPs, cadastrar `Elo do Mar` e o e-mail de envio, verificando-o pela mensagem recebida. Se possuir domínio, autenticar DNS conforme instruções acima. Em SMTP & API, gerar uma **API key**, não uma chave SMTP. Guardar diretamente para uso no Render. Não enviar mensagens a terceiros como teste sem autorização; usar caixas suas ou de participantes que concordaram.

## Etapa 3 — Render: preparar o serviço

O commit local precisa estar disponível no repositório remoto. **Fazer push somente depois de autorização explícita para esse envio.** Pode-se conectar repositório privado ao Render; não é necessário torná-lo público.

1. No Render, criar workspace Hobby, New > Web Service, conectar o repositório e escolher a branch/commit conferido.
2. Runtime **Docker**, Dockerfile `./Dockerfile`, contexto raiz do projeto (a pasta que contém `pom.xml`). Selecionar **Free**. Região próxima à oferecida pelo Aiven, quando disponível. Deixar Docker Command vazio para usar o entrypoint.
3. Health Check Path: `/actuator/health/readiness`. A resposta não expõe detalhes. Desativar auto-deploy durante a validação para controlar cada implantação.
4. Em Environment > Secret Files criar `aiven-ca.pem` com o conteúdo PEM completo da CA. Render disponibiliza `/etc/secrets/aiven-ca.pem`. Não colocá-la no Dockerfile, Git ou build args.
5. Inserir as variáveis abaixo. Usar o nome real atribuído ao serviço para a origem HTTPS. Gerar um JWT novo no painel/gerenciador de segredos, sem reutilizar o local.
6. Criar/deployar e acompanhar logs. Confirmar Java 25, perfis `prod,mysql,render`, migrações até V16 e aplicação iniciada. Se ocorrer erro de CA, hostname, permissões ou migration, corrigir a causa; não desativar validação TLS.

| Variável | Valor/fonte |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod,render` (o grupo prod inclui mysql) |
| `DB_HOST` | Host DNS Aiven, sem protocolo, porta, query ou credenciais |
| `DB_PORT` | Porta indicada pelo Aiven; não presumir 3306 |
| `DB_NAME` | `defaultdb` ou banco novo selecionado |
| `DB_USER`, `DB_PASSWORD` | Credenciais somente no painel |
| `JWT_SECRET` | Base64 de pelo menos 32 bytes aleatórios exclusivos (não uma frase ou UUID) |
| `BREVO_API_KEY` | Chave da API HTTPS Brevo |
| `ACCOUNT_MAIL_FROM` | Endereço cadastrado/verificado no Brevo |
| `ACCOUNT_PUBLIC_BASE_URL` | `https://NOME-REAL.onrender.com`, sem barra/caminho/query |
| `PUBLIC_ACCESS_ENABLED` | `false` durante testes; `true` após validação |
| `MAIL_DELIVERY_VALIDATED` | `false` inicialmente; `true` somente após entrega comprovada |
| `PORT` | Fornecida pelo Render; não precisa cadastrar |

`DB_TRUSTSTORE_URL` é gerada pelo entrypoint. Não definir `DB_URL`, `SPRING_DATASOURCE_URL`, `SPRING_FLYWAY_URL`, Gmail ou overrides TLS. O exemplo sem segredos está em [deploy/render.env.example](../deploy/render.env.example).

`PUBLIC_ACCESS_ENABLED=false` é uma declaração de prontidão, **não um bloqueio de rede**: o Web Service já tem URL acessível durante a homologação. Não divulgar o link antes dos testes. A aplicação confia nos headers do proxy Render; validar HTTPS/IP do cliente no ambiente hospedado e não expor diretamente o container fora desse proxy usando essa configuração.

Fontes: [Web Services/porta](https://render.com/docs/web-services), [secrets](https://render.com/docs/configure-environment-variables).

## Etapa 4 — validar o link antes do LinkedIn

1. Abrir `/` e `/actuator/health/readiness` por HTTPS; confirmar frontend, imagens e API sem erro de conteúdo misto. Registrar URL, commit e data sem tokens.
2. Cadastrar conta sua, comprovar que login não é liberado antes da confirmação, receber a mensagem real, conferir remetente/destino e link apontando para Render. Confirmar e entrar. Testar reenvio e rejeição do token reutilizado.
3. Pedir recuperação, receber a mensagem real, redefinir senha, conferir rejeição da senha antiga e das sessões antigas. Verificar spam e logs do provedor. Nunca registrar o token completo como evidência.
4. Verificar cadastro, inscrição e coleta conforme dados disponíveis. O banco novo não tem administrador/eventos de demonstração: planejar a promoção controlada de uma conta sua já confirmada pelo banco Aiven, se necessária, em etapa separada. Não habilitar o perfil test para obter usuários de exemplo.
5. Reiniciar/redeployar e confirmar persistência de conta, histórico e fila. Verificar reexecução Flyway sem novas migrações. Esperar suspensão natural e validar retomada; medir tempo e memória.
6. Em conexão de diagnóstico TLS validada, executar `SELECT VERSION();`, `SHOW SESSION STATUS LIKE 'Ssl_cipher';` e consultar versão/sucesso de `flyway_schema_history` (16 migrações, nenhuma falha). Validar também falha com CA incorreta e hostname incorreto em cliente isolado, sem modificar o serviço estável. Ainda pendente nesta preparação.
7. Após os fluxos reais, definir `MAIL_DELIVERY_VALIDATED=true` e `PUBLIC_ACCESS_ENABLED=true`, redeployar, repetir health/login e só então compartilhar no LinkedIn. Guardar evidências resumidas, sem senhas/e-mails pessoais/tokens. Conferir limites de disco, tráfego e envios no painel.

Rollback da imagem não desfaz esquema. Fazer backup antes de mudanças futuras; nunca tentar restaurar sobre seu banco local. Falhas reais de entrega não devem ser contornadas desabilitando confirmação ou usando modo memory.

## Verificações desta preparação

Executado em 21/10/2026 sobre a árvore local, incluindo as alterações preexistentes preservadas (elas não integram o commit desta tarefa):

| Verificação | Evidência/resultado |
| --- | --- |
| Suíte Java completa, perfil Maven `mysql-it` | 195 testes em 36 classes: 174 testes locais/H2 e 21 MySQL; zero falhas, erros ou ignorados nos XML Surefire. Todos registram Java 25.0.2. |
| MySQL isolado | Community 8.0.46, diretório temporário e porta dinâmica, iniciado com `--no-defaults`. Migrações, preservação/restauração e retomada verificadas pela suíte existente. Nenhuma conexão ao banco local de trabalho. |
| Configuração final e transporte | 13 testes reexecutados após os últimos ajustes: AccountPolicy, ProductionConfig, RenderConfig e BrevoMailTransport. Rejeição de configuração TLS insegura/local, confirmação obrigatória, porta do ambiente, conteúdo das mensagens, 401/429/500 e redirects. HTTP simulado, sem envio externo. |
| Empacotamento final | Maven `verify`: BUILD SUCCESS; JAR executável gerado com Java 25. |
| OpenAPI | 29 caminhos, 40 operações, referências/rotas e V1–V16 consistentes. Validação estrutural própria, não certificação completa do schema OpenAPI. |
| Python | 12 testes aprovados. |
| JavaScript | 29 testes aprovados. |
| Entrypoint | `bash -n deploy/render-entrypoint.sh` aprovado; sem execução de container. |
| Navegador | Roteiro completo aprovado no Edge headless com Java 25 e H2 descartável: confirmação/recuperação simuladas, sessões, perfil/senha, inscrições, coletas, administração, permissões, teclado/foco, contraste, desktop/mobile e CSP. |

O primeiro comando completo gerou os 195 relatórios e o JAR, mas o invólucro PowerShell encerrou com erro ao interpretar um aviso do Mockito escrito em stderr. Corrigido `tools/run_mysql_tests.ps1` para conferir o código de saída dos comandos nativos, mantendo falhas reais como erros e restaurando a preferência de erros. O script corrigido foi executado com os 13 testes direcionados, empacotamento, OpenAPI e Python, retornando código zero. Não foi necessário repetir os demais testes de domínio, que não tiveram mudanças após a execução completa.

Logs locais (ignorados pelo Git): `target/render-full-validation.log`, `target/render-final-package.log`, `target/render-js-tests.log`, `target/render-browser-final.log` e `target/surefire-reports/`. A primeira tentativa de navegador falhou por restrições do sandbox ao Edge; a execução final com a permissão necessária terminou com código zero e resultado PASS.

**Ainda não executado:** build/run Docker (Docker indisponível nesta máquina), CI remoto MySQL 8.4, conexão Aiven, testes negativos de handshake com CA/hostname inválidos, memória/cold start no Render, entrega Brevo real, confirmação/recuperação pela URL HTTPS hospedada e persistência após redeploy. Os mocks não comprovam aprovação de conta, cota disponível, autenticação do domínio ou entrega externa. A demonstração só estará validada após a etapa 4.

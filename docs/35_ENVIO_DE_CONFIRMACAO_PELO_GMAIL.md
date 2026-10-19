# Envio de confirmação pelo Gmail

Preparação em 19/10/2026. O perfil `mysql` exigia confirmação, mas o envio ficava `disabled` sem configuração SMTP. Assim, uma conta nova não conseguia entrar e nenhum e-mail chegava. O envio simulado do perfil `test` também não entrega mensagens em caixas reais.

Foi preparada uma conta **Gmail como remetente**, capaz de endereçar mensagens tanto a Gmail quanto a Hotmail/Outlook. Não é necessário configurar SMTP da Microsoft para receber no Hotmail. A chegada real ainda precisa ser validada com a conta autorizada; nenhum e-mail real foi enviado nesta entrega e nenhuma senha foi configurada pelo agente.

## Ativar na sua conta Google

1. Na conta Gmail que enviará as mensagens, ativar a [verificação em duas etapas](https://support.google.com/accounts/answer/185839?hl=pt-BR).
2. Criar uma [senha de app](https://myaccount.google.com/apppasswords) para BeachCleanup. Usar essa senha de 16 caracteres, sem os espaços de agrupamento, e não a senha normal da conta. Segundo a [documentação do Google](https://support.google.com/mail/answer/185833?hl=pt-BR), essa opção depende da verificação em duas etapas e pode não estar disponível em contas com restrições de organização ou Proteção Avançada.
3. Manter essa senha fora do chat, dos arquivos versionados e dos logs. A senha de login do voluntário é independente da senha de app do remetente Gmail.

## Ativar no Eclipse com o banco atual

Parar a aplicação. Em **Run > Run Configurations > execução do BeachCleanup**:

1. Em **Arguments > Program arguments**, usar `--spring.profiles.active=mysql,gmail-local`. Se o perfil estiver na aba Spring Boot/Profiles ou na variável `SPRING_PROFILES_ACTIVE`, atualizar essa configuração para o mesmo valor e evitar versões conflitantes.
2. Em **Environment**, acrescentar:

| Variável | Valor |
|---|---|
| `GMAIL_USERNAME` | Endereço completo da conta Gmail remetente |
| `GMAIL_APP_PASSWORD` | Senha de app gerada no Google, sem espaços |

3. Preservar `DB_URL`, `DB_USER`, `DB_PASSWORD` e o `JWT_SECRET` já usados. Não criar outro banco, não recriar a conta do voluntário e não marcar `email_verified` manualmente.
4. Aplicar e iniciar novamente. Não compartilhar/exportar a configuração de execução com os segredos. O arquivo [gmail.env.example](../deploy/gmail.env.example) contém somente o modelo, não credenciais reais.

O grupo `gmail-local` ativa o perfil `gmail`. Os parâmetros são `smtp.gmail.com`, porta `587`, autenticação, STARTTLS obrigatório e verificação de certificado. O remetente é `GMAIL_USERNAME`. A confirmação e os limites de tentativa continuam ativos. `spring.mail.test-connection=true` verifica conexão/autenticação SMTP na inicialização; credenciais inválidas ou falha de conexão interrompem a subida, em vez de aceitar novos cadastros sem o serviço acessível. Esse teste não comprova entrega na caixa postal.

O perfil local usa `http://localhost:8080` para o link e restringe a aplicação a `127.0.0.1`. Se usar outra porta, o endereço acompanha `server.port`. A exceção HTTP precisa ser explícita, só aceita host loopback com servidor também em loopback e é proibida com `prod` ou acesso público. O link localhost deve ser aberto **no mesmo computador que executa a aplicação**, mesmo que o e-mail seja lido no celular.

## Confirmar a conta já criada

1. Depois da aplicação iniciar com o SMTP configurado, abrir o portal.
2. Clicar em **Reenviar confirmação de e-mail** e informar o e-mail usado no cadastro, seja Gmail ou Hotmail.
3. Conferir a caixa de entrada, spam/lixo eletrônico e a pasta Outros. Abrir o link no computador da aplicação e clicar para confirmar o e-mail no portal.
4. Entrar com a senha do cadastro. Não é necessário criar outra conta.

As respostas de reenvio são genéricas para não revelar contas cadastradas. Há limite de três solicitações por endereço/finalidade por hora, além do limite por IP. Se o limite já foi atingido enquanto o envio estava desativado, aguardar a janela; não apagar os contadores nem burlar a confirmação. Falhas posteriores de envio geram aviso fixo no console, sem senha, token ou destinatário.

Validar primeiro uma caixa Gmail e uma Hotmail controladas pelo responsável. Registrar recebimento, confirmação e login; depois testar recuperação de senha. O aceite pelo SMTP não garante chegada ao destinatário. Não foram realizados esses envios reais, pois dependem da conta e da senha de app configuradas pelo responsável.

## Hospedagem

Usar perfis `prod,gmail`, configurar `ACCOUNT_PUBLIC_BASE_URL=https://SEU_DOMINIO` e manter as variáveis Gmail no gerenciador de segredos/processos. Não usar `gmail-local` em hospedagem. A abertura pública continua condicionada à validação real de entrega e aos demais controles da etapa de homologação. Nunca usar `localhost` em links destinados a usuários de outro computador.

## Verificações locais

Os testes verificam o carregamento dos perfis, as configurações de TLS/remetente, a recusa de credenciais ausentes, as restrições da exceção HTTP local e o fluxo de confirmação com e-mail simulado. Os testes de configuração não abrem conexão com o Google. Autenticação da conta real e entrega em Gmail/Hotmail permanecem pendentes até a configuração das duas variáveis acima.

Resultado: 15 testes de configuração/política/recuperação aprovados; as seis verificações de configuração foram repetidas após a guarda final de credenciais vazias e também passaram. OpenAPI validado com 28 caminhos e 39 operações. Nenhum teste abriu conexão ou enviou mensagem ao Gmail real.

# Fila persistente de e-mail e validação dos fluxos de conta

A V15 substitui o envio baseado somente no executor em memória por solicitações persistidas em `tb_account_mail_queue`. O esquema vigente passa a **V1–V15**. V1–V14 permanecem intactas; aplicar a nova migração pelo fluxo normal do Flyway, sem recriar bancos ou alterar históricos aplicados. O OpenAPI mantém 29 caminhos e 40 operações.

## Garantias de processamento

- Cadastro, alteração de e-mail, recuperação e aviso de senha alterada gravam a solicitação na mesma transação da operação. Uma reversão também desfaz a solicitação.
- O executor guarda somente avisos para consultar a fila. Quando ele está ocupado, a solicitação continua no banco e a consulta periódica a retoma. Não há descarte silencioso por lotação do executor.
- Cada tarefa recebe uma reserva temporária e um identificador exclusivo. A tomada usa `READ_COMMITTED` e atualização condicional atômica para evitar bloqueios de intervalo entre reservas concorrentes no MySQL. A confirmação exige o mesmo identificador; um processo antigo não consegue finalizar uma reserva retomada por outro processo.
- O envio SMTP acontece fora da transação de reserva, sem manter bloqueios de banco durante a conexão. Após interrupção do processo, a reserva vencida fica disponível novamente.
- Falhas recebem novas tentativas com espera progressiva. Ao esgotar o limite, a tarefa fica em `DEAD` para diagnóstico. A tarefa também termina se o processo morrer durante a última tentativa.
- Antes de gerar um link, a aplicação confere a conta original, endereço, geração de credenciais, confirmação e idade do pedido. Pedidos antigos não geram novos links após troca de senha/e-mail, exclusão ou expiração. Um endereço desconhecido não passa a receber um pedido anterior quando alguém cria uma conta com esse endereço.

O banco guarda destinatário e metadados necessários ao envio; não guarda senha, corpo da mensagem ou token de ação em texto. Tokens de confirmação/recuperação continuam armazenados somente por hash. O identificador de reserva não é um token de conta. Logs de falha contêm identificador da tarefa e número da tentativa, sem conteúdo da exceção SMTP, destinatário ou link.

O processamento admite repetição de mensagem: se o SMTP aceitar a mensagem e o processo terminar antes de registrar o sucesso, a retomada pode enviar outra. `SENT` indica aceite pelo transporte configurado, não recebimento na caixa postal. O modo `memory` continua exclusivo dos testes.

## Operação e configuração

A limpeza periódica de sessões, refresh tokens, tokens de conta e contadores está descrita na [manutenção dos dados de autenticação](41_MANUTENCAO_DOS_DADOS_DE_AUTENTICACAO.md), adicionada com V16. Os prazos e resultados de V15 registrados neste documento permanecem como histórico.

| Propriedade | Padrão | Efeito |
| --- | --- | --- |
| `account.mail.queue.max-attempts` | `6` | Limite de tentativas; aceita 1 a 20 |
| `account.mail.queue.initial-retry-seconds` | `30` | Primeira espera após falha |
| `account.mail.queue.max-retry-seconds` | `600` | Limite da espera exponencial |
| `account.mail.queue.lease-seconds` | `120` | Prazo da reserva; mínimo 30 segundos |
| `account.mail.queue.poll-delay-ms` | `1000` | Intervalo entre consultas ao executor |
| `account.mail.queue.poll-initial-delay-ms` | `1000` | Espera inicial da consulta |

Os padrões produzem esperas de 30, 60, 120, 240 e 480 segundos, sujeitas à validade do pedido. A reserva deve exceder a duração máxima de uma tentativa SMTP, considerando conexão, leitura e escrita. Cada execução processa até 20 tarefas; consultas posteriores continuam o restante.

Pedidos de recuperação aguardam no máximo 15 minutos; confirmação e aviso de senha, 24 horas. A validade do link emitido continua sendo 15 minutos ou 24 horas, respectivamente. Envio desativado mantém os pedidos até reativação ou vencimento. A manutenção horária cancela pendências vencidas e remove estados terminais após sete dias, inclusive com envio desativado. Reservas de processos interrompidos são retomadas quando o envio é reativado.

Consulta de diagnóstico sem expor destinatários:

```sql
SELECT status, COUNT(*) AS quantidade
FROM tb_account_mail_queue GROUP BY status;

SELECT id, status, attempts, next_attempt_at, locked_until, last_error
FROM tb_account_mail_queue
WHERE status IN ('READY', 'PROCESSING', 'DEAD') ORDER BY id;
```

`READY` aguarda processamento; `PROCESSING` está reservado; `SENT` foi aceito pelo transporte; `CANCELLED` deixou de ser aplicável; `DEAD` esgotou as tentativas. Datas são gravadas e lidas em UTC. `DELIVERY_FAILED` e `ATTEMPTS_EXHAUSTED` são categorias fixas, não respostas SMTP. Corrigir a configuração antes de solicitar outra mensagem pela API/portal; não redefinir estados nem ampliar a validade de links por SQL.

## Validação automatizada e entrega real

Os testes cobrem cadastro com confirmação obrigatória, consumo único, recuperação, revogação de sessões, finalidade incorreta, expiração exata em 15 minutos/24 horas e invalidação por troca de senha/e-mail. A fila acrescenta reversão de transação, executor lotado, falhas transitórias e persistentes, espera progressiva, concorrência, reserva vencida, processo interrompido, conta excluída/desconhecida e retenção. Um servidor SMTP local sem STARTTLS verifica que uma conexão SMTP real é recusada antes do envio do link e permanece disponível para nova tentativa. Esse servidor não entrega mensagens externas.

O teste MySQL usa servidor descartável, aplica V15 e reinicia a aplicação entre reserva, recuperação e nova tentativa. O transporte desse teste é simulado; nenhum teste automatizado comprova entrega externa.

**Entrega real pendente:** nesta sessão não estão disponíveis variáveis SMTP, arquivo local de credenciais nem endereço de teste autorizado. Foi solicitado ao responsável um endereço sob seu controle e a localização da configuração SMTP. Não habilitar `account.mail.delivery-validated` com base em testes simulados ou somente em aceite SMTP.

Quando esses dados estiverem disponíveis, executar em ambiente isolado com a configuração [Gmail](35_ENVIO_DE_CONFIRMACAO_PELO_GMAIL.md):

1. Cadastrar a conta de teste e registrar recebimento na caixa de entrada ou spam. Verificar que o login depende da confirmação.
2. Abrir o link, confirmar explicitamente, entrar e tentar reutilizar o link, que deve ser recusado.
3. Solicitar recuperação, conferir o recebimento, redefinir a senha e verificar a recusa da senha antiga, encerramento das sessões e aviso de alteração recebido.
4. Em banco e relógio exclusivos de teste, verificar um link de recuperação vencido em 15 minutos e um de confirmação vencido em 24 horas. O relógio não deve afetar a instalação do usuário; os testes automatizados usam relógio controlado. Não publicar links, senhas ou tokens nas evidências.

Resultado em 19/10/2026, após o ajuste de isolamento das reservas:

- Suíte Maven completa com Java **25.0.2**: **178 testes**, sendo 158 testes unitários/de integração local e 20 com MySQL 8 descartável; zero falhas, erros ou testes ignorados. As integrações locais usam H2. Todos os relatórios foram gerados nesta execução com Java 25, e o JAR contém a fila compilada com bytecode de versão 69.
- O cenário de reserva concorrente e retomada após reinícios passou no MySQL. A suíte também verificou migrações legadas, preservação de dados e restauração de backup incluindo a nova tabela.
- Cliente do portal: **29 testes** aprovados; ferramentas Python: **12 testes** aprovados; OpenAPI validado com **29 caminhos, 40 operações e V1–V15**.
- Fluxos de navegador de voluntário e administrador aprovados: formulários de confirmação/recuperação, sessão, perfil/senha, coletas e materiais, administração, permissões e verificações de acessibilidade. O navegador usou o JAR desta execução, Java 25 e banco H2 isolado. Esses fluxos não enviam e-mail externo.

A execução completa terminou às 23:22 (America/Sao_Paulo). A metodologia está na [validação completa anterior](39_VALIDACAO_COMPLETA_JAVA25_MYSQL_E_NAVEGADOR.md); as evidências locais desta execução estão em `target/validation-mail-full-java25.log` e nos relatórios Surefire. A pendência de entrega real descrita acima permanece.

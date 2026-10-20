# Privacidade antes de publicar o repositório

As contas de demonstração usam endereços reservados em `example.com`. A conta original do perfil `test` usa `admin-original@example.com`, senha de demonstração `demo-original7!`; o administrador adicional continua em `admin@example.com`. Esses dados pertencem ao banco em memória de teste, recriado ao reiniciar. Credenciais e dados de um banco persistente não são alterados por esta substituição.

Caminhos pessoais da documentação foram generalizados com `EXEMPLO`. Substitua esse marcador pelo seu diretório local ao executar comandos históricos. Configurações reais, arquivos de chaves, bancos locais e arquivos ZIP ficam ignorados pelo Git; modelos `.env.example` podem ser versionados. O `.gitignore` não retira arquivos que já foram enviados.

## Autoria dos próximos commits

Configure o endereço `noreply` fornecido pelo seu GitHub somente neste repositório:

```text
git config --local user.email "SEU_ID+SEU_USUARIO@users.noreply.github.com"
```

O endereço pode ser consultado em GitHub → Settings → Emails. Ative também a privacidade de e-mail e o bloqueio de pushes que exponham o endereço nas configurações da conta, se desejar essa proteção nas operações feitas fora deste checkout. A configuração local não modifica a configuração global nem outros projetos.

## Informações já enviadas

Um commit novo não apaga dados de revisões antigas. Para remover endereços e caminhos do histórico, preparar e conferir uma cópia isolada, manter uma cópia privada de recuperação e autorizar expressamente a substituição do histórico remoto. Essa operação muda os identificadores dos commits; outros clones devem adotar a versão sanitizada para não reintroduzir os dados. Usar uma atualização protegida pelo SHA remoto esperado, sem sobrescrever commits novos de terceiros.

Reescrever o histórico não remove cópias em outros clones, capturas de tela, logs, artefatos, comentários ou referências retidas pelo GitHub. Conferir e remover os conteúdos antigos do Actions, releases e anexos antes de alterar a visibilidade. Quando necessário, seguir o procedimento de remoção de dados sensíveis do GitHub. Não publicar backups, relatórios privados ou arquivos de substituição usados na limpeza.

O projeto não contém credenciais de produção conhecidas na revisão realizada. Valores reais de SMTP, MySQL e JWT permanecem em variáveis de ambiente. O alerta do scanner na lista de senhas proibidas em `PasswordPolicy` foi conferido como falso positivo.

Referências:

- https://docs.github.com/en/account-and-profile/how-tos/email-preferences/setting-your-commit-email-address
- https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository

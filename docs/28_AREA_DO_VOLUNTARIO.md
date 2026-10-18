# Área do voluntário completa

O portal passa a oferecer **Meu perfil**, troca de senha e edição/exclusão das próprias coletas, aproveitando os endpoints e as verificações de autorização existentes. Os fluxos funcionam pelo navegador, sem Postman.

## Uso

1. Entre na conta e abra **Meu perfil**. Altere nome/e-mail e escolha **Salvar perfil**. O portal envia a `version` recebida em `GET /users/{id}` para `PUT /users/{id}`. O nome no cabeçalho acompanha a resposta da API.
2. Para trocar a senha, informe a senha atual, a nova senha e sua confirmação. A confirmação deve coincidir e cada senha deve respeitar o limite de 72 bytes UTF-8. O formulário usa `PUT /auth/password`. Após sucesso, o portal limpa a sessão e pede novo login; a API invalida os tokens anteriores.
3. Em **Minhas coletas**, escolha **Editar coleta**, confira praia, material, peso e data e salve. O portal usa os IDs dos catálogos e envia a `version` do registro aberto em `PUT /trashCollection/{id}`. Coletas vinculadas mantêm as regras de praia/data do evento e oferecem a opção de remover o vínculo. Referências históricas ausentes na lista do catálogo continuam disponíveis na edição.
4. Para remover uma coleta, escolha **Excluir coleta** e confirme a exclusão. Cancelar a confirmação não envia a operação. Após salvar/excluir, o portal consulta novamente o histórico e o total pessoal.

## Validação, conflitos e privacidade

Mensagens de validação da API aparecem no formulário da coleta ou no aviso da página. Em HTTP 409, os campos digitados permanecem visíveis e nenhuma escrita é repetida automaticamente. Na coleta, feche o formulário e reabra **Editar coleta** para comparar os dados atuais antes de fazer nova alteração. No perfil, reabra **Meu perfil** para consultar a versão atual. Conflitos de perfil também podem indicar e-mail já utilizado.

O navegador envia JWT nas operações pessoais e consulta as coletas do usuário autenticado. O servidor continua responsável por verificar a propriedade: alterar controles ou enviar uma requisição manual não permite consultar, editar, excluir ou transferir coletas de outra pessoa, nem editar o perfil de outra conta. Sair da conta limpa os campos pessoais e fecha a edição. Senhas não são persistidas no armazenamento do navegador e o formulário de troca é limpo após a tentativa.

Os contratos de perfil, senha e coletas permanecem compatíveis. [openapi.json](openapi.json) documenta o envio de versão e a reação do portal ao conflito; [27_RELATORIOS_DE_IMPACTO.md](27_RELATORIOS_DE_IMPACTO.md) descreve como alterações nas coletas afetam os relatórios.

## Verificação

Os testes de integração existentes de senha, formulários antigos e permissões continuam cobrindo as regras da API. `ImpactReportIntegrationTests` acrescenta a conciliação de edições e exclusões com o total pessoal e os relatórios, além de tentativas de alteração de dados de outra conta.

`tools/portal-api.test.cjs` verifica JWT, URLs pessoais e envio de versões. `tools/verify_portal_browser.cjs` cobre edição de coleta e perfil, conflito com atualização externa e preservação do rascunho, cancelamento/confirmação de exclusão, atualização dos totais, confirmação de senha, troca de senha e novo login com a senha alterada, além da regressão administrativa e de celular.

Validação em 18/10/2026: regressão existente com 106 testes Java e cinco testes novos de integração aprovados; 18 testes JavaScript e OpenAPI aprovados. O fluxo de navegador passou em Edge headless com tela de computador e celular. Um teste adicional em MySQL real confirmou a conciliação dos relatórios após manutenção das coletas. Os registros de execução e capturas ficam em `target`, sem versionamento.

# Etapa 14 — Concorrência e autorização das coletas

Data: 14/10/2026.

## Problema reproduzido

A autorização de atualização e exclusão era feita somente no controller, antes da transação de escrita. Uma coleta carregada nessa verificação podia ficar desatualizada durante uma transferência realizada pelo administrador. Como `TrashCollection` não tinha controle de versão, a gravação posterior podia sobrescrever a transferência ou excluir a coleta usando a autorização do proprietário anterior.

Os quatro testes novos foram executados antes da correção e reproduziram as lacunas: duas edições da mesma versão recebiam 200; atualização/exclusão com proprietário desatualizado recebiam 200/204 após a transferência; uma chamada direta ao service permitia ao antigo proprietário atualizar o registro.

## Implementação

- `TrashCollection` ganhou `rowVersion` com `@Version`, usado pelo Hibernate para comparar a versão lida com a versão armazenada ao atualizar ou excluir. A versão é interna, protegida com `@JsonIgnore` e não faz parte dos DTOs.
- `TrashCollectionService.update` e `delete` conferem a autorização sobre a entidade gerenciada dentro da mesma transação que realiza a escrita. As verificações existentes do controller continuam como primeira barreira.
- O vínculo do proprietário recebido no PUT é tratado no service, dentro da transação. Voluntários continuam impedidos de transferir a coleta, e `null` continua vinculando a coleta ao próprio usuário. Administradores podem transferir ou remover o proprietário.
- A atualização usa `saveAndFlush`; a exclusão mantém `flush`. Uma versão desatualizada falha antes da resposta de sucesso e usa o tratamento já existente de `OptimisticLockingFailureException`: HTTP 409 com `error` igual a `Concurrent update`. A transação é revertida.
- O contrato [OpenAPI](openapi.json) documenta o conflito concorrente no PUT e no DELETE, sem mudar rotas ou campos JSON.

Se a transação carregar o proprietário atual após a transferência, o antigo proprietário recebe 403. Se a entidade já estiver carregada com a versão anterior, a tentativa de escrita recebe 409. Em ambos os casos, a transferência permanece preservada.

## Limite do controle de versão

O controle compara as versões lidas pelas operações no servidor. Duas escritas que usam a mesma versão não podem ambas concluir; operações que leem versões sucessivas podem concluir normalmente. Isso não detecta um formulário antigo no cliente quando a requisição posterior já lê a versão nova no servidor. Esta etapa não introduz ETag, `If-Match` ou versão obrigatória no JSON.

Ao receber 409, consulte a coleta novamente e confira peso, vínculos e permissão antes de decidir repetir a alteração. Evite repetir automaticamente uma transferência com dados antigos.

## Migração e dados existentes

A nova migração `V3__add_collection_row_version.sql` adiciona `row_version BIGINT NOT NULL DEFAULT 0` em `tb_trash_collection`. Registros existentes recebem versão zero; as próximas alterações feitas pelo Hibernate incrementam a versão. V1 e V2 permanecem intactas.

O perfil MySQL mantém `ddl-auto=validate`. Na próxima inicialização com esse perfil, o Flyway aplicará V3 ao banco configurado. O teste de migração prepara um banco com V1/V2 e dados antes de iniciar a aplicação atual, verificando preservação do ID da coleta, peso, material, data, proprietário, evento, inscrição, hash de senha, versão de credenciais e total calculado.

O MySQL local contém dados, conforme o [registro da etapa 12](12_MYSQL_LOCAL_VALIDACAO_MANUAL.md). Nenhum comando foi executado contra esse servidor nesta etapa. Os testes usam H2 em memória e H2 em arquivo no modo MySQL; não substituem uma validação contra MySQL real.

## Testes

`CollectionConcurrencyIntegrationTests` cobre:

1. Dois PUTs com a mesma versão previamente carregada: exatamente um 200 e um 409, com o peso vencedor e o total preservados.
2. Transferência por administrador enquanto o antigo proprietário mantém uma entidade carregada: atualização posterior retorna 409, sem recuperar a propriedade ou mudar o peso.
3. O mesmo cenário com DELETE: retorna 409 e preserva a coleta transferida.
4. Chamadas diretas ao service pelo antigo proprietário: atualização e exclusão são negadas; o novo proprietário consegue atualizar e excluir normalmente.

As execuções concorrentes usam transações independentes e `CountDownLatch` com limites de tempo. A entidade carregada antes da chamada HTTP representa a consulta de autorização/OSIV, evitando depender da velocidade das threads para reproduzir a versão desatualizada. Os testes também verificam totais derivados, ausência da versão interna no JSON e acesso negado ao antigo proprietário em uma nova requisição.

`PersistentDatabaseIntegrationTests` cobre a aplicação de V3 sobre dados existentes e mantém o cenário de persistência após reinício, agora com três migrações aplicadas.

## Verificação

Comandos executados da raiz do projeto:

```text
mvn -Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository -o -B test
python tools/validate_openapi.py
```

Foi usado o Maven instalado com o Apache NetBeans. Resultado final: **BUILD SUCCESS — 63 testes, zero falhas, zero erros e zero testes ignorados**, incluindo os quatro cenários de concorrência e a migração sobre dados existentes. A verificação OpenAPI aprovou 13 caminhos e 23 operações. A revisão das alterações também passou em `git diff --check`.

## Git

O commit desta etapa inclui somente a implementação, V3, testes, contrato OpenAPI e este documento. Alterações locais preexistentes são preservadas e não fazem parte do commit. Push não autorizado nesta etapa.

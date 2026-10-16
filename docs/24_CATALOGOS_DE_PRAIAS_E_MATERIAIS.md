# Catálogos de praias e materiais

Data: 16/10/2026.

## Resultado

Praias e materiais agora possuem IDs estáveis, nomes principais e nomes alternativos. Eventos referenciam uma praia; coletas referenciam uma praia e um material. Os formulários e os filtros do portal utilizam seletores desses catálogos.

O agrupamento dos futuros relatórios deve usar `beachId` e `materialId`, nunca os rótulos. O nome pode mudar sem mudar a identidade. Não foram implementados relatórios nesta entrega.

Caixa, acentos e espaços consecutivos não criam novos nomes: `Plástico`, `PLASTICO` e ` plástico ` identificam a mesma entrada. Pontuação e palavras continuam significativas. `Morro` e `Praia do Morro` só identificam a mesma praia se um administrador cadastrar essa associação ou unificar os dois cadastros. Não há inferência por semelhança geográfica, nem agrupamento automático de materiais de categorias diferentes.

Praias homônimas devem ter nomes qualificados, por exemplo `Praia do Morro (Cidade/UF)`. Um nome alternativo só pode pertencer a um item dentro de cada catálogo; apelidos ambíguos devem ser qualificados também. Os catálogos de praias e materiais são independentes.

## Uso no portal

- Voluntários escolhem praia e material no formulário de coleta. Ao abrir a partir de um evento, a praia vinculada já vem selecionada.
- Administradores escolhem a praia ao cadastrar/editar eventos e a praia/material ao cadastrar/editar coletas.
- As abas **Praias** e **Materiais** permitem cadastrar um item, renomear seu nome principal e acrescentar nomes reconhecidos, separados por vírgula.
- **Unificar com outro item** mantém o nome/ID do destino e move os nomes alternativos e os vínculos da origem para ele. A confirmação solicita conferir se representam o mesmo local ou material.
- Renomear preserva os nomes anteriores como aliases. Os nomes alternativos são acumulativos nesta versão; apagar texto do formulário não remove aliases já reconhecidos.
- Conflitos mantêm o formulário aberto. Recarregue o registro e revise antes de tentar novamente. Usuários sem perfil administrativo não podem alterar os catálogos.

Cadastros novos não criam praias ou materiais implicitamente. Se o item não estiver disponível, um administrador precisa cadastrá-lo antes do registro da coleta/evento.

## Contrato da API

O contrato completo está em [openapi.json](openapi.json).

| Rota | Acesso e comportamento |
| --- | --- |
| `GET /catalog/beaches` | Público; lista praias ativas com `id`, `name`, `version` e `aliases` |
| `GET /catalog/materials` | Público; lista materiais ativos no mesmo formato |
| `POST /catalog/{kind}` | Administrador; cria nome principal e aliases opcionais; retorna 201 |
| `PUT /catalog/{kind}/{id}` | Administrador; recebe `name`, `aliases` opcionais e a `version` lida |
| `POST /catalog/{kind}/{id}/merge` | Administrador; recebe `targetId`, `version` da origem e `targetVersion` do destino |

`kind` aceita `beaches` ou `materials`. As listas são ordenadas por nome e ID. Os aliases retornados estão normalizados, sem acentos, em minúsculas e com espaços simples. Não há exclusão física pela API.

Exemplo de cadastro:

```json
{
  "name": "Praia do Morro",
  "aliases": ["Morro"]
}
```

Exemplo de coleta usando os IDs obtidos nos catálogos:

```json
{
  "beachId": 10,
  "materialId": 20,
  "weightKg": 2.5,
  "registrationDate": "2026-10-16"
}
```

O vínculo com evento e as permissões sobre o proprietário mantêm suas regras anteriores. Na edição, acrescente a `version` da coleta. Eventos aceitam `beachId` junto de título, data/hora e status; `location` na resposta apresenta o nome principal da praia.

As respostas continuam incluindo `beach`, `materialType` e `location`, agora com o nome principal do catálogo vinculado. Também incluem `beachId` e, em coletas, `materialId`. Isso vale para listas, buscas, registros individuais e eventos aninhados.

Clientes anteriores ainda podem enviar nomes em `beach`, `materialType` e `location`, desde que estejam cadastrados como nomes principais ou alternativos. Nomes desconhecidos retornam 400; não são criados automaticamente. Se nome e ID forem enviados juntos, devem identificar o mesmo item. Um ID de outro catálogo retorna 400; ID inexistente retorna 404; ID já unificado retorna 409 e exige recarregar a lista.

Buscas de eventos aceitam `beachId`; buscas de coletas aceitam `beachId` e `materialId`. Esses filtros são exatos e podem ser combinados com período, proprietário, evento e paginação. Os filtros textuais anteriores continuam aceitando partes de nomes e passam a reconhecer aliases, caixa e acentos; `%` e `_` continuam sendo tratados literalmente. As restrições de visibilidade de coletas permanecem aplicadas.

## Unificação e concorrência

A unificação é uma correção explícita de classificação. Ela preserva IDs dos eventos/coletas, pesos, datas, proprietários, inscrições e vínculos entre coleta e evento. Os totais calculados a partir dos pesos não mudam. Os rótulos e as referências passam a usar o destino. O cadastro da origem é mantido internamente como unificado, sem aparecer nas opções ativas; seus aliases passam ao destino.

Renomear ou unificar incrementa as versões dos registros afetados. Assim, um formulário aberto antes da correção recebe 409 e não pode desfazê-la. As versões da origem e do destino também são verificadas para impedir alterações de catálogo a partir de telas antigas.

As escritas resolvem e bloqueiam os itens de catálogo dentro da transação. Uma gravação que concorre com uma unificação termina com o vínculo correto ou é rejeitada para revisão; não pode reintroduzir silenciosamente uma referência desativada. A restrição de unicidade dos aliases também rejeita criações equivalentes simultâneas. A aplicação faz INSERT explícito de novos aliases, sem sobrescrever o dono de uma chave já existente.

## Migração e registros anteriores

- `V5__create_reference_catalogs.sql` cria os cadastros e aliases e acrescenta as referências e os índices em eventos/coletas.
- `V6__backfill_reference_catalogs` é uma migração Java do Flyway que associa os textos existentes às identidades normalizadas. Ela não depende de um serviço Spring nem cria dados demonstrativos no ambiente persistente.
- Textos originais, pesos, datas, versões, contas e vínculos são preservados na migração. Variações inequívocas de caixa/acentos/espaços compartilham o ID. A primeira grafia encontrada fornece o nome inicial; o administrador pode revisá-lo depois.
- Registros legados com nomes nulos ou vazios permanecem intactos e sem referência nesses campos. Precisam de revisão administrativa; futuros relatórios devem tratar explicitamente essa ausência, sem inferir uma praia ou material.
- Nomes semanticamente equivalentes, como `Morro`/`Praia do Morro`, podem aparecer inicialmente como duas entradas. Use a unificação após conferir os registros. O seed demonstrativo do perfil `test` já apresenta `Morro` como alias de `Praia do Morro`.

O perfil persistente aplica as migrações antes da validação do esquema. Os testes existentes de persistência/MySQL foram atualizados para seis migrações. A atualização do contrato é aditiva nos IDs de resposta, mas torna incompatível a criação de nomes livres desconhecidos; clientes externos precisam consultar/cadastrar os itens antes das escritas.

## Verificações

- Maven offline, `package`: **88 testes Java aprovados, zero falhas, zero erros e zero testes ignorados**.
- Os novos testes cobrem nomes normalizados, colisões de aliases, autorização, IDs/tipos incorretos, nomes desconhecidos, filtros por ID/alias, renomeação/unificação, totais, preservação de vínculos, versões de formulários e cadastros equivalentes concorrentes.
- O teste de migração parte de um banco H2 persistente no esquema V4, com variações de grafia, praias semelhantes e registros incompletos. Confere pesos, datas, credenciais, versões e inscrições após a migração.
- `node --test tools/portal-api.test.cjs`: **15 testes aprovados**.
- JavaScript verificado com `node --check`; OpenAPI aprovado com 16 caminhos e 27 operações.
- `node tools/verify_portal_browser.cjs`: aprovado no Edge headless com o JAR final e banco H2 descartável. Verificados seletores, cadastro de praia/alias, edição de aliases de materiais e unificação de praia com coleta vinculada, além das regressões de formulários antigos, permissões, sessões, totais, CSP e desktop/celular. Celular foi verificado por emulação.
- O teste concorrente adicional carrega uma entrada no contexto de persistência, conclui sua unificação em outra transação e tenta criar uma coleta com o ID antigo. A aplicação atualiza a leitura após adquirir o bloqueio e rejeita o ID desativado com 409.

MySQL real e CI remoto não foram executados nesta tarefa. A validação das migrações usou H2 em modo MySQL. As alterações locais anteriores, inclusive o planejamento, permanecem preservadas e fora do commit desta entrega.

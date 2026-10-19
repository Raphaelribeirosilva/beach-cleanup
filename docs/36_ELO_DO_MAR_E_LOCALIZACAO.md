# Elo do mar: localização dos eventos

O portal usa **Elo do mar** no título, cabeçalho e rodapé. A busca pública e os filtros administrativos de eventos permitem combinar Estado, cidade, praia e status. É possível buscar somente pelo Estado ou pela cidade, sem escolher uma praia. Os filtros são aplicados no servidor antes da paginação.

## Preparar as praias

Em **Administração → Praias**, cadastrar ou editar cada praia e selecionar Estado e cidade. Para praias com nomes iguais, incluir a cidade no nome principal, pois os nomes e apelidos do catálogo continuam únicos. A cidade deve pertencer ao Estado selecionado; o servidor também verifica essa relação.

As praias listadas são as cadastradas pelo administrador, não uma lista nacional de todas as praias. Uma cidade sem praias cadastradas mostra uma mensagem explicativa. O administrador deve cadastrar a praia antes de criar seu primeiro evento nela.

## Criar e procurar eventos

Em **Administração → Eventos → Criar evento**, escolher Estado, cidade e praia, além de título, data e status. Ao editar um evento com praia identificada, os seletores iniciam com a localização correspondente. Trocar o Estado limpa cidade e praia; trocar a cidade limpa a praia.

Em **Explorar eventos**, selecionar a localização desejada e clicar **Buscar**. Deixar a praia em **Todas as praias** permite encontrar os eventos da cidade inteira. Os cartões exibem cidade/UF quando esses dados estiverem cadastrados. A administração oferece os mesmos filtros.

## Dados e compatibilidade

A migração V12 acrescenta `state` e `city`, opcionais, ao catálogo e cria um índice de localização. Não deduz a cidade pelo nome da praia nem modifica os dados antigos. Eventos existentes permanecem visíveis na busca sem filtros de localização; passam a aparecer por Estado/cidade quando o administrador completa o cadastro da praia. Formulários antigos de edição continuam utilizáveis; novas criações pelo portal exigem localização.

Os contratos JSON de praias e eventos expõem `state` e `city`. A rota `/cleanupEvent/search` aceita os dois novos filtros; `city` exige `state`. Clientes antigos podem omitir localização ao gravar praias, preservando o valor já cadastrado. Unificar praias adota a localização do cadastro de destino, assim como já ocorre com seu nome.

A lista local inclui 27 UFs e 5.571 municípios obtidos em 19/10/2026 pela [API oficial de localidades do IBGE](https://servicodados.ibge.gov.br/api/docs/localidades), rotas `estados?orderBy=nome` e `municipios?orderBy=nome`. O site e o servidor usam o mesmo arquivo `static/locations.json`; não consultam o IBGE durante o uso. Para atualizar a base, executar `python tools/update_locations.py` e revisar a alteração.

Para testar no PC, reiniciar a aplicação no Eclipse com os perfis já configurados e recarregar o navegador. O perfil MySQL aplica V12 pelo Flyway durante a inicialização. Completar a localização das praias existentes pela administração.

## Verificações

- Maven `package` offline: 146 testes aprovados, sem falhas, erros ou testes ignorados; JAR gerado.
- MySQL 8.0 descartável: teste de persistência da localização e busca por cidade após reinício aprovado. Nenhum acesso ao banco habitual da aplicação.
- Cliente JavaScript: 29 testes aprovados; OpenAPI com 28 caminhos e 39 operações validado.
- Navegador Edge: fluxo completo aprovado, incluindo marca, seleção encadeada, busca por cidade sem praia, limpeza dos campos dependentes, criação de evento e cadastro de praia com localização. Regressões de autenticação, acessibilidade, permissões e larguras de 320, 768 e 1280 pixels também passaram.

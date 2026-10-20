# Composição, fotografias e navegação por localidades

Revisão realizada em 20/10/2026, dando continuidade à [revisão visual anterior](20_REVISAO_VISUAL_ELO_DO_MAR.md).

## Composição

A abertura mantém Camburi e a ação de encontrar mutirões. A seção seguinte apresenta localidades com fotos em proporções diferentes, legendas abertas e alinhamentos alternados. A orientação para participar combina uma fotografia à esquerda com etapas à direita, sobre verde suave. A consulta de impacto encerra a página sobre fundo areia. No celular, a ordem de leitura permanece linear, sem carrossel ou rolagem horizontal.

Botões têm transições de cor de 200 ms; fotografias dos links se aproximam em 3,5% no hover, dentro do próprio recorte. A seta e a borda dão feedback sem deslocar o conteúdo. Links têm texto de ação permanente, foco visível e funcionamento por teclado, sem depender do hover.

Algumas seções recebem uma entrada única de 380 ms, com deslocamento de 10 px e opacidade de 0,8 a 1. O conteúdo nunca começa oculto nem aguarda JavaScript. A animação é um aprimoramento via `IntersectionObserver`, sem atrasos em sequência. `prefers-reduced-motion` desativa animações, transições, aproximação das fotos e rolagem suave, inclusive se a preferência mudar com a página aberta.

## As seis fotografias

Todos os originais foram reabertos e examinados. O nome do arquivo não foi tratado como prova de localização. Nenhuma imagem externa foi incorporada ao site; referências externas serviram apenas à identificação.

| Original | Uso e identificação | Evidência e limites |
| --- | --- | --- |
| `praia-de-camburi.jpg` | Abertura: Praia de Camburi, Vitória / ES | Nome original e comparação da orla, deque e paisagismo com a [descrição municipal](https://www.vitoria.es.gov.br/semmam/praia-de-camburi). |
| `florianopolis.jpg` | Localidades: Prainha da Barra da Lagoa, Florianópolis / SC | Identificação visual por rochas, cruz e silhueta dos morros, comparadas às fotografias de [Monkey’s Tale](https://monkeystale.ca/2023/04/21/hiking-on-florianopolis/) e à localização [Prainha da Barra](https://beachsearcher.com/pt/beach/76201155/prainha-da-barra). A foto de referência não é atribuída como fonte do arquivo fornecido. |
| `guarapari.jpg` | Localidades: Praia do Morro, Guarapari / ES | Encontrada a mesma cena, incluindo veículos, árvores e edifícios, na [publicação sobre Praia do Morro](https://hipersessao.blogspot.com/2010/10/praia-do-morro-de-guarapari.html), imagem `pmg34.jpg`. Retrata uma configuração anterior da orla; não é apresentada como registro atual de um evento. |
| `riodejaneiro.jpg` | Localidades: Praia de Copacabana, Rio de Janeiro / RJ | Identificação visual da orla de Copacabana, Avenida Atlântica, calçadão e extremidade junto ao Forte. Não foi identificada a publicação de origem ou autoria. |
| `salvador.jpg` | Localidades: Praia do Forte, Mata de São João / BA | Mesma cena de piscinas naturais e construções da imagem `praia-do-forte-capa-3.jpg`, da [publicação do Melhores Destinos](https://www.melhoresdestinos.com.br/praia-do-forte-bahia.html). A publicação confirma o município. O nome do arquivo foi preservado, mas a interface **não atribui a foto a Salvador**. |
| `saopaulo.jpg` | Ilustra a orientação de participação; legenda “Foto do acervo · localização em confirmação” | Município e praia ainda sem identificação segura. Referências de praias do Cedro em Ubatuba não permitiram uma correspondência conclusiva. A imagem não recebe localização nem link para busca geográfica. |

Não foram inventados créditos ou licenças. As fontes de comparação acima não equivalem a autorização de uso ou autoria das imagens fornecidas. A matéria do Melhores Destinos tem autoria de Monique Renne e diferentes créditos por foto; não se transferiu automaticamente o crédito de outra legenda para a imagem de capa. Os arquivos fornecidos não contêm atribuição nos campos consultados na revisão anterior. As referências e a incerteza ficam registradas aqui para futuras atualizações.

## Busca de mutirões

Os quatro cartões de localidades e o link junto a Camburi abrem **mutirões na cidade**, como informado no próprio link. Estado e cidade são enviados aos filtros existentes da API; a foto não promete um evento na praia mostrada. A pessoa pode refinar a praia e o status no formulário.

Os links usam fragmentos compartilháveis, por exemplo `#/mutiroes?state=BA&city=Mata+de+S%C3%A3o+Jo%C3%A3o`. A navegação, o botão Voltar e o recarregamento preservam a seleção recebida. A busca pública permite selecionar também cidades sem praias cadastradas: ausência no catálogo não pode apagar o filtro e exibir eventos de outros locais. Os formulários administrativos continuam limitados pelo comportamento anterior; a opção de todas as localidades se aplica somente à busca pública.

Sem eventos, aparece “Nenhum evento encontrado.” e a orientação para escolher outro local ou status. Não há eventos, contadores, parceiros ou resultados fictícios adicionados à interface. Os cartões de eventos continuam sem fotos, evitando correspondências por cidade que representariam praias diferentes.

## Arquivos e carregamento

`tools/optimize_portal_photos.ps1` agora gera versões das seis fotografias em `images/web`. Não sobrescreve os originais nem amplia arquivos pequenos. Mantém proporções e campos de crédito disponíveis. A abertura usa prioridade alta; as demais imagens usam carregamento adiado, decodificação assíncrona, dimensões explícitas e `srcset`/`sizes`. Os recortes são feitos por CSS, sem distorção.

| Fotografia | Larguras geradas | Bytes por versão |
| --- | --- | --- |
| Camburi | 480 / 960 / 1440 | 36.057 / 132.274 / 276.955 |
| Florianópolis | 480 / 960 | 37.789 / 145.969 |
| Guarapari | 480 / 662 | 29.379 / 47.626 |
| Rio de Janeiro | 480 / 960 | 47.100 / 164.955 |
| Praia do Forte | 480 / 960 | 42.661 / 138.400 |
| Localização em confirmação | 480 / 683 | 49.817 / 92.727 |

As versões de largura nativa das duas fotos menores são recomprimidas; a economia principal nesses casos vem da versão de 480 px selecionada pelo navegador. Os originais permanecem disponíveis localmente, sem ampliação ou alteração.

## Verificações realizadas

- `node --check` em `portal.js` e `portal-locations.js`: sem erros de sintaxe.
- `node --test tools/portal-api.test.cjs`: **29 testes aprovados**.
- Maven offline `-DskipTests package`: **BUILD SUCCESS**. Esse comando empacota; não executa testes Java.
- `node tools/verify_portal_browser.cjs`: **aprovado**, com Edge headless e API Spring Boot/H2 descartável.
- Navegador: computador 1280 × 960; início e localidades em celular de 320 × 844; demais fluxos também em 390 × 844. Sem transbordamento horizontal no início.
- As seis imagens carregaram. Capturas completas foram feitas após percorrer a página e carregar as imagens adiadas; examinadas visualmente em computador e celular.
- Os cinco links geográficos foram acionados por teclado; estado/cidade permaneceram selecionados. Guarapari apresentou o evento da base de teste; as demais cidades apresentaram estado vazio, sem resultados de outras cidades. Link de Mata de São João continuou filtrado após recarregar; Voltar retornou ao início.
- Movimento reduzido: estilos computados sem animação e conteúdo opaco. JavaScript desativado: todas as seções do início continuaram visíveis.
- Auditoria automatizada de contraste, nomes de controles e foco passou, incluindo as buscas por localidades. Não substitui uma avaliação manual completa com leitores de tela.
- Regressão existente passou para autenticação, recuperação, renovação de sessão, permissões, inscrições, registro/edição/exclusão de coletas, relatórios, perfil e formulários administrativos. Os dados e números nas capturas desses fluxos pertencem exclusivamente à base descartável de teste.

## Capturas para revisão

- [Início completo — computador](capturas/21/inicio-computador.png)
- [Início completo — celular](capturas/21/inicio-celular.png)
- [Busca por localidade sem eventos — celular](capturas/21/localidade-sem-eventos-celular.png)
- [Mutirões — computador](capturas/21/mutiroes-computador.png)
- [Impacto — computador](capturas/21/impacto-computador.png)
- [Administração — celular](capturas/21/administracao-celular.png)

A identificação de `saopaulo.jpg` continua pendente. Não há associação inventada enquanto não houver referência ou confirmação segura.

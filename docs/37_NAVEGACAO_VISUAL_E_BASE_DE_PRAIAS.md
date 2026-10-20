# Elo do mar: navegação, seletores e praias disponíveis

## Navegação e visual

O portal agora tem uma página inicial de apresentação e áreas separadas para mutirões, impacto, inscrições, coletas, perfil e administração. O menu permanece no topo. No celular, o botão de menu abre a navegação; pessoas autenticadas encontram seus recursos em **Minha área**.

As áreas têm endereços próprios, como `/#/mutiroes`, `/#/impacto` e `/#/coletas`. Os botões Voltar e Avançar do navegador navegam entre elas. A apresentação inicial fica apenas em Início; a busca abre diretamente no topo da área de mutirões. Os links de confirmação e recuperação continuam usando seus fragmentos privados, removidos do histórico antes de exibir o formulário.

A identidade visual usa azul oceano, verde e areia, com cabeçalho fixo, cartões, hierarquia de títulos, formulários e rodapé redesenhados. Não há fontes ou scripts de terceiros.

## Listas com pesquisa

Estados, cidades, praias e os demais seletores têm uma lista personalizada com pesquisa por nome, rolagem e indicação da opção selecionada. A pesquisa ignora acentos. Setas navegam entre opções, Enter confirma e Escape fecha a lista. A lista se ajusta ao espaço disponível, inclusive em diálogos.

O campo nativo continua sendo a fonte dos valores e da validação dos formulários. Trocar Estado limpa cidade e praia; trocar cidade limpa praia. Na cidade selecionada, as praias aparecem pelo nome curto; em consultas gerais, o nome com município permite distinguir praias homônimas.

## Praias que já aparecem como opção

Foi incluída uma base inicial de **211 praias em 21 municípios de cinco Estados**. Ela é carregada no catálogo pela migração V13 e fica disponível na busca e na criação de eventos. Não é uma lista completa das praias do Brasil.

| Estado | Municípios incluídos |
| --- | --- |
| ES | Guarapari, Vitória |
| SP | Santos, Guarujá, Ubatuba, Caraguatatuba, São Sebastião, Ilhabela, Bertioga, Praia Grande, São Vicente |
| SC | Florianópolis, Balneário Camboriú, Bombinhas, Garopaba, Imbituba, Itajaí, Itapema, Governador Celso Ramos |
| RJ | Rio de Janeiro |
| BA | Salvador |

Atualização da versão atual: nos seletores de praias, Estados e cidades são limitados aos locais com praias cadastradas; escolher a praia diretamente preenche Estado e cidade. No cadastro administrativo de praias, todas as cidades da base local continuam disponíveis. A administração pode acrescentar locais além da base inicial. Consulte a [consolidação com V14 e coleta em lote](38_VERSAO_ATUAL_COLETAS_EM_LOTE_E_V14.md).

As fontes de localização são registradas por município em `src/main/resources/db/beaches-v13.json`:

- [Prefeitura de Guarapari — praias](https://www.guarapari.es.gov.br/pagina/ler/2116/setec-praias).
- [Prefeitura de Vitória — praias](https://m.vitoria.es.gov.br/semmam/praias).
- [CETESB — praias do litoral paulista](https://sistemasinter.cetesb.sp.gov.br/praias/mapa_praias/respraia1.asp).
- [IMA/SC — relatório com praias por município](https://balneabilidade.ima.sc.gov.br/relatorio/downloadPDF/2026-10-16).
- [Riotur — praias do Rio](https://riotur.rio/que_fazer/rio-praias/).
- [Turismo de Salvador — praias](https://www.salvadordabahia.com/roteiros/10-dias-de-praia-em-salvador/).

Os boletins foram usados para identificar locais; condições de banho não são importadas nem exibidas pelo portal. Não há consulta a esses serviços durante o uso do site.

## Migração e preservação

V13 acrescenta cadastros com nomes que incluem município/UF e seus aliases correspondentes. Não muda a localização de praias antigas sem cidade, não reatribui eventos e não substitui praias que o administrador já registrou com o mesmo nome, cidade e Estado. A importação reconhece aliases já existentes, inclusive de registros unificados, e pode ser reaplicada sem duplicar seus próprios cadastros.

Cadastros antigos sem localização continuam exigindo revisão administrativa para aparecer por Estado/cidade. A Praia do Morro do perfil de demonstração tem localização explícita em Guarapari/ES; isso não preenche praias desconhecidas do banco do usuário.

O arquivo de dados da V13 é parte dessa migração: futuras ampliações da base persistente devem usar uma nova migração, preservando o histórico da V13.

Para usar no Eclipse, reiniciar a aplicação com os perfis atuais e atualizar o navegador com Ctrl+F5. A execução MySQL aplica V13 pelo Flyway. As configurações de envio de e-mail continuam as existentes.

## Validação

- Suíte Java: 147 casos executados; 146 passaram na primeira execução. Um teste existente de concorrência na troca de senha excedeu o timeout de bloqueio H2 enquanto a conferência do navegador rodava em paralelo. Os seis casos dessa classe passaram na repetição isolada.
- Migração H2: preservação de dados antigos, respeito a cadastro manual de praia e reaplicação sem duplicação aprovados.
- MySQL 8.0 descartável: V13 aplicada e localização/busca por cidade preservadas após reinício. A execução conjunta com os testes de migração e troca de senha terminou com nove casos aprovados e gerou o JAR.
- Cliente JavaScript: 29 testes aprovados; OpenAPI validado; 12 testes das ferramentas Python aprovados.
- Conferência em Edge: navegação, busca pesquisável e sem acentos, seleção de praias reais, filtros encadeados, cadastro de praias/eventos e regressões de conta, permissões, inscrições, coletas, contraste e teclado aprovados. Capturas locais em `target/elo-home.png`, `target/elo-events.png` e `target/elo-beach-picker.png`.
- A rodada final utilizou os arquivos do JAR empacotado e também verificou o menu móvel, o botão Voltar e as páginas inicial/de eventos em 320 pixels. Capturas em `target/elo-home-mobile.png` e `target/elo-events-mobile.png`.

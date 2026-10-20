# Agrupamento de conteúdo e alinhamento dos filtros

Revisão de 20/10/2026, após a [composição com localidades](21_COMPOSICAO_E_LOCALIDADES.md).

## Alterações

- **Início:** cada cartão de localidade reúne fotografia, cidade, praia e ação sobre uma superfície verde clara, com borda discreta, cantos de 6 px e espaçamento interno. O cartão inteiro continua sendo um único link, inclusive sua área de respiro, com foco visível e feedback de cor. A variação de proporções e o deslocamento intencional do cartão de Guarapari foram mantidos. Foto de abertura e fotografia de participação também têm legendas integradas à superfície. Não foram alterados arquivos, identificação ou destino das fotografias.
- **Mutirões:** filtros reunidos numa superfície própria. No desktop, uma grade coloca Estado, Cidade, Praia, Status e Buscar na mesma linha; controles e botão medem 42 px. A mensagem auxiliar ocupa uma linha independente abaixo, sem aumentar a altura do grupo de campos. Resultados têm cartões brancos e ações separadas por uma borda interna discreta; estados vazios usam uma superfície suave.
- **Impacto:** os três totais formam um conjunto sobre verde claro; filtros têm seu próprio agrupamento; tabela e gráfico compartilham uma superfície branca, sem uma caixa por métrica ou linha de resultado.
- **Minhas inscrições e Minhas coletas:** cartões compartilham o tratamento de resultados, com separação visual das ações. O resumo pessoal permanece agrupado e os estados vazios ganham o mesmo tratamento das demais telas.
- **Meu perfil:** dados pessoais e troca de senha são dois formulários claramente agrupados, com borda e preenchimento consistentes. Não há caixas individuais para cada campo.
- **Administração:** navegação por recursos e formulário de filtros têm superfícies distintas; resultados e ações seguem os mesmos cartões. Mensagens auxiliares, de erro e de validação diretamente nos filtros ocupam uma linha completa, sem competir com campos ou botões.

Os seletores pesquisáveis, campos de data e demais entradas dos filtros de Impacto e Administração também usam altura de 42 px. A linha da seta do seletor foi normalizada para não aumentar o controle em relação às entradas nativas.

As alterações de produto estão restritas a `portal-design.css`. API, permissões, regras, textos de negócio, seletores pesquisáveis e handlers não foram modificados. O ajuste local anterior de tamanho do botão Sair foi preservado e excluído do commit desta tarefa.

## Responsividade e acessibilidade

Acima de 900 px, os cinco itens da busca ficam alinhados. Entre 601 e 900 px, os três campos de localização ficam juntos e Status/Buscar passam para a linha seguinte, depois da mensagem quando houver. Até 600 px, Estado e Cidade dividem uma linha, Praia ocupa a largura disponível e Status/Buscar ficam abaixo. Até 380 px, os campos e a ação ficam em uma coluna.

Os valores extensos continuam dentro dos seletores pesquisáveis; o texto completo permanece disponível nas opções. Mensagens quebram linha dentro da largura do formulário. Foco não é recortado pelos cartões. Transições e aproximação das fotos permanecem, assim como o suporte a movimento reduzido.

## Verificação

- Empacotamento Maven offline com `-DskipTests package`: concluído. Esse comando não executa testes Java.
- `node tools/verify_portal_browser.cjs`: **PASS** no Edge headless, perfil novo, API Spring Boot e H2 descartáveis. Passaram os cenários de layout adicionados e a regressão existente de navegação, autenticação, permissões, inscrições, coletas, relatórios, perfil, administração, foco, contraste e CSP.
- Testes de geometria adicionados em 1280, 768, 390 e 320 px: alinhamento dos rótulos e controles no desktop; campos/botão de 42 px; ausência de sobreposição e transbordamento; mensagem abaixo dos três campos de localização.
- Cenários com e sem mensagem auxiliar e com nomes longos de cidade e praia. Os textos longos foram inseridos apenas no DOM de teste, restaurados em seguida, sem envio à API ou persistência.
- Escala de página fixada em 1 e verificada via `visualViewport.scale`: zoom de 100%. As capturas das demais telas também usam o perfil novo e escala 1.
- A primeira execução encontrou conflito de especificidade nas regras de uma coluna em 320 px. A regra foi corrigida e a verificação repetida.
- Capturas das telas públicas e autenticadas, incluindo impacto, inscrições, coletas, perfil e administração, são geradas pelo roteiro existente. Seus dados são exclusivamente da base de teste.

## Capturas

Capturas examinadas visualmente e preservadas para revisão:

| Tela | Computador | Celular |
| --- | --- | --- |
| Início | [Completa](capturas/22/elo-home.png) | [Completa](capturas/22/elo-home-mobile.png) |
| Mutirões | [Resultados](capturas/22/elo-events.png) | [Resultados](capturas/22/elo-events-mobile.png) |
| Filtros com mensagem | [1280 px](capturas/22/filters-helper-1280.png) | [390 px](capturas/22/filters-helper-390.png) |
| Impacto | [Tela](capturas/22/impact-desktop.png) | [Tela](capturas/22/impact-mobile.png) |
| Minhas inscrições | [Tela](capturas/22/registrations-desktop.png) | Fluxo de inscrição verificado no roteiro |
| Minhas coletas | [Tela](capturas/22/portal-desktop.png) | [Tela](capturas/22/portal-mobile.png) |
| Meu perfil | [Tela](capturas/22/profile-desktop.png) | [Tela](capturas/22/profile-mobile.png) |
| Administração | [Tela](capturas/22/admin-desktop.png) | [Tela](capturas/22/admin-mobile.png) |

As capturas de coletas incluem texto proposital de teste de escape HTML, exibido como texto inofensivo. Não é conteúdo real do projeto. A auditoria automatizada não substitui uma avaliação completa com leitores de tela.

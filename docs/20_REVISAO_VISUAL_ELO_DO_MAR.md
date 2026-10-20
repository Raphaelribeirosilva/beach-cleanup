# Revisão visual do Elo do mar

> Histórico desta primeira revisão. A composição do início, o uso das seis fotos e as identificações atualizadas estão em [Composição e localidades](21_COMPOSICAO_E_LOCALIDADES.md).

## Interface

A revisão mantém os fluxos da API e a navegação existente. Azul oceano estrutura a página, verde identifica ações e areia aparece em detalhes de contexto. A folha `portal-design.css` foi reescrita, substituindo o tema anterior.

- **Início:** fotografia real no lugar da ilustração, apresentação de até 360 px para a imagem no computador e 200 px no celular, título direto e ação principal para encontrar mutirões. As instruções de participação usam três etapas sem caixas decorativas. Não há carrossel.
- **Mutirões:** título simples, filtros compactos, data em destaque, praia e cidade em linhas próprias, situação da inscrição junto ao status. As faixas coloridas foram removidas. Inscrever-se é a ação principal do cartão; registrar coleta e cancelar inscrição têm tratamento secundário.
- **Voluntário:** resumo de participação, inscrições e coletas com a mesma hierarquia. Perfil e senha ficam em duas colunas no computador e uma no celular. A saudação com o nome foi mantida.
- **Administração:** abas, filtros, cartões e formulários compartilham os mesmos controles, bordas e tipografia. Operações e permissões permanecem nas rotas originais.
- **Impacto:** peso, quantidade de coletas e voluntários com coleta registrada vêm antes dos filtros. Tabela e gráfico ficam lado a lado quando há espaço. A tabela tem uma região com rolagem horizontal e foco por teclado em telas estreitas. Sem registros, há totais reais e uma mensagem de orientação, sem tabela ou gráfico vazios.
- **Textos:** removidos slogans repetitivos e chamadas vagas, inclusive dos diálogos e do rodapé.

Títulos internos usam aproximadamente 26–30 px, controles usam cantos de 4 px e os cartões 5 px. O foco permanece visível, inclusive nas capturas. A preferência por movimento reduzido continua respeitada.

## Fotografias examinadas

Os seis arquivos de `src/main/resources/static/images` foram abertos e examinados antes da edição. Os nomes de arquivo foram tratados como informação fornecida pelo projeto, não como prova de identificação de uma praia específica.

| Arquivo original | Dimensões | Identificação disponível e decisão |
| --- | --- | --- |
| `praia-de-camburi.jpg` | 2400 × 1800 | Nome da praia informado no arquivo. Utilizada no início com a legenda **Praia de Camburi**. Município não acrescentado sem confirmação. |
| `florianopolis.jpg` | 1600 × 1066 | Nome de município; praia específica não confirmada. Não associada a eventos. |
| `guarapari.jpg` | 662 × 463 | Nome de município; praia específica não confirmada. Não associada a eventos. |
| `riodejaneiro.jpg` | 1200 × 900 | Nome de cidade/estado; praia específica não confirmada. Não associada a eventos. |
| `salvador.jpg` | 1024 × 720 | Nome de município; localização exata não confirmada. Não associada a eventos. |
| `saopaulo.jpg` | 683 × 449 | Nome ambíguo de cidade/estado; município e praia não confirmados. Não associada a eventos. |

Os cartões de evento permanecem sem fotografia: não foi feita correspondência aproximada por cidade, nome parcial ou ID. Não é seguro usar uma foto de uma praia como representação de outra. As demais imagens não foram incluídas apenas para preencher a página.

Não foram encontrados créditos nos campos consultados de descrição, artista, copyright, IPTC e XMP. Não foi atribuída autoria, fonte externa ou licença. Informações adicionais de identificação e crédito poderão ser incorporadas quando fornecidas.

## Otimização e preservação

Os originais não são sobrescritos. `tools/optimize_portal_photos.ps1` gera JPEGs com qualidade 80 e interpolação bicúbica, preservando proporção e campos de atribuição disponíveis. Os metadados de câmera e perfil continuam no original; o copyright do perfil de cor não é tratado como autoria da fotografia.

| Versão de Camburi | Dimensões | Tamanho |
| --- | --- | --- |
| Original | 2400 × 1800 | 944.535 bytes |
| Pequena | 480 × 360 | 36.057 bytes |
| Média | 960 × 720 | 132.274 bytes |
| Grande | 1440 × 1080 | 276.955 bytes |

O navegador seleciona a versão por `srcset` e `sizes`. `width`, `height` e a área da foto reservam espaço; `object-fit: cover` e posições diferentes para computador/celular ajustam o recorte sem distorcer. A fotografia inicial tem prioridade alta de carregamento. Não há fotografias adicionais abaixo da primeira tela nesta revisão; quando acrescentadas, devem usar `loading="lazy"`, dimensões e uma associação de local confirmada.

Apenas GET e HEAD de `/images/web/**` são públicos. Os originais não foram adicionados à lista pública. A política de conteúdo continua permitindo imagens somente da mesma origem.

Para gerar novamente no Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/optimize_portal_photos.ps1
```

## Seleção de evento no impacto

O campo numérico foi substituído por um `select` nativo com melhoria progressiva pelo componente `portal-select.js`. O usuário pesquisa o título; data, praia e número do registro ajudam a distinguir nomes iguais. O valor enviado continua sendo `eventId` numérico, inclusive no relatório administrativo de inscrições.

As opções são obtidas de `/cleanupEvent/search` em páginas de até 100 registros, ordenadas por ID. Todas as páginas são percorridas; não há limite silencioso na primeira página. A pesquisa é local às opções carregadas, porque a API atual não oferece filtro por título. Para volumes muito grandes, uma pesquisa remota por título seria uma evolução separada da API. A navegação para outra tela invalida respostas pendentes.

## Verificações

Executadas em 20/10/2026:

- `node --check` para `portal.js`, `portal-reports.js` e o roteiro de navegador: sem erros de sintaxe.
- `node --test tools/portal-api.test.cjs`: **29 testes aprovados**.
- Maven offline, `-Dtest=VolunteerPortalIntegrationTests test`: **3 testes aprovados**, incluindo leitura pública das fotos, rejeição de escrita e proteção dos originais e rotas privadas.
- Maven offline, `-DskipTests package`: pacote gerado com sucesso para a verificação no navegador. Não foi executada toda a suíte Java nesta tarefa.
- `node tools/verify_portal_browser.cjs`: **aprovado** contra API real local e banco H2 descartável. Executado no Edge em modo headless, com janelas de 1280 × 960, 390 × 844 e 320 × 844. Não houve exceções JavaScript ou violações de CSP detectadas.
- **17 etapas de auditoria de acessibilidade**, sem falhas nos critérios automatizados: cores computadas, nomes dos controles, tamanho mínimo de alvo e identificação dos diálogos. Há verificações adicionais de teclado, foco, link para pular ao conteúdo, contenção de foco nos diálogos e ausência de transbordamento horizontal nos cenários exercitados. Isso não substitui uma auditoria completa com leitor de tela ou aparelhos físicos.
- Inspeção visual das capturas de início, mutirões, impacto, coletas, perfil, administração e formulários, em computador e celular. Indicadores de foco foram preservados.

O roteiro exercita navegação e histórico, filtros de estado/cidade/praia, paginação, inscrição/cancelamento, coletas vinculadas e independentes, múltiplos materiais, edição/exclusão, conflitos de versão, login, recuperação e confirmação, atualização de perfil/senha, renovação de sessão e saída. Na administração, verifica eventos, coletas, usuários, catálogos, auditoria e revisão de dados.

Nos relatórios, verificou seleção pesquisável por teclado, envio de `eventId` na URL da API, totais reais da base de teste, estado vazio e acesso administrativo às inscrições. Foram criados 101 eventos adicionais **somente no H2 descartável** para confirmar que a pesquisa encontra o último evento além da primeira página da API.

As tentativas iniciais encontraram bloqueios do sandbox no cache Maven e no Edge; a execução autorizada fora do sandbox permitiu concluir. Uma primeira asserção buscava um evento de teste inexistente (`Mutirão navegador 0`); foi corrigida para o evento criado pelo roteiro. Um processo descartável remanescente foi encerrado para liberar o JAR. Os resultados acima correspondem às execuções concluídas, não às tentativas interrompidas.

## Capturas para revisão

**Os nomes, e-mails, eventos e números das capturas são dados de teste, não resultados reais do Elo do mar.** O texto semelhante a HTML em alguns materiais faz parte do teste de segurança: aparece como texto e não é executado. Não foram inseridos depoimentos, parceiros ou estatísticas fictícias na interface de produção.

| Tela | Computador | Celular |
| --- | --- | --- |
| Início | [Captura](capturas/20/elo-home.png) | [Captura](capturas/20/elo-home-mobile.png) |
| Mutirões | [Captura](capturas/20/elo-events.png) | [Captura](capturas/20/elo-events-mobile.png) |
| Impacto | [Captura](capturas/20/impact-desktop.png) | [Captura](capturas/20/impact-mobile.png) |
| Minhas coletas | [Captura](capturas/20/portal-desktop.png) | [Captura](capturas/20/portal-mobile.png) |
| Perfil | [Captura](capturas/20/profile-desktop.png) | [Captura](capturas/20/profile-mobile.png) |
| Administração | [Captura](capturas/20/admin-desktop.png) | [Captura](capturas/20/admin-mobile.png) |

Detalhes: [inscrição confirmada](capturas/20/registrations-desktop.png), [pesquisa de evento](capturas/20/impact-event-picker.png), [relatório vazio](capturas/20/impact-empty-desktop.png), [formulário de coleta](capturas/20/collection-form-mobile.png) e [formulário administrativo](capturas/20/admin-event-form-mobile.png).

O resumo verificável das etapas de acessibilidade está em [verificacoes.json](capturas/20/verificacoes.json). Para repetir a verificação, gere o pacote e execute o roteiro Node acima. Ele cria seu próprio servidor, navegador e banco temporários e não depende de uma sessão da aplicação do usuário.

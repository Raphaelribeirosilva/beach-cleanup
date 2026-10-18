# Interface, acessibilidade e desempenho

O portal usa páginas limitadas, mantém contagens globais separadas dos itens visíveis e preserva mensagens e rascunhos em conflitos. A medição inicial foi feita antes de alterar as consultas. O teste completo de navegador passa a fazer parte do CI, depois do empacotamento.

## Navegação e formulários

- O link “Pular para o conteúdo” transfere o foco para o conteúdo principal. A navegação explícita entre áreas transfere o foco para o título correspondente. As abas usam botões nativos, navegação por Tab e `aria-current`; não simulam um componente ARIA com comportamento de teclado incompleto.
- Diálogos nativos recebem foco no primeiro campo disponível, mantêm a navegação modal e permitem fechar com Escape. Ao fechar, o foco retorna ao controle anterior ou ao título da área se esse controle deixou de existir.
- Erros de formulário recebem foco e são anunciados como alertas. A confirmação de cadastro usa uma mensagem de sucesso separada. Botões em operação apresentam “Aguarde”, ficam desabilitados e usam `aria-busy`; uma resposta antiga não sobrescreve uma área aberta depois.
- Os filtros e cartões se adaptam a telas pequenas; a navegação permite rolagem horizontal dentro da própria barra. Cores de texto secundário, placeholder, contornos de campos e foco foram revistas. Botões de fechar e ações receberam alvos maiores.

O teste `tools/portal-accessibility.cjs` verifica, no navegador real, nomes dos campos, botões e diálogos, tamanho de botões e contraste calculado das cores efetivas, incluindo fundos com transparência. Usa 4,5:1 para texto normal e 3:1 para texto grande conforme os [critérios de contraste do W3C](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html). A navegação por teclado também verifica [foco visível](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible). A primeira execução identificou textos de identificação dos cartões entre 4,35:1 e 4,41:1; a cor secundária foi escurecida.

Essas verificações cobrem os fluxos exercitados, sem constituir certificação integral de WCAG. Não houve teste com um leitor de tela real nesta entrega.

## Limites e permissões

`GET /users/me/events/search?page=0&size=20&eventIds=7,8` retorna:

- `content`, `number`, `size`, `totalElements` e `totalPages` para as inscrições da própria conta;
- `registeredEventIds`, contendo somente os IDs solicitados nos quais essa conta está inscrita.

`size` aceita 1 a 100 e `eventIds` aceita até 100 IDs positivos. A conta vem da autenticação, não de um parâmetro de usuário. O portal exibe seis inscrições por página e solicita os IDs dos eventos visíveis para marcar as inscrições, sem carregar toda a relação. O total do indicador considera todas as inscrições. Ao cancelar o último item da última página, a interface volta à página anterior.

As listagens antigas `/users`, `/cleanupEvent`, `/trashCollection` e `/users/me/events` continuam retornando arrays quando há até 100 registros visíveis. Acima disso, retornam **400**, com orientação para usar a rota `/search`; não retornam um resultado truncado como se estivesse completo. A restrição de coletas à própria conta continua aplicada antes da contagem e da paginação. Clientes antigos precisam migrar para as buscas paginadas.

Relatórios admitem até 500 grupos ou eventos. Se o resultado exceder esse limite, retornam **400** solicitando filtros mais específicos. O banco busca no máximo 501 grupos para detectar o excesso; o portal não monta milhares de linhas ou gráficos nem apresenta totais parciais. O processamento das agregações no banco continua dependendo do volume filtrado.

Os catálogos ainda são carregados integralmente para os seletores. A criação de uma busca de catálogo com autocomplete deve ser guiada pelo crescimento real e por uma nova medição; esta entrega não transforma todos os endpoints em APIs paginadas. O contrato atualizado está no [OpenAPI](openapi.json).

## Medição antes e depois

Conjunto sintético e determinístico: **10.000 coletas**, **500 eventos**, **200 usuários**, **500 inscrições** de uma conta e **30 itens de catálogo referenciados** (além dos catálogos iniciais das fixtures), com referências distribuídas entre as páginas. Os testes executam duas chamadas de aquecimento e nove amostras, limpando as estatísticas do Hibernate antes de cada uma. Cada chamada de serviço usa sua transação e contexto de persistência. Não se contabilizam apenas consultas JPQL: o orçamento usa a quantidade de statements preparados, incluindo carregamentos adicionais de referências.

Medições locais em H2 2.4.240, Windows, Java 26.0.1, com a mesma carga:

| Operação | SQL antes | SQL depois | Mediana antes | Mediana depois |
| --- | ---: | ---: | ---: | ---: |
| Página de 20 eventos | 22 | 2 | 23,38 ms | 8,92 ms |
| Página de 20 coletas | 33 | 3 | 23,62 ms | 15,02 ms |
| Impacto por praia | 2 | 2 | 19,75 ms | 20,01 ms |
| Inscrições da conta | 2, retornando 500 eventos | 4, retornando 20 eventos e correspondências de IDs | 19,70 ms | 8,05 ms |

A medição identificou consultas adicionais para carregar as referências de cada item da página. A correção usa `EntityGraph` somente com relações para um registro, incluindo praia, material, evento e usuário necessários ao DTO. Não há paginação sobre um fetch de coleção. Os totais de peso continuam calculados em lote. O relatório de impacto já usava agregação e não recebeu uma otimização sem evidência de gargalo.

Os dados brutos, com p95 e contagem de consultas, estão em [antes](performance/portal-before.json) e [depois](performance/portal-after.json). As latências são descritivas de uma execução local, incluem a chamada do serviço e não representam tempo HTTP nem SLA de produção. Não se compara a latência H2 com MySQL como se fossem o mesmo ambiente. Com nove amostras, o p95 informado corresponde à maior amostra pelo método de posição mais próxima.

A execução em MySQL 8.0.46 local confirmou os orçamentos: eventos, 2 consultas, mediana de 6,35 ms e p95 de 8,81 ms; coletas, 3 consultas, 17,77 ms e 21,35 ms; inscrições paginadas, 4 consultas, 10,96 ms e 14,03 ms; impacto, 2 consultas, 45,84 ms e 48,60 ms. Os dados brutos estão em [MySQL](performance/portal-mysql.json). São tempos de serviço em loopback, sem a rede ou a concorrência de uma implantação pública.

O mesmo probe roda em MySQL descartável no perfil `mysql-it`. Ele gera `target/portal-performance-mysql.json`. O CI exige orçamento de até duas consultas para a página de eventos e três para a de coletas; não impõe limites em milissegundos sujeitos à velocidade do runner. Também verifica contagens, limites de relatórios e preservação das permissões.

## Organização e CI

`portal-ui.js` concentra controles assíncronos, DOM e foco; `portal-reports.js` concentra o ciclo de carregamento e a renderização de relatórios. `portal.js` coordena a conta e as áreas pessoais; `portal-admin.js` mantém a área administrativa; `portal-api.js` mantém transporte e sessão. CSS foi formatado e separado entre base, administração e conta/relatórios. Os novos arquivos estáticos estão explicitamente permitidos na configuração de segurança, mantendo CSP e a proteção das rotas de negócio.

O workflow `.github/workflows/ci.yml` executa os testes Java/MySQL, os testes do cliente, o OpenAPI e o navegador. O script usa Edge no Windows ou Chrome/Chromium no Linux, permitindo informar `PORTAL_BROWSER`. O runner Ubuntu usado no CI fornece Chrome conforme o [inventário oficial de imagens do GitHub](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md). Não é necessário instalar bibliotecas de automação no projeto.

Os artefatos incluem relatórios de testes, medições, verificações de acessibilidade, tempos de navegação/recursos e capturas de tela. Em falhas, o script preserva o log da aplicação descartável antes de encerrar os processos e limpar somente seu próprio diretório temporário. A integração remota será exercitada quando o commit for enviado; a execução local não comprova uma execução já concluída no GitHub Actions.

Comandos de reprodução, após empacotar a aplicação:

```text
node --test tools/portal-api.test.cjs
node tools/verify_portal_browser.cjs
python tools/validate_openapi.py
```

Para repetir a medição H2, execute Maven com `-Dtest=PortalPerformanceTests -Dportal.benchmark.label=current test`. Para MySQL, use `tools/run_mysql_tests.ps1` com um servidor descartável ou o perfil `mysql-it` e as variáveis `MYSQL_TEST_*` descritas na documentação de persistência. Não aponte o probe para um banco com dados reais: ele prepara exclusivamente os dados de seu ambiente de testes.

Validação desta entrega: 151 testes Java aprovados, incluindo 15 testes MySQL; 28 testes do cliente; 3 verificações Python; OpenAPI com 28 caminhos e 39 operações. O teste completo no Edge passou, com sete verificações de acessibilidade sem violações nas regras automatizadas, teclado real via CDP, formulário de coleta em 320 px, páginas com 320/768/1280 px, paginação de inscrições, regressões de permissões, conflitos, sessões e CSP. Uma verificação adicional do probe confirmou que as 10 mil coletas globais não impedem a consulta às 50 coletas visíveis da própria conta na rota antiga.

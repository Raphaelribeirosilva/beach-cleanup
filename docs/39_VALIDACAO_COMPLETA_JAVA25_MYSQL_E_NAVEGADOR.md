# Validação completa: Java 25, MySQL e navegador

Data: 19/10/2026. Execução local sobre a árvore baseada em `aea4459`, com as alterações locais preexistentes preservadas e a correção de teste descrita abaixo.

## Resultado final

**Todas as fases aprovadas**, em sequência, entre 22:37:04 e 22:46:05 (UTC−03).

| Verificação | Resultado |
| --- | --- |
| Suíte Java completa, `-Pmysql-it verify` | 168 testes: 149 H2 e 19 MySQL; zero falhas, erros ou ignorados |
| Empacotamento | JAR executável gerado com sucesso |
| OpenAPI | 29 caminhos e 40 operações; rotas, referências e migrações aprovadas |
| Ferramentas Python | 12 testes aprovados |
| Cliente JavaScript | 29 testes aprovados |
| Navegador | Fluxos completos de voluntário e administrador aprovados; 14 etapas de auditoria de interface |
| Backup/restauração MySQL | 13 tabelas comparadas, dados preservados e credenciais antigas revogadas |

No navegador foram verificados cadastro/login, recuperação/confirmação, renovação e encerramento de sessão, inscrições, coletas independentes e vinculadas a evento, edição/exclusão, perfil/senha e totais. A administração cobriu eventos/status, usuários, catálogos/unificação, auditoria, qualidade e permissões. A seleção de praias e o registro de múltiplos materiais passaram nas duas telas de coleta. O roteiro também aprovou teclado/foco, contraste, tamanhos de tela desktop/móvel e ausência de exceções JavaScript ou violações de CSP.

## Ambiente e sequência

| Componente | Versão utilizada |
| --- | --- |
| JDK e runtime dos testes/aplicação | Eclipse Temurin 25.0.2+10 |
| Maven | 3.9.12, instalação local |
| MySQL descartável | Community Server 8.0.46 |
| Node.js | 24.21.0 |
| Navegador | Microsoft Edge 155.0.4283.62, headless |

`JAVA_HOME` e `PATH` foram ajustados somente no processo de validação. O log registra `java -version`, `mvn -v` e a resolução de `java` pelo Node antes do navegador; os testes Spring também registram Java 25.0.2. A conferência dos relatórios XML confirmou Java 25.0.2 nos 168 testes; a classe principal do JAR tem versão de bytecode 69, correspondente ao Java 25.

A sequência segue o CI, sem execução simultânea das etapas:

1. `tools/run_mysql_tests.ps1` com Maven `-o -B -Pmysql-it verify`, sem filtro `-Dtest`. Inclui toda a suíte H2/MySQL, migrações, empacotamento, OpenAPI e ferramentas Python. O script cria sua própria instância MySQL em porta e diretório temporários e a encerra ao terminar.
2. `node --test tools/portal-api.test.cjs`.
3. `node tools/verify_portal_browser.cjs`, sobre o JAR gerado na etapa anterior e usando Java 25. O roteiro cria aplicação H2 e perfil de navegador temporários, separados da instância MySQL da suíte de integração.

## Correção encontrada na rodada completa

O teste `timestampUpgradeAlignsOnlyV8ChecksumAndPreservesStoredSessionDates` prepara uma base histórica na V10 e verifica exclusivamente a correção temporal da V11, incluindo a restauração do fuso da conexão, os checksums e a comparação integral dos dados.

A primeira rodada executou 168 testes e encontrou uma falha: esse cenário esperava uma migração pendente, mas o Flyway sem limite executava quatro, de V11 a V14. V13/V14 também acrescentam linhas ao catálogo, incompatíveis com as comparações integrais específicas desse cenário histórico.

A configuração desse teste agora usa `target("11")`. Foram mantidas a expectativa de uma migração e todas as comparações de dados, timestamps e checksums. Os demais testes continuam instalando e atualizando até V14. Após essa correção, a validação foi reiniciada pela suíte completa, antes de seguir para o navegador.

## Evidências locais

Os artefatos gerados ficam em `target/` e não são versionados:

- `validation-full-java25.log`: execução integrada final, com identificação do Java, fases e resultados.
- `validation-full-java25-summary.json`: contagens conferidas nos XMLs, versão do Java, etapas do navegador e SHA-256 do JAR.
- `validation-full-java25-initial-failure.log` e `validation-full-java25-initial-mysql.txt`: primeira rodada e diagnóstico da expectativa histórica.
- `surefire-reports/`: relatórios XML/texto das classes Java.
- `staging-recovery-proof.json`: prova de backup/restauração no MySQL temporário.
- `portal-browser-accessibility.json` e `portal-browser-performance.json`: verificações do navegador.

O banco habitual e as alterações locais anteriores foram preservados. A validação é local e não representa uma execução do GitHub Actions ou implantação em ambiente hospedado.

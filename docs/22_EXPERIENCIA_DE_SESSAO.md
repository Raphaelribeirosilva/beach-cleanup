# Experiência de sessão do portal

Data: 16/10/2026.

## Comportamento entregue

O login passa a emitir JWT com validade de **1.800 segundos (30 minutos)**. Tanto `expiresIn` quanto a diferença entre `iat` e `exp` refletem esse prazo. Tokens já emitidos mantêm sua expiração original; o novo prazo vale após um novo login.

O portal armazena o resultado do login e um horário absoluto de expiração em `sessionStorage`, na chave `beachcleanup.session.v1`. Recarregar a mesma aba recupera a sessão válida e consulta novamente perfil e inscrições na API. O prazo armazenado permanece o mesmo: atualizar a página não concede mais 30 minutos. Dados de perfil, listas, senha e formulário de login não são persistidos.

Sair remove a chave e limpa os dados privados da interface. Um temporizador encerra a sessão local ao atingir o prazo; a conferência também ocorre nas requisições e ao voltar o foco para a janela, cobrindo abas suspensas. Uma resposta 401 de operação privada encerra a sessão e solicita login, inclusive após restauração. Respostas atrasadas não recuperam dados de uma sessão encerrada, e um login pendente não pode desfazer uma saída posterior.

Conteúdo armazenado inválido ou expirado é descartado. Quando o navegador bloqueia o armazenamento, o portal continua permitindo login em memória; nesse caso, recarregar exige nova autenticação. A autorização efetiva continua sendo feita pelo servidor, que verifica assinatura, expiração, conta, versão de credenciais e permissões. Alterar o papel armazenado no navegador não concede permissão na API.

## Limites e renovação

Esta versão preserva a sessão durante os 30 minutos de validade e **não implementa refresh token nem renovação automática**. Ao expirar, é necessário entrar novamente. Foi solicitada a preferência do usuário sobre incluir esse fluxo adicional; este registro descreve a melhoria de duração e recarga implementada enquanto essa preferência não foi informada.

O token armazenado permanece acessível a JavaScript da mesma origem. A CSP e a renderização de texto sem HTML continuam aplicadas. `sessionStorage` é limitado à sessão da aba e normalmente é eliminado ao fechá-la; restauração de abas e duplicação podem preservar/copiar seu conteúdo conforme o navegador. A opção Sair é o mecanismo explícito de limpeza. Não há gravação em `localStorage` nem cookie de autenticação.

Sair remove as credenciais deste portal; não revoga um JWT previamente copiado. A troca de senha continua invalidando os tokens anteriores pelo `credentialVersion`. O prazo maior também aumenta de 15 para 30 minutos a validade máxima de um token copiado que não tenha sido revogado.

Uma futura renovação automática exige contrato de refresh e logout no backend, armazenamento seguro e revogação/rotação dos tokens de renovação, proteção das rotas correspondentes e testes de concorrência, expiração e troca de senha. Preservar o token da aba não substitui esse fluxo.

## Arquivos e verificação

- [JwtConfig](../src/main/java/com/raphael/beachcleanup/config/JwtConfig.java): duração de 1.800 segundos.
- [Cliente da API](../src/main/resources/static/portal-api.js) e [portal](../src/main/resources/static/portal.js): restauração, limpeza e expiração da sessão.
- [Testes do cliente](../tools/portal-api.test.cjs): recarga sem prolongar prazo, expiração, armazenamento indisponível/corrompido, 401 após restauração e saída durante login pendente, além das regressões existentes.
- [Teste de segurança](../src/test/java/com/raphael/beachcleanup/SecurityIntegrationTests.java): `expiresIn` e duração real do JWT.
- [Roteiro de navegador](../tools/verify_portal_browser.cjs): sessão preservada após recarga, saída persistida e descarte de sessão expirada, junto dos fluxos de voluntário e administrador.
- [OpenAPI](openapi.json), [entrada do projeto](../README.md) e [portal do voluntário](18_PORTAL_DO_VOLUNTARIO.md): documentação do comportamento atual.

Resultados executados nesta entrega:

- Maven offline `package`: **BUILD SUCCESS, 71 testes, zero falhas, erros ou ignorados**, com JAR gerado. Não foi executado o perfil opcional MySQL, pois não houve mudança de persistência.
- Node `--test tools/portal-api.test.cjs`: **14 testes aprovados**.
- Python `tools/validate_openapi.py`: **13 caminhos e 23 operações**, estrutura e cobertura aprovadas.
- Edge headless `tools/verify_portal_browser.cjs`: aprovado, incluindo recarga autenticada, recarga após saída e descarte de sessão expirada, além dos fluxos administrativos/voluntários, autorização real, viewports de computador/celular e ausência de erros JavaScript/CSP. O roteiro aguarda a criação do novo documento após cada recarga para não confundir a tela anterior com uma restauração bem-sucedida.
- **37 links locais** conferidos nos três arquivos Markdown de entrada/portal/entrega; nenhum inexistente. Diferenças de whitespace conferidas antes do commit.

Maven e Edge precisaram executar fora do sandbox após bloqueios de acesso à dependência local e a recursos do Windows, respectivamente. Os testes de navegador utilizaram instância H2 e perfil temporários; não acessaram o banco do usuário. Os testes de expiração usam relógio controlado ou prazo armazenado vencido, sem esperar 30 minutos reais.

As verificações utilizam a árvore de trabalho atual, que contém alterações preexistentes preservadas e excluídas deste commit. Não há mudança de banco, migração ou implantação nesta entrega. Não foi solicitado push deste novo trabalho.

## Referência

[MDN: sessionStorage](https://developer.mozilla.org/en-US/docs/Web/API/Window/sessionStorage) documenta a persistência durante recargas, o isolamento por aba e as exceções de acesso ao armazenamento.

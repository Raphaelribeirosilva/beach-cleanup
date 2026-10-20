# Versão atual: coletas em lote e migrações V1–V14

Consolidação em 19/10/2026. A última migração disponível no repositório é **V14**; não há V15 nesta versão. O aplicativo continua em `0.0.1-SNAPSHOT` no Maven e no OpenAPI. O formato do contrato é OpenAPI 3.0.3. Esses identificadores são independentes das versões do banco.

O [README principal](../README.md) apresenta a execução e as regras vigentes. O [OpenAPI](openapi.json) documenta **29 caminhos e 40 operações**, incluindo `POST /trashCollection/batch`. Documentos anteriores registram suas etapas e resultados históricos.

## Funcionalidades consolidadas

O portal **Elo do mar** oferece navegação por área, busca de eventos por Estado/cidade/praia, inscrições, coletas, relatórios de impacto e manutenção da conta. A administração gerencia eventos, coletas e catálogos e consulta usuários, qualidade dos dados e auditoria. As sessões da aba são renováveis; logout e troca de senha revogam sessões. Recuperação e confirmação de e-mail dependem da configuração de envio do ambiente.

Nos seletores de praias, Estado e cidade oferecem locais com praias cadastradas. Escolher uma praia diretamente preenche sua localização; trocar Estado limpa cidade/praia e trocar cidade limpa praia. O cadastro administrativo de praias continua permitindo qualquer cidade da base local de municípios. Locais antigos sem geografia não são preenchidos por inferência.

## Materiais e coleta em lote no portal

Em **Registrar coleta** e **Administração → Coletas → Registrar coleta**, marque um ou mais materiais do catálogo e informe o peso de cada material em kg. Os campos de peso só são habilitados para materiais marcados. Não há digitação livre do nome do material.

Uma seleção de vidro com 1,250 kg e metal com 2,500 kg gera duas coletas, com total de 3,750 kg. O peso não é repetido para todos os materiais nem dividido automaticamente. Com um material, o portal usa a criação individual; com vários, envia um único lote. A lista mostra os registros separados; edição e exclusão continuam individuais, com ID e `version`.

## Contrato de `POST /trashCollection/batch`

Enviar `Authorization: Bearer <accessToken>` e `Content-Type: application/json`. O corpo é diretamente um array, sem objeto externo:

```json
[
  {
    "beachId": 1,
    "materialId": 2,
    "weightKg": 1.25,
    "registrationDate": "2026-10-19"
  },
  {
    "beachId": 1,
    "materialId": 3,
    "weightKg": 2.5,
    "registrationDate": "2026-10-19"
  }
]
```

IDs ilustrativos: consulte `GET /catalog/beaches` e `GET /catalog/materials` e substitua pelos IDs reais do ambiente. O lote exige:

- De 1 a 50 itens não nulos; `materialId` positivo, obrigatório e distinto em cada item. Não se aceita apenas `materialType` para identificar um material no lote.
- Peso individual de 0,001 a 100.000 kg, com até três casas decimais; não há arredondamento de novos pesos.
- Data entre 1900-01-01 e hoje em `America/Sao_Paulo`. Praia deve ser reconhecida pelo catálogo; `beachId` é preferível aos nomes de compatibilidade.
- `event` e `volunteer` são opcionais por item. Voluntário é vinculado à própria conta e não pode registrar por outra pessoa. Administrador pode definir proprietário ou deixá-lo nulo.
- Novo vínculo com evento exige estado `Agendado` ou `Em andamento`, praia e dia iguais aos do evento; inscrição não é requisito. Criação não exige `version`.

Sucesso: **201**, array de respostas `TrashCollection`, na ordem enviada, com IDs, versões e pesos individuais. Não há header `Location` do lote. Cada item gera sua entrada de auditoria `COLLECTION/CREATE`.

O serviço executa todos os itens e sua auditoria na mesma transação. Se qualquer item falhar, nenhum item do lote é gravado, inclusive quando o erro ocorre depois de um item válido. Não existe resposta de sucesso parcial.

| HTTP | Situação |
| --- | --- |
| 400 | Lista vazia, mais de 50 itens, item nulo, material ausente/repetido, peso/data ou outro dado inválido |
| 401 | Autenticação ausente, inválida ou expirada |
| 403 | Proprietário não autorizado |
| 404 | Referência inexistente |
| 409 | Conflito de estado, catálogo unificado ou integridade |

A operação não é idempotente: repetir um lote válido cria novos registros. Após falha de conexão, confira o histórico antes de reenviar. Os formatos de erro seguem o contrato geral `StandardError`/`ValidationError`.

## Migrações disponíveis

| Versão | Conteúdo |
| --- | --- |
| V1 | Esquema inicial |
| V2 | Índices de consulta |
| V3 | Versão concorrente das coletas |
| V4 | Versão concorrente dos eventos |
| V5 | Catálogos, aliases e referências de praias/materiais |
| V6 | Associação dos nomes antigos aos catálogos, em Java |
| V7 | Pesos decimais e auditoria da conversão, em Java |
| V8 | Sessões renováveis e hashes de refresh tokens |
| V9 | Recuperação, confirmação e limitação de tentativas |
| V10 | Auditoria administrativa |
| V11 | Correção da semântica temporal |
| V12 | Estado/cidade das praias e índice de localização |
| V13 | Base inicial de praias com município/UF, em Java |
| V14 | Opções iniciais de materiais, em Java |

V14 oferece **Plástico, Vidro, Metal, Papel, Papelão, Borracha, Tecido, Resíduos orgânicos e Resíduos mistos**. Antes de inserir, verifica o alias normalizado; respeita nomes já reconhecidos, inclusive renomeações e unificações. Não renomeia materiais existentes, não reatribui coletas e não altera pesos. A rotina de seed pode ser reaplicada sem duplicar seus materiais. O perfil H2 `test` também carrega essas opções; em MySQL, Flyway aplica V14 uma vez.

V13 contém uma base inicial de praias, não um cadastro nacional completo. V12/V13 não deduzem a localização de registros antigos. Administradores podem cadastrar praias e materiais adicionais.

## Atualização de um ambiente

Empacote a versão atual e preserve o backup e a configuração existente do banco. Em `mysql`/`prod`, Flyway aplica apenas as migrações pendentes até V14 e Hibernate valida o esquema. Um banco já na V13 recebe V14; um banco anterior recebe as pendências intermediárias. Na operação com migrações externas, execute-as com a conta de manutenção antes de iniciar o runtime, que valida o histórico. Não reescreva migrações aplicadas nem recrie o banco para atualizar.

Confira `flyway_schema_history`, os catálogos públicos e uma coleta após atualizar. Reinicie a aplicação e recarregue o navegador para obter os formulários atuais. A recuperação especial da V8 permanece descrita na [etapa 34](34_CORRECAO_TIMESTAMP_E_RECUPERACAO_V8.md); o procedimento de instalação anterior está na [etapa 25](25_CONSOLIDACAO_DA_BASE.md).

## Verificações

As expectativas dos testes agora incluem a sequência V1–V14 e oito migrações pendentes ao atualizar uma base na V6 (V7–V14). O cenário MySQL que preserva dados ao atualizar bases antigas compara a lista completa até V14, espera nove materiais após V14 e confere os quatro aliases antigos separadamente dos aliases adicionados por V13/V14. Após reiniciar, verifica as duas praias antigas sem geografia e os nove materiais, em vez de confundir todos os cadastros sem cidade. Permanecem as verificações de pesos, vínculos, credenciais, versões e reaplicação sem pendências.

`tools/validate_openapi.py` compara rotas documentadas com os controllers e a extensão `x-database-migrations` com os arquivos SQL/Java. Uma futura migração exige atualizar o contrato; metadados desatualizados fazem a validação falhar.

```powershell
.\mvnw.cmd -B test
python tools/validate_openapi.py
python -m unittest discover -s tools -p 'test_*.py'
node --test tools/portal-api.test.cjs
```

Para conferir em MySQL 8.0 descartável, use `tools/run_mysql_tests.ps1`; ele cria instância própria e não usa o banco habitual da aplicação. As regras HTTP do lote são cobertas em `CatalogIntegrationTests`; V14 e preservação de nomes são cobertas em `CatalogMigrationTests`. O roteiro `node tools/verify_portal_browser.cjs`, após empacotar, verifica seleção de praias e múltiplos materiais nas duas telas de coleta.

Resultados desta consolidação em 19/10/2026:

| Verificação | Resultado |
| --- | --- |
| Suíte Java padrão com H2 | 149 testes, sem falhas, erros ou ignorados |
| MySQL Community 8.0.46 temporário | 4 cenários selecionados, sem falhas, erros ou ignorados; `verify` e empacotamento aprovados |
| Cliente JavaScript | 29 testes aprovados |
| Ferramentas Python | 12 testes aprovados |
| OpenAPI | 29 caminhos e 40 operações, referências/rotas e migrações aprovadas |
| Conferência negativa do validador | Rejeitou versão mais recente desatualizada, lista incompleta de migrações e ausência da rota de lote |

A seleção MySQL executou a atualização de bases V2 e V4 com preservação dos dados, a conversão decimal desde V6 e a instalação/reinicialização com autenticação e restrições. Não foi necessário acessar o banco habitual da aplicação. Os resultados locais não equivalem a uma execução do CI remoto.

A [validação integrada com Java 25](39_VALIDACAO_COMPLETA_JAVA25_MYSQL_E_NAVEGADOR.md) registra a rodada posterior da suíte completa: 168 testes Java, incluindo todos os 19 cenários MySQL, seguidos dos fluxos de voluntário e administrador no navegador. Ela também documenta o isolamento do teste histórico da V11 e as evidências locais.

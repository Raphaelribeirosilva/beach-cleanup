# Etapa 13 — Contrato OpenAPI

O arquivo [openapi.json](openapi.json) descreve as 29 operações atuais em 18 caminhos: autenticação, usuários, eventos, inscrições, coletas, catálogos e conferência de qualidade/arredondamentos, incluindo as três buscas paginadas. Usa [OpenAPI 3.0.3](https://spec.openapis.org/oas/v3.0.3.html), com esquemas de entrada/saída, parâmetros, respostas, JWT Bearer e permissões descritas por operação.

## Como usar

1. Importe `docs/openapi.json` como definição OpenAPI em uma ferramenta de API compatível ou abra o arquivo em um editor OpenAPI.
2. Ajuste o servidor `http://localhost:8080` à porta e ao endereço usados na execução da aplicação.
3. Faça login por `POST /auth/login` com sua conta. Use o `accessToken` retornado na autenticação Bearer da ferramenta. O token pertence à sessão de uso e não deve ser salvo no contrato ou no Git.
4. Use `/search` para paginação e filtros; as listagens originais retornam arrays completos.

É um contrato estático versionado no repositório. Esta etapa não adiciona Swagger UI, `/v3/api-docs` ou uma rota para servir o arquivo na aplicação. A importação e a navegação do contrato podem ser feitas sem iniciar a API; chamadas HTTP exigem a aplicação em execução.

## Regras documentadas

- Login e cadastro são públicos, assim como leitura/busca de eventos e listagem dos catálogos. Demais operações exigem JWT; restrições de administrador e propriedade estão descritas em cada operação.
- As listas de praias e materiais também são públicas. Cadastro, edição e unificação de catálogos exigem administrador. Eventos recebem `beachId`; coletas recebem `beachId` e `materialId`. Nomes antigos são aceitos somente quando já cadastrados como principais ou aliases; itens desconhecidos não são criados automaticamente.
- PUTs de usuários, eventos, coletas e catálogos exigem a `version` obtida na leitura. Ausência/valor inválido retorna 400; versão desatualizada retorna 409. Criação dispensa versão. Unificação exige `version` da origem e `targetVersion` do destino. Não há ETag/If-Match; veja a [etapa 23](23_PROTECAO_DE_FORMULARIOS_ANTIGOS.md).
- Trocar a senha revoga os tokens anteriores, incluindo o usado na troca; o cliente precisa fazer novo login.
- Voluntários consultam e alteram somente suas próprias coletas. O administrador pode transferir ou remover o proprietário. Referências opcionais aceitam `null`; os PUTs substituem os campos de entrada.
- Novos eventos começam como `Agendado`; transições seguem a [etapa 9](09_STATUS_DOS_EVENTOS.md). Novas inscrições e vínculos de coleta exigem evento `Agendado` ou `Em andamento`.
- Inscrições são idempotentes. Cancelar inscrição preserva coletas e é permitido em qualquer estado do evento. Inscrição não é requisito para registrar coleta.
- `totalCollectedKg` vem da soma das coletas existentes, inclusive nas respostas paginadas. A resposta de usuário não inclui senha, perfil ou versão interna das credenciais; o perfil aparece na resposta de login.
- Datas de coleta são ISO (`YYYY-MM-DD`). Data/hora de evento é local, sem offset; não usa o formato OpenAPI `date-time`, que pressupõe semântica RFC 3339.
- Pesos de entrada: 0,001 a 100.000 kg, resolução 0,001 kg; excesso de precisão retorna 400. Coletas não podem ter data futura em São Paulo e devem coincidir com praia/dia do evento vinculado. Datas históricas são permitidas a partir de 1900. Veja [qualidade dos dados](26_QUALIDADE_DOS_DADOS.md).
- `/admin/data-quality` e `/admin/data-quality/rounding` são GETs paginados exclusivos do administrador. A auditoria histórica envia pesos/diferenças em strings decimais para preservar precisão; as respostas correntes de coleta/total continuam usando números JSON.
- Senhas possuem limite de 72 **bytes UTF-8**, não de 72 caracteres. O limite e os retornos 400/401 estão descritos nos campos correspondentes.
- Erros usam `StandardError`; falhas de validação por campo acrescentam `errors` com `fieldName` e `message`. JSON/tipos inválidos podem retornar 400 sem essa lista.

## MySQL e dados existentes

A base atual possui V1–V7. A instalação e a atualização de bancos com histórico Flyway anterior aos catálogos estão documentadas na [consolidação](25_CONSOLIDACAO_DA_BASE.md); a conversão decimal e conferência dos legados estão na [etapa 26](26_QUALIDADE_DOS_DADOS.md). O restante desta seção registra o contexto histórico da entrega inicial do OpenAPI.

O ambiente persistente permanece o [MySQL local validado pelo usuário](12_MYSQL_LOCAL_VALIDACAO_MANUAL.md). Nenhuma mudança de esquema é necessária nesta etapa: não há migração nova nem alteração em V1/V2, configuração do banco ou dados.

O contrato não inclui senhas, chave JWT ou tokens reais. A validação manual do MySQL continua sendo o relato do usuário; o Codex não acessou esse servidor nesta etapa.

## Validador e resultados históricos da entrega inicial

Execute da raiz do projeto:

```text
python tools/validate_openapi.py
```

O verificador usa somente a biblioteca padrão do Python. Confere a leitura do JSON, referências locais, IDs únicos de operações, parâmetros de caminho, respostas sem corpo para 204, distinção entre operações públicas/autenticadas e cobertura dos mapeamentos nos controllers Java.

Resultado em 11/10/2026: **13 caminhos e 23 operações, estrutura e cobertura aprovadas**. A revisão dos DTOs, services e segurança fundamenta os campos e regras descritos. Este verificador não substitui validação integral pelo esquema oficial OpenAPI, nem testes HTTP: não compara automaticamente todas as restrições e regras de negócio. Nenhum teste foi executado no MySQL do usuário. A suíte Maven não foi executada nesta etapa, que altera somente documentação e sua ferramenta de verificação.

Ao mudar rotas ou DTOs, atualize o contrato junto da implementação. O documento `07_ANALISE_ATUAL_E_PROXIMO_PASSO.md` é um diagnóstico anterior: totais, inscrições, estados, persistência e buscas já foram implementados nas etapas 7 a 11; o contrato OpenAPI completa o item de documentação da API daquela sequência.

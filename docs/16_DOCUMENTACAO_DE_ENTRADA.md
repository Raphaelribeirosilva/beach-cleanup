# Etapa 16 — Documentação de entrada e prioridades atuais

Data: 14/10/2026.

## O que foi feito

O novo [README da raiz](../README.md) passa a ser o ponto de entrada para execução e consulta do projeto. Consolida requisitos, Maven Wrapper, inicialização pelo JAR ou IDE, configuração dos perfis, variáveis MySQL/JWT, exemplos PowerShell e regras principais da API.

O README diferencia o perfil Spring `mysql` do perfil Maven `mysql-it`, registra as migrações V1/V2/V3 e explica a execução dos testes em banco descartável. Inclui links para o contrato OpenAPI, instruções de testes/CI e as etapas técnicas atuais.

As análises antigas são identificadas como históricas. Suas recomendações sobre totais, inscrições, estados, persistência, paginação e OpenAPI já foram implementadas. O resultado de 67 testes da etapa 15 é apresentado como uma verificação anterior, sem atribuí-lo a uma nova execução nesta etapa documental.

O arquivo `docs/README.md` já possui uma alteração local anterior, preparada no índice Git. Ele foi preservado integralmente. O README da raiz fornece o índice atual sem incluir nem sobrescrever essa alteração no commit desta etapa.

## Estado atual

- CRUD com DTOs, validações e erros padronizados.
- BCrypt, JWT, autorização por perfil/proprietário e troca de senha com revogação de tokens.
- Total por usuário calculado a partir das coletas existentes, incluindo transferências e exclusões.
- Inscrições idempotentes e validação dos estados/transições dos eventos.
- Perfil MySQL persistente com Flyway e validação de esquema.
- Buscas com filtros/paginação e contrato OpenAPI estático.
- Controle otimista de versão e autorização transacional para alterações/exclusões de coletas.
- Testes H2/MySQL real, script de instância temporária e workflow CI.

## Próximas etapas

| Ordem | Etapa | Resultado esperado |
|---|---|---|
| 1 | Implantação e operação | Definir configuração de execução com perfil persistente explícito, verificação de saúde, logs úteis e procedimento de backup/restauração. Documentar o destino de implantação e verificar a configuração antes de publicar. |
| 2 | Interface de uso | Criar fluxos de cadastro/login, consulta e inscrição em eventos, registro de coletas e administração, consumindo as buscas paginadas. Definir CORS conforme a origem da interface. |
| 3 | Relatórios de impacto | Definir e implementar agregações por evento, praia, material e período, preservando as permissões dos dados. |

Durante essas etapas, avaliar paginação das listagens antigas, precisão/limites de peso e restrições adicionais no banco conforme a necessidade. Mudanças de esquema devem usar novas migrações e preservar os registros existentes.

A implantação não é iniciada por esta revisão. O destino de hospedagem ainda não está configurado no repositório. O workflow CI está versionado; sua publicação e execução no GitHub dependem do envio do commit.

## Verificação

As instruções foram conferidas contra `pom.xml`, propriedades de aplicação, Maven Wrapper, DTOs, controllers, serviços e os documentos das etapas 6 a 15. O comando `mvnw.cmd -v` confirmou Maven 3.9.16 e Java 26.0.1 neste ambiente; o projeto compila para Java 25. No sandbox, a primeira tentativa do wrapper falhou; a repetição autorizada fora dele passou.

Verificação concluída: **21 links locais válidos**, **dois exemplos JSON** conferidos contra os esquemas OpenAPI e sintaxe dos blocos PowerShell aprovada pelo parser. O verificador OpenAPI aprovou **13 caminhos e 23 operações**. As diferenças também passaram em `git diff --check`. As requisições dos exemplos não foram executadas por HTTP nesta etapa.

A suíte de código não é reexecutada por esta alteração documental; seus resultados anteriores estão na [etapa 15](15_TESTES_MYSQL_E_CI.md).

## Git

O commit desta etapa contém somente os dois documentos novos. Alterações locais preexistentes, incluindo o índice da pasta `docs`, são preservadas. Push depende de autorização explícita.

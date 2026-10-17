# Etapa 10 — Perfil MySQL e migrações

## Configuração

O perfil `mysql` usa banco persistente, Flyway e validação do esquema pelo Hibernate. O perfil `test` continua como padrão para desenvolvimento, mas pode ser substituído por `SPRING_PROFILES_ACTIVE=mysql`; não é mais fixado como perfil ativo no arquivo base.

Configure externamente:

- `SPRING_PROFILES_ACTIVE=mysql`
- `DB_URL`: URL JDBC do banco criado para a aplicação, por exemplo `jdbc:mysql://localhost:3306/beachcleanup`
- `DB_USER` e `DB_PASSWORD`: credenciais de uma conta com acesso a esse banco
- `JWT_SECRET`: ao menos 32 bytes aleatórios, codificados em Base64; preserve a mesma chave entre reinícios

O banco deve existir antes da inicialização. O usuário precisa de permissões para as migrações. O projeto não provisiona servidor, banco ou conta MySQL e não inclui credenciais reais. O console H2 e logs SQL estão desabilitados neste perfil; as respostas são montadas sem Open Session in View.

## Migrações

| Versão | Alteração |
| --- | --- |
| V1 | Contas, eventos, inscrições e coletas, unicidade de e-mail e chaves estrangeiras |
| V2 | Índices de usuário, evento e datas |
| V3 | `row_version` nas coletas, inicialmente zero |
| V4 | `row_version` nos eventos, inicialmente zero |
| V5 | Catálogos, aliases, referências e índices em eventos/coletas |
| V6 | Migração Java: associa textos antigos a praias/materiais com IDs estáveis |
| V7 | Migração Java: `weight_kg` para `DECIMAL(38,3)`, original/diferença em auditoria e incremento de versão se o peso mudar |

Os scripts SQL ficam em `src/main/resources/db/migration/mysql`; V6/V7 ficam em `src/main/java/db/migration/mysql` e são descobertas no JAR compilado. A coluna legada do total é preservada, mas o total da API continua derivado das coletas com aritmética decimal. Usuários já possuem `row_version` em V1. O campo público `version` das edições corresponde à versão persistida; não é a versão das credenciais.

Flyway aplica versões pendentes na inicialização e valida os checksums. Hibernate usa `ddl-auto=validate`, sem recriar ou atualizar o esquema por conta própria. Migração automática de banco não vazio sem histórico (`baseline-on-migrate`) e limpeza (`clean`) estão desabilitadas.

V1–V7 inicializam **um banco vazio** e atualizam bancos com histórico Flyway compatível, aplicando apenas versões pendentes. A atualização de bases V2, V4 e V6 com dados é coberta pela suíte MySQL. Para banco legado sem histórico Flyway, faça uma migração específica dos nomes antigos, dados e restrições antes de adotá-las. Não edite migrações já aplicadas; crie uma nova versão para cada alteração. Confira o [arredondamento e a revisão da V7](26_QUALIDADE_DOS_DADOS.md), incluindo a necessidade de backup e ensaio em cópia separada.

V6 preserva os textos originais, pesos, datas, contas, hashes, versões, inscrições e vínculos. Agrupa apenas variações de caixa, acentos e espaços; `Morro` e `Praia do Morro` continuam distintos até unificação administrativa. Campos antigos nulos/vazios continuam sem referência e exigem revisão. Um banco novo não recebe catálogos nem contas demonstrativas. Veja o [procedimento reproduzível de instalação/atualização e os resultados em MySQL real](25_CONSOLIDACAO_DA_BASE.md).

O perfil MySQL não carrega contas de exemplo. Cadastre a primeira conta por `POST /users`; ela recebe `VOLUNTEER`. Para definir o primeiro administrador, um operador do banco deve promover uma conta específica após verificar sua identidade, por exemplo `UPDATE tb_user SET role = 'ADMIN' WHERE id = <ID_VERIFICADO>`. A API não permite escolher o perfil no cadastro. Não use contas/senhas de exemplo em um ambiente persistente com dados reais.

## Verificação histórica da etapa 10

O usuário posteriormente configurou o MySQL local e confirmou cadastro, login e persistência da conta após reinício. O ambiente e o relato dessa validação manual estão no [registro do MySQL local](12_MYSQL_LOCAL_VALIDACAO_MANUAL.md).

`PersistentDatabaseIntegrationTests` inicia a aplicação duas vezes usando o perfil `mysql`, com conexão substituída por H2 em arquivo no modo MySQL. Executa as migrações reais, valida o esquema e verifica persistência de conta, senha, evento, inscrição, coleta e total após reinício; também verifica ausência do seed, bloqueio de limpeza, unicidade e integridade dos vínculos.

Esse teste **não substitui uma validação contra um servidor MySQL real**. A conexão ao banco de destino depende de sua configuração externa. O teste usa uma chave JWT exclusiva de teste e dados temporários.

Suíte completa: **BUILD SUCCESS — 53 testes, zero falhas e zero erros**, em 10/10/2026. O teste de persistência passou, incluindo reinício com os dados preservados.

Referências oficiais: [inicialização e migrações no Spring Boot](https://docs.spring.io/spring-boot/4.1/how-to/data-initialization.html) e [suporte MySQL do Flyway](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql).

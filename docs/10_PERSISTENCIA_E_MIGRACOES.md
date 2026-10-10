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

`db/migration/mysql/V1__create_schema.sql` cria contas, eventos, inscrições e coletas com IDs, unicidade de e-mail, chave composta das inscrições e chaves estrangeiras. `V2__add_query_indexes.sql` adiciona índices para consulta por usuário, evento e datas. A coluna legada do total é preservada, mas o total da API continua derivado das coletas.

Flyway aplica versões pendentes na inicialização e valida os checksums. Hibernate usa `ddl-auto=validate`, sem recriar ou atualizar o esquema por conta própria. Migração automática de banco não vazio sem histórico (`baseline-on-migrate`) e limpeza (`clean`) estão desabilitadas.

Essas migrações inicializam **um banco vazio**. Para um banco persistente anterior, faça uma migração específica dos nomes antigos, dados e restrições antes de adotá-las. Não edite migrações já aplicadas; crie uma nova versão para cada alteração. Nenhum banco externo foi alterado nesta etapa.

O perfil MySQL não carrega contas de exemplo. Cadastre a primeira conta por `POST /users`; ela recebe `VOLUNTEER`. Para definir o primeiro administrador, um operador do banco deve promover uma conta específica após verificar sua identidade, por exemplo `UPDATE tb_user SET role = 'ADMIN' WHERE id = <ID_VERIFICADO>`. A API não permite escolher o perfil no cadastro. Não use contas/senhas de exemplo em um ambiente persistente com dados reais.

## Verificação

O usuário posteriormente configurou o MySQL local e confirmou cadastro, login e persistência da conta após reinício. O ambiente e o relato dessa validação manual estão no [registro do MySQL local](12_MYSQL_LOCAL_VALIDACAO_MANUAL.md).

`PersistentDatabaseIntegrationTests` inicia a aplicação duas vezes usando o perfil `mysql`, com conexão substituída por H2 em arquivo no modo MySQL. Executa as migrações reais, valida o esquema e verifica persistência de conta, senha, evento, inscrição, coleta e total após reinício; também verifica ausência do seed, bloqueio de limpeza, unicidade e integridade dos vínculos.

Esse teste **não substitui uma validação contra um servidor MySQL real**. A conexão ao banco de destino depende de sua configuração externa. O teste usa uma chave JWT exclusiva de teste e dados temporários.

Suíte completa: **BUILD SUCCESS — 53 testes, zero falhas e zero erros**, em 10/10/2026. O teste de persistência passou, incluindo reinício com os dados preservados.

Referências oficiais: [inicialização e migrações no Spring Boot](https://docs.spring.io/spring-boot/4.1/how-to/data-initialization.html) e [suporte MySQL do Flyway](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql).

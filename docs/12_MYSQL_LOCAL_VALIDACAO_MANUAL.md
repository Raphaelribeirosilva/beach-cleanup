# MySQL local — configuração e validação manual

Registro: 10/10/2026. Fonte: configuração e validação relatadas pelo usuário.

## Ambiente em uso

- Banco: `beachcleanup`.
- Servidor: `localhost:3306`.
- Execução da aplicação: Eclipse.
- Perfil ativo na execução: `SPRING_PROFILES_ACTIVE=mysql`.
- `DB_URL`, `DB_USER`, `DB_PASSWORD` e `JWT_SECRET` estão configurados nas variáveis de ambiente da execução no Eclipse.

Este documento registra somente os nomes das variáveis. Não contém senhas, valores da chave JWT ou tokens. A configuração do perfil e as migrações estão descritas na [etapa 10](10_PERSISTENCIA_E_MIGRACOES.md).

## Validação manual realizada pelo usuário

O usuário informou que:

1. A aplicação iniciou com o perfil `mysql` e o banco local configurado.
2. Conseguiu cadastrar um usuário e fazer login.
3. Reiniciou a aplicação.
4. Conseguiu fazer login novamente com o mesmo usuário após o reinício, confirmando a persistência dessa conta.

Essa validação foi executada pelo usuário. O Codex apenas registrou o relato; não executou testes nem acessou esse servidor MySQL para produzir este registro. O resultado complementa os testes automatizados com H2 documentados na etapa 10 e confirma os fluxos de cadastro, login e persistência da conta relatados acima.

## Orientação para próximas alterações

O MySQL local já contém dados e deve ser considerado nas próximas alterações do projeto.

- Preserve os registros existentes, incluindo contas, hashes de senha, IDs, perfis, eventos, inscrições, coletas e vínculos.
- Para mudanças no esquema, crie novas migrações Flyway em `src/main/resources/db/migration/mysql`, com versão superior às existentes.
- Não edite migrações já aplicadas. As migrações `V1` e `V2` existentes no repositório devem permanecer intactas; a próxima versão disponível atualmente é `V3`.
- Mantenha o perfil persistente com `spring.jpa.hibernate.ddl-auto=validate`; não use recriação do esquema, Flyway `clean` ou reinicialização de dados para implementar alterações.
- Mudanças de nomes, tipos ou restrições devem migrar os dados existentes de forma compatível, sem presumir que o banco esteja vazio.
- Registre separadamente testes executados pelo Codex e validações relatadas pelo usuário, identificando o banco usado em cada caso.

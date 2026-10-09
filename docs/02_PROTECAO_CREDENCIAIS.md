# Etapa 2 — Proteção de credenciais

## O que foi implementado

- Senhas de novos voluntários recebem hash BCrypt antes de serem persistidas.
- Os dados iniciais do perfil de teste também armazenam hash.
- Todas as respostas de voluntários, eventos e coletas usam DTOs, incluindo voluntários aninhados.
- A propriedade `password` é somente de entrada na serialização JSON da entidade e do DTO de cadastro.
- A edição do perfil preserva exatamente o hash existente.
- Senhas acima do limite de 72 bytes em UTF-8 recebem 400 sem serem salvas.

A etapa 1 e a análise inicial permanecem como registros históricos. Este documento descreve o comportamento atualizado.

## Dependência e configuração

O `pom.xml` agora declara `org.springframework.security:spring-security-crypto`, com versão gerenciada pelo parent Spring Boot. Na execução desta etapa foi resolvida a versão `7.1.1`.

`config/PasswordConfig.java` fornece um bean `PasswordEncoder` com `BCryptPasswordEncoder(12)`. O número 12 configura o custo do algoritmo. BCrypt produz um hash com salt aleatório: duas senhas iguais podem gerar hashes diferentes e continuam verificáveis com `matches`. O hash é uma transformação de mão única; não há operação para recuperar a senha original. Referência: [armazenamento de senhas no Spring Security](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html).

Foi adicionado o módulo de criptografia. Login, permissões, JWT e filtros de autenticação continuam para uma etapa própria; não foi adicionado o starter completo de segurança.

## Cadastro e armazenamento

O contrato de entrada de `POST /volunteers` permanece:

```json
{
  "name": "Ana",
  "email": "ana@example.com",
  "password": "senha-de-exemplo"
}
```

O fluxo é:

1. O controller recebe `VolunteerInsertDTO` e mantém a validação de campos obrigatórios/e-mail.
2. O service verifica se a senha está presente e dentro do limite em bytes.
3. `passwordEncoder.encode(password)` gera o hash.
4. A entidade é persistida com o hash no campo `password`.
5. A resposta retorna `VolunteerDTO`, sem senha ou hash.

O cliente deve enviar a senha original no cadastro. Não deve calcular ou enviar um hash como substituto. Um exemplo de resposta:

```json
{
  "id": 2,
  "name": "Ana",
  "email": "ana@example.com",
  "totalTrashCollecting": 0.0
}
```

A verificação futura de uma senha deve usar:

```java
passwordEncoder.matches(senhaRecebida, volunteer.getPassword());
```

Gerar outro hash e compará-lo por igualdade não funciona como verificação, pois o salt é aleatório.

## Limite da senha

BCrypt aceita até 72 bytes. A verificação usa `StandardCharsets.UTF_8`, pois caracteres acentuados e emojis podem ocupar mais de um byte. Entradas maiores recebem 400 antes do hash e da gravação; a senha não é truncada.

A mensagem informa o limite sem incluir a senha recebida. O formato global dos erros de validação continua pertencendo à próxima etapa. A implementação e o limite podem ser conferidos no [código oficial do BCrypt](https://github.com/spring-projects/spring-security/blob/7.1.1/crypto/src/main/java/org/springframework/security/crypto/bcrypt/BCrypt.java).

## Respostas da API

| Recurso | DTO de saída | Campos |
|---|---|---|
| Voluntário | `VolunteerDTO` | `id`, `name`, `email`, `totalTrashCollecting` |
| Evento | `CleanupEventDTO` | `id`, `title`, `location`, `dateTime`, `status` |
| Coleta | `TrashCollectionDTO` | `id`, `beach`, `materialType`, `weithKg`, `regitrationDate`, `volunteer`, `event` |

Na coleta, `volunteer` é um `VolunteerDTO` e `event` é um `CleanupEventDTO`. Associações nulas continuam representadas como nulas. Os nomes dos campos de peso e data foram mantidos.

Exemplo:

```json
{
  "id": 2,
  "beach": "Praia do Morro",
  "materialType": "Plástico",
  "weithKg": 2.5,
  "regitrationDate": "2026-10-15",
  "volunteer": {
    "id": 2,
    "name": "Ana",
    "email": "ana@example.com",
    "totalTrashCollecting": 0.0
  },
  "event": {
    "id": 2,
    "title": "Mutirão",
    "location": "Praia do Morro",
    "dateTime": "2026-10-15T08:00:00",
    "status": "Agendado"
  }
}
```

A mudança se aplica às listagens, consultas individuais, cadastros e atualizações. Em especial, a consulta individual e o PUT de voluntários deixam de retornar a entidade, e o voluntário de uma coleta deixa de expor `password` e a lista de eventos. As rotas, métodos HTTP e status de sucesso continuam os definidos na etapa 1.

`Volunteer.password` e `VolunteerInsertDTO.password` também possuem `@JsonProperty(access = WRITE_ONLY)`. Assim, a senha ainda pode ser recebida no JSON, mas é omitida mesmo se esses objetos forem serializados diretamente.

## Atualização do perfil

`PUT /volunteers/{id}` continua alterando apenas nome e e-mail. Se o corpo incluir uma senha, ela não substitui o hash salvo. O hash também não é gerado novamente durante essa operação.

Não foi criado endpoint de troca de senha. Esse fluxo deve ser definido junto com autenticação e verificação de identidade.

## Dados de exemplo e registros anteriores

`TestConfig` aplica o mesmo bean de BCrypt à senha de exemplo antes de chamar o repository. Seus valores de domínio foram preservados.

O banco configurado é H2 em memória. Ao reiniciar a aplicação, os registros iniciais são recriados com hash. Esta mudança não converte automaticamente registros antigos de um banco persistente: caso existam senhas anteriores em texto puro, será necessário um processo controlado de conversão ou redefinição antes de utilizá-las em um futuro login. A aplicação não oferece fallback de autenticação com texto puro.

Os repositories continuam sendo a infraestrutura JPA; a codificação ocorre no service de cadastro e explicitamente no seed. Novos fluxos que criem credenciais devem seguir essa mesma regra.

## Testes

Os testes de CRUD foram atualizados para:

- Verificar que o valor persistido difere da senha original.
- Confirmar `matches` para a senha correta e rejeição de outra senha.
- Garantir que PUT preserva exatamente o hash existente.
- Verificar ausência de qualquer campo `password`, inclusive nos objetos aninhados, nas respostas de criação, consulta, listagem e atualização.
- Criar os voluntários de teste pelo service para exercitar o armazenamento com hash.

Foram adicionados cenários para:

- Duas senhas iguais com hashes diferentes e verificações bem-sucedidas.
- Senha de 72 bytes aceita e senhas maiores rejeitadas, incluindo caracteres multibyte.
- Nenhum registro adicional criado quando a senha é rejeitada.
- Serialização direta da entidade e do DTO de entrada sem senha/hash, mantendo a desserialização da senha.

O teste de contexto também verifica que os voluntários do seed possuem hash válido.

Comando usado neste ambiente, com o Maven instalado e o cache configurado:

```powershell
& 'C:\Program Files\Apache NetBeans\java\maven\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\EXEMPLO\.m2\repository' -o -B test
```

Resultado final: **BUILD SUCCESS — 13 testes, 0 falhas, 0 erros e 0 ignorados** (1 teste de contexto/seed e 12 testes de integração do CRUD e da proteção de credenciais).

## Arquivos desta etapa

Sob `src/main/java/com/raphael/beachcleanup/`:

- Criado `config/PasswordConfig.java`.
- Alterado `config/TestConfig.java`.
- Alterados `services/VolunteerService.java`, `entities/Volunteer.java` e `dto/VolunteerInsertDTO.java`.
- Criados `dto/CleanupEventDTO.java` e `dto/TrashCollectionDTO.java`.
- Alterados os três controllers em `resources/`.

Também foram alterados `pom.xml`, os dois testes existentes e o índice `docs/README.md`, além da criação deste documento.

## Próxima etapa

Completar validações de cadastro/atualização e padronizar os erros da API. Autenticação, autorização, redefinição/troca de senha e migrações de banco permanecem etapas posteriores.

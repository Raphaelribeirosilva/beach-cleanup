# Exemplos de buscas paginadas

Use método GET e, nas rotas protegidas, o header `Authorization: Bearer <accessToken>` obtido por `POST /auth/login`. No Postman, coloque os filtros na aba Params para que textos, espaços e acentos sejam codificados na URL.

## Eventos — sem necessidade de login

```http
GET http://localhost:8080/cleanupEvent/search?location=Praia&page=0&size=10&sortBy=dateTime&direction=asc
```

Para restringir por estado e período, acrescente os parâmetros:

| Parâmetro | Exemplo |
|---|---|
| `status` | `Agendado` |
| `from` | `2026-10-15T00:00:00` |
| `to` | `2026-10-31T23:59:59` |

## Coletas — login de voluntário ou administrador

```http
GET http://localhost:8080/trashCollection/search?beach=Praia&materialType=Vidro&from=2026-10-01&to=2026-10-31&page=0&size=10
Authorization: Bearer <accessToken>
```

Com token de voluntário, o resultado inclui somente suas coletas. Com token administrativo, acrescente `volunteerId=<ID>` ou `eventId=<ID>` para filtrar os vínculos.

## Contas — login de administrador

```http
GET http://localhost:8080/users/search?name=Ana&email=example.com&page=0&size=10&sortBy=name&direction=asc
Authorization: Bearer <accessToken>
```

Os dois filtros devem corresponder à mesma conta. O total coletado apresentado é a soma completa do usuário, não apenas das coletas que apareceriam em outra busca.

## Navegação e erros

Leia `content` para obter os registros e `totalPages` para saber o número de páginas. Avance incrementando `page`; a última página com registros normalmente é `totalPages - 1`. Não há resultados quando `totalElements` é zero.

- 400: página/tamanho/ordenação, datas, status ou IDs inválidos.
- 401: token ausente ou inválido nas rotas protegidas.
- 403: busca de contas sem perfil administrativo ou tentativa do voluntário de filtrar coletas de outra conta.

O contrato, as validações e a organização do código estão na [documentação da etapa 11](11_BUSCAS_PAGINADAS.md).

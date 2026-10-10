# Etapa 8 — Inscrição em eventos

## Endpoints

Todas as rotas exigem `Authorization: Bearer <accessToken>`. A conta vem do token; não é enviado ID do usuário nem corpo JSON. Voluntários e administradores gerenciam suas próprias inscrições.

| Método | URL | Resultado |
|---|---|---|
| GET | `/users/me/events` | 200, lista dos eventos em que a conta autenticada está inscrita, ordenada por ID; sem inscrições, `[]` |
| PUT | `/users/me/events/{eventId}` | 204, inscreve a própria conta; repetir não cria duplicidade |
| DELETE | `/users/me/events/{eventId}` | 204, cancela a própria inscrição; repetir para evento existente também retorna 204 |

Exemplo local: `PUT http://localhost:8080/users/me/events/1`, sem corpo, com o token do login. Consulte com `GET http://localhost:8080/users/me/events` e cancele com `DELETE http://localhost:8080/users/me/events/1`.

IDs inexistentes de evento retornam 404 na inscrição e no cancelamento; IDs não positivos ou com formato inválido retornam 400. Sem autenticação, as três rotas retornam 401. Não há rota para consultar ou editar inscrições de outra conta, inclusive por um administrador. A listagem retorna somente os campos de `CleanupEventDTO`, sem dados pessoais dos participantes.

## Persistência e limites desta etapa

As operações alteram a associação existente `User.events`/`CleanupEvent.users`, sincronizam os dois lados e executam em uma transação. O usuário recebe um bloqueio de escrita durante inscrição/cancelamento para serializar alterações concorrentes na sua coleção de eventos. O registro é recarregado após o bloqueio, pois a autenticação pode tê-lo carregado antes de outra operação terminar. A tabela `tb_event_volunteer`, seus vínculos e sua chave composta permanecem; não foi alterado o esquema.

Cancelar inscrição remove somente esse vínculo; coletas existentes e totais permanecem. A inscrição continua opcional para cadastrar coletas. Estados/transições do evento ainda não têm regra de domínio; esta etapa não restringe inscrição por status ou data. As políticas existentes de exclusão e conflitos de integridade permanecem.

## Verificação

`EventRegistrationIntegrationTests` cobre login real/JWT, ciclo de inscrição e cancelamento, repetição idempotente, listagem ordenada, isolamento de contas, tentativa de enviar ID alheio no corpo, autenticação, IDs inválidos/inexistentes, inscrição do administrador, preservação das coletas/totais, sincronização das associações e requisições simultâneas para o mesmo evento e para eventos distintos.

Suíte completa executada em 10/10/2026 com Maven offline: **BUILD SUCCESS — 48 testes, zero falhas, zero erros e zero ignorados**. Seis testes pertencem à nova suíte de inscrições.

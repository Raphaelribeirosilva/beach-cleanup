# Documentação do projeto

Esta pasta reúne o contrato vigente e os documentos históricos das etapas de implementação.

## Documentação vigente

- [Etapa 6 — Padronização de nomes](06_PADRONIZACAO_DE_NOMES.md): nomes atuais, rotas, campos JSON, permissões, exemplos de requisições, impacto no banco e testes.
- [Usuários de teste](06_USUARIOS_DE_TESTE.md): contas de exemplo, senhas iniciais e cenários com os nomes atuais.

## Histórico

Os documentos abaixo registram o comportamento e os nomes na época de cada etapa. Exemplos com `/volunteers`, `volunteerId`, `weithKg`, `regitrationDate` e `totalTrashCollecting` devem ser adaptados conforme a etapa 6. As regras de segurança continuam valendo, com a troca de senha da etapa 5.

- [Análise inicial do projeto](ANALISE_PROJETO.md): diagnóstico anterior às correções; preservado como referência histórica.
- [Etapa 1 — Correção do CRUD](01_CORRECAO_CRUD.md): alterações, endpoints, exemplos de requisições e testes.
- [Etapa 2 — Proteção de credenciais](02_PROTECAO_CREDENCIAIS.md): BCrypt, respostas por DTO e testes de proteção das senhas.
- [Etapa 3 — Validações e erros da API](03_VALIDACOES_E_ERROS.md): validações de cadastro/atualização, contrato de erros e testes de entradas inválidas.
- [Etapa 4 — Autenticação e autorização](04_AUTENTICACAO_E_AUTORIZACAO.md): login JWT, perfis, permissões, isolamento dos dados e configuração da chave.
- [Etapa 5 — Troca de senha](05_TROCA_DE_SENHA.md): conferência da senha atual, invalidação de tokens e proteção contra alterações concorrentes.

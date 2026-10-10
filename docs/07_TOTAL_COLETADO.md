# Etapa 7 — Total coletado por usuário

## Regra implementada

`totalCollectedKg` nas respostas da API representa a soma, em kg, de `weightKg` de todas as coletas atualmente vinculadas ao usuário. Sem coletas, o valor é zero. Coletas sem voluntário não contribuem para nenhum usuário.

A soma é calculada pelo banco ao montar as respostas. Não existe atualização incremental de um acumulador: cada leitura considera os registros confirmados, incluindo criação, alteração de peso, exclusão, transferência para outro usuário e remoção do vínculo pelo administrador. Assim, escritas concorrentes não disputam um contador separado.

O valor aparece nas respostas de cadastro, consulta, listagem e edição de usuários, e dentro de `volunteer` nas respostas de cadastro, consulta, listagem e edição de coletas. Nas listagens, uma consulta agregada calcula os totais dos usuários envolvidos, sem uma consulta de soma por item. Essa regra não altera as permissões existentes.

Exemplo: cadastrar coletas de 2 kg e 3,5 kg para Ana retorna total de 5,5 kg. Transferir a primeira coleta para Bruno resulta em 3,5 kg para Ana e 2 kg para Bruno. Excluir a última coleta de Ana resulta em zero.

## Compatibilidade e seed

O contrato JSON mantém `totalCollectedKg`. O cliente não pode definir esse valor por cadastro/edição do usuário.

A coluna legada `tb_user.total_collected_kg` permanece para compatibilidade do esquema, mas **não é usada como fonte do total nas respostas da API e não é mantida como acumulador**. Consultas SQL ou consumidores externos devem somar `tb_trash_collection.weight_kg` por `volunteer_id`, em vez de ler essa coluna. Uma remoção futura da coluna deve ter migração própria em ambientes persistentes.

Nenhuma migração de esquema é necessária nesta etapa. Totais antigos divergentes não afetam a API: o cálculo usa os registros de coleta existentes. Valores históricos sem uma coleta correspondente não entram na soma. O valor inicial legado do usuário Raphael foi alinhado à sua coleta de 15,5 kg.

Os documentos das etapas anteriores registram o comportamento à época; o significado atual do total é o descrito aqui.

## Verificação

`CollectedTotalsIntegrationTests` verifica múltiplas coletas, usuário sem coletas, alterações de peso, transferência, exclusão, vínculos opcionais, respostas de listagem, total legado divergente, tentativa de definir total pelo cliente, escritas rejeitadas e isolamento entre voluntários.

O teste de CRUD foi ajustado para esperar o total derivado no usuário aninhado da coleta. O teste de inicialização foi ajustado ao seed de 15,5 kg.

Suíte completa executada em 10/10/2026 com Maven offline: **BUILD SUCCESS — 42 testes, zero falhas, zero erros e zero ignorados**. Quatro desses testes pertencem à nova suíte de totais.

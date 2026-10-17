# Especificação — mini-autorizador-ex

## 1. Objetivo e escopo

Disponibilizar uma API REST para criar cartões, consultar seus saldos e autorizar transações de débito. Esta especificação consolida os requisitos fechados do enunciado e de `specs/constitution.md`; as pendências da seção 6 exigem decisão antes da implementação correspondente.

A solução deve respeitar a constituição, inclusive sua base tecnológica, segurança, restrição de fluxo e garantias de integridade. A declaração do serviço MySQL em `docker/docker-compose.yml` deve ser preservada. Não é obrigatório manter histórico de negócio das transações.

## 2. Requisitos funcionais

| ID | Comportamento esperado |
| --- | --- |
| RF-01 | Criar e persistir um cartão identificado por `numeroCartao`, associado à senha informada e com saldo inicial de R$ 500,00. O número deve ser único. |
| RF-02 | Recusar a criação de cartão já existente, preservando seus dados e saldo. |
| RF-03 | Consultar o saldo atual de um cartão existente; informar ausência quando o cartão não existir. |
| RF-04 | Para entradas válidas, autorizar uma transação somente quando o cartão existir, a senha corresponder e o saldo for maior ou igual ao valor solicitado. |
| RF-05 | Na aprovação, debitar exatamente o valor solicitado uma única vez. Na recusa, preservar o saldo. Usar valores monetários decimais exatos; o saldo nunca pode ficar negativo. |
| RF-06 | Preservar cartões e saldos após reinício da aplicação. |

## 3. Contratos HTTP

Endereço local: `http://localhost:8080`. Todas as operações exigem autenticação BASIC com usuário `username` e senha `password`. Credenciais ausentes ou inválidas resultam em HTTP `401`, sem executar a operação solicitada.

| Operação | Entrada | Sucesso | Recusa ou ausência |
| --- | --- | --- | --- |
| `POST /cartoes` | JSON com `numeroCartao` e `senha`, ambos strings. | `201`, JSON com os campos `senha` e `numeroCartao`. | Cartão existente: `422`, JSON com esses mesmos campos. |
| `GET /cartoes/{numeroCartao}` | Número do cartão no caminho. | `200`, corpo contendo somente o saldo decimal, com ponto como separador. | Cartão inexistente: `404`, sem corpo. |
| `POST /transacoes` | JSON com `numeroCartao` e `senhaCartao` como strings e `valor` como número. | `201`, corpo literal `OK`. | `422`, corpo contendo um único motivo: `CARTAO_INEXISTENTE`, `SENHA_INVALIDA` ou `SALDO_INSUFICIENTE`. |

Os nomes de campos, rotas, códigos e corpos acima são obrigatórios. O valor de `senha` na resposta de cartão duplicado e os detalhes de representação ainda não definidos estão em P-05 e P-06. A senha de autenticação BASIC é independente da senha do cartão.

## 4. Integridade, idempotência e segurança

- **Concorrência:** aprovações concorrentes, inclusive em instâncias diferentes, devem atualizar o saldo de forma atômica e consistente. Com saldo de R$ 10,00, duas operações distintas e válidas de R$ 10,00 devem produzir uma aprovação, uma recusa por `SALDO_INSUFICIENTE` e saldo final zero.
- **Idempotência:** repetições identificadas da mesma operação devem recuperar seu resultado concluído sem novo débito, inclusive entre instâncias e após reinício. O resultado durável e o débito devem permanecer consistentes diante de falhas. Identificação, retenção e demais condições dependem de P-07 a P-11; coincidência de cartão e valor não identifica repetição.
- **Compatibilidade:** as chamadas originais devem continuar funcionando sem novos campos ou identificadores obrigatórios. Registros técnicos de idempotência são permitidos.
- **Falhas:** distinguir falhas técnicas de recusas de autorização, impedir confirmação parcial e limitar eventuais retentativas de forma segura.
- **Senhas:** armazenar a senha do cartão com hash seguro e nunca registrá-la em logs. Retornar o campo `senha` somente nas respostas exigidas pelo contrato de criação de cartão.

## 5. Diretrizes de aceite

Testes automatizados devem comprovar resultados e invariantes, com alta cobertura, abrangendo:

1. Criação com saldo de R$ 500,00, consulta e duplicidade sem alteração do cartão existente.
2. Débitos sucessivos, débito igual ao saldo disponível e recusa por saldo insuficiente; senha inválida e cartão inexistente, respeitando a precedência a definir em P-04.
3. Contratos HTTP, autenticação nas três rotas e ausência de alteração de saldo nas recusas.
4. Persistência após reinício, precisão monetária, rollback e concorrência com MySQL e transações/conexões independentes que representem instâncias diferentes.
5. Repetição de operações sem débito adicional, inclusive com concorrência, reinício e falhas, após fechamento das políticas de idempotência.

## 6. Definições pendentes

Os pontos abaixo permanecem abertos; sugestões apresentadas para discussão não constituem requisitos aprovados.

- **P-01:** obrigatoriedade, formato e tamanho dos campos de cartão e senha.
- **P-02:** validade de `valor`, incluindo zero, negativos, casas decimais e limites.
- **P-03:** resposta para entradas inválidas ou JSON malformado.
- **P-04:** precedência quando mais de uma regra de autorização falhar.
- **P-05:** origem do campo `senha` na resposta de criação duplicada.
- **P-06:** tipos de mídia, apresentação do saldo e corpo de `401`.
- **P-07:** identificação da operação, escopo e chamadas sem identificador.
- **P-08:** equivalência do conteúdo e reutilização da identificação com dados diferentes.
- **P-09:** resultados preservados e reproduzidos pela idempotência.
- **P-10:** resposta a chamadas simultâneas da mesma operação ainda em processamento.
- **P-11:** prazo de retenção e comportamento após expiração da identificação.
- **P-12:** códigos e corpos das respostas a falhas técnicas.

# Especificação — mini-autorizador-ex

## 1. Objetivo e escopo

Disponibilizar uma API REST para criar cartões, consultar saldos e autorizar transações de débito, conforme `specs/constitution.md` e as decisões P-01 a P-12 consolidadas neste documento.

A solução deve respeitar a constituição, inclusive sua base tecnológica, segurança e restrição de fluxo. A declaração do serviço MySQL em `docker/docker-compose.yml` deve ser preservada. Histórico de negócio das transações não é obrigatório; registros técnicos de idempotência são permitidos.

## 2. Requisitos funcionais

| ID | Comportamento esperado |
| --- | --- |
| RF-01 | Criar e persistir um cartão identificado por `numeroCartao`, associado à senha informada e com saldo inicial de R$ 500,00. O número deve ser único. |
| RF-02 | Recusar a criação de cartão existente, preservando seus dados e saldo. |
| RF-03 | Consultar o saldo atual de um cartão existente; informar ausência quando o cartão não existir. |
| RF-04 | Para entradas válidas, verificar nesta ordem: existência do cartão, senha correta e saldo maior ou igual ao valor solicitado. Retornar o primeiro motivo de recusa; senha inválida prevalece sobre saldo insuficiente (P-04). |
| RF-05 | Na aprovação, debitar exatamente o valor solicitado uma única vez. Na recusa, preservar o saldo. Usar valores monetários decimais exatos; o saldo nunca pode ficar negativo. |
| RF-06 | Preservar cartões e saldos após reinício da aplicação. |

## 3. Validação das entradas

- **P-01 — Cartão e senha:** `numeroCartao`, `senha` e `senhaCartao` são strings obrigatórias nas respectivas entradas, sem nulos ou vazios. O cartão deve ter exatamente 16 dígitos e as senhas, 4 dígitos, preservando zeros iniciais. Não exigir validação pelo algoritmo de Luhn.
- **P-02 — Valor:** `valor` é obrigatório, numérico, maior que zero e com até duas casas decimais, sem arredondamento automático. Valores acima do saldo resultam em `SALDO_INSUFICIENTE`.
- **P-03 — Requisição inválida:** entradas que violem essas regras ou JSON malformado resultam em `400`, com JSON contendo `codigo` e `mensagem`, sem executar a operação.

## 4. Contratos HTTP

Endereço local: `http://localhost:8080`. Todas as chamadas exigem autenticação BASIC com usuário `username` e senha `password`, independentemente da senha do cartão. Credenciais ausentes ou inválidas resultam em `401`, sem corpo e sem executar a operação.

| Operação | Entrada | Sucesso | Recusa ou ausência |
| --- | --- | --- | --- |
| `POST /cartoes` | JSON com `numeroCartao` e `senha`. | `201`, JSON com `senha` e `numeroCartao`. | Cartão existente: `422`, JSON com esses mesmos campos. |
| `GET /cartoes/{numeroCartao}` | Número do cartão no caminho. | `200`, corpo contendo somente o saldo decimal. | Cartão inexistente: `404`, sem corpo. |
| `POST /transacoes` | JSON com `numeroCartao`, `senhaCartao` e `valor`. | `201`, corpo literal `OK`. | `422`, corpo com um único motivo: `CARTAO_INEXISTENTE`, `SENHA_INVALIDA` ou `SALDO_INSUFICIENTE`. |

- **P-05 — Resposta de criação:** tanto no `201` quanto no `422`, retornar os valores de `numeroCartao` e `senha` recebidos na requisição. Nunca recuperar a senha armazenada nem alterar o cartão existente para compor a resposta.
- **P-06 — Representação:** objetos de cartão em `application/json`; saldo e respostas literais em `text/plain`. Saldo com duas casas decimais e ponto como separador, por exemplo, `500.00`. Os literais são enviados sem aspas JSON.
- **P-12 — Falhas técnicas:** retornar `503` para indisponibilidade ou falha transitória e `500` para falha inesperada. Usar `application/json`, com `codigo` e `mensagem`, sem expor detalhes internos. Não representar falhas técnicas como recusas de autorização.

## 5. Integridade, idempotência e segurança

A atualização do saldo deve ser atômica e consistente entre instâncias. Com saldo de R$ 10,00, duas operações distintas e válidas de R$ 10,00, executadas simultaneamente em instâncias diferentes, devem produzir uma aprovação, uma recusa por `SALDO_INSUFICIENTE` e saldo final zero.

A idempotência aplica-se a `POST /transacoes`:

| Decisão | Comportamento esperado |
| --- | --- |
| P-07 — Identificação | Aceitar o cabeçalho opcional `Idempotency-Key`, no formato UUID, com escopo nessa rota. Sem cabeçalho, cada chamada representa uma nova operação. Coincidência de cartão e valor não identifica repetição. |
| P-08 — Conteúdo | Comparar `numeroCartao`, `senhaCartao` e o valor numérico; `10` e `10.00` são equivalentes. Durante a retenção, reutilizar a chave com conteúdo diferente resulta em `409`, corpo literal `CHAVE_IDEMPOTENCIA_REUTILIZADA`, sem novo débito. |
| P-09 — Resultado | Preservar resultados concluídos `201` e `422`, reproduzindo status e corpo sem reexecutar a operação, mesmo que o estado do cartão mude. Não preservar erros de entrada, autenticação ou falhas técnicas como resultados concluídos. |
| P-10 — Simultaneidade | Para a mesma chave e conteúdo, aguardar até 5 segundos pelo resultado original. Persistindo o processamento, retornar `409`, corpo literal `OPERACAO_EM_PROCESSAMENTO` e cabeçalho `Retry-After: 1`, sem novo débito. Essa resposta transitória não é registrada como resultado concluído. |
| P-11 — Retenção | Reter resultados por 24 horas após a conclusão. Após a expiração, a mesma chave representa uma nova operação. Não expirar operações em processamento. |

Durante a retenção, os resultados devem permanecer disponíveis entre instâncias e após reinício. O débito aprovado e seu resultado durável devem permanecer consistentes diante de falhas, sem confirmação parcial. Eventuais retentativas devem ser limitadas e não duplicar efeitos.

Armazenar senhas de cartão com hash seguro e nunca registrá-las em logs. Seu retorno fica restrito às respostas de criação previstas na seção 4.

## 6. Diretrizes de aceite

Testes automatizados devem comprovar resultados e invariantes, com alta cobertura, abrangendo:

1. Criação, saldo inicial, consulta e duplicidade sem alteração dos dados existentes, inclusive quando a nova requisição informar outra senha válida.
2. Validações de entrada, preservação de zeros iniciais, precisão monetária e contratos HTTP, incluindo tipos de mídia, corpos vazios e autenticação nas três rotas.
3. Débitos sucessivos até saldo insuficiente, débito igual ao saldo e precedência existência → senha → saldo, sem alteração do saldo nas recusas.
4. Persistência após reinício, rollback e concorrência com MySQL e transações/conexões independentes que representem instâncias diferentes.
5. Idempotência com repetição sequencial, simultânea e após reinício; conteúdo divergente; equivalência numérica; reprodução de aprovações e recusas; espera, retenção e expiração conforme a seção 5.
6. Falhas técnicas diferenciadas das recusas de negócio, sem confirmação parcial nem débito duplicado.

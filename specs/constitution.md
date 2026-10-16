# Constituição — mini-autorizador-ex

## 1. Base tecnológica e escopo

- Java 21, Spring Boot, Maven 3.9.16 pelo Maven Wrapper e MySQL são a base do projeto. Flyway controla a evolução do esquema.
- A aplicação oferece uma API REST para criar cartões com saldo inicial de R$ 500,00, consultar saldo e autorizar transações mediante existência do cartão, senha correta e saldo suficiente.
- Preservar rotas, campos, códigos HTTP, corpos de resposta e autenticação BASIC (`username` / `password`) do enunciado. A especificação deve consolidar os contratos de `POST /cartoes`, `GET /cartoes/{numeroCartao}` e `POST /transacoes` antes da implementação.
- A declaração do serviço MySQL fornecida em `docker/docker-compose.yml` deve ser preservada. MongoDB não integra a solução escolhida.

## 2. Arquitetura e qualidade do código

- Aplicar DDD e arquitetura hexagonal, com linguagem do domínio e responsabilidades coesas.
- O domínio permanece independente de Spring, JPA, HTTP e banco de dados. A aplicação orquestra casos de uso por portas; adaptadores implementam integração, persistência e exposição REST. Dependências apontam para o interior.
- SOLID e Clean Code orientam clareza, composição e testabilidade, sem estruturas além das necessárias.

## 3. Restrição de fluxo

- O código Java de autoria do projeto, incluindo testes, não pode utilizar `if`, `break` ou `continue`.
- Preferir polimorfismo, composição e políticas explícitas, sem artifícios que ocultem condicionais e prejudiquem a legibilidade.
- Dependências externas e scripts padronizados do Maven Wrapper ficam fora dessa restrição e não devem ser reescritos para atendê-la.

## 4. Integridade, concorrência e idempotência

- Persistir cartões e saldo no MySQL, com precisão monetária decimal exata. Uma aprovação debita o valor uma única vez; uma recusa não altera o saldo. O saldo nunca pode ficar negativo.
- Proteger a atualização do saldo no MySQL, com atomicidade e consistência entre instâncias. Bloqueios apenas em memória de uma instância não atendem a essa garantia.
- Com saldo de R$ 10,00, duas operações distintas e válidas de R$ 10,00 em instâncias concorrentes devem gerar uma aprovação, uma recusa por saldo insuficiente e saldo final zero.
- Repetições identificadas da mesma operação recuperam o resultado concluído sem novo débito, inclusive entre instâncias e após reinício. O controle deve ser durável e consistente com o débito, sem confirmação parcial diante de falhas.
- A especificação deve definir identificação da operação, reutilização com conteúdo divergente, retenção dos registros e comportamento de chamadas sem identificação, preservando as chamadas do contrato original. Coincidência de cartão e valor não identifica uma repetição.
- Retentativas devem ser limitadas e seguras, sem duplicar efeitos. Falhas técnicas devem ser diferenciadas de recusas de autorização.
- Histórico de negócio das transações não é obrigatório; registros técnicos de idempotência são permitidos.

## 5. Segurança e testes

- Armazenar senhas de cartão com hash seguro e nunca registrá-las em logs. O retorno do campo `senha` fica limitado às respostas exigidas pelo contrato de criação de cartão. Segredos de ambientes externos não pertencem ao código.
- Buscar alta cobertura com testes de resultados e invariantes: autorização, contratos HTTP, autenticação, persistência, rollback, idempotência e concorrência.
- Verificar persistência e concorrência com MySQL e transações/conexões independentes que representem instâncias diferentes.

## 6. Governança documental

- Artefatos SDD devem ser curtos, objetivos, em Português Brasileiro e exclusivos deste projeto; especificação, plano e tarefas detalham estes princípios.
- Decisões e suposições que afetem comportamento ou contratos devem ser explícitas e registradas antes da implementação correspondente.
- O enunciado original é material privado de consulta. A documentação pública não deve identificar o cliente nem reproduzir o documento recebido.

# mini-autorizador-ex

API REST para criar cartões com saldo inicial de R$ 500,00, consultar saldos e autorizar débitos, com persistência MySQL, controle de concorrência e idempotência opcional.

Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.16, Spring Security, JPA/Hibernate e Flyway. Integração validada com MySQL 5.7.44, Connector/J 8.0.33 e `MySQLLegacyDialect`.

## Preparação e execução

Requer JDK 21, Docker em execução e Docker Compose. Execute os comandos na raiz do projeto. As portas locais padrão são 3306 para o MySQL e 8080 para a API.

Inicie apenas o serviço utilizado e aguarde o MySQL aceitar conexões:

```sh
docker compose -f docker/docker-compose.yml up -d mysql
docker compose -f docker/docker-compose.yml logs mysql
```

O serviço MySQL fornecido foi preservado. Na configuração local padrão, o banco é `miniautorizador`, o usuário é `root` e a senha é vazia. A API executa as migrações Flyway ao iniciar; o Hibernate apenas valida o esquema.

| Variável de ambiente | Configuração |
| --- | --- |
| `DB_URL` | Padrão: `jdbc:mysql://localhost:3306/miniautorizador?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`. |
| `DB_USERNAME` | Padrão: `root`. |
| `DB_PASSWORD` | Padrão: vazio. |
| `IDEMPOTENCY_HMAC_SECRET` | Obrigatória na inicialização, inclusive para uso sem chave; segredo aleatório com pelo menos 32 bytes em UTF-8. |

Gere o segredo HMAC uma única vez e guarde-o em armazenamento seguro. Todas as instâncias devem usar o mesmo valor, restaurado nos reinícios enquanto houver reservas pendentes ou resultados vigentes dependentes dele. Pendências podem durar mais de 24 horas; trocar o segredo exige tratar também esses registros. Substitua o marcador abaixo pelo seu segredo e mantenha-o fora do código e dos logs.

Windows PowerShell:

```powershell
$env:IDEMPOTENCY_HMAC_SECRET = "<seu-segredo-persistente>"
.\mvnw.cmd spring-boot:run
```

Linux/macOS ou Git Bash:

```sh
export IDEMPOTENCY_HMAC_SECRET='<seu-segredo-persistente>'
./mvnw spring-boot:run
```

Endereço padrão: `http://localhost:8080`. Após gerar o pacote com `clean verify`, também é possível iniciar com `java -jar target/mini-autorizador-ex-0.1.0-SNAPSHOT.jar`, mantendo as mesmas variáveis de ambiente.

## API e exemplos

Todas as rotas exigem BASIC com usuário `username` e senha `password`, distintos da senha do cartão. Autenticação ausente ou inválida retorna `401` sem corpo e sem executar a operação.

| Operação | Sucesso | Recusa ou ausência |
| --- | --- | --- |
| `POST /cartoes` | `201`, JSON com `numeroCartao` e `senha` recebidos. | `422` com os mesmos campos recebidos; preserva o cartão existente. |
| `GET /cartoes/{numeroCartao}` | `200`, saldo em `text/plain`, como `500.00`. | `404` sem corpo. |
| `POST /transacoes` | `201`, literal `OK` em `text/plain`. | `422`, um dos literais `CARTAO_INEXISTENTE`, `SENHA_INVALIDA` ou `SALDO_INSUFICIENTE`. |

Cartão e senha são strings com, respectivamente, 16 e 4 dígitos; zeros iniciais são preservados e não se exige Luhn. O valor é um número JSON positivo com até duas casas decimais, sem arredondamento. Entradas inválidas retornam `400` em JSON com `codigo` e `mensagem`, sem efeitos.

Os exemplos usam sintaxe Bash, também disponível no Git Bash para Windows. Em um banco sem o cartão de exemplo, crie e consulte:

```sh
curl -i -u username:password -H 'Content-Type: application/json' \
  -d '{"numeroCartao":"0000000000000001","senha":"0123"}' \
  http://localhost:8080/cartoes

curl -i -u username:password http://localhost:8080/cartoes/0000000000000001
```

Resultado: criação `201` e consulta com saldo `500.00`.

Autorize sem chave; cada chamada representa um novo débito:

```sh
curl -i -u username:password -H 'Content-Type: application/json' \
  -d '{"numeroCartao":"0000000000000001","senhaCartao":"0123","valor":10.00}' \
  http://localhost:8080/transacoes
```

Resultado: `201`, corpo `OK` e saldo `490.00`.

Autorize com chave e execute este mesmo comando duas vezes:

```sh
curl -i -u username:password -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 8ac7b03e-364f-4f4c-96b4-9d023dc25c96' \
  -d '{"numeroCartao":"0000000000000001","senhaCartao":"0123","valor":10.00}' \
  http://localhost:8080/transacoes
```

As duas respostas são `201` com `OK`. Na sequência acima e dentro da retenção, o saldo fica em `480.00`: a repetição com a mesma chave não gera outro débito. Confirme pela consulta de saldo. Se o cartão já existia, os saldos dependem das operações anteriores.

## Decisões e garantias

- Organização por `cartao`, `transacao` e `idempotencia`, com domínio independente de frameworks, aplicação por portas e adaptadores para HTTP, segurança e persistência. A autorização compõe implementações de `AuthorizationPolicy` e usa `Stream.findFirst()` para selecionar a primeira recusa na ordem existência → senha → saldo. `Optional` encadeia resultados e alternativas; expressões ternárias simples resolvem decisões pontuais. Uma análise sintática automatizada verifica a estrutura de controle no Java de produção e testes.
- Valores monetários usam `BigDecimal`. A autorização verifica existência, senha e saldo nessa ordem; recusas não alteram o saldo. As senhas persistidas usam BCrypt e só retornam nas respostas contratuais de criação, com os valores recebidos naquela chamada.
- Transações InnoDB em `READ COMMITTED`, bloqueio de linha e atualização defensiva protegem débitos entre instâncias. A operação identificada confirma débito e resultado técnico na mesma transação; os bloqueios seguem a ordem idempotência → cartão.
- A comparação idempotente usa HMAC-SHA-256 de cartão, senha e valor normalizado. `10` e `10.00` são equivalentes. O banco mantém reservas e resultados técnicos; não há histórico de negócio das transações.

| Situação com `Idempotency-Key` | Comportamento |
| --- | --- |
| Cabeçalho presente vazio ou UUID inválido | `400`, JSON de erro, sem reserva ou autorização. |
| Mesma chave e conteúdo, resultado vigente | Reproduz `201` ou `422`, mesmo após mudança do cartão ou reinício. |
| Conteúdo divergente, reserva pendente ou resultado vigente | `409`, literal `CHAVE_IDEMPOTENCIA_REUTILIZADA`, sem novo débito. |
| Mesma operação ainda em processamento | Espera concorrente com prazo único de 5 segundos; persistindo o processamento, `409`, literal `OPERACAO_EM_PROCESSAMENTO` e `Retry-After: 1`. |
| Resultado concluído há 24 horas | Após a expiração, a chave pode identificar uma nova operação; reservas pendentes não expiram. |

Erros de entrada, autenticação e falhas técnicas não viram resultados concluídos. Falhas transitórias ou indisponibilidade retornam `503`; falhas inesperadas retornam `500`, em JSON com `codigo` e `mensagem`. Essas respostas são distintas das recusas de negócio. Há no máximo uma retentativa automática por deadlock com rollback comprovado; commit incerto sem chave não autoriza repetir o débito automaticamente.

## Testes e verificação

| Finalidade | Windows | Linux/macOS ou Git Bash |
| --- | --- | --- |
| Unitários e análise sintática | `.\mvnw.cmd test` | `./mvnw test` |
| Suíte completa, pacote e JaCoCo | `.\mvnw.cmd clean verify` | `./mvnw clean verify` |

As integrações exigem Docker e provisionam MySQL 5.7.44 por Testcontainers em bancos exclusivos, sem usar o banco local da API. O perfil de testes fornece um segredo sintético próprio. A primeira execução pode baixar Maven, dependências e imagens. Docker indisponível implica validação pendente, não aprovação das integrações.

Relatórios: `target/surefire-reports/`, `target/failsafe-reports/` e `target/site/jacoco/index.html`.

Verificação final de 18/10/2026: `clean verify` com código de saída 0 e **79 testes aprovados**, sem falhas, erros ou ignorados: 30 unitários (incluindo análise sintática) e 49 de integração. JaCoCo: **429/437 linhas (98,17%)** e **84/96 ramos (87,50%)**. Foram verificados contratos, precisão, persistência, concorrência entre contextos independentes, retenção, recuperação, rollback e ausência de débito duplicado.

A evidência HTTP usa MockMvc com MVC e filtros de segurança reais, integrado ao MySQL. Reinício é exercitado por novos contextos Spring sobre o mesmo banco. O JAR executável foi gerado; uma execução adicional por `java -jar` não integra essa evidência. A consolidação documental conferiu fontes, configurações, log e relatórios recebidos, sem reexecutar o Maven.

Podem aparecer avisos de CDS da JVM instrumentada, informações de suporte do Flyway ao MySQL legado e mensagens de conexão encerrada/rollback nos cenários de falha injetada. Esses registros foram examinados na execução aprovada; não houve avisos de depreciação ou recursos do compilador. A compatibilidade comprovada é a combinação informada acima; outras versões e comportamento sob carga não foram validados.

# Plano técnico — Mini Autorizador

Implementar [spec.md](spec.md), respeitando [constitution.md](../constitution.md) e [AGENTS.md](../../AGENTS.md). Regras, contratos HTTP e aceite permanecem na especificação.

## 1. Base tecnológica e compatibilidade

- Manter Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.16, Spring MVC, Spring Security, Bean Validation, Spring Data JPA e Flyway.
- Preservar o serviço MySQL 5.7 fornecido, usar InnoDB e iniciar somente o serviço `mysql`.
- Para MySQL 5.7, adotar `hibernate-community-dialects`, alinhado ao Hibernate gerenciado pelo Spring Boot, com `org.hibernate.community.dialect.MySQLLegacyDialect`, e fixar Connector/J 8.0.33. O dialeto padrão atual exige 8.0; a integração escolhida será validada na verificação inicial. Ver referências ao final.
- Verificar dependências, conexão, Flyway e bloqueio no MySQL real antes de implementar os fluxos completos.

## 2. Arquitetura e domínio

- Preservar os pacotes `cartao`, `transacao` e `idempotencia` em `br.com.miniautorizador`, com `domain`, `application` e `adapter`; portas ficam na aplicação.
- Modelar cartão, número, valor monetário e resultado de autorização no domínio independente de frameworks. Entidades JPA, DTOs e mapeamentos ficam nos adaptadores.
- Coordenar persistência, senha e idempotência por portas. Aplicar políticas ordenadas conforme RF-04; retornar recusas como resultados, permitindo confirmar seu registro idempotente.
- Usar composição e polimorfismo. Integrar ao `verify` uma verificação por tokens Java contra `if`, `break` e `continue` em produção e testes, acompanhada de revisão de legibilidade.

## 3. Persistência

Criar migrações em `src/main/resources/db/migration`. Flyway controla o esquema; Hibernate apenas o valida. Desabilitar Open Session in View.

| Tabela | Estrutura e finalidade |
| --- | --- |
| `cartao` | `numero_cartao CHAR(16)` como chave primária, hash da senha e `saldo DECIMAL(19,2) NOT NULL`. |
| `idempotencia` | Chave única por rota e UUID normalizado; impressão digital da entrada; estado pendente/concluído; status, corpo, tipo de mídia, conclusão e expiração. Sem chave estrangeira para cartão, permitindo preservar a recusa por inexistência. |

- Usar `BigDecimal` desde a desserialização, validar sem arredondar e normalizar para escala 2. A coluna de saldo não deve impor limite adicional à entrada: valores superiores continuam sujeitos à recusa por saldo insuficiente.
- Inserir cartões com o saldo inicial especificado. A chave primária resolve duplicidades simultâneas; tratar sua violação após rollback, preservando o registro existente.
- Usar consultas parametrizadas, índices das chaves e instantes UTC obtidos do MySQL para conclusão e expiração.

## 4. HTTP e segurança

- Validar DTOs, tipos JSON e UUID estritamente, impedindo coerções entre números e strings. Autenticar antes dos casos de uso.
- Configurar BASIC sem sessão nem formulário, com CSRF desabilitado. Preservar as credenciais do contrato e controlar o `401` sem corpo, inclusive em chamadas de navegador.
- Implementar BCrypt por porta de senha. Compor respostas de criação com a entrada recebida, conforme P-05.
- Centralizar a tradução de resultados e exceções para os contratos definidos. Formatar saldo com `toPlainString()` em escala 2; enviar literais sem serialização JSON.
- Configurar segredos por ambiente e excluir senhas, credenciais e corpos sensíveis dos logs e registros técnicos.

## 5. Débito e concorrência

- Executar autorizações em transações InnoDB `READ COMMITTED`, por adaptador transacional Spring.
- Bloquear o cartão com `SELECT ... FOR UPDATE`, avaliar suas políticas e manter o bloqueio até o commit, serializando débitos entre instâncias.
- Atualizar com condição adicional de saldo suficiente e conferir as linhas alteradas. Não depender de `CHECK` no MySQL 5.7.
- Adquirir bloqueios na ordem idempotência → cartão. Confirmar saldo e resultado durável na mesma transação; responder à conclusão após o commit.

## 6. Idempotência durável

1. Normalizar UUID e gerar HMAC-SHA-256 da composição inequívoca de cartão, senha e valor em escala 2. Compartilhar a chave secreta entre instâncias e mantê-la válida durante a retenção; não persistir senha original.
2. Para UUID ainda não registrado, criar reserva pendente em transação curta independente, protegida pela unicidade. Disputas exigem nova leitura após rollback. A reserva não representa autorização concluída.
3. Bloquear a reserva na transação de execução e reler estado e expiração. Para registro vigente, comparar a impressão digital: reproduzir resultado concluído ou devolver conflito por conteúdo divergente; para reserva pendente com mesmo conteúdo, executar a autorização.
4. Reinicializar resultado expirado sob bloqueio para a nova operação e entrada. Não expirar reservas pendentes.
5. Gravar resultado `201` ou `422`, conclusão e expiração de 24 horas na mesma transação do eventual débito. Falha técnica provoca rollback sem novo resultado concluído.
6. Limitar a espera concorrente ao prazo único de 5 segundos de P-10, incluindo disputas da reserva. Ajustar limites JDBC/InnoDB sem reiniciar o prazo; devolver a resposta transitória prevista quando esgotado.

Após falha ou reinício, retomar pendências somente sob bloqueio, garantindo exclusividade e rollback da execução anterior. Reproduzir operações confirmadas. Reutilizar resultados expirados sob bloqueio dispensa um processo de limpeza para cumprir a retenção.

Sem cabeçalho, executar uma autorização independente. Permitir no máximo uma retentativa automática por deadlock com rollback comprovado. Com commit incerto, consultar a chave quando disponível; não repetir automaticamente débitos sem identificação. Distinguir falhas técnicas de P-12 da espera de P-10.

## 7. Verificação e entrega

- Usar JUnit Jupiter gerenciado pelo Spring Boot: testes unitários de domínio/aplicação e testes HTTP dos contratos completos.
- Executar integrações em MySQL 5.7 exclusivo de testes, com as mesmas migrações e conexões, transações e contextos independentes. Evitar transação externa de teste que mascare commits.
- Coordenar concorrência com barreiras e sinais; verificar espera, retomada, expiração e reinício preservando o banco.
- Injetar falhas entre débito e resultado, antes do commit e após commit com perda de resposta. Conferir saldo e registro durável, cobrindo os critérios do `spec.md`.
- Configurar JaCoCo e executar `./mvnw verify` com integrações e restrição de fluxo. MySQL indisponível implica validação pendente explícita.
- Consolidar instruções de execução e configuração no README. Definir sequência e blocos em `tasks.md`; entregar evidências, resultados reais e pendências.

Referências técnicas de compatibilidade: [Hibernate — dialetos](https://docs.hibernate.org/orm/7.4/dialect/), [Connector/J 8.0.33](https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-0-33.html) e [Flyway — MySQL](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql).

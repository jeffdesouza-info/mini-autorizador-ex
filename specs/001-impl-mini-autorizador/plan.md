# Plano técnico — Mini Autorizador

Implementar [spec.md](spec.md), respeitando [constitution.md](../constitution.md) e [AGENTS.md](../../AGENTS.md). Regras, contratos HTTP e aceite permanecem na especificação.

## 1. Base tecnológica e compatibilidade

- Manter Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.16, Spring MVC, Spring Security, Bean Validation, Spring Data JPA e Flyway.
- Preservar o serviço MySQL 5.7 fornecido, usar InnoDB e iniciar somente o serviço `mysql`.
- Para MySQL 5.7, adotar `hibernate-community-dialects`, alinhado ao Hibernate gerenciado pelo Spring Boot, com `org.hibernate.community.dialect.MySQLLegacyDialect`, e fixar Connector/J 8.0.33. O dialeto padrão atual exige 8.0; a integração escolhida será validada na verificação inicial. Ver referências ao final.
- Verificar dependências, conexão, Flyway e bloqueio no MySQL real antes de implementar os fluxos completos.

## 2. Arquitetura e domínio

- Preservar os pacotes `cartao`, `transacao` e `idempotencia` em `br.com.miniautorizador`, com `domain`, `application` e `adapter`; portas ficam na aplicação.
- Manter cartão, resultados e políticas no domínio independente de frameworks. Entidades JPA, DTOs e mapeamentos ficam nos adaptadores.
- Usar campos diretos dos tipos padrão Java: `String` para número do cartão, senha recebida e hash; `BigDecimal` para saldo e valor solicitado. Preservar modelos, resultados, políticas, portas e DTOs com responsabilidade concreta; não criar um tipo próprio apenas para encapsular cada campo.
- Centralizar as validações reutilizáveis em `ValidacaoCartao` e `RegrasMonetarias`, sem dependências de frameworks. Preservar as invariantes no domínio e na aplicação, inclusive em chamadas diretas aos casos de uso; distinguir saldo não negativo de débito positivo.
- Coordenar persistência, senha e idempotência por portas. Aplicar políticas ordenadas conforme RF-04; retornar recusas como resultados, permitindo confirmar seu registro idempotente.
- Usar composição e polimorfismo. Integrar ao `verify` uma verificação por tokens Java contra `if`, `break` e `continue` em produção e testes, acompanhada de revisão de legibilidade.

## 3. Persistência

Criar migrações em `src/main/resources/db/migration`. Flyway controla o esquema; Hibernate apenas o valida. Desabilitar Open Session in View.

| Tabela | Estrutura e finalidade |
| --- | --- |
| `cartao` | `numero_cartao CHAR(16)` como chave primária, hash da senha e `saldo DECIMAL(19,2) NOT NULL`. |
| `idempotencia` | Chave única por rota e UUID normalizado; geração interna da reserva; impressão digital da entrada; estado pendente/concluído; status, corpo, tipo de mídia, conclusão e expiração. A geração distingue novas operações após expiração. Sem chave estrangeira para cartão, permitindo preservar a recusa por inexistência. |

- Usar `BigDecimal` desde a desserialização, validar sem arredondar e normalizar para escala 2. A coluna de saldo não deve impor limite adicional à entrada: valores superiores continuam sujeitos à recusa por saldo insuficiente.
- Inserir cartões com o saldo inicial especificado. A chave primária resolve duplicidades simultâneas; tratar sua violação após rollback, preservando o registro existente.
- Usar consultas parametrizadas, índices das chaves e instantes UTC obtidos do MySQL para conclusão e expiração.

## 4. HTTP e segurança

- Validar DTOs, tipos JSON e UUID estritamente, impedindo coerções entre números e strings. Autenticar antes dos casos de uso. Cabeçalho de idempotência presente e vazio ou inválido segue o `400` de P-07, antes de qualquer reserva ou autorização.
- Preservar precisão e escala na leitura JSON: configurar `spring.jackson.deserialization.use-big-decimal-for-floats=true` e `spring.jackson.datatype.json-node.strip-trailing-bigdecimal-zeroes=false`. Validar o limite de casas antes de normalizar, sem conversão intermediária para `double` ou `float`.
- Configurar BASIC sem sessão nem formulário, com CSRF desabilitado. Preservar as credenciais do contrato e controlar o `401` sem corpo, inclusive em chamadas de navegador.
- Implementar BCrypt por porta de senha. Compor respostas de criação com a entrada recebida, conforme P-05.
- Centralizar a tradução de resultados e exceções para os contratos definidos. Formatar saldo com `toPlainString()` em escala 2; enviar literais sem serialização JSON.
- Configurar segredos por ambiente e excluir senhas, credenciais e corpos sensíveis dos logs e registros técnicos. Ocultar senha e hash em `toString()` de modelos e DTOs; a senha original não integra o estado persistente do cartão.

## 5. Débito e concorrência

- Executar autorizações em transações InnoDB `READ COMMITTED`, por adaptador transacional Spring.
- Bloquear o cartão com `SELECT ... FOR UPDATE`, avaliar suas políticas e manter o bloqueio até o commit, serializando débitos entre instâncias.
- Atualizar com condição adicional de saldo suficiente e conferir as linhas alteradas. Não depender de `CHECK` no MySQL 5.7.
- Adquirir bloqueios na ordem idempotência → cartão. Confirmar saldo e resultado durável na mesma transação; responder à conclusão após o commit.

## 6. Idempotência durável

1. Normalizar UUID e gerar HMAC-SHA-256 da composição inequívoca de cartão, senha e valor em escala 2. Usar segredo persistente configurado por ambiente e compartilhado entre instâncias, estável nos reinícios enquanto houver reservas pendentes ou resultados vigentes dependentes dele. Não gerar novo segredo a cada inicialização nem persistir a senha original.
2. Para UUID ausente no banco, publicar reserva pendente com geração interna e impressão digital em transação curta independente, protegida pela unicidade. Para resultado expirado, bloquear e reler o registro nessa transação; somente se continuar expirado, substituir pela nova reserva e geração. Confirmar a reserva antes da execução, tornando seu conteúdo visível entre instâncias. Não expirar pendências. Disputas exigem nova leitura após rollback.
3. Consultar a reserva por leitura sem bloqueio de execução e comparar o conteúdo da geração vigente antes de aguardar seu processamento. Conteúdo divergente segue P-08; resultado concluído vigente com mesmo conteúdo é reproduzido. A comparação usa a impressão digital, sem recuperar a senha original.
4. Para pendência com mesmo conteúdo, adquirir o bloqueio da reserva na transação de execução e reler geração, impressão digital, estado e expiração. Se a geração mudou, reavaliar a reserva vigente; se já concluiu, reproduzir o resultado; se continua pendente e compatível, executar a autorização. Manter a ordem idempotência → cartão.
5. Gravar resultado `201` ou `422`, conclusão e expiração de 24 horas na mesma transação do eventual débito. Falha técnica provoca rollback sem novo resultado concluído. Responder à conclusão após o commit.
6. Limitar a espera concorrente ao prazo único de 5 segundos de P-10, incluindo disputas da reserva e releituras, sem reiniciar o prazo. Após espera por bloqueio, reler a geração vigente e reavaliar o conteúdo antes de classificar a resposta. Ao esgotar o prazo, usar `OPERACAO_EM_PROCESSAMENTO` somente quando a mesma operação e conteúdo estiverem confirmados; divergência segue P-08 e falha técnica que impeça a identificação segue P-12. Ajustar limites JDBC/InnoDB ao tempo restante.

Após falha ou reinício, retomar pendências somente sob bloqueio e revalidação, garantindo exclusividade e rollback da execução anterior. Reproduzir operações confirmadas. A retomada inclui reservas com mais de 24 horas; o segredo HMAC deve continuar apto a reconhecê-las. Eventual substituição do segredo exige preservar a verificação dos registros ainda dependentes dele. Reutilizar resultados expirados pela publicação de nova reserva dispensa um processo de limpeza para cumprir a retenção.

Sem cabeçalho, executar uma autorização independente. Permitir no máximo uma retentativa automática por deadlock com rollback comprovado. Com commit incerto, consultar a chave quando disponível; não repetir automaticamente débitos sem identificação. Distinguir falhas técnicas de P-12 da espera de P-10.

## 7. Verificação e entrega

- Usar JUnit Jupiter gerenciado pelo Spring Boot: testes unitários de domínio/aplicação e testes HTTP dos contratos completos.
- Executar integrações em MySQL 5.7 exclusivo de testes, com as mesmas migrações e conexões, transações e contextos independentes. Evitar transação externa de teste que mascare commits.
- Coordenar concorrência com barreiras e sinais; verificar espera, retomada, expiração e reinício preservando o banco. Cobrir conteúdo divergente durante execução, inclusive na nova reserva após expiração, e retomada de pendência com mais de 24 horas usando o mesmo segredo.
- Injetar falhas entre débito e resultado, antes do commit e após commit com perda de resposta. Conferir saldo e registro durável, cobrindo os critérios do `spec.md`.
- Configurar JaCoCo e executar `./mvnw verify` com integrações e restrição de fluxo. Examinar cobertura e cenários de aceite; percentuais altos não substituem a comprovação dos contratos. MySQL indisponível implica validação pendente explícita.
- Preservar, por execução de aceite, os relatórios completos de Surefire, Failsafe e JaCoCo, o código/configuração correspondente e o log do Maven fora de `target`. Informar resultados reais; execuções apenas relatadas devem ser distinguidas das evidências examinadas.
- Entregar evidências, resultados reais e pendências da implementação. O assistente de documentação consolida instruções de execução e configuração no README e os estados em `tasks.md`, conforme a divisão de responsabilidades ali definida.

Referências técnicas de compatibilidade: [Hibernate — dialetos](https://docs.hibernate.org/orm/7.4/dialect/), [Connector/J 8.0.33](https://dev.mysql.com/doc/relnotes/connector-j/en/news-8-0-33.html) e [Flyway — MySQL](https://documentation.red-gate.com/flyway/reference/database-driver-reference/mysql).


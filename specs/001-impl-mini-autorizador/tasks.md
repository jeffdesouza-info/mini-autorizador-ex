# Tarefas — mini-autorizador-ex

Executar [plan.md](plan.md), conforme [spec.md](spec.md), [constitution.md](../constitution.md) e [AGENTS.md](../../AGENTS.md). T001 e T002 estão concluídas; T003 a T011 permanecem pendentes. Os contratos e as decisões permanecem nas fontes citadas.

## 1. Execução por blocos

- Seguir a ordem abaixo. Cada bloco depende do anterior e reúne implementação, testes e correções dentro do escopo autorizado, sem exigir nova autorização a cada tarefa.
- O executor implementa código, configurações e testes; o assistente de documentação mantém os artefatos e seus estados a partir das evidências. A T011 pertence ao assistente.
- Registrar conflitos e lacunas materiais que afetem requisitos, contratos ou princípios e obter definição antes de implementar a parte afetada. Interromper somente essa parte, ampliações de escopo ou atividades com bloqueio não resolvido; continuar atividades independentes autorizadas.
- Resolver escolhas técnicas rotineiras dentro do plano e do escopo autorizados, sem alterar requisitos, contratos ou princípios; informar as escolhas relevantes na entrega.
- Validar durante cada tarefa. Uma tarefa só está concluída com seu aceite comprovado; limitações de ambiente devem constar como validação pendente.

## 2. Bloco 1 — Base executável e riscos de persistência

| ID | Estado | Trabalho previsto | Aceite verificável |
| --- | --- | --- | --- |
| T001 | Concluída | Criar a entrada da aplicação e configurações de ambiente; ajustar dependências e compatibilidade MySQL conforme o plano. Configurar JUnit, integrações MySQL no `verify`, JaCoCo e verificação por tokens da restrição de fluxo em produção e testes. | Contexto inicia com MySQL 5.7; dependências resolvidas; teste de conexão executado; relatórios e verificação de fluxo funcionam. Serviço MySQL fornecido preservado. |
| T002 | Concluída | Criar migrações de `cartao` e `idempotencia`, validação do esquema e suporte transacional. Preparar banco exclusivo de testes e conexões/contextos independentes. Comprovar cedo os mecanismos usados por concorrência e idempotência. | Migrações aplicadas e reaplicação segura; testes demonstram unicidade das chaves, bloqueio entre conexões e rollback conjunto de alterações no saldo e no resultado técnico. |

**Saída do bloco:** base compatível com MySQL real e evidências dos mecanismos de integridade antes dos fluxos completos.

**Conclusão do bloco:** T001 e T002 concluídas conforme relatório do executor apresentado em 17/10/2026. `.\mvnw.cmd verify` aprovado (`BUILD SUCCESS`) com MySQL 5.7.44, 1 teste unitário e 5 de integração, sem falhas ou testes ignorados; pacote gerado. Aceites do bloco reportados como atendidos, sem pendências de validação.

**Evidências locais informadas pelo executor:**

- Surefire: `target/surefire-reports/br.com.miniautorizador.JavaFlowRestrictionTest.txt`.
- Failsafe: `target/failsafe-reports/br.com.miniautorizador.DatabaseFoundationIT.txt`.
- JaCoCo: `target/site/jacoco/index.html`.

## 3. Bloco 2 — Cartões e autorização sem chave

| ID | Estado | Trabalho previsto | Aceite verificável |
| --- | --- | --- | --- |
| T003 | Pendente | Implementar modelos, resultados, políticas ordenadas e portas de cartão, autorização e senha; implementar BCrypt no adaptador. | Testes unitários comprovam precisão monetária, invariantes e precedência das recusas; domínio independente de frameworks e sem os comandos Java proibidos. |
| T004 | Pendente | Implementar criação e consulta, adaptadores MySQL e REST, mapeamentos e BASIC sem sessão. | Testes HTTP/MySQL cobrem RF-01 a RF-03 e RF-06, duplicidade sequencial e simultânea, senha recebida na resposta e preservação dos dados existentes. Senhas persistidas como hash; `401` vazio nas três rotas. |
| T005 | Pendente | Implementar autorização sem cabeçalho, com bloqueio de cartão, atualização defensiva e fronteira transacional conforme o plano. | Testes comprovam RF-04 e RF-05, débitos sucessivos, débito igual ao saldo e recusas sem alteração. Duas operações independentes de R$ 10,00 sobre saldo de R$ 10,00 geram uma aprovação, uma recusa e saldo zero entre contextos distintos. |
| T006 | Pendente | Completar validação estrita das entradas e tradução centralizada dos resultados e falhas para HTTP. | Testes das três rotas cobrem P-01 a P-06 e P-12: tipos JSON, zeros iniciais, valor sem arredondamento, corpos e mídias exatos, saldo com ponto e duas casas, autenticação sem efeitos e falhas técnicas distintas das recusas. |

**Saída do bloco:** contrato original utilizável, persistente e protegido contra débitos concorrentes.

## 4. Bloco 3 — Idempotência e recuperação

| ID | Estado | Trabalho previsto | Aceite verificável |
| --- | --- | --- | --- |
| T007 | Pendente | Implementar identificação opcional, normalização, HMAC com segredo persistente compartilhado, reserva durável com geração interna e execução com resultado na mesma transação do débito. Integrar ao endpoint. | Testes HTTP/MySQL cobrem P-07 a P-09: cabeçalho vazio ou UUID inválido retorna `400` sem reserva nem autorização; equivalência numérica; divergência de conteúdo; reprodução de aprovações e recusas após mudança do cartão ou reinício. Sem chave, chamadas continuam independentes; entradas inválidas e falhas não viram resultados concluídos. |
| T008 | Pendente | Implementar comparação de conteúdo antes da espera, disputas e revalidação da geração sob bloqueio, prazo único, retenção, publicação de nova reserva após expiração e retomada exclusiva de pendências. | Testes coordenados entre contextos independentes comprovam P-08, P-10 e P-11: conteúdo divergente durante processamento retorna `CHAVE_IDEMPOTENCIA_REUTILIZADA`, inclusive após reutilização de chave expirada; mesmo conteúdo recebe resultado dentro da espera ou resposta transitória com cabeçalho correto no limite total de 5 segundos. Comprovar retenção de 24 horas, ausência de expiração de pendências e débito único. |
| T009 | Pendente | Completar tratamento de deadlock, rollback, commit incerto e recuperação após falhas, respeitando os limites de retentativa e a continuidade do segredo HMAC previstos no plano. | Injetar falhas entre débito e resultado, antes do commit e após commit com perda de resposta. Comprovar saldo/resultado consistentes, retomada após reinício de reserva pendente há mais de 24 horas com reconhecimento do conteúdo e débito único, reprodução do resultado confirmado e ausência de retentativa automática insegura sem chave. |

**Saída do bloco:** operação identificada reproduzível entre instâncias e após reinício, com falhas e expiração verificadas em MySQL.

## 5. Bloco 4 — Verificação integrada e entrega

| ID | Estado | Trabalho previsto | Aceite verificável |
| --- | --- | --- | --- |
| T010 | Pendente | Revisar fronteiras arquiteturais, legibilidade e proteção de dados sensíveis. Executar `./mvnw verify`, corrigir falhas e conferir os seis grupos de aceite do `spec.md`. | Suíte unitária, HTTP e MySQL executada; cobertura JaCoCo examinada e lacunas relevantes corrigidas; restrição de fluxo aprovada em produção e testes; pacote gerado. Testes ausentes ou bloqueados não são apresentados como aprovados. |
| T011 | Pendente | Consolidar o README com base no resultado verificado: preparação, configuração, execução, testes, exemplos e decisões relevantes. Atualizar o estado das tarefas com as evidências recebidas. | Instruções correspondem aos comandos e comportamentos comprovados; exemplos incluem chamadas com e sem chave; requisitos de segredo compartilhado e limites conhecidos estão claros, sem dados privados. |

Ao encerrar cada bloco, devolver um relatório curto com status, resultado, arquivos alterados, verificações executadas, localização das evidências e pendências. Na interrupção, indicar o próximo passo para retomada.

A implementação está concluída após T001 a T010 verificadas e a entrega das evidências pelo executor. Nesse encerramento, informar a T011 como pendente do assistente de documentação. A conclusão integral da entrega ocorre após T001 a T011 verificadas.


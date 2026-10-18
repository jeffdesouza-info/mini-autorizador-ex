# Tarefas — mini-autorizador-ex

Executar [plan.md](plan.md), conforme [spec.md](spec.md), [constitution.md](../constitution.md) e [AGENTS.md](../../AGENTS.md). T001 a T011 estão concluídas; implementação e entrega documental encerradas em 18/10/2026. Os contratos e as decisões permanecem nas fontes citadas.

## 1. Execução por blocos

- Seguir a ordem abaixo. Cada bloco depende do anterior e reúne implementação, testes e correções dentro do escopo autorizado, sem exigir nova autorização a cada tarefa.
- O executor implementa código, configurações e testes; o assistente de documentação mantém os artefatos e seus estados a partir das evidências. A T011 pertence ao assistente.
- Registrar conflitos e lacunas materiais que afetem requisitos, contratos ou princípios e obter definição antes de implementar a parte afetada. Interromper somente essa parte, ampliações de escopo ou atividades com bloqueio não resolvido; continuar atividades independentes autorizadas.
- Resolver escolhas técnicas rotineiras dentro do plano e do escopo autorizados, sem alterar requisitos, contratos ou princípios; informar as escolhas relevantes na entrega.
- Validar durante cada tarefa. Uma tarefa só está concluída com seu aceite comprovado; limitações de ambiente devem constar como validação pendente.

## 2. Bloco 1 — Base executável e riscos de persistência

| ID | Estado | Trabalho previsto | Aceite verificável |
| --- | --- | --- | --- |
| T001 | Concluída | Criar a entrada da aplicação e configurações de ambiente; ajustar dependências e compatibilidade MySQL conforme o plano. Configurar JUnit, integrações MySQL no `verify`, JaCoCo e análise sintática automatizada da estrutura de controle em produção e testes. | Contexto inicia com MySQL 5.7; dependências resolvidas; teste de conexão executado; relatórios e análise sintática funcionam. Serviço MySQL fornecido preservado. |
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
| T003 | Concluída | Implementar modelos com campos Java diretos, validações reutilizáveis, resultados, políticas ordenadas e portas de cartão, autorização e senha; implementar BCrypt no adaptador. | Testes unitários comprovam precisão monetária, invariantes e precedência das recusas; domínio independente de frameworks, com políticas polimórficas, `Optional` e avaliação por `Stream`. |
| T004 | Concluída | Implementar criação e consulta, adaptadores MySQL e REST, mapeamentos e BASIC sem sessão. | Testes HTTP/MySQL cobrem RF-01 a RF-03 e RF-06, duplicidade sequencial e simultânea, senha recebida na resposta e preservação dos dados existentes. Senhas persistidas como hash; `401` vazio nas três rotas. |
| T005 | Concluída | Implementar autorização sem cabeçalho, com bloqueio de cartão, atualização defensiva e fronteira transacional conforme o plano. | Testes comprovam RF-04 e RF-05, débitos sucessivos, débito igual ao saldo e recusas sem alteração. Duas operações independentes de R$ 10,00 sobre saldo de R$ 10,00 geram uma aprovação, uma recusa e saldo zero entre contextos distintos. |
| T006 | Concluída | Completar validação estrita das entradas e tradução centralizada dos resultados e falhas para HTTP. | Testes das três rotas cobrem P-01 a P-06 e P-12: tipos JSON, zeros iniciais, valor sem arredondamento, corpos e mídias exatos, saldo com ponto e duas casas, autenticação sem efeitos e falhas técnicas distintas das recusas. |

**Saída do bloco:** contrato original utilizável, persistente e protegido contra débitos concorrentes.

**Conclusão do bloco:** T003 a T006 concluídas e aceitas em 17/10/2026, após revisão dos fontes e das evidências de `evidencias-bloco-2-t006.zip`. O log de `.\mvnw.cmd clean verify` registra `BUILD SUCCESS`, pacote gerado e 33 testes aprovados: 15 unitários e 18 de integração, sem falhas, erros ou testes ignorados, com MySQL 5.7.44. A análise sintática da estrutura de controle foi aprovada. JaCoCo: 210/221 linhas (95,0%) e 37/42 ramos (88,1%).

**Decisão consolidada:** campos `String` para número, senha recebida e hash; `BigDecimal` para saldo e débito, com validações em `ValidacaoCartao` e `RegrasMonetarias`. A revisão de T006 comprovou precisão e escala desde o JSON, rejeição de casas excedentes sem débito, aceitação de `10` e `10.00`, BASIC inválido nas três rotas e respostas `500`/`503` pelo MVC. Modelos e DTOs ocultam senha/hash nas representações textuais.

**Evidências examinadas:** `src/`, `pom.xml`, `target/surefire-reports/`, `target/failsafe-reports/`, `target/site/jacoco/` e `mini-autorizador-clean-verify-t006.log`, reunidos no pacote citado. O aceite decorre da conferência desses arquivos; o revisor não reexecutou o Maven. O responsável também informou nova execução local de `clean verify` sem falhas.

Sem pendências impeditivas do Bloco 2. A conclusão da revisão geral e da documentação final está registrada no Bloco 4.

## 4. Bloco 3 — Idempotência e recuperação

| ID | Estado | Trabalho previsto | Aceite verificável |
| --- | --- | --- | --- |
| T007 | Concluída | Implementar identificação opcional, normalização, HMAC com segredo persistente compartilhado, reserva durável com geração interna e execução com resultado na mesma transação do débito. Integrar ao endpoint. | Testes HTTP/MySQL cobrem P-07 a P-09: cabeçalho vazio ou UUID inválido retorna `400` sem reserva nem autorização; equivalência numérica; divergência de conteúdo; reprodução de aprovações e recusas após mudança do cartão ou reinício. Sem chave, chamadas continuam independentes; entradas inválidas e falhas não viram resultados concluídos. |
| T008 | Concluída | Implementar comparação de conteúdo antes da espera, disputas e revalidação da geração sob bloqueio, prazo único, retenção, publicação de nova reserva após expiração e retomada exclusiva de pendências. | Testes coordenados entre contextos independentes comprovam P-08, P-10 e P-11: conteúdo divergente durante processamento retorna `CHAVE_IDEMPOTENCIA_REUTILIZADA`, inclusive após reutilização de chave expirada; mesmo conteúdo recebe resultado dentro da espera ou resposta transitória com cabeçalho correto no limite total de 5 segundos. Comprovar retenção de 24 horas, ausência de expiração de pendências e débito único. |
| T009 | Concluída | Completar tratamento de deadlock, rollback, commit incerto e recuperação após falhas, respeitando os limites de retentativa e a continuidade do segredo HMAC previstos no plano. | Injetar falhas entre débito e resultado, antes do commit e após commit com perda de resposta. Comprovar saldo/resultado consistentes, retomada após reinício de reserva pendente há mais de 24 horas com reconhecimento do conteúdo e débito único, reprodução do resultado confirmado e ausência de retentativa automática insegura sem chave. |

**Saída do bloco:** operação identificada reproduzível entre instâncias e após reinício, com falhas e expiração verificadas em MySQL.

**Conclusão do bloco:** T007 a T009 concluídas e aceitas em 18/10/2026, após revisão dos fontes e das evidências de `evidencias-bloco-3-t008-final.zip`. O log de `.\mvnw.cmd clean verify` registra `BUILD SUCCESS`, código de saída 0 e 62 testes aprovados: 17 unitários e 45 de integração, sem falhas, erros ou testes ignorados, com MySQL 5.7.44. A análise sintática da estrutura de controle foi aprovada. JaCoCo: 396/437 linhas (90,6%) e 68/92 ramos (73,9%).

**Aceite comprovado:** identificação e reprodução duráveis, concorrência entre contextos independentes, divergência de conteúdo, retenção de 24 horas, publicação independente de nova reserva após expiração e retomada de pendências antigas. O prazo comum de cinco segundos preserva o tempo útil entre bloqueios sucessivos; os testes demonstram conclusão após espera parcial, reprodução na parte final da janela e resposta transitória com `Retry-After: 1`. Rollback e recuperação automática por chave após commit incerto preservam saldo e resultado, sem débito duplicado ou retentativa insegura sem chave.

**Evidências examinadas:** `src/`, `pom.xml`, `target/surefire-reports/`, `target/failsafe-reports/`, `target/site/jacoco/`, `clean-verify-final.log` e `clean-verify-final-exit-code.txt`, reunidos no pacote citado. Os fontes correspondem aos apresentados pelo JaCoCo. O aceite decorre da inspeção desses arquivos; o revisor não reexecutou o Maven.

Sem pendências impeditivas do Bloco 3. A conclusão de T010 e T011 está registrada no Bloco 4.


## 5. Bloco 4 — Verificação integrada e entrega

| ID | Estado | Trabalho previsto | Aceite verificável |
| --- | --- | --- | --- |
| T010 | Concluída | Revisar fronteiras arquiteturais, legibilidade, APIs depreciadas, avisos de recursos e proteção de dados sensíveis. Executar `./mvnw verify`, corrigir falhas e conferir os seis grupos de aceite do `spec.md`. | Suíte unitária, HTTP e MySQL executada; cobertura JaCoCo examinada e lacunas relevantes corrigidas; análise sintática da estrutura de controle aprovada em produção e testes; pacote gerado. Testes ausentes ou bloqueados não são apresentados como aprovados. |
| T011 | Concluída | Consolidar o README com base no resultado verificado: preparação, configuração, execução, testes, exemplos e decisões relevantes. Atualizar o estado das tarefas com as evidências recebidas. | Instruções correspondem aos comandos e comportamentos comprovados; exemplos incluem chamadas com e sem chave; requisitos de segredo compartilhado e limites conhecidos estão claros, sem dados privados. |

**Conclusão de T010:** concluída e aceita em 18/10/2026 após conferência de `evidencias-bloco-4-t010.zip`. O log de `.\mvnw.cmd clean verify` registra `BUILD SUCCESS` e código de saída 0. Os XMLs confirmam 79 testes aprovados, sem falhas, erros ou ignorados: 30 unitários (incluindo análise sintática) e 49 integrações (6 de fundação e 43 HTTP/MySQL), com MySQL 5.7.44 real. JaCoCo: 429/437 linhas (98,17%) e 84/96 ramos (87,50%). Pacote executável gerado.

**Revisão final:** fronteiras da aplicação e tradução HTTP ajustadas; APIs depreciadas e avisos de recursos revisados. A espera JDBC usa o tempo restante como timeout de rede, preservando a exceção original quando o rollback encontra conexão encerrada; a revalidação da geração/conteúdo e as asserções de prazo foram mantidas. Os seis grupos de aceite têm correspondência com testes aprovados, incluindo rollback antes do commit, recuperação por chave e ausência de débito duplicado. Senhas, hash e segredo HMAC permanecem protegidos.

**Evidências examinadas:** fontes e configurações em `codigo/`, XMLs e relatórios completos em `relatorios/`, `clean-verify-final.log`, `clean-verify-final-exit-code.txt`, `matriz-aceite.csv`, `inventario.csv`, `resumo.md` e JAR em `pacote/`. A configuração da aplicação, o build, a declaração MySQL e o adaptador de prazo correspondem ao repositório consultado. O aceite decorre da inspeção das evidências, sem reexecução do Maven pelo revisor. HTTP foi exercitado com MockMvc, e reinício, por novos contextos Spring; não houve ensaio adicional de `java -jar`.

**Limites registrados:** lacunas residuais de cobertura foram justificadas. Avisos de CDS, informação de suporte do Flyway ao MySQL legado e mensagens esperadas nos cenários de timeout/rollback não impediram o aceite. Não há verificações obrigatórias ausentes ou bloqueadas na execução final.

**Conclusão de T011:** concluída em 18/10/2026. README consolidado com preparação, variáveis de ambiente, segredo HMAC obrigatório e compartilhado, execução Windows/Linux, testes, relatórios, contratos, exemplos com e sem chave, decisões e limites comprovados. Conteúdo conferido com fontes, configurações e evidências da T010; estados e registros deste arquivo atualizados. O README não identifica o cliente, não reproduz o material privado e não aponta para arquivos específicos de specs. Não foram alterados código, contratos, princípios, `AGENTS.md` ou constituição; não houve escrita no GitHub.

Ao encerrar cada bloco, devolver um relatório curto com status, resultado, arquivos alterados, verificações executadas, localização das evidências e pendências. Na interrupção, indicar o próximo passo para retomada.

**Encerramento:** T001 a T010 verificadas e evidências entregues; T011 concluída pelo assistente de documentação. Implementação e entrega documental integral concluídas, sem pendências impeditivas. A aplicação destes documentos ao repositório permanece a cargo do responsável.



# Diretrizes para agentes — mini-autorizador-ex

## Fluxo de trabalho

- Responda e escreva a documentação em Português Brasileiro.
- Antes de atuar, leia `specs/constitution.md` e os artefatos SDD aplicáveis (`spec.md`, `plan.md` e `tasks.md`), quando disponíveis.
- Execute somente a tarefa autorizada. Trabalho documental não autoriza implementação; trabalho de implementação não autoriza alterar a governança ou ampliar requisitos.
- Registre conflitos e lacunas e obtenha uma definição antes de implementar o ponto afetado.
- Preserve alterações preexistentes. Operações de escrita no GitHub, commits e publicação exigem autorização explícita.

## Organização do projeto

- Código de produção: `src/main/java/br/com/miniautorizador`, nos pacotes `cartao`, `transacao` e `idempotencia`. Reserve `shared` para elementos efetivamente compartilhados.
- Dentro de cada pacote, respeite as responsabilidades existentes: `domain`, `application` e `adapter`; portas ficam em `application/port/in` e `application/port/out`, conforme necessário.
- Recursos: `src/main/resources`; migrações Flyway: `src/main/resources/db/migration`, com nomes como `V1__descricao.sql`.
- Testes: `src/test/java`, espelhando os pacotes de produção; recursos de teste: `src/test/resources`.
- Serviços locais: `docker/docker-compose.yml`. Não introduza dependências ou módulos sem necessidade da tarefa.

## Escrita e revisão

- Use quatro espaços, pacotes em minúsculas, tipos em `UpperCamelCase`, métodos e atributos em `lowerCamelCase` e constantes em `UPPER_SNAKE_CASE`.
- Prefira nomes do domínio e métodos coesos. Remova imports e código sem uso; evite abstrações sem aplicação concreta.
- Revise as fronteiras arquiteturais e a restrição de fluxo da constituição em todo código Java produzido, inclusive testes.
- Preserve os contratos públicos e adicione ou ajuste testes quando houver mudança de comportamento.

## Execução e verificação

Use o Maven Wrapper. No Windows, substitua `./mvnw` por `.\mvnw.cmd`.

| Finalidade | Comando |
| --- | --- |
| Executar testes | `./mvnw test` |
| Gerar o pacote | `./mvnw package` |
| Executar as verificações configuradas | `./mvnw verify` |
| Iniciar o MySQL local | `docker compose -f docker/docker-compose.yml up -d mysql` |
| Iniciar a API | `./mvnw spring-boot:run` |

- A execução da API depende da classe de entrada e da configuração de conexão implementadas.
- Em tarefas de código, execute as verificações pertinentes e informe os resultados reais. Diferencie testes executados, ausentes e bloqueados; um build sem testes não comprova comportamento.
- Alterações de persistência, concorrência e idempotência exigem evidências com MySQL, conforme os princípios da constituição.

## Documentação e entrega

- Mantenha cada artefato em seu propósito. Registre decisões e suposições aprovadas no artefato correspondente e sintetize as relevantes no README público.
- Use o enunciado recebido apenas como fonte de consulta: não o altere, não o copie para o repositório e não publique referências que identifiquem o cliente.
- Não registre hashes, tags ou referências a revisões do Git nos documentos.
- Ao concluir, informe arquivos alterados, comportamento ou conteúdo resultante, verificações e pendências. Atualize documentação e estado das tarefas somente quando autorizado.

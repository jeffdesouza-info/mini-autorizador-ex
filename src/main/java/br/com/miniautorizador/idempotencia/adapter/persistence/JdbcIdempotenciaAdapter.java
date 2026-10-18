package br.com.miniautorizador.idempotencia.adapter.persistence;

import br.com.miniautorizador.idempotencia.application.EsperaConcorrenteIdempotenteException;
import br.com.miniautorizador.idempotencia.application.port.out.IdempotenciaPort;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaExcedidoException;
import br.com.miniautorizador.shared.adapter.persistence.PrazoEsperaMySql;
import br.com.miniautorizador.idempotencia.application.ResultadoOperacaoIdempotente;
import br.com.miniautorizador.transacao.domain.ResultadoAutorizacao;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcIdempotenciaAdapter implements IdempotenciaPort {

    private static final String ROTA = "POST /transacoes";
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    public JdbcIdempotenciaAdapter(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
    }

    @Override
    public Reserva reservar(String chave, byte[] impressaoDigital, PrazoConcorrenciaIdempotente prazo) {
        Reserva existente = selecionarOpcional(chave, false, prazo).orElse(null);
        return Optional.ofNullable(existente)
                .filter(reserva -> !reserva.expirada())
                .orElseGet(() -> criarOuSubstituirReserva(chave, impressaoDigital, prazo));
    }

    private Reserva criarOuSubstituirReserva(String chave, byte[] impressaoDigital,
            PrazoConcorrenciaIdempotente prazo) {
        try {
            return transacionar(() -> reservarNaTransacao(chave, impressaoDigital, prazo));
        } catch (DuplicateKeyException disputa) {
            return transacionar(() -> bloquearEReservarSeExpirada(chave, impressaoDigital, prazo));
        }
    }

    @Override
    public Optional<ResultadoOperacaoIdempotente> executar(String chave, long geracaoEsperada,
            byte[] impressaoDigital, PrazoConcorrenciaIdempotente prazo,
            Supplier<ResultadoAutorizacao> autorizacao) {
        return transacionar(() -> {
            Reserva reservaBloqueada = selecionar(chave, true, prazo);
            // A geração vigente é sempre revalidada sob bloqueio, mesmo se a esperada ficou obsoleta.
            return reservaBloqueada.expirada() ? Optional.empty()
                    : Optional.of(avaliarReservaVigente(chave, reservaBloqueada, impressaoDigital, autorizacao));
        });
    }

    @Override
    public Optional<Reserva> consultar(String chave) {
        return selecionarOpcional(chave, false, null);
    }

    private Reserva reservarNaTransacao(String chave, byte[] digital, PrazoConcorrenciaIdempotente prazo) {
        List<Reserva> existentes = selecionarLista(chave, true, prazo);
        return Optional.of(existentes)
                .filter(lista -> !lista.isEmpty())
                .map(lista -> substituirSeExpirada(chave, digital, lista.getFirst()))
                .orElseGet(() -> inserir(chave, digital, prazo));
    }

    private Reserva bloquearEReservarSeExpirada(String chave, byte[] digital,
            PrazoConcorrenciaIdempotente prazo) {
        Reserva atual = selecionar(chave, true, prazo);
        return substituirSeExpirada(chave, digital, atual);
    }

    private Reserva substituirSeExpirada(String chave, byte[] digital, Reserva atual) {
        return Optional.of(atual.expirada())
                .filter(Boolean::booleanValue)
                .map(ignorado -> {
                    jdbcTemplate.update("UPDATE idempotencia SET geracao = geracao + 1, impressao_digital = ?, "
                            + "estado = 'PENDENTE', status_http = NULL, corpo_resposta = NULL, tipo_midia = NULL, "
                            + "criada_em = UTC_TIMESTAMP(6), concluida_em = NULL, expira_em = NULL "
                            + "WHERE rota = ? AND chave = ?", digital, ROTA, chave);
                    return selecionar(chave, false, null);
                })
                .orElse(atual);
    }

    private ResultadoOperacaoIdempotente avaliarReservaVigente(String chave, Reserva reserva, byte[] digital,
            Supplier<ResultadoAutorizacao> autorizacao) {
        return Optional.of(reserva)
                .filter(atual -> !java.security.MessageDigest.isEqual(atual.impressaoDigital(), digital))
                .map(ignorado -> ResultadoOperacaoIdempotente.chaveReutilizada())
                .orElseGet(() -> Optional.of(reserva)
                        .filter(Reserva::concluida)
                        .map(this::resultado)
                        .orElseGet(() -> concluir(chave, autorizacao.get())));
    }

    private Reserva inserir(String chave, byte[] digital, PrazoConcorrenciaIdempotente prazo) {
        try {
            jdbcTemplate.update("INSERT INTO idempotencia (rota, chave, impressao_digital, estado) "
                    + "VALUES (?, ?, ?, 'PENDENTE')", statement -> {
                        statement.setString(1, ROTA);
                        statement.setString(2, chave);
                        statement.setBytes(3, digital);
                        PrazoEsperaMySql.aplicar(statement, prazo);
                    });
        } catch (DataAccessException | PrazoConcorrenciaExcedidoException falha) {
            throw classificarEspera(falha);
        }
        return selecionar(chave, false, null);
    }

    private ResultadoOperacaoIdempotente concluir(String chave, ResultadoAutorizacao autorizacao) {
        int status = autorizacao.aprovada() ? 201 : 422;
        String corpo = autorizacao.motivoRecusa().map(Enum::name).orElse("OK");
        jdbcTemplate.update("UPDATE idempotencia SET estado = 'CONCLUIDA', status_http = ?, corpo_resposta = ?, "
                + "tipo_midia = 'text/plain', concluida_em = UTC_TIMESTAMP(6), "
                + "expira_em = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 24 HOUR) WHERE rota = ? AND chave = ?",
                status, corpo, ROTA, chave);
        return new ResultadoOperacaoIdempotente(status, corpo, "text/plain", false);
    }

    private ResultadoOperacaoIdempotente resultado(Reserva reserva) {
        return new ResultadoOperacaoIdempotente(reserva.status(), reserva.corpo(), reserva.tipoMidia(), false);
    }

    private Reserva selecionar(String chave, boolean bloquear, PrazoConcorrenciaIdempotente prazo) {
        return selecionarOpcional(chave, bloquear, prazo).orElseThrow(IllegalStateException::new);
    }

    private Optional<Reserva> selecionarOpcional(String chave, boolean bloquear,
            PrazoConcorrenciaIdempotente prazo) {
        return selecionarLista(chave, bloquear, prazo).stream().findFirst();
    }

    private List<Reserva> selecionarLista(String chave, boolean bloquear, PrazoConcorrenciaIdempotente prazo) {
        String sql = "SELECT geracao, impressao_digital, estado, status_http, corpo_resposta, tipo_midia, "
                + "(estado = 'CONCLUIDA' AND expira_em <= UTC_TIMESTAMP(6)) AS expirada FROM idempotencia "
                + "WHERE rota = ? AND chave = ?" + (bloquear ? " FOR UPDATE" : "");
        try {
            return jdbcTemplate.query(sql, statement -> {
                statement.setString(1, ROTA);
                statement.setString(2, chave);
                Optional.ofNullable(prazo).ifPresent(limite -> {
                    try {
                        PrazoEsperaMySql.aplicar(statement, limite);
                    } catch (SQLException falha) {
                        throw new org.springframework.dao.DataAccessResourceFailureException(
                                "Não foi possível configurar o prazo da consulta.", falha);
                    }
                });
            }, (resultSet, rowNumber) -> new Reserva(
                    resultSet.getLong("geracao"), resultSet.getBytes("impressao_digital"),
                    "CONCLUIDA".equals(resultSet.getString("estado")),
                    (Integer) resultSet.getObject("status_http"), resultSet.getString("corpo_resposta"),
                    resultSet.getString("tipo_midia"), resultSet.getBoolean("expirada")));
        } catch (DataAccessException | PrazoConcorrenciaExcedidoException falha) {
            throw bloquear ? classificarEspera(falha) : falha;
        }
    }

    private TransactionTemplate novaTransacao() {
        TransactionTemplate transacao = new TransactionTemplate(transactionManager);
        transacao.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transacao.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transacao;
    }

    private <T> T transacionar(Supplier<T> operacao) {
        AtomicReference<RuntimeException> falhaOperacao = new AtomicReference<>();
        try {
            return novaTransacao().execute(status -> {
                try {
                    return operacao.get();
                } catch (RuntimeException falha) {
                    falhaOperacao.set(falha);
                    throw falha;
                }
            });
        } catch (RuntimeException falhaNoEncerramento) {
            // Uma conexão encerrada por timeout também pode recusar o rollback JDBC.
            // Preservar a falha da operação permite distinguir a espera da falha técnica.
            RuntimeException original = Optional.ofNullable(falhaOperacao.get()).orElse(falhaNoEncerramento);
            Optional.of(falhaNoEncerramento).filter(falha -> falha != original).ifPresent(original::addSuppressed);
            throw original;
        }
    }

    private RuntimeException classificarEspera(RuntimeException falha) {
        return Optional.of(falha)
                .filter(excecao -> excecao instanceof PrazoConcorrenciaExcedidoException
                        || ehTimeoutDeBloqueioMysql(excecao))
                .<RuntimeException>map(EsperaConcorrenteIdempotenteException::new)
                .orElse(falha);
    }

    private boolean ehTimeoutDeBloqueioMysql(Throwable falha) {
        return Stream.iterate(falha, Objects::nonNull, Throwable::getCause)
                .anyMatch(excecao -> excecao instanceof SocketTimeoutException
                        || excecao instanceof SQLTimeoutException
                        || excecao instanceof SQLException sql && sql.getErrorCode() == 1205);
    }
}

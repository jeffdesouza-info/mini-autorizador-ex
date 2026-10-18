package br.com.miniautorizador.idempotencia.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.miniautorizador.idempotencia.application.EsperaConcorrenteIdempotenteException;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente;
import br.com.miniautorizador.idempotencia.application.port.out.IdempotenciaPort;
import jakarta.persistence.PersistenceException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;

class JdbcIdempotenciaAdapterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transacoes = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final JdbcIdempotenciaAdapter adaptador = new JdbcIdempotenciaAdapter(jdbc, transacoes);

    @Test
    void timeoutNaReservaPreservaEsperaMesmoQuandoRollbackFalha() {
        when(transacoes.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class),
                ArgumentMatchers.<RowMapper<IdempotenciaPort.Reserva>>any()))
                .thenThrow(new DataAccessResourceFailureException("Leitura interrompida", new SocketTimeoutException()));
        JpaSystemException falhaRollback = new JpaSystemException(new PersistenceException("Conexão encerrada"));
        doThrow(falhaRollback).when(transacoes).rollback(status);

        assertThatThrownBy(() -> adaptador.executar("chave", 1, new byte[32],
                PrazoConcorrenciaIdempotente.iniciar(), () -> {
                    throw new AssertionError("Não pode autorizar sem adquirir a reserva.");
                })).isInstanceOf(EsperaConcorrenteIdempotenteException.class)
                .hasSuppressedException(falhaRollback);
        verify(transacoes).rollback(status);
    }

    @Test
    void falhaDeCommitSemFalhaDaOperacaoNaoEEncoberta() {
        when(transacoes.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class),
                ArgumentMatchers.<RowMapper<IdempotenciaPort.Reserva>>any()))
                .thenReturn(List.of(new IdempotenciaPort.Reserva(1, new byte[32], true, 201, "OK", "text/plain", false)));
        TransactionSystemException commitIncerto = new TransactionSystemException("Confirmação indisponível");
        doThrow(commitIncerto).when(transacoes).commit(status);

        assertThatThrownBy(() -> adaptador.executar("chave", 1, new byte[32],
                PrazoConcorrenciaIdempotente.iniciar(), () -> {
                    throw new AssertionError("Resultado concluído não pode executar novo débito.");
                })).isSameAs(commitIncerto);
        verify(transacoes).commit(status);
    }
}

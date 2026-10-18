package br.com.miniautorizador.shared.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaExcedidoException;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;

class PrazoEsperaMySqlTest {

    @Test
    void prazoEsgotadoNaoDesabilitaTimeoutDoDriver() throws Exception {
        try (PreparedStatement statement = mock(PreparedStatement.class)) {
            Connection connection = mock(Connection.class);
            when(statement.getConnection()).thenReturn(connection);
            PrazoConcorrenciaIdempotente prazo = new PrazoConcorrenciaIdempotente(System.nanoTime() - 1);

            assertThatThrownBy(() -> PrazoEsperaMySql.aplicar(statement, prazo))
                    .isInstanceOf(PrazoConcorrenciaExcedidoException.class);
            verify(connection, never()).setNetworkTimeout(any(), anyInt());
        }
    }
}

package br.com.miniautorizador.shared.adapter.persistence;

import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaExcedidoException;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente;
import com.mysql.cj.jdbc.JdbcStatement;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

public final class PrazoEsperaMySql {

    private PrazoEsperaMySql() {
    }

    public static void aplicar(PreparedStatement statement, PrazoConcorrenciaIdempotente prazo) throws SQLException {
        JdbcStatement mysql = statement.unwrap(JdbcStatement.class);
        // O timer do Connector/J usa milissegundos; zero desabilitaria o cancelamento.
        // A fração final não inicia outra espera nem é arredondada para cima.
        while (prazo.temTempoRestante() && prazo.milissegundosRestantes() == 0) {
            LockSupport.parkNanos(prazo.nanosRestantes());
        }
        int millis = Optional.of(prazo.milissegundosRestantes()).filter(valor -> valor > 0)
                .orElseThrow(PrazoConcorrenciaExcedidoException::new);
        mysql.setTimeoutInMillis(millis);
    }
}

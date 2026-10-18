package br.com.miniautorizador.shared.adapter.persistence;

import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaExcedidoException;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.locks.LockSupport;

public final class PrazoEsperaMySql {

    private PrazoEsperaMySql() {
    }

    public static void aplicar(PreparedStatement statement, PrazoConcorrenciaIdempotente prazo) throws SQLException {
        // O timeout de rede encerra a leitura sem abrir outra conexão para KILL QUERY.
        // A conexão lógica do Hikari rastreia e restaura esse limite ao voltar ao pool.
        // Zero desabilitaria o limite de rede.
        // A fração final não inicia outra espera nem é arredondada para cima.
        while (prazo.temTempoRestante() && prazo.milissegundosRestantes() == 0) {
            LockSupport.parkNanos(prazo.nanosRestantes());
        }
        int millis = Optional.of(prazo.milissegundosRestantes()).filter(valor -> valor > 0)
                .orElseThrow(PrazoConcorrenciaExcedidoException::new);
        statement.getConnection().setNetworkTimeout(Runnable::run, millis);
    }
}

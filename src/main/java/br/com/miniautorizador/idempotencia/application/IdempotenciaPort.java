package br.com.miniautorizador.idempotencia.application;

import java.util.Optional;
import java.util.function.Supplier;
import br.com.miniautorizador.transacao.domain.ResultadoAutorizacao;

public interface IdempotenciaPort {

    Reserva reservar(String chave, byte[] impressaoDigital, PrazoConcorrenciaIdempotente prazo);

    Optional<ResultadoOperacaoIdempotente> executar(String chave, long geracaoEsperada, byte[] impressaoDigital,
            PrazoConcorrenciaIdempotente prazo,
            Supplier<ResultadoAutorizacao> autorizacao);

    Optional<Reserva> consultar(String chave);

    record Reserva(long geracao, byte[] impressaoDigital, boolean concluida, Integer status,
            String corpo, String tipoMidia, boolean expirada) {
    }
}

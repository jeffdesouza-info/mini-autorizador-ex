package br.com.miniautorizador.cartao.application.port.out;

import br.com.miniautorizador.cartao.domain.Cartao;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente;
import java.math.BigDecimal;
import java.util.Optional;

public interface CartaoPort {

    boolean criar(Cartao cartao);

    Optional<Cartao> buscar(String numeroCartao);

    Optional<Cartao> bloquearParaAutorizacao(String numeroCartao);

    Optional<Cartao> bloquearParaAutorizacao(String numeroCartao, PrazoConcorrenciaIdempotente prazo);

    int debitarSaldo(String numeroCartao, BigDecimal valor);
}

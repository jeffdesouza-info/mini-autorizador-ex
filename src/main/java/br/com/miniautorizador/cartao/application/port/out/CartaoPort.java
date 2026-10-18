package br.com.miniautorizador.cartao.application.port.out;

import br.com.miniautorizador.cartao.domain.Cartao;
import java.math.BigDecimal;
import java.util.Optional;

public interface CartaoPort {

    boolean criar(Cartao cartao);

    Optional<Cartao> buscar(String numeroCartao);

    Optional<Cartao> bloquearParaAutorizacao(String numeroCartao);

    int debitarSaldo(String numeroCartao, BigDecimal valor);
}

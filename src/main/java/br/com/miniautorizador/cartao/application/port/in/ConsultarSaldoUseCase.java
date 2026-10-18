package br.com.miniautorizador.cartao.application.port.in;

import java.math.BigDecimal;
import java.util.Optional;

public interface ConsultarSaldoUseCase {

    Optional<BigDecimal> executar(String numeroCartao);
}

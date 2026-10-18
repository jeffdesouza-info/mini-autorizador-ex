package br.com.miniautorizador.transacao.domain;

import br.com.miniautorizador.cartao.domain.Cartao;
import br.com.miniautorizador.shared.domain.RegrasMonetarias;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record AuthorizationContext(Optional<Cartao> cartao, boolean senhaValida, BigDecimal valor) {

    public AuthorizationContext {
        Objects.requireNonNull(cartao);
        valor = RegrasMonetarias.debito(valor);
    }
}

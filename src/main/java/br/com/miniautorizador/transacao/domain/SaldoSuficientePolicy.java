package br.com.miniautorizador.transacao.domain;

import java.util.Optional;

public final class SaldoSuficientePolicy implements AuthorizationPolicy {

    @Override
    public Optional<MotivoRecusa> avaliar(AuthorizationContext contexto) {
        return contexto.cartao()
                .filter(cartao -> cartao.saldo().compareTo(contexto.valor()) >= 0)
                .map(cartao -> Optional.<MotivoRecusa>empty())
                .orElseGet(() -> Optional.of(MotivoRecusa.SALDO_INSUFICIENTE));
    }
}

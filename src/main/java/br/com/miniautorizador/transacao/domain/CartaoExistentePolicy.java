package br.com.miniautorizador.transacao.domain;

import java.util.Optional;

public final class CartaoExistentePolicy implements AuthorizationPolicy {

    @Override
    public Optional<MotivoRecusa> avaliar(AuthorizationContext contexto) {
        return contexto.cartao().isPresent()
                ? Optional.empty()
                : Optional.of(MotivoRecusa.CARTAO_INEXISTENTE);
    }
}

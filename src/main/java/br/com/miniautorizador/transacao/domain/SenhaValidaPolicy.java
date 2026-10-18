package br.com.miniautorizador.transacao.domain;

import java.util.Optional;

public final class SenhaValidaPolicy implements AuthorizationPolicy {

    @Override
    public Optional<MotivoRecusa> avaliar(AuthorizationContext contexto) {
        return contexto.senhaValida()
                ? Optional.empty()
                : Optional.of(MotivoRecusa.SENHA_INVALIDA);
    }
}

package br.com.miniautorizador.transacao.domain;

import java.util.Optional;

public interface AuthorizationPolicy {

    Optional<MotivoRecusa> avaliar(AuthorizationContext contexto);
}

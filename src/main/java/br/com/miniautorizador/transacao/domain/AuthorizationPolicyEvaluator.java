package br.com.miniautorizador.transacao.domain;

import java.util.List;
import java.util.Objects;

public final class AuthorizationPolicyEvaluator {

    private final List<AuthorizationPolicy> policies;

    public AuthorizationPolicyEvaluator(List<AuthorizationPolicy> policies) {
        this.policies = List.copyOf(Objects.requireNonNull(policies));
    }

    public ResultadoAutorizacao avaliar(AuthorizationContext contexto) {
        return policies.stream()
                .map(policy -> policy.avaliar(contexto))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::orElseThrow)
                .findFirst()
                .map(ResultadoAutorizacao::recusada)
                .orElseGet(ResultadoAutorizacao::sucesso);
    }
}

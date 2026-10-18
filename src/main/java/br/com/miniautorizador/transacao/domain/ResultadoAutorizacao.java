package br.com.miniautorizador.transacao.domain;

import java.util.Optional;

public record ResultadoAutorizacao(Optional<MotivoRecusa> motivoRecusa) {

    public static ResultadoAutorizacao sucesso() {
        return new ResultadoAutorizacao(Optional.empty());
    }

    public static ResultadoAutorizacao recusada(MotivoRecusa motivo) {
        return new ResultadoAutorizacao(Optional.of(motivo));
    }

    public boolean aprovada() {
        return motivoRecusa.isEmpty();
    }
}

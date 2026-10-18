package br.com.miniautorizador.idempotencia.application;

public record ResultadoOperacaoIdempotente(int status, String corpo, String tipoMidia, boolean emProcessamento) {

    public static ResultadoOperacaoIdempotente processamento() {
        return new ResultadoOperacaoIdempotente(409, "OPERACAO_EM_PROCESSAMENTO", "text/plain", true);
    }

    public static ResultadoOperacaoIdempotente chaveReutilizada() {
        return new ResultadoOperacaoIdempotente(409, "CHAVE_IDEMPOTENCIA_REUTILIZADA", "text/plain", false);
    }
}

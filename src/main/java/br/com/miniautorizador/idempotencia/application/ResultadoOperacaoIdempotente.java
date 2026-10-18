package br.com.miniautorizador.idempotencia.application;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public record ResultadoOperacaoIdempotente(int status, String corpo, String tipoMidia, boolean emProcessamento) {

    public ResponseEntity<String> paraHttp() {
        ResponseEntity.BodyBuilder resposta = ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(tipoMidia));
        return emProcessamento ? resposta.header(HttpHeaders.RETRY_AFTER, "1").body(corpo) : resposta.body(corpo);
    }

    public static ResultadoOperacaoIdempotente processamento() {
        return new ResultadoOperacaoIdempotente(409, "OPERACAO_EM_PROCESSAMENTO", "text/plain", true);
    }

    public static ResultadoOperacaoIdempotente chaveReutilizada() {
        return new ResultadoOperacaoIdempotente(409, "CHAVE_IDEMPOTENCIA_REUTILIZADA", "text/plain", false);
    }
}

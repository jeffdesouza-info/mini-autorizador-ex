package br.com.miniautorizador.shared.adapter.web;

import br.com.miniautorizador.cartao.application.port.in.ResultadoCriacaoCartao;
import br.com.miniautorizador.transacao.domain.ResultadoAutorizacao;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
class ApiResponseTranslator {

    ResponseEntity<RespostaCartao> cartao(ResultadoCriacaoCartao resultado, String numero, String senha) {
        HttpStatus status = resultado == ResultadoCriacaoCartao.CRIADO ? HttpStatus.CREATED
                : HttpStatus.UNPROCESSABLE_CONTENT;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new RespostaCartao(senha, numero));
    }

    ResponseEntity<String> saldo(Optional<BigDecimal> saldo) {
        return saldo.map(valor -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(valor.toPlainString()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    ResponseEntity<String> transacao(ResultadoAutorizacao resultado) {
        return resultado.motivoRecusa()
                .map(motivo -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(motivo.name()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CREATED)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("OK"));
    }

    record RespostaCartao(String senha, String numeroCartao) {
        @Override
        public String toString() {
            return "RespostaCartao[senha=[PROTEGIDA], numeroCartao=" + numeroCartao + "]";
        }
    }
}

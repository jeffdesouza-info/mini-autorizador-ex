package br.com.miniautorizador.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void traduzIndisponibilidadeComo503SemDetalhesInternos() {
        var resposta = handler.servicoIndisponivel(new TransientDataAccessResourceException("segredo interno"));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resposta.getHeaders().getContentType().toString()).isEqualTo("application/json");
        assertThat(resposta.getBody().codigo()).isEqualTo("SERVICO_INDISPONIVEL");
        assertThat(resposta.getBody().mensagem()).doesNotContain("segredo interno");
    }

    @Test
    void traduzFalhaInesperadaComo500SemExporExcecao() {
        var resposta = handler.falhaInesperada(new IllegalStateException("detalhe interno"));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resposta.getBody().codigo()).isEqualTo("ERRO_INTERNO");
        assertThat(resposta.getBody().mensagem()).doesNotContain("detalhe interno");
    }
}

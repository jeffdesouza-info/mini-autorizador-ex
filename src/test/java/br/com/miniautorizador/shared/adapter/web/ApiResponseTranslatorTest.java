package br.com.miniautorizador.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTranslatorTest {

    @Test
    void dtoDeRespostaOcultaSenhaNosRegistrosDeDepuracao() {
        ApiResponseTranslator.RespostaCartao resposta =
                new ApiResponseTranslator.RespostaCartao("0042", "0000000000000001");

        assertThat(resposta.toString()).doesNotContain("0042");
    }
}

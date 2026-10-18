package br.com.miniautorizador.shared.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ApiRequestParserTest {

    @Test
    void dtoDeEntradaOcultaSenhaNosRegistrosDeDepuracao() {
        ApiRequestParser.DadosCartao dadosCartao = new ApiRequestParser.DadosCartao("0000000000000001", "0042");
        ApiRequestParser.DadosTransacao dadosTransacao = new ApiRequestParser.DadosTransacao(
                "0000000000000001", "0042", new BigDecimal("1.00"));

        assertThat(dadosCartao.toString()).doesNotContain("0042");
        assertThat(dadosTransacao.toString()).doesNotContain("0042");
    }
}

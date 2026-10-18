package br.com.miniautorizador.cartao.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CartaoTest {

    @Test
    void mantemNumeroComZeroInicialEProtegeHashNoTexto() {
        String hash = "$2a$10$segredo-hash";
        Cartao cartao = new Cartao("0000000000000001", hash, new BigDecimal("500"));

        assertThat(cartao.numeroCartao()).isEqualTo("0000000000000001");
        assertThat(cartao.senhaHash()).isEqualTo(hash);
        assertThat(cartao.toString()).doesNotContain(hash);
    }
}

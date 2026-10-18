package br.com.miniautorizador.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RegrasMonetariasTest {

    @Test
    void normalizaSaldoEValorParaEscalaDoisSemPerderPrecisao() {
        assertThat(RegrasMonetarias.saldo(new BigDecimal("500"))).isEqualByComparingTo("500.00");
        assertThat(RegrasMonetarias.debito(new BigDecimal("3.45"))).isEqualByComparingTo("3.45");
        assertThat(new BigDecimal("10").compareTo(new BigDecimal("10.00"))).isZero();
    }

    @Test
    void permiteSaldoZeroERecusaSaldoNegativo() {
        assertThat(RegrasMonetarias.saldo(new BigDecimal("0"))).isEqualByComparingTo("0.00");
        assertThatThrownBy(() -> RegrasMonetarias.saldo(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exigeDebitoPositivoComNoMaximoDuasCasasSemArredondar() {
        assertThatThrownBy(() -> RegrasMonetarias.debito(new BigDecimal("0.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegrasMonetarias.debito(new BigDecimal("1.001")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

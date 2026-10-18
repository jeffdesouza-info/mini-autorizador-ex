package br.com.miniautorizador.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class RegrasMonetarias {

    private RegrasMonetarias() {
    }

    public static BigDecimal saldo(BigDecimal valor) {
        return normalizar(valor, false);
    }

    public static BigDecimal debito(BigDecimal valor) {
        return normalizar(valor, true);
    }

    public static boolean debitoValido(BigDecimal valor) {
        return valor != null && valor.signum() > 0 && valor.scale() <= 2;
    }

    private static BigDecimal normalizar(BigDecimal valor, boolean deveSerPositivo) {
        Objects.requireNonNull(valor);
        boolean escalaValida = valor.scale() <= 2;
        boolean sinalValido = deveSerPositivo ? valor.signum() > 0 : valor.signum() >= 0;
        return escalaValida && sinalValido
                ? valor.setScale(2, RoundingMode.UNNECESSARY)
                : exigir("Valor monetário inválido");
    }

    private static BigDecimal exigir(String mensagem) {
        throw new IllegalArgumentException(mensagem);
    }
}

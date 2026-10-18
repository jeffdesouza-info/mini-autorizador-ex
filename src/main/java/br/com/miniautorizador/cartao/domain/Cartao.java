package br.com.miniautorizador.cartao.domain;

import br.com.miniautorizador.shared.domain.RegrasMonetarias;
import java.math.BigDecimal;

public record Cartao(String numeroCartao, String senhaHash, BigDecimal saldo) {

    public Cartao {
        numeroCartao = ValidacaoCartao.exigirNumeroCartao(numeroCartao);
        senhaHash = ValidacaoCartao.exigirHashSenha(senhaHash);
        saldo = RegrasMonetarias.saldo(saldo);
    }

    @Override
    public String toString() {
        return "Cartao[numeroCartao=" + numeroCartao + ", senhaHash=[PROTEGIDO], saldo="
                + saldo.toPlainString() + "]";
    }
}

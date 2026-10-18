package br.com.miniautorizador.cartao.adapter.persistence;

import br.com.miniautorizador.cartao.domain.Cartao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "cartao")
class CartaoEntity {

    @Id
    @Column(name = "numero_cartao", length = 16, nullable = false, columnDefinition = "char(16)")
    private String numeroCartao;

    @Column(name = "senha_hash", length = 60, nullable = false)
    private String senhaHash;

    @Column(name = "saldo", precision = 19, scale = 2, nullable = false)
    private BigDecimal saldo;

    protected CartaoEntity() {
    }

    CartaoEntity(String numeroCartao, String senhaHash, BigDecimal saldo) {
        this.numeroCartao = numeroCartao;
        this.senhaHash = senhaHash;
        this.saldo = saldo;
    }

    Cartao paraDominio() {
        return new Cartao(numeroCartao, senhaHash, saldo);
    }

    String numeroCartao() {
        return numeroCartao;
    }

    String senhaHash() {
        return senhaHash;
    }

    BigDecimal saldo() {
        return saldo;
    }
}

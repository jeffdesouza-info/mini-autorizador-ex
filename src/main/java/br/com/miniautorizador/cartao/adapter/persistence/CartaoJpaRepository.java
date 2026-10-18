package br.com.miniautorizador.cartao.adapter.persistence;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface CartaoJpaRepository extends JpaRepository<CartaoEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cartao from CartaoEntity cartao where cartao.numeroCartao = :numeroCartao")
    Optional<CartaoEntity> buscarParaAtualizacao(@Param("numeroCartao") String numeroCartao);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CartaoEntity cartao set cartao.saldo = cartao.saldo - :valor "
            + "where cartao.numeroCartao = :numeroCartao and cartao.saldo >= :valor")
    int debitarComSaldoSuficiente(@Param("numeroCartao") String numeroCartao, @Param("valor") BigDecimal valor);
}

package br.com.miniautorizador.cartao.adapter.persistence;

import br.com.miniautorizador.cartao.application.port.out.CartaoPort;
import br.com.miniautorizador.cartao.domain.Cartao;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente;
import br.com.miniautorizador.shared.adapter.persistence.PrazoEsperaMySql;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CartaoPersistenceAdapter implements CartaoPort {

    private final JdbcTemplate jdbcTemplate;
    private final CartaoJpaRepository repository;

    public CartaoPersistenceAdapter(JdbcTemplate jdbcTemplate, CartaoJpaRepository repository) {
        this.jdbcTemplate = jdbcTemplate;
        this.repository = repository;
    }

    @Override
    @Transactional
    public boolean criar(Cartao cartao) {
        try {
            jdbcTemplate.update("insert into cartao (numero_cartao, senha_hash, saldo) values (?, ?, ?)",
                    cartao.numeroCartao(), cartao.senhaHash(), cartao.saldo());
            return true;
        } catch (DuplicateKeyException excecao) {
            return false;
        }
    }

    @Override
    public Optional<Cartao> buscar(String numeroCartao) {
        return repository.findById(numeroCartao).map(CartaoEntity::paraDominio);
    }

    @Override
    public Optional<Cartao> bloquearParaAutorizacao(String numeroCartao) {
        return repository.buscarParaAtualizacao(numeroCartao).map(CartaoEntity::paraDominio);
    }

    @Override
    public Optional<Cartao> bloquearParaAutorizacao(String numeroCartao, PrazoConcorrenciaIdempotente prazo) {
        return jdbcTemplate.query("SELECT numero_cartao, senha_hash, saldo FROM cartao "
                + "WHERE numero_cartao = ? FOR UPDATE", statement -> {
                    statement.setString(1, numeroCartao);
                    PrazoEsperaMySql.aplicar(statement, prazo);
                }, (resultado, linha) -> new Cartao(resultado.getString("numero_cartao"),
                        resultado.getString("senha_hash"), resultado.getBigDecimal("saldo")))
                .stream().findFirst();
    }

    @Override
    public int debitarSaldo(String numeroCartao, BigDecimal valor) {
        return repository.debitarComSaldoSuficiente(numeroCartao, valor);
    }
}

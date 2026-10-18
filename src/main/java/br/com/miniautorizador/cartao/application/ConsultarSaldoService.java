package br.com.miniautorizador.cartao.application;

import br.com.miniautorizador.cartao.application.port.in.ConsultarSaldoUseCase;
import br.com.miniautorizador.cartao.application.port.out.CartaoPort;
import br.com.miniautorizador.cartao.domain.ValidacaoCartao;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ConsultarSaldoService implements ConsultarSaldoUseCase {

    private final CartaoPort cartaoPort;

    public ConsultarSaldoService(CartaoPort cartaoPort) {
        this.cartaoPort = cartaoPort;
    }

    @Override
    public Optional<BigDecimal> executar(String numeroCartao) {
        return cartaoPort.buscar(ValidacaoCartao.exigirNumeroCartao(numeroCartao)).map(cartao -> cartao.saldo());
    }
}

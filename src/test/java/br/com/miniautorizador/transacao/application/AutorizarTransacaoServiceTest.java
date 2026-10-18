package br.com.miniautorizador.transacao.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import br.com.miniautorizador.cartao.application.port.out.CartaoPort;
import br.com.miniautorizador.cartao.application.port.out.SenhaCartaoPort;
import br.com.miniautorizador.cartao.domain.Cartao;
import br.com.miniautorizador.transacao.domain.CartaoExistentePolicy;
import br.com.miniautorizador.transacao.domain.AuthorizationPolicyEvaluator;
import br.com.miniautorizador.transacao.domain.SaldoSuficientePolicy;
import br.com.miniautorizador.transacao.domain.SenhaValidaPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AutorizarTransacaoServiceTest {

    private final CartaoPort cartaoPort = mock(CartaoPort.class);
    private final SenhaCartaoPort senhaCartaoPort = mock(SenhaCartaoPort.class);
    private final AutorizarTransacaoService service = new AutorizarTransacaoService(cartaoPort, senhaCartaoPort,
            new AuthorizationPolicyEvaluator(List.of()));

    @Test
    void entradasInvalidasFalhamAntesDeBloquearCartao() {
        assertThatThrownBy(() -> service.executar("123", "0042", new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.executar("0000000000000001", "42", new BigDecimal("1.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.executar("0000000000000001", "0042", new BigDecimal("0.00")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.executar("0000000000000001", "0042", new BigDecimal("1.001")))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(cartaoPort, senhaCartaoPort);
    }

    @Test
    void falhaSeAtualizacaoDefensivaNaoDebitarUmaLinha() {
        Cartao cartao = new Cartao("0000000000000001", "$2a$10$hash", new BigDecimal("10.00"));
        when(cartaoPort.bloquearParaAutorizacao(cartao.numeroCartao())).thenReturn(Optional.of(cartao));
        when(senhaCartaoPort.corresponde("0042", cartao.senhaHash())).thenReturn(true);
        when(cartaoPort.debitarSaldo(cartao.numeroCartao(), new BigDecimal("10.00"))).thenReturn(0);
        AutorizarTransacaoService serviceComPoliticas = new AutorizarTransacaoService(cartaoPort, senhaCartaoPort,
                new AuthorizationPolicyEvaluator(
                        List.of(new CartaoExistentePolicy(), new SenhaValidaPolicy(), new SaldoSuficientePolicy())));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> serviceComPoliticas.executar(cartao.numeroCartao(), "0042", new BigDecimal("10.00")))
                .isInstanceOf(AutorizarTransacaoService.SaldoAtualizacaoInesperadaException.class);

        verify(cartaoPort).bloquearParaAutorizacao(cartao.numeroCartao());
        verify(cartaoPort).debitarSaldo(cartao.numeroCartao(), new BigDecimal("10.00"));
    }
}

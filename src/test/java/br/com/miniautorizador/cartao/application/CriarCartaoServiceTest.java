package br.com.miniautorizador.cartao.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import br.com.miniautorizador.cartao.application.port.out.CartaoPort;
import br.com.miniautorizador.cartao.application.port.out.SenhaCartaoPort;
import org.junit.jupiter.api.Test;

class CriarCartaoServiceTest {

    private final CartaoPort cartaoPort = mock(CartaoPort.class);
    private final SenhaCartaoPort senhaCartaoPort = mock(SenhaCartaoPort.class);
    private final CriarCartaoService service = new CriarCartaoService(cartaoPort, senhaCartaoPort);

    @Test
    void entradasInvalidasFalhamAntesDeInteragirComAsPortas() {
        assertThatThrownBy(() -> service.executar("123", "0042"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.executar("0000000000000001", "42"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(cartaoPort, senhaCartaoPort);
    }
}

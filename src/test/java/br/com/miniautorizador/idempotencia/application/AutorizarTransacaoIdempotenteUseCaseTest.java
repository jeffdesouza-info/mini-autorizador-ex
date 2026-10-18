package br.com.miniautorizador.idempotencia.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import br.com.miniautorizador.idempotencia.application.port.out.IdempotenciaPort;
import br.com.miniautorizador.shared.application.InvalidRequestException;
import br.com.miniautorizador.transacao.application.port.in.AutorizarTransacaoUseCase;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AutorizarTransacaoIdempotenteUseCaseTest {

    private static final String CHAVE = "00000000-0000-0000-0000-000000000001";
    private static final String CARTAO = "0000000000000001";
    private static final String SENHA = "0042";
    private static final String SEGREDO_TESTE = "segredo-de-teste-compartilhado-com-32-bytes";
    private final IdempotenciaPort porta = mock(IdempotenciaPort.class);
    private final AutorizarTransacaoUseCase autorizacao = mock(AutorizarTransacaoUseCase.class);
    private final AtomicReference<byte[]> digital = new AtomicReference<>();
    private final AutorizarTransacaoIdempotenteUseCase casoDeUso =
            new AutorizarTransacaoIdempotenteUseCase(porta, autorizacao, SEGREDO_TESTE);

    @Test
    void rejeitaSegredoCurtoAntesDeUsarAsPortas() {
        assertThatThrownBy(() -> new AutorizarTransacaoIdempotenteUseCase(porta, autorizacao, "curto"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(porta, autorizacao);
    }

    @Test
    void rejeitaChavesInvalidasAntesDeReservar() {
        List.of("", "invalida", "1-1-1-1-1").forEach(chave ->
                assertThatThrownBy(() -> executar(chave)).isInstanceOf(InvalidRequestException.class));
        assertThatThrownBy(() -> executar(null)).isInstanceOf(InvalidRequestException.class);
        verifyNoInteractions(porta, autorizacao);
    }

    @Test
    void chamadasDiretasInvalidasNaoCriamReserva() {
        assertThatThrownBy(() -> casoDeUso.executar(CHAVE, null, SENHA, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> casoDeUso.executar(CHAVE, CARTAO, null, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        List.of(BigDecimal.ZERO, new BigDecimal("-1"), new BigDecimal("1.001")).forEach(valor ->
                assertThatThrownBy(() -> casoDeUso.executar(CHAVE, CARTAO, SENHA, valor))
                        .isInstanceOf(IllegalArgumentException.class));
        assertThatThrownBy(() -> casoDeUso.executar(CHAVE, CARTAO, SENHA, null))
                .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(porta, autorizacao);
    }

    @Test
    void disputaNaPublicacaoRecuperaResultadoSemExecutarAutorizacao() {
        when(porta.reservar(eq(CHAVE), any(), any())).thenAnswer(invocacao -> {
            digital.set(invocacao.getArgument(1));
            throw espera();
        });
        when(porta.consultar(CHAVE)).thenAnswer(ignorado -> Optional.of(reserva(true, false)));

        assertThat(executar(CHAVE)).isEqualTo(new ResultadoOperacaoIdempotente(201, "OK", "text/plain", false));
        verify(porta, never()).executar(any(), anyLong(), any(), any(), any());
        verifyNoInteractions(autorizacao);
    }

    @Test
    void esperaConsultaAteResultadoConcluidoSemReexecutar() {
        prepararReserva();
        when(porta.executar(eq(CHAVE), anyLong(), any(), any(), any())).thenThrow(espera());
        when(porta.consultar(CHAVE))
                .thenAnswer(ignorado -> Optional.of(reserva(false, false)))
                .thenAnswer(ignorado -> Optional.of(reserva(true, false)));

        assertThat(executar(CHAVE).status()).isEqualTo(201);
        verify(porta, times(2)).consultar(CHAVE);
        verify(porta, times(1)).executar(eq(CHAVE), anyLong(), any(), any(), any());
        verifyNoInteractions(autorizacao);
    }

    @Test
    void divergenciaDescobertaDuranteEsperaPrevaleceSobreProcessamento() {
        prepararReserva();
        when(porta.executar(eq(CHAVE), anyLong(), any(), any(), any())).thenThrow(espera());
        when(porta.consultar(CHAVE)).thenReturn(Optional.of(
                new IdempotenciaPort.Reserva(2, new byte[32], false, null, null, null, false)));

        assertThat(executar(CHAVE)).isEqualTo(ResultadoOperacaoIdempotente.chaveReutilizada());
        verifyNoInteractions(autorizacao);
    }

    @Test
    void reservaExpiradaDuranteEsperaEPublicadaAntesDaNovaExecucao() {
        prepararReserva();
        when(porta.executar(eq(CHAVE), anyLong(), any(), any(), any()))
                .thenThrow(espera())
                .thenReturn(Optional.of(new ResultadoOperacaoIdempotente(201, "OK", "text/plain", false)));
        when(porta.consultar(CHAVE)).thenAnswer(ignorado -> Optional.of(reserva(true, true)));

        assertThat(executar(CHAVE).status()).isEqualTo(201);
        verify(porta, times(2)).reservar(eq(CHAVE), any(), any());
        verify(porta, times(2)).executar(eq(CHAVE), anyLong(), any(), any(), any());
    }

    @Test
    void expiracaoSobBloqueioReavaliaReservaSemResponderComResultadoAntigo() {
        prepararReserva();
        when(porta.executar(eq(CHAVE), anyLong(), any(), any(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ResultadoOperacaoIdempotente(422, "SALDO_INSUFICIENTE",
                        "text/plain", false)));

        assertThat(executar(CHAVE).corpo()).isEqualTo("SALDO_INSUFICIENTE");
        verify(porta, times(2)).reservar(eq(CHAVE), any(), any());
    }

    @Test
    void ausenciaDaReservaDuranteEsperaEFalhaTecnica() {
        when(porta.reservar(eq(CHAVE), any(), any())).thenThrow(espera());
        when(porta.consultar(CHAVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executar(CHAVE)).isInstanceOf(PrazoConcorrenciaExcedidoException.class);
        verifyNoInteractions(autorizacao);
    }

    private void prepararReserva() {
        when(porta.reservar(eq(CHAVE), any(), any())).thenAnswer(invocacao -> {
            digital.set(invocacao.getArgument(1));
            return reserva(false, false);
        });
    }

    private IdempotenciaPort.Reserva reserva(boolean concluida, boolean expirada) {
        return new IdempotenciaPort.Reserva(1, digital.get(), concluida, concluida ? 201 : null,
                concluida ? "OK" : null, concluida ? "text/plain" : null, expirada);
    }

    private ResultadoOperacaoIdempotente executar(String chave) {
        return casoDeUso.executar(chave, CARTAO, SENHA, BigDecimal.TEN);
    }

    private EsperaConcorrenteIdempotenteException espera() {
        return new EsperaConcorrenteIdempotenteException(new IllegalStateException("Bloqueio simulado"));
    }
}

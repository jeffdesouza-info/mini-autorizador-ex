package br.com.miniautorizador.transacao.domain;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.miniautorizador.cartao.domain.Cartao;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AuthorizationPolicyEvaluatorTest {

    private final AuthorizationPolicyEvaluator evaluator = new AuthorizationPolicyEvaluator(
            List.of(new CartaoExistentePolicy(), new SenhaValidaPolicy(), new SaldoSuficientePolicy()));

    @Test
    void priorizaCartaoInexistenteMesmoComSenhaInvalidaEValorAlto() {
        ResultadoAutorizacao resultado = evaluator.avaliar(
                new AuthorizationContext(Optional.empty(), false, new BigDecimal("900.00")));

        assertThat(resultado.motivoRecusa()).contains(MotivoRecusa.CARTAO_INEXISTENTE);
    }

    @Test
    void priorizaSenhaInvalidaSobreSaldoInsuficiente() {
        ResultadoAutorizacao resultado = evaluator.avaliar(
                contexto(new BigDecimal("10.00"), false, new BigDecimal("11.00")));

        assertThat(resultado.motivoRecusa()).contains(MotivoRecusa.SENHA_INVALIDA);
    }

    @Test
    void recusaPorSaldoInsuficienteEAprovaDebitoIgualAoSaldo() {
        ResultadoAutorizacao recusada = evaluator.avaliar(
                contexto(new BigDecimal("10.00"), true, new BigDecimal("10.01")));
        ResultadoAutorizacao aprovada = evaluator.avaliar(
                contexto(new BigDecimal("10.00"), true, new BigDecimal("10.00")));

        assertThat(recusada.motivoRecusa()).contains(MotivoRecusa.SALDO_INSUFICIENTE);
        assertThat(aprovada.aprovada()).isTrue();
    }

    private AuthorizationContext contexto(BigDecimal saldo, boolean senhaValida, BigDecimal valor) {
        Cartao cartao = new Cartao("0000000000000001", "$2a$10$hash", saldo);
        return new AuthorizationContext(Optional.of(cartao), senhaValida, valor);
    }
}

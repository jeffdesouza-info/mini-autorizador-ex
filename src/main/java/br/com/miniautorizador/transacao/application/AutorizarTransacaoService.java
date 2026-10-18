package br.com.miniautorizador.transacao.application;

import br.com.miniautorizador.cartao.application.port.out.CartaoPort;
import br.com.miniautorizador.cartao.application.port.out.SenhaCartaoPort;
import br.com.miniautorizador.cartao.domain.Cartao;
import br.com.miniautorizador.cartao.domain.ValidacaoCartao;
import br.com.miniautorizador.shared.domain.RegrasMonetarias;
import br.com.miniautorizador.transacao.application.port.in.AutorizarTransacaoUseCase;
import br.com.miniautorizador.transacao.domain.AuthorizationContext;
import br.com.miniautorizador.transacao.domain.AuthorizationPolicyEvaluator;
import br.com.miniautorizador.transacao.domain.MotivoRecusa;
import br.com.miniautorizador.transacao.domain.ResultadoAutorizacao;
import java.util.Optional;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutorizarTransacaoService implements AutorizarTransacaoUseCase {

    private final CartaoPort cartaoPort;
    private final SenhaCartaoPort senhaCartaoPort;
    private final AuthorizationPolicyEvaluator policyEvaluator;

    public AutorizarTransacaoService(CartaoPort cartaoPort, SenhaCartaoPort senhaCartaoPort,
            AuthorizationPolicyEvaluator policyEvaluator) {
        this.cartaoPort = cartaoPort;
        this.senhaCartaoPort = senhaCartaoPort;
        this.policyEvaluator = policyEvaluator;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultadoAutorizacao executar(String numeroCartao, String senhaCartao, BigDecimal valor) {
        String numeroValidado = ValidacaoCartao.exigirNumeroCartao(numeroCartao);
        String senhaValidada = ValidacaoCartao.exigirSenha(senhaCartao);
        BigDecimal valorValidado = RegrasMonetarias.debito(valor);
        Optional<Cartao> cartao = cartaoPort.bloquearParaAutorizacao(numeroValidado);
        boolean senhaValida = cartao.map(candidato -> senhaCartaoPort.corresponde(senhaValidada, candidato.senhaHash()))
                .orElse(false);
        AuthorizationContext contexto = new AuthorizationContext(cartao, senhaValida, valorValidado);
        return policyEvaluator.avaliar(contexto).motivoRecusa()
                .map(ResultadoAutorizacao::recusada)
                .orElseGet(() -> debitar(numeroValidado, valorValidado));
    }

    private ResultadoAutorizacao debitar(String numeroCartao, BigDecimal valor) {
        int linhasAtualizadas = cartaoPort.debitarSaldo(numeroCartao, valor);
        return Optional.of(linhasAtualizadas)
                .filter(linhas -> linhas == 1)
                .map(linhas -> ResultadoAutorizacao.sucesso())
                .orElseThrow(() -> new SaldoAtualizacaoInesperadaException());
    }

    public static final class SaldoAtualizacaoInesperadaException extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}

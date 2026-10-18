package br.com.miniautorizador.cartao.application;

import br.com.miniautorizador.cartao.application.port.in.CriarCartaoUseCase;
import br.com.miniautorizador.cartao.application.port.in.ResultadoCriacaoCartao;
import br.com.miniautorizador.cartao.application.port.out.CartaoPort;
import br.com.miniautorizador.cartao.application.port.out.SenhaCartaoPort;
import br.com.miniautorizador.cartao.domain.Cartao;
import br.com.miniautorizador.cartao.domain.ValidacaoCartao;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;

@Service
public class CriarCartaoService implements CriarCartaoUseCase {

    private final CartaoPort cartaoPort;
    private final SenhaCartaoPort senhaCartaoPort;

    public CriarCartaoService(CartaoPort cartaoPort, SenhaCartaoPort senhaCartaoPort) {
        this.cartaoPort = cartaoPort;
        this.senhaCartaoPort = senhaCartaoPort;
    }

    @Override
    public ResultadoCriacaoCartao executar(String numeroCartao, String senha) {
        String numeroValidado = ValidacaoCartao.exigirNumeroCartao(numeroCartao);
        String senhaValidada = ValidacaoCartao.exigirSenha(senha);
        Cartao cartao = new Cartao(numeroValidado, senhaCartaoPort.gerarHash(senhaValidada), new BigDecimal("500.00"));
        return cartaoPort.criar(cartao) ? ResultadoCriacaoCartao.CRIADO : ResultadoCriacaoCartao.JA_EXISTENTE;
    }
}

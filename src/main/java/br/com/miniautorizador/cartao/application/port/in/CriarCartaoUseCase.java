package br.com.miniautorizador.cartao.application.port.in;

public interface CriarCartaoUseCase {

    ResultadoCriacaoCartao executar(String numeroCartao, String senha);
}

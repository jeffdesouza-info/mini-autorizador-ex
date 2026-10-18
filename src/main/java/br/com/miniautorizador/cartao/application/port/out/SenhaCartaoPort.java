package br.com.miniautorizador.cartao.application.port.out;

public interface SenhaCartaoPort {

    String gerarHash(String senha);

    boolean corresponde(String senha, String senhaHash);
}

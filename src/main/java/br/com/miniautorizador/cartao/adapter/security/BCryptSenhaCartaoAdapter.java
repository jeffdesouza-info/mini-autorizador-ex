package br.com.miniautorizador.cartao.adapter.security;

import br.com.miniautorizador.cartao.application.port.out.SenhaCartaoPort;
import br.com.miniautorizador.cartao.domain.ValidacaoCartao;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BCryptSenhaCartaoAdapter implements SenhaCartaoPort {

    private final PasswordEncoder passwordEncoder;

    public BCryptSenhaCartaoAdapter(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public String gerarHash(String senha) {
        return passwordEncoder.encode(ValidacaoCartao.exigirSenha(senha));
    }

    @Override
    public boolean corresponde(String senha, String senhaHash) {
        return passwordEncoder.matches(ValidacaoCartao.exigirSenha(senha),
                ValidacaoCartao.exigirHashSenha(senhaHash));
    }
}

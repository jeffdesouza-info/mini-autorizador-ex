package br.com.miniautorizador.transacao.adapter;

import br.com.miniautorizador.transacao.domain.AuthorizationPolicyEvaluator;
import br.com.miniautorizador.transacao.domain.CartaoExistentePolicy;
import br.com.miniautorizador.transacao.domain.SaldoSuficientePolicy;
import br.com.miniautorizador.transacao.domain.SenhaValidaPolicy;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthorizationConfiguration {

    @Bean
    AuthorizationPolicyEvaluator authorizationPolicyEvaluator() {
        return new AuthorizationPolicyEvaluator(
                List.of(new CartaoExistentePolicy(), new SenhaValidaPolicy(), new SaldoSuficientePolicy()));
    }
}

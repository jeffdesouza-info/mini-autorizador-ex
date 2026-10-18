package br.com.miniautorizador.transacao.application.port.in;

import java.math.BigDecimal;
import br.com.miniautorizador.transacao.domain.ResultadoAutorizacao;

public interface AutorizarTransacaoUseCase {

    ResultadoAutorizacao executar(String numeroCartao, String senhaCartao, BigDecimal valor);
}

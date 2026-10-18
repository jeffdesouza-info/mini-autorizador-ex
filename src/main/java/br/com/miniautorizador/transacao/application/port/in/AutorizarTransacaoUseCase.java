package br.com.miniautorizador.transacao.application.port.in;

import java.math.BigDecimal;
import br.com.miniautorizador.transacao.domain.ResultadoAutorizacao;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaIdempotente;

public interface AutorizarTransacaoUseCase {

    ResultadoAutorizacao executar(String numeroCartao, String senhaCartao, BigDecimal valor);

    ResultadoAutorizacao executar(String numeroCartao, String senhaCartao, BigDecimal valor,
            PrazoConcorrenciaIdempotente prazo);
}

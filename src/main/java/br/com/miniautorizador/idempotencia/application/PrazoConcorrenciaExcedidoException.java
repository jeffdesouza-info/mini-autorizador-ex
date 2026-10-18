package br.com.miniautorizador.idempotencia.application;

import org.springframework.dao.TransientDataAccessException;

public class PrazoConcorrenciaExcedidoException extends TransientDataAccessException {

    public PrazoConcorrenciaExcedidoException() {
        super("O prazo para iniciar ou revalidar a reserva idempotente foi excedido.");
    }
}

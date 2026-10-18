package br.com.miniautorizador.idempotencia.application;

public class PrazoConcorrenciaExcedidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PrazoConcorrenciaExcedidoException() {
        super("O prazo para iniciar ou revalidar a reserva idempotente foi excedido.");
    }
}

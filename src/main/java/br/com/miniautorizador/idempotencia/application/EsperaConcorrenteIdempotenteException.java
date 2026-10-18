package br.com.miniautorizador.idempotencia.application;

public class EsperaConcorrenteIdempotenteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EsperaConcorrenteIdempotenteException(Throwable causa) {
        super("A reserva idempotente permaneceu bloqueada por outra execução.", causa);
    }
}

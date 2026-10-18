package br.com.miniautorizador.idempotencia.application;

import org.springframework.dao.TransientDataAccessException;

public class EsperaConcorrenteIdempotenteException extends TransientDataAccessException {

    public EsperaConcorrenteIdempotenteException(Throwable causa) {
        super("A reserva idempotente permaneceu bloqueada por outra execução.", causa);
    }
}

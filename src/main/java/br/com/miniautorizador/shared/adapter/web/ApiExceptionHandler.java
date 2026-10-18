package br.com.miniautorizador.shared.adapter.web;

import br.com.miniautorizador.shared.application.InvalidRequestException;
import br.com.miniautorizador.idempotencia.application.EsperaConcorrenteIdempotenteException;
import br.com.miniautorizador.idempotencia.application.PrazoConcorrenciaExcedidoException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({InvalidRequestException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> requisicaoInvalida(Exception excecao) {
        return resposta(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "A requisição contém dados inválidos.");
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
            RecoverableDataAccessException.class,
            EsperaConcorrenteIdempotenteException.class, PrazoConcorrenciaExcedidoException.class})
    public ResponseEntity<ApiError> servicoIndisponivel(Exception excecao) {
        return resposta(HttpStatus.SERVICE_UNAVAILABLE, "SERVICO_INDISPONIVEL",
                "O serviço está temporariamente indisponível.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> falhaInesperada(Exception excecao) {
        return resposta(HttpStatus.INTERNAL_SERVER_ERROR, "ERRO_INTERNO", "Não foi possível concluir a operação.");
    }

    private ResponseEntity<ApiError> resposta(HttpStatus status, String codigo, String mensagem) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(codigo, mensagem));
    }
}

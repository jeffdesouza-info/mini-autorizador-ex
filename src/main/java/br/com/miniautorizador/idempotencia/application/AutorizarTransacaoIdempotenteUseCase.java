package br.com.miniautorizador.idempotencia.application;

import br.com.miniautorizador.cartao.domain.ValidacaoCartao;
import br.com.miniautorizador.idempotencia.application.port.out.IdempotenciaPort;
import br.com.miniautorizador.shared.application.InvalidRequestException;
import br.com.miniautorizador.shared.domain.RegrasMonetarias;
import br.com.miniautorizador.transacao.application.port.in.AutorizarTransacaoUseCase;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AutorizarTransacaoIdempotenteUseCase {

    private final IdempotenciaPort idempotenciaPort;
    private final AutorizarTransacaoUseCase autorizacao;
    private final SecretKeySpec chaveHmac;

    public AutorizarTransacaoIdempotenteUseCase(IdempotenciaPort idempotenciaPort,
            AutorizarTransacaoUseCase autorizacao,
            @Value("${idempotencia.segredo-hmac}") String segredo) {
        this.idempotenciaPort = idempotenciaPort;
        this.autorizacao = autorizacao;
        byte[] bytes = segredo.getBytes(StandardCharsets.UTF_8);
        Optional.of(bytes).filter(valor -> valor.length >= 32).orElseThrow(IllegalStateException::new);
        this.chaveHmac = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public ResultadoOperacaoIdempotente executar(String chave, String numeroCartao, String senhaCartao,
            BigDecimal valor) {
        PrazoConcorrenciaIdempotente prazo = PrazoConcorrenciaIdempotente.iniciar();
        String uuid = normalizar(chave);
        ValidacaoCartao.exigirNumeroCartao(numeroCartao);
        ValidacaoCartao.exigirSenha(senhaCartao);
        valor = RegrasMonetarias.debito(valor);
        byte[] digital = imprimir(numeroCartao, senhaCartao, valor);
        try {
            IdempotenciaPort.Reserva reserva = idempotenciaPort.reservar(uuid, digital, prazo);
            return resolverReserva(uuid, reserva, digital, numeroCartao, senhaCartao, valor, prazo);
        } catch (EsperaConcorrenteIdempotenteException espera) {
            return aguardar(uuid, digital, numeroCartao, senhaCartao, valor, prazo);
        }
    }

    private ResultadoOperacaoIdempotente resolverReserva(String chave, IdempotenciaPort.Reserva reserva,
            byte[] digital, String numeroCartao, String senhaCartao, BigDecimal valor,
            PrazoConcorrenciaIdempotente prazo) {
        return respostaVigente(reserva, digital)
                .orElseGet(() -> processar(chave, reserva.geracao(), digital, numeroCartao, senhaCartao, valor, prazo));
    }

    private ResultadoOperacaoIdempotente processar(String chave, long geracao, byte[] digital,
            String numeroCartao, String senhaCartao, BigDecimal valor, PrazoConcorrenciaIdempotente prazo) {
        try {
            Optional<ResultadoOperacaoIdempotente> resultado = idempotenciaPort.executar(chave, geracao, digital,
                    prazo, () -> autorizacao.executar(numeroCartao, senhaCartao, valor, prazo));
            return resultado.orElseGet(() -> resolverReserva(chave, idempotenciaPort.reservar(chave, digital, prazo),
                    digital, numeroCartao, senhaCartao, valor, prazo));
        } catch (EsperaConcorrenteIdempotenteException espera) {
            return aguardar(chave, digital, numeroCartao, senhaCartao, valor, prazo);
        } catch (RuntimeException falha) {
            return recuperarResultadoDuravel(chave, digital, falha);
        }
    }

    private ResultadoOperacaoIdempotente recuperarResultadoDuravel(String chave, byte[] digital,
            RuntimeException falha) {
        Optional<IdempotenciaPort.Reserva> reservaConsultada;
        try {
            reservaConsultada = idempotenciaPort.consultar(chave);
        } catch (RuntimeException falhaNaConsulta) {
            falha.addSuppressed(falhaNaConsulta);
            throw falha;
        }
        return reservaConsultada
                .filter(reserva -> !reserva.expirada())
                .filter(IdempotenciaPort.Reserva::concluida)
                .filter(reserva -> java.security.MessageDigest.isEqual(reserva.impressaoDigital(), digital))
                .map(reserva -> new ResultadoOperacaoIdempotente(reserva.status(), reserva.corpo(),
                        reserva.tipoMidia(), false))
                .orElseThrow(() -> falha);
    }

    private ResultadoOperacaoIdempotente aguardar(String chave, byte[] digital, String numeroCartao,
            String senhaCartao, BigDecimal valor, PrazoConcorrenciaIdempotente prazo) {
        AtomicReference<ResultadoOperacaoIdempotente> resposta = new AtomicReference<>();
        while (prazo.temTempoRestante() && resposta.get() == null) {
            IdempotenciaPort.Reserva atual = idempotenciaPort.consultar(chave)
                    .orElseThrow(PrazoConcorrenciaExcedidoException::new);
            respostaVigente(atual, digital).ifPresent(resposta::set);
            Optional.of(atual)
                    .filter(IdempotenciaPort.Reserva::expirada)
                    .filter(ignorado -> resposta.get() == null)
                    .map(ignorado -> idempotenciaPort.reservar(chave, digital, prazo))
                    .map(reserva -> resolverReserva(chave, reserva, digital, numeroCartao, senhaCartao, valor, prazo))
                    .ifPresent(resposta::set);
            Optional.of(resposta).filter(atualResposta -> atualResposta.get() == null)
                    .ifPresent(ignorado -> LockSupport.parkNanos(
                            Math.min(TimeUnit.MILLISECONDS.toNanos(25), prazo.nanosRestantes())));
        }
        return Optional.ofNullable(resposta.get()).orElseGet(() -> respostaNoLimite(chave, digital,
                numeroCartao, senhaCartao, valor, prazo));
    }

    private ResultadoOperacaoIdempotente respostaNoLimite(String chave, byte[] digital, String numeroCartao,
            String senhaCartao, BigDecimal valor, PrazoConcorrenciaIdempotente prazo) {
        IdempotenciaPort.Reserva atual = idempotenciaPort.consultar(chave)
                .orElseThrow(PrazoConcorrenciaExcedidoException::new);
        return respostaVigente(atual, digital)
                .orElseGet(() -> Optional.of(atual)
                        .filter(IdempotenciaPort.Reserva::expirada)
                        .map(ignorado -> resolverReserva(chave, idempotenciaPort.reservar(chave, digital, prazo),
                                digital, numeroCartao, senhaCartao, valor, prazo))
                        .orElseGet(ResultadoOperacaoIdempotente::processamento));
    }

    private Optional<ResultadoOperacaoIdempotente> respostaVigente(IdempotenciaPort.Reserva reserva,
            byte[] digital) {
        return Optional.of(reserva)
                .filter(atual -> !atual.expirada())
                .flatMap(atual -> Optional.of(atual)
                        .filter(ignorado -> !java.security.MessageDigest.isEqual(atual.impressaoDigital(), digital))
                        .map(ignorado -> ResultadoOperacaoIdempotente.chaveReutilizada())
                        .or(() -> Optional.of(atual)
                                .filter(IdempotenciaPort.Reserva::concluida)
                                .map(resultado -> new ResultadoOperacaoIdempotente(resultado.status(),
                                        resultado.corpo(), resultado.tipoMidia(), false))));
    }

    private String normalizar(String valor) {
        return Optional.ofNullable(valor)
                .filter(texto -> texto.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                .map(UUID::fromString)
                .map(UUID::toString)
                .orElseThrow(InvalidRequestException::new);
    }

    private byte[] imprimir(String cartao, String senha, BigDecimal valor) {
        String entrada = campo(cartao) + campo(senha) + campo(valor.stripTrailingZeros().toPlainString());
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(chaveHmac);
            return mac.doFinal(entrada.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException excecao) {
            throw new IllegalStateException(excecao);
        }
    }

    private String campo(String valor) {
        byte[] bytes = valor.getBytes(StandardCharsets.UTF_8);
        return HexFormat.of().formatHex(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array())
                + HexFormat.of().formatHex(bytes);
    }
}

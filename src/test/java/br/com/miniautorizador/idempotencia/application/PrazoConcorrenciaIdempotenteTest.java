package br.com.miniautorizador.idempotencia.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PrazoConcorrenciaIdempotenteTest {

    @Test
    void doisSegundosENovecentosMillisContinuamDisponiveis() {
        AtomicLong relogio = new AtomicLong(100);
        PrazoConcorrenciaIdempotente prazo = PrazoConcorrenciaIdempotente.iniciar(relogio::get);
        relogio.addAndGet(TimeUnit.MILLISECONDS.toNanos(2100));

        assertThat(prazo.temTempoRestante()).isTrue();
        assertThat(prazo.milissegundosRestantes()).isEqualTo(2900);
        relogio.addAndGet(TimeUnit.MILLISECONDS.toNanos(2000));
        assertThat(prazo.milissegundosRestantes()).isEqualTo(900);
    }

    @Test
    void fracaoDeMilissegundoNaoRenovaPrazoNemSignificaEsgotamento() {
        AtomicLong relogio = new AtomicLong();
        PrazoConcorrenciaIdempotente prazo = PrazoConcorrenciaIdempotente.iniciar(relogio::get);
        relogio.set(TimeUnit.SECONDS.toNanos(5) - 1);

        assertThat(prazo.temTempoRestante()).isTrue();
        assertThat(prazo.milissegundosRestantes()).isZero();
        assertThat(prazo.nanosRestantes()).isEqualTo(1);

        relogio.incrementAndGet();
        assertThat(prazo.temTempoRestante()).isFalse();
        assertThat(prazo.nanosRestantes()).isZero();
        relogio.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertThat(prazo.milissegundosRestantes()).isZero();
    }
}

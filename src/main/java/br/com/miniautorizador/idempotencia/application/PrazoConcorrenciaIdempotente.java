package br.com.miniautorizador.idempotencia.application;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class PrazoConcorrenciaIdempotente {

    private static final long DURACAO_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final long limiteNanos;
    private final LongSupplier relogio;

    public PrazoConcorrenciaIdempotente(long limiteNanos) {
        this(limiteNanos, System::nanoTime);
    }

    private PrazoConcorrenciaIdempotente(long limiteNanos, LongSupplier relogio) {
        this.limiteNanos = limiteNanos;
        this.relogio = relogio;
    }

    public static PrazoConcorrenciaIdempotente iniciar() {
        return iniciar(System::nanoTime);
    }

    static PrazoConcorrenciaIdempotente iniciar(LongSupplier relogio) {
        return new PrazoConcorrenciaIdempotente(relogio.getAsLong() + DURACAO_NANOS, relogio);
    }

    public long nanosRestantes() {
        return Math.max(0, limiteNanos - relogio.getAsLong());
    }

    public long limiteNanos() {
        return limiteNanos;
    }

    public boolean temTempoRestante() {
        return nanosRestantes() > 0;
    }

    public int milissegundosRestantes() {
        return Math.toIntExact(TimeUnit.NANOSECONDS.toMillis(nanosRestantes()));
    }
}

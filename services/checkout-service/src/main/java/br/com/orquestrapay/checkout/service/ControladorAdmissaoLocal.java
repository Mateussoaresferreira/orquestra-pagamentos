package br.com.orquestrapay.checkout.service;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.orquestrapay.checkout.config.PropriedadesLimiteRequisicoes;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class ControladorAdmissaoLocal {

    private final Semaphore vagas;
    private final AtomicInteger emProcessamento = new AtomicInteger();

    public ControladorAdmissaoLocal(
            PropriedadesLimiteRequisicoes propriedades,
            MeterRegistry metricas) {
        this.vagas = new Semaphore(propriedades.maximoEmProcessamento());
        Gauge.builder("orquestrapay.admissao.em_processamento", emProcessamento, AtomicInteger::get)
                .description("Compras admitidas e ainda em processamento nesta replica")
                .register(metricas);
    }

    public Optional<Permissao> tentarAdmitir() {
        if (!vagas.tryAcquire()) {
            return Optional.empty();
        }
        emProcessamento.incrementAndGet();
        return Optional.of(new Permissao(this));
    }

    public int emProcessamento() {
        return emProcessamento.get();
    }

    private void liberar() {
        emProcessamento.decrementAndGet();
        vagas.release();
    }

    public static final class Permissao implements AutoCloseable {

        private final ControladorAdmissaoLocal controlador;
        private final AtomicBoolean liberada = new AtomicBoolean();

        private Permissao(ControladorAdmissaoLocal controlador) {
            this.controlador = controlador;
        }

        @Override
        public void close() {
            if (liberada.compareAndSet(false, true)) {
                controlador.liberar();
            }
        }
    }
}

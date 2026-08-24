package br.com.orquestrapay.platform.event;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.micrometer.tracing.Tracer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public class RegistroEventos {

    private final RepositorioEventos repositorio;
    private final ObjectMapper json;
    private final Clock relogio;
    private final Tracer rastreador;

    public RegistroEventos(
            RepositorioEventos repositorio,
            ObjectMapper json,
            Clock relogio,
            Tracer rastreador) {
        this.repositorio = repositorio;
        this.json = json;
        this.relogio = relogio;
        this.rastreador = rastreador;
    }

    public UUID registrar(
            String tipo,
            UUID idCorrelacao,
            UUID idEmpresa,
            UUID idCompra,
            String origem,
            Object dados) {
        return registrar(tipo, 1, idCorrelacao, idEmpresa, idCompra, origem, dados, traceparentAtual());
    }

    public UUID registrar(
            String tipo,
            int versao,
            UUID idCorrelacao,
            UUID idEmpresa,
            UUID idCompra,
            String origem,
            Object dados,
            String traceparent) {
        Objects.requireNonNull(tipo, "O tipo do evento e obrigatorio");
        Objects.requireNonNull(idCorrelacao, "A correlacao e obrigatoria");
        Objects.requireNonNull(idEmpresa, "A empresa e obrigatoria");
        Objects.requireNonNull(idCompra, "A compra e obrigatoria");
        Objects.requireNonNull(origem, "A origem e obrigatoria");

        UUID idEvento = UUID.randomUUID();
        Instant ocorridoEm = relogio.instant();
        repositorio.adicionar(
                idEvento,
                tipo,
                versao,
                idCorrelacao,
                idEmpresa,
                idCompra,
                origem,
                serializar(dados),
                traceparent,
                ocorridoEm);
        return idEvento;
    }

    private String serializar(Object dados) {
        try {
            return json.writeValueAsString(dados);
        } catch (JacksonException excecao) {
            throw new IllegalArgumentException("Nao foi possivel serializar os dados do evento", excecao);
        }
    }

    private String traceparentAtual() {
        if (rastreador == null || rastreador.currentSpan() == null) {
            return null;
        }
        var contexto = rastreador.currentSpan().context();
        String amostrado = Boolean.TRUE.equals(contexto.sampled()) ? "01" : "00";
        return "00-" + contexto.traceId() + "-" + contexto.spanId() + "-" + amostrado;
    }
}

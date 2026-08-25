package br.com.orquestrapay.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.notification.config.PropriedadesWebhooks;
import br.com.orquestrapay.notification.data.RepositorioWebhooks;
import br.com.orquestrapay.platform.security.ProtecaoTokenPagamento;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoFilaWebhooks {

    private final RepositorioWebhooks repositorio;
    private final PropriedadesWebhooks propriedades;
    private final ProtecaoTokenPagamento protecaoSegredo;
    private final Clock relogio;
    private final MeterRegistry metricas;

    public ServicoFilaWebhooks(
            RepositorioWebhooks repositorio,
            PropriedadesWebhooks propriedades,
            ProtecaoTokenPagamento protecaoSegredo,
            Clock relogio,
            MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.propriedades = propriedades;
        this.protecaoSegredo = protecaoSegredo;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    @Transactional
    public List<EntregaWebhook> reivindicar() {
        Instant agora = relogio.instant();
        UUID token = UUID.randomUUID();
        return repositorio.reivindicar(
                        propriedades.tamanhoLote(),
                        agora,
                        agora.plus(propriedades.duracaoBloqueio()),
                        token)
                .stream()
                .map(entrega -> new EntregaWebhook(
                        entrega,
                        protecaoSegredo.revelar(
                                entrega.segredoProtegido(), entrega.idEmpresa())))
                .toList();
    }

    @Transactional
    public void confirmar(EntregaWebhook entrega, int statusHttp) {
        if (repositorio.marcarEntregue(entrega.dados(), statusHttp, relogio.instant())) {
            metricas.counter("orquestrapay.webhooks.entregas", "resultado", "sucesso").increment();
        }
    }

    @Transactional
    public void falhar(EntregaWebhook entrega, Integer statusHttp, String erro) {
        Instant agora = relogio.instant();
        boolean definitiva = entrega.dados().tentativas() >= propriedades.maximoTentativas();
        Duration atraso = calcularAtraso(entrega.dados().tentativas());
        if (repositorio.registrarFalha(
                entrega.dados(),
                statusHttp,
                erro,
                agora.plus(atraso),
                definitiva,
                agora)) {
            metricas.counter(
                    "orquestrapay.webhooks.entregas",
                    "resultado",
                    definitiva ? "falha_definitiva" : "reagendada").increment();
        }
    }

    private Duration calcularAtraso(int tentativas) {
        long multiplicador = 1L << Math.min(Math.max(tentativas - 1, 0), 20);
        Duration calculado = propriedades.atrasoBase().multipliedBy(multiplicador);
        return calculado.compareTo(propriedades.atrasoMaximo()) > 0
                ? propriedades.atrasoMaximo()
                : calculado;
    }

    public record EntregaWebhook(
            RepositorioWebhooks.EntregaPendente dados,
            String segredo) {
    }
}

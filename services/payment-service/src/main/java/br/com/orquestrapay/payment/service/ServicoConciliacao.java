package br.com.orquestrapay.payment.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import br.com.orquestrapay.payment.api.PedidoConciliacao;
import br.com.orquestrapay.payment.api.RegistroProvedor;
import br.com.orquestrapay.payment.api.RespostaPagamento;
import br.com.orquestrapay.payment.api.ResultadoConciliacao;
import br.com.orquestrapay.payment.api.AtualizacaoDivergencia;
import br.com.orquestrapay.payment.api.PaginaDivergencias;
import br.com.orquestrapay.payment.api.RespostaConciliacaoResumo;
import br.com.orquestrapay.payment.api.RespostaDivergencia;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoConciliacao {

    private final RepositorioPagamentos repositorio;
    private final Clock relogio;
    private final MeterRegistry metricas;

    public ServicoConciliacao(
            RepositorioPagamentos repositorio,
            Clock relogio,
            MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.relogio = relogio;
        this.metricas = metricas;
    }

    @Transactional
    public ResultadoConciliacao conciliar(UUID idEmpresa, PedidoConciliacao pedido) {
        var iniciadaEm = relogio.instant();
        var divergencias = new ArrayList<String>();
        Map<UUID, RespostaPagamento> pagamentosLocais =
                repositorio.buscarPorPagamentos(
                        idEmpresa,
                        pedido.registros().stream().map(RegistroProvedor::idPagamento).toList());

        for (RegistroProvedor registro : pedido.registros()) {
            var local = pagamentosLocais.get(registro.idPagamento());
            String divergencia = null;
            String tipo = null;
            if (local == null) {
                tipo = "AUSENTE_LOCALMENTE";
                divergencia = "Pagamento %s existe no provedor, mas nao localmente"
                        .formatted(registro.idPagamento());
            } else if (local.valor().compareTo(registro.valor()) != 0) {
                tipo = "VALOR_DIVERGENTE";
                divergencia = "Pagamento %s: local %s, provedor %s"
                        .formatted(registro.idPagamento(), local.valor(), registro.valor());
            } else if (!local.status().equals(registro.status())) {
                tipo = "STATUS_DIVERGENTE";
                divergencia = "Pagamento %s: local %s, provedor %s"
                        .formatted(registro.idPagamento(), local.status(), registro.status());
            }

            if (divergencia != null) {
                divergencias.add(divergencia);
                metricas.counter(
                        "orquestrapay.conciliacoes.divergencias",
                        "tipo",
                        tipo).increment();
                repositorio.registrarDivergencia(
                        idEmpresa,
                        registro.idPagamento(),
                        tipo,
                        divergencia,
                        relogio.instant());
            }
        }
        var concluidaEm = relogio.instant();
        UUID idConciliacao = repositorio.registrarConciliacao(
                idEmpresa,
                pedido.registros().size(),
                divergencias.size(),
                iniciadaEm,
                concluidaEm);
        metricas.counter(
                "orquestrapay.conciliacoes.execucoes",
                "resultado",
                divergencias.isEmpty() ? "sem_divergencia" : "com_divergencia").increment();
        return new ResultadoConciliacao(
                pedido.registros().size(),
                divergencias.size(),
                List.copyOf(divergencias),
                idConciliacao,
                concluidaEm);
    }

    @Transactional(readOnly = true)
    public List<RespostaConciliacaoResumo> listar(UUID idEmpresa, int limite) {
        return repositorio.listarConciliacoes(idEmpresa, limite);
    }

    @Transactional(readOnly = true)
    public PaginaDivergencias listarDivergencias(
            UUID idEmpresa,
            String status,
            int pagina,
            int tamanho) {
        if (status != null && !Set.of("ABERTA", "INVESTIGANDO", "RESOLVIDA").contains(status)) {
            throw new ExcecaoNegocio(
                    HttpStatus.BAD_REQUEST,
                    "status-divergencia-invalido",
                    "Use ABERTA, INVESTIGANDO ou RESOLVIDA");
        }
        return repositorio.listarDivergencias(idEmpresa, status, pagina, tamanho);
    }

    @Transactional
    public RespostaDivergencia atualizarDivergencia(
            UUID idEmpresa,
            UUID idDivergencia,
            AtualizacaoDivergencia atualizacao) {
        return repositorio.atualizarDivergencia(
                        idEmpresa,
                        idDivergencia,
                        atualizacao.status(),
                        atualizacao.observacao(),
                        relogio.instant())
                .orElseThrow(() -> new ExcecaoNegocio(
                        HttpStatus.NOT_FOUND,
                        "divergencia-nao-encontrada",
                        "Divergencia nao encontrada para esta empresa"));
    }
}

package br.com.orquestrapay.payment.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.orquestrapay.payment.api.PedidoConciliacao;
import br.com.orquestrapay.payment.api.RegistroProvedor;
import br.com.orquestrapay.payment.api.RespostaPagamento;
import br.com.orquestrapay.payment.api.ResultadoConciliacao;
import br.com.orquestrapay.payment.data.RepositorioPagamentos;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoConciliacao {

    private final RepositorioPagamentos repositorio;
    private final Clock relogio;

    public ServicoConciliacao(RepositorioPagamentos repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    @Transactional
    public ResultadoConciliacao conciliar(UUID idEmpresa, PedidoConciliacao pedido) {
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
                repositorio.registrarDivergencia(
                        idEmpresa,
                        registro.idPagamento(),
                        tipo,
                        divergencia,
                        relogio.instant());
            }
        }
        return new ResultadoConciliacao(
                pedido.registros().size(),
                divergencias.size(),
                List.copyOf(divergencias));
    }
}

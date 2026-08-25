package br.com.orquestrapay.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.notification.config.PropriedadesNotificacoes;
import br.com.orquestrapay.notification.data.RepositorioNotificacoes;
import br.com.orquestrapay.notification.data.RepositorioNotificacoes.NotificacaoPendente;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServicoFilaNotificacoes {

    private final RepositorioNotificacoes repositorio;
    private final PropriedadesNotificacoes propriedades;
    private final Clock relogio;

    public ServicoFilaNotificacoes(
            RepositorioNotificacoes repositorio,
            PropriedadesNotificacoes propriedades,
            Clock relogio) {
        this.repositorio = repositorio;
        this.propriedades = propriedades;
        this.relogio = relogio;
    }

    @Transactional
    public List<NotificacaoPendente> reivindicar() {
        var agora = relogio.instant();
        return repositorio.reivindicarPendentes(
                propriedades.tamanhoLote(),
                propriedades.maximoTentativas(),
                agora,
                agora.plus(propriedades.duracaoBloqueio()));
    }

    @Transactional
    public void confirmar(UUID idNotificacao) {
        repositorio.marcarEnviada(idNotificacao, relogio.instant());
    }

    @Transactional
    public void falhar(NotificacaoPendente notificacao, String erro) {
        int tentativaAtual = notificacao.tentativas() + 1;
        boolean falhaDefinitiva = tentativaAtual >= propriedades.maximoTentativas();
        var agora = relogio.instant();
        repositorio.registrarFalha(
                notificacao.idNotificacao(),
                erro,
                agora.plus(calcularAtraso(tentativaAtual)),
                falhaDefinitiva ? agora : null);
    }

    private Duration calcularAtraso(int tentativaAtual) {
        long multiplicador = 1L << Math.min(Math.max(0, tentativaAtual - 1), 10);
        Duration calculado = propriedades.atrasoBase().multipliedBy(multiplicador);
        return calculado.compareTo(propriedades.atrasoMaximo()) > 0
                ? propriedades.atrasoMaximo()
                : calculado;
    }
}

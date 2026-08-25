package br.com.orquestrapay.notification.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

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
                Math.min(propriedades.tamanhoLote(), propriedades.concorrencia()),
                propriedades.maximoTentativas(),
                agora,
                agora.plus(propriedades.duracaoBloqueio()));
    }

    @Transactional
    public boolean confirmar(NotificacaoPendente notificacao) {
        return repositorio.marcarEnviada(notificacao, relogio.instant());
    }

    @Transactional
    public boolean falhar(NotificacaoPendente notificacao, String erro) {
        int tentativaAtual = notificacao.tentativas();
        boolean falhaDefinitiva = tentativaAtual >= propriedades.maximoTentativas();
        var agora = relogio.instant();
        return repositorio.registrarFalha(
                notificacao,
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

package br.com.orquestrapay.payment.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.pagamentos")
public record PropriedadesPagamentos(
        Map<String, PropriedadesProvedor> provedores,
        Trabalhador trabalhador,
        Pix pix,
        ControleProvedores controleProvedores) {

    public PropriedadesPagamentos {
        if (provedores == null || provedores.isEmpty()) {
            throw new IllegalArgumentException("Ao menos um provedor de pagamento deve ser configurado");
        }
        provedores = Map.copyOf(provedores);
        trabalhador = trabalhador == null ? Trabalhador.padrao() : trabalhador;
        pix = pix == null ? Pix.padrao() : pix;
        controleProvedores = controleProvedores == null ? ControleProvedores.padrao() : controleProvedores;
    }

    public record Trabalhador(
            int tamanhoLote,
            int maximoTentativas,
            Duration duracaoBloqueio,
            Duration atrasoInicial,
            Duration atrasoMaximo) {

        public Trabalhador {
            if (tamanhoLote <= 0 || tamanhoLote > 100) {
                throw new IllegalArgumentException("O tamanho do lote deve ficar entre 1 e 100");
            }
            if (maximoTentativas <= 0 || maximoTentativas > 20) {
                throw new IllegalArgumentException("O maximo de tentativas deve ficar entre 1 e 20");
            }
            validarDuracao(duracaoBloqueio, "duracaoBloqueio");
            validarDuracao(atrasoInicial, "atrasoInicial");
            validarDuracao(atrasoMaximo, "atrasoMaximo");
        }

        static Trabalhador padrao() {
            return new Trabalhador(20, 6, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(5));
        }
    }

    public record Pix(
            Duration expiracao,
            URI urlNotificacao,
            Duration toleranciaAssinatura) {

        public Pix {
            validarDuracao(expiracao, "expiracao");
            if (urlNotificacao == null) {
                throw new IllegalArgumentException("A URL de notificacao PIX e obrigatoria");
            }
            validarDuracao(toleranciaAssinatura, "toleranciaAssinatura");
        }

        static Pix padrao() {
            return new Pix(
                    Duration.ofMinutes(15),
                    URI.create("http://localhost:8083/api/v1/webhooks/provedores"),
                    Duration.ofMinutes(5));
        }
    }

    public record ControleProvedores(
            boolean limiteDistribuidoHabilitado,
            boolean permitirSemRedis) {

        static ControleProvedores padrao() {
            return new ControleProvedores(true, false);
        }
    }

    private static void validarDuracao(Duration duracao, String propriedade) {
        if (duracao == null || duracao.isZero() || duracao.isNegative()) {
            throw new IllegalArgumentException(propriedade + " deve ser maior que zero");
        }
    }
}

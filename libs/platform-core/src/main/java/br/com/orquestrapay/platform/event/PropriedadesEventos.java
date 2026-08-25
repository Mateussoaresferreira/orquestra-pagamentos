package br.com.orquestrapay.platform.event;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.eventos")
public record PropriedadesEventos(
        boolean habilitado,
        Topicos topicos,
        int particoes,
        int tamanhoLote,
        int concorrenciaPublicacao,
        Duration tempoLimitePublicacao,
        Duration duracaoBloqueio,
        int maximoTentativas,
        Duration atrasoBase,
        Duration atrasoMaximo) {

    public PropriedadesEventos {
        topicos = topicos == null ? Topicos.padrao() : topicos;
        particoes = particoes <= 0 ? 12 : particoes;
        if (particoes > 1_000) {
            throw new IllegalArgumentException("A quantidade de particoes deve ser menor ou igual a 1.000");
        }
        tamanhoLote = tamanhoLote <= 0 ? 50 : tamanhoLote;
        concorrenciaPublicacao = concorrenciaPublicacao <= 0 ? 4 : concorrenciaPublicacao;
        tempoLimitePublicacao = tempoLimitePublicacao == null
                ? Duration.ofSeconds(10)
                : tempoLimitePublicacao;
        duracaoBloqueio = duracaoBloqueio == null
                ? Duration.ofSeconds(30)
                : duracaoBloqueio;
        maximoTentativas = maximoTentativas <= 0 ? 12 : maximoTentativas;
        atrasoBase = atrasoBase == null ? Duration.ofSeconds(1) : atrasoBase;
        atrasoMaximo = atrasoMaximo == null ? Duration.ofMinutes(5) : atrasoMaximo;
    }

    public record Topicos(
            String checkout,
            String estoque,
            String risco,
            String pagamento,
            String razao,
            String notificacao) {

        public Topicos {
            checkout = normalizar(checkout, "orquestrapay.checkout.v1");
            estoque = normalizar(estoque, "orquestrapay.estoque.v1");
            risco = normalizar(risco, "orquestrapay.risco.v1");
            pagamento = normalizar(pagamento, "orquestrapay.pagamento.v1");
            razao = normalizar(razao, "orquestrapay.razao.v1");
            notificacao = normalizar(notificacao, "orquestrapay.notificacao.v1");
            if (new LinkedHashSet<>(List.of(
                    checkout,
                    estoque,
                    risco,
                    pagamento,
                    razao,
                    notificacao)).size() != 6) {
                throw new IllegalArgumentException("Cada dominio deve possuir um topico Kafka exclusivo");
            }
        }

        public static Topicos padrao() {
            return new Topicos(null, null, null, null, null, null);
        }

        public List<String> todos() {
            return List.of(checkout, estoque, risco, pagamento, razao, notificacao);
        }

        private static String normalizar(String valor, String padrao) {
            return valor == null || valor.isBlank() ? padrao : valor;
        }
    }
}

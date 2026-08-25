package br.com.orquestrapay.payment.config;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

import br.com.orquestrapay.contracts.MetodoPagamento;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

public record PropriedadesProvedor(
        URI url,
        Duration tempoLimiteConexao,
        Duration tempoLimiteLeitura,
        String chaveApi,
        String segredoWebhook,
        int prioridade,
        Set<MetodoPagamento> metodos,
        int maximoConcorrente,
        int maximoChamadasPorPeriodo,
        Duration periodoLimite) {

    public PropriedadesProvedor(
            URI url,
            Duration tempoLimiteConexao,
            Duration tempoLimiteLeitura,
            String chaveApi) {
        this(
                url,
                tempoLimiteConexao,
                tempoLimiteLeitura,
                chaveApi,
                chaveApi,
                100,
                Set.of(MetodoPagamento.CARTAO),
                10,
                100,
                Duration.ofSeconds(1));
    }

    @ConstructorBinding
    public PropriedadesProvedor {
        Objects.requireNonNull(url, "A URL do provedor e obrigatoria");
        validarDuracaoPositiva(tempoLimiteConexao, "tempoLimiteConexao");
        validarDuracaoPositiva(tempoLimiteLeitura, "tempoLimiteLeitura");
        validarSegredo(chaveApi, "A chave de API do provedor");
        validarSegredo(segredoWebhook, "O segredo de webhook do provedor");
        if (prioridade <= 0) {
            throw new IllegalArgumentException("A prioridade do provedor deve ser maior que zero");
        }
        if (metodos == null || metodos.isEmpty()) {
            throw new IllegalArgumentException("O provedor deve aceitar ao menos um metodo de pagamento");
        }
        maximoConcorrente = maximoConcorrente <= 0 ? 10 : maximoConcorrente;
        maximoChamadasPorPeriodo = maximoChamadasPorPeriodo <= 0 ? 100 : maximoChamadasPorPeriodo;
        periodoLimite = periodoLimite == null ? Duration.ofSeconds(1) : periodoLimite;
        if (maximoConcorrente > 1_000) {
            throw new IllegalArgumentException("O maximo concorrente deve ficar entre 1 e 1.000");
        }
        if (maximoChamadasPorPeriodo > 1_000_000) {
            throw new IllegalArgumentException("O maximo de chamadas deve ficar entre 1 e 1.000.000");
        }
        validarDuracaoPositiva(periodoLimite, "periodoLimite");
        metodos = Set.copyOf(metodos);
    }

    private static void validarDuracaoPositiva(Duration duracao, String propriedade) {
        if (duracao == null || duracao.isZero() || duracao.isNegative()) {
            throw new IllegalArgumentException(propriedade + " deve ser maior que zero");
        }
    }

    private static void validarSegredo(String segredo, String descricao) {
        if (segredo == null || segredo.length() < 24) {
            throw new IllegalArgumentException(descricao + " deve ter ao menos 24 caracteres");
        }
    }
}

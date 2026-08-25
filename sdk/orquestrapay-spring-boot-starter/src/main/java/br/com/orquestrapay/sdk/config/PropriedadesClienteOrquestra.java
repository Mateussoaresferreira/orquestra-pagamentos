package br.com.orquestrapay.sdk.config;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configura o cliente tipado da Orquestra de Pagamentos.
 *
 * @param idEmpresa empresa enviada em todas as chamadas; habilita a auto-configuracao
 * @param urlCheckout URL base do servico de checkout
 * @param urlPagamento URL base do servico de pagamento
 * @param urlRazao URL base do servico de razao contabil
 * @param tempoLimite limite de conexao e resposta das chamadas HTTP
 */
@ConfigurationProperties("orquestrapay.cliente")
public record PropriedadesClienteOrquestra(
        UUID idEmpresa,
        URI urlCheckout,
        URI urlPagamento,
        URI urlRazao,
        Duration tempoLimite) {

    public PropriedadesClienteOrquestra {
        urlCheckout = urlCheckout == null ? URI.create("http://localhost:8080") : urlCheckout;
        urlPagamento = urlPagamento == null ? URI.create("http://localhost:8083") : urlPagamento;
        urlRazao = urlRazao == null ? URI.create("http://localhost:8084") : urlRazao;
        tempoLimite = tempoLimite == null ? Duration.ofSeconds(5) : tempoLimite;
        if (tempoLimite.isZero() || tempoLimite.isNegative()) {
            throw new IllegalArgumentException("O tempo limite do cliente deve ser positivo");
        }
    }
}

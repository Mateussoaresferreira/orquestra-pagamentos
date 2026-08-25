package br.com.orquestrapay.sdk.client;

import java.net.http.HttpClient;
import java.util.UUID;

import br.com.orquestrapay.sdk.api.NovaCompraCliente;
import br.com.orquestrapay.sdk.api.RespostaCompraCliente;
import br.com.orquestrapay.sdk.api.RespostaPagamentoCliente;
import br.com.orquestrapay.sdk.api.RespostaTransacaoContabilCliente;
import br.com.orquestrapay.sdk.config.PropriedadesClienteOrquestra;
import br.com.orquestrapay.sdk.security.FornecedorTokenAcesso;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

public class ClienteOrquestraPay {

    private final UUID idEmpresa;
    private final FornecedorTokenAcesso fornecedorToken;
    private final RestClient checkout;
    private final RestClient pagamento;
    private final RestClient razao;

    public ClienteOrquestraPay(
            PropriedadesClienteOrquestra propriedades,
            FornecedorTokenAcesso fornecedorToken) {
        if (propriedades.idEmpresa() == null) {
            throw new IllegalArgumentException("O id da empresa e obrigatorio");
        }
        this.idEmpresa = propriedades.idEmpresa();
        this.fornecedorToken = fornecedorToken;
        var clienteHttp = HttpClient.newBuilder()
                .connectTimeout(propriedades.tempoLimite())
                .build();
        var fabrica = new JdkClientHttpRequestFactory(clienteHttp);
        fabrica.setReadTimeout(propriedades.tempoLimite());
        this.checkout = construir(propriedades.urlCheckout().toString(), fabrica);
        this.pagamento = construir(propriedades.urlPagamento().toString(), fabrica);
        this.razao = construir(propriedades.urlRazao().toString(), fabrica);
    }

    public RespostaCompraCliente criarCompra(
            String chaveIdempotencia,
            NovaCompraCliente compra) {
        if (chaveIdempotencia == null || chaveIdempotencia.isBlank()) {
            throw new IllegalArgumentException("A chave de idempotencia e obrigatoria");
        }
        return executar(() -> checkout.post()
                .uri("/api/v1/compras")
                .headers(this::adicionarCabecalhos)
                .header("Idempotency-Key", chaveIdempotencia)
                .body(compra)
                .retrieve()
                .body(RespostaCompraCliente.class));
    }

    public RespostaCompraCliente consultarCompra(UUID idCompra) {
        return executar(() -> checkout.get()
                .uri("/api/v1/compras/{idCompra}", idCompra)
                .headers(this::adicionarCabecalhos)
                .retrieve()
                .body(RespostaCompraCliente.class));
    }

    public RespostaPagamentoCliente consultarPagamento(UUID idCompra) {
        return executar(() -> pagamento.get()
                .uri("/api/v1/pagamentos/compras/{idCompra}", idCompra)
                .headers(this::adicionarCabecalhos)
                .retrieve()
                .body(RespostaPagamentoCliente.class));
    }

    public RespostaTransacaoContabilCliente consultarRazao(UUID idCompra) {
        return executar(() -> razao.get()
                .uri("/api/v1/transacoes-contabeis/compras/{idCompra}", idCompra)
                .headers(this::adicionarCabecalhos)
                .retrieve()
                .body(RespostaTransacaoContabilCliente.class));
    }

    private RestClient construir(String urlBase, JdkClientHttpRequestFactory fabrica) {
        return RestClient.builder()
                .baseUrl(urlBase)
                .requestFactory(fabrica)
                .build();
    }

    private void adicionarCabecalhos(HttpHeaders cabecalhos) {
        cabecalhos.set("X-Empresa-Id", idEmpresa.toString());
        if (fornecedorToken == null) {
            return;
        }
        String token = fornecedorToken.obterToken();
        if (token != null && !token.isBlank()) {
            cabecalhos.setBearerAuth(token);
        }
    }

    private <T> T executar(java.util.function.Supplier<T> chamada) {
        try {
            T resposta = chamada.get();
            if (resposta == null) {
                throw new ExcecaoClienteOrquestra(
                        502,
                        "O OrquestraPay retornou uma resposta vazia",
                        null);
            }
            return resposta;
        } catch (RestClientResponseException excecao) {
            throw new ExcecaoClienteOrquestra(
                    excecao.getStatusCode().value(),
                    "O OrquestraPay recusou a operacao com HTTP "
                            + excecao.getStatusCode().value(),
                    excecao);
        }
    }
}

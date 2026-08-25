/* exported sendingRequest, responseReceived */

var UUID = Java.type('java.util.UUID');
var UUID_NORMALIZADO = '00000000-0000-0000-0000-000000000000';
var INSTANTE_NORMALIZADO = '2026-01-01T00:00:00Z';

// Os nomes destas funcoes sao definidos pela API de scripts HTTP Sender do ZAP.
function sendingRequest(mensagem, iniciador, auxiliar) {
    var cabecalho = mensagem.getRequestHeader();
    var caminho = cabecalho.getURI().getPath();

    if (cabecalho.getMethod() === 'POST' && caminho === '/api/v1/compras') {
        cabecalho.setHeader('Idempotency-Key', UUID.randomUUID().toString());
        cabecalho.setHeader('X-Empresa-Id', UUID.randomUUID().toString());
    }
}

function responseReceived(mensagem, iniciador, auxiliar) {
    var requisicao = mensagem.getRequestHeader();
    var resposta = mensagem.getResponseHeader();

    if (requisicao.getMethod() !== 'POST'
            || requisicao.getURI().getPath() !== '/api/v1/compras'
            || resposta.getStatusCode() !== 202) {
        return;
    }

    // IDs e instantes mudam em toda compra. Normaliza somente a copia vista pelo
    // scanner para que a comparacao considere os campos controlados pela sonda.
    var corpo = mensagem.getResponseBody().toString()
        .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, UUID_NORMALIZADO)
        .replace(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/g, INSTANTE_NORMALIZADO);

    mensagem.setResponseBody(corpo);
    resposta.setHeader('Location', '/api/v1/compras/' + UUID_NORMALIZADO);
    resposta.setContentLength(mensagem.getResponseBody().length());
}

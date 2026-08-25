/* exported sendingRequest, responseReceived */

var UUID = Java.type('java.util.UUID');

// Os nomes destas funcoes sao definidos pela API de scripts HTTP Sender do ZAP.
function sendingRequest(mensagem, iniciador, auxiliar) {
    var cabecalho = mensagem.getRequestHeader();
    var caminho = cabecalho.getURI().getPath();

    if (cabecalho.getMethod() === 'POST' && caminho === '/api/v1/compras') {
        cabecalho.setHeader('Idempotency-Key', UUID.randomUUID().toString());
    }
}

function responseReceived(mensagem, iniciador, auxiliar) {
    // Nenhuma alteracao de resposta e necessaria.
}

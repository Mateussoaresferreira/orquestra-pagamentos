import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const comprasAceitas = new Counter('compras_aceitas');
const urlCheckout = __ENV.URL_CHECKOUT || 'http://servico-checkout:8080';
const urlEstoque = __ENV.URL_ESTOQUE || 'http://servico-estoque:8080';
const idEmpresa = '10000000-0000-0000-0000-000000000001';
const idProduto = '20000000-0000-0000-0000-000000000001';

export const options = {
  scenarios: {
    checkout: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '20s', target: 20 },
        { duration: '40s', target: 20 },
        { duration: '20s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<750'],
    compras_aceitas: ['count>100'],
  },
};

export function setup() {
  const resposta = http.put(
    `${urlEstoque}/api/v1/estoques/${idProduto}`,
    JSON.stringify({ quantidadeDisponivel: 100000, motivo: 'Teste de carga' }),
    { headers: { 'Content-Type': 'application/json', 'X-Empresa-Id': idEmpresa } },
  );
  check(resposta, { 'estoque preparado': (r) => r.status === 200 });
}

export default function () {
  const identificador = `${__VU}-${__ITER}-${Date.now()}`;
  const resposta = http.post(
    `${urlCheckout}/api/v1/compras`,
    JSON.stringify({
      idCliente: `cliente-${identificador}`,
      emailCliente: `carga-${__VU}@orquestrapay.local`,
      moeda: 'BRL',
      pais: 'BR',
      identificadorDispositivo: `dispositivo-${identificador}`,
      tokenPagamento: 'tok_aprovado',
      itens: [{ idProduto, quantidade: 1, precoUnitario: 19.90 }],
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Empresa-Id': idEmpresa,
        'Idempotency-Key': `carga-${identificador}`,
      },
    },
  );
  if (check(resposta, { 'compra aceita': (r) => r.status === 202 })) {
    comprasAceitas.add(1);
  }
  sleep(0.1);
}

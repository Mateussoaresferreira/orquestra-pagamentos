import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate } from 'k6/metrics';

const comprasAceitas = new Counter('compras_aceitas');
const requisicoesLimitadas = new Counter('requisicoes_limitadas');
const respostasInesperadas = new Counter('respostas_inesperadas');
const taxaAceitacao = new Rate('taxa_aceitacao');
const urlCheckout = __ENV.URL_CHECKOUT || 'http://servico-checkout:8080';
const urlEstoque = __ENV.URL_ESTOQUE || 'http://servico-estoque:8080';
const idProduto = '20000000-0000-0000-0000-000000000001';
const quantidadeEmpresas = Number(__ENV.QUANTIDADE_EMPRESAS || 100);
const taxaPico = Number(__ENV.TAXA_PICO || 25);
const taxaInicial = Number(__ENV.TAXA_INICIAL || Math.max(1, Math.floor(taxaPico * 0.2)));
const taxaAquecimento = Number(
  __ENV.TAXA_AQUECIMENTO || Math.max(taxaInicial, Math.floor(taxaPico * 0.5)),
);
const duracaoAquecimento = __ENV.DURACAO_AQUECIMENTO || '20s';
const duracaoSubida = __ENV.DURACAO_SUBIDA || '40s';
const duracaoSustentacao = __ENV.DURACAO_SUSTENTACAO || '40s';
const duracaoReducao = __ENV.DURACAO_REDUCAO || '20s';
const vusPreAlocados = Number(__ENV.VUS_PRE_ALOCADOS || 20);
const vusMaximos = Number(__ENV.VUS_MAXIMOS || 80);
const minimoComprasAceitas = Number(__ENV.MINIMO_COMPRAS_ACEITAS || 500);
const maximoIteracoesDescartadas = Number(__ENV.MAXIMO_ITERACOES_DESCARTADAS || 5);
const maximoRequisicoesLimitadas = Number(__ENV.MAXIMO_REQUISICOES_LIMITADAS || 0);
const minimoTaxaAceitacao = Number(__ENV.MINIMO_TAXA_ACEITACAO || 0.99);
const identificadorTrabalhador = __ENV.ID_TRABALHADOR || 'local';

function idEmpresa(indice) {
  return `10000000-0000-0000-0000-${String(indice + 101).padStart(12, '0')}`;
}

export const options = {
  scenarios: {
    checkout: {
      executor: 'ramping-arrival-rate',
      startRate: taxaInicial,
      timeUnit: '1s',
      preAllocatedVUs: vusPreAlocados,
      maxVUs: vusMaximos,
      stages: [
        { duration: duracaoAquecimento, target: taxaAquecimento },
        { duration: duracaoSubida, target: taxaPico },
        { duration: duracaoSustentacao, target: taxaPico },
        { duration: duracaoReducao, target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<750'],
    checks: ['rate>0.99'],
    compras_aceitas: [`count>=${minimoComprasAceitas}`],
    taxa_aceitacao: [`rate>=${minimoTaxaAceitacao}`],
    requisicoes_limitadas: [`count<=${maximoRequisicoesLimitadas}`],
    respostas_inesperadas: ['count<1'],
    dropped_iterations: [`count<=${maximoIteracoesDescartadas}`],
  },
};

export function setup() {
  const empresas = Array.from({ length: quantidadeEmpresas }, (_, indice) => idEmpresa(indice));
  for (let inicio = 0; inicio < empresas.length; inicio += 20) {
    const requisicoes = empresas.slice(inicio, inicio + 20).map((empresa) => ({
      method: 'PUT',
      url: `${urlEstoque}/api/v1/estoques/${idProduto}`,
      body: JSON.stringify({ quantidadeDisponivel: 1000, motivo: 'Teste de carga multiempresa' }),
      params: {
        headers: { 'Content-Type': 'application/json', 'X-Empresa-Id': empresa },
      },
    }));
    const respostas = http.batch(requisicoes);
    respostas.forEach((resposta) => check(resposta, {
      'estoque preparado': (resultado) => resultado.status === 200,
    }));
  }
  return { empresas };
}

export default function (dados) {
  const iteracao = exec.scenario.iterationInTest;
  const empresa = dados.empresas[iteracao % dados.empresas.length];
  const identificador = `${identificadorTrabalhador}-${exec.vu.idInTest}-${iteracao}-${Date.now()}`;
  const resposta = http.post(
    `${urlCheckout}/api/v1/compras`,
    JSON.stringify({
      idCliente: `cliente-${identificador}`,
      emailCliente: `carga-${identificadorTrabalhador}-${__VU}@orquestrapay.local`,
      moeda: 'BRL',
      pais: 'BR',
      identificadorDispositivo: `dispositivo-${identificador}`,
      tokenPagamento: 'tok_aprovado',
      itens: [{ idProduto, quantidade: 1, precoUnitario: 19.90 }],
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Empresa-Id': empresa,
        'Idempotency-Key': `carga-${identificador}`,
      },
      responseCallback: http.expectedStatuses(202, 429),
    },
  );
  const respostaControlada = check(resposta, {
    'resposta controlada': (r) => r.status === 202 || r.status === 429,
  });
  if (resposta.status === 202) {
    comprasAceitas.add(1);
    taxaAceitacao.add(true);
  } else if (resposta.status === 429) {
    requisicoesLimitadas.add(1);
    taxaAceitacao.add(false);
  } else {
    respostasInesperadas.add(1);
    taxaAceitacao.add(false);
    if (!respostaControlada) {
      console.error(`Resposta inesperada: status=${resposta.status} corpo=${resposta.body}`);
    }
  }
}

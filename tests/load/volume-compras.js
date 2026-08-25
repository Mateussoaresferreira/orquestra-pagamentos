import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const comprasAceitas = new Counter('compras_aceitas');
const comprasReprocessadas = new Counter('compras_reprocessadas');
const requisicoesLimitadas = new Counter('requisicoes_limitadas');
const respostasInesperadas = new Counter('respostas_inesperadas');

const urlCheckout = __ENV.URL_CHECKOUT || 'http://servico-checkout:8080';
const totalComprasLote = Number(__ENV.TOTAL_COMPRAS_LOTE || 100);
const offsetCompras = Number(__ENV.OFFSET_COMPRAS || 0);
const quantidadeEmpresas = Number(__ENV.QUANTIDADE_EMPRESAS || 100);
const usuarios = Number(__ENV.USUARIOS || 20);
const taxaAlvo = Number(__ENV.TAXA_ALVO || 25);
const duracaoMaximaLote = __ENV.DURACAO_MAXIMA_LOTE || '2h';
const limiteP95Ms = Number(__ENV.LIMITE_P95_MS || 1000);
const idExecucao = __ENV.ID_EXECUCAO || 'volume-local';
const idProduto = __ENV.ID_PRODUTO;

function validarConfiguracao() {
  const inteirosPositivos = [totalComprasLote, quantidadeEmpresas, usuarios, taxaAlvo];
  if (inteirosPositivos.some((valor) => !Number.isSafeInteger(valor) || valor <= 0)) {
    throw new Error('TOTAL_COMPRAS_LOTE, QUANTIDADE_EMPRESAS, USUARIOS e TAXA_ALVO devem ser inteiros positivos');
  }
  if (!Number.isSafeInteger(offsetCompras) || offsetCompras < 0) {
    throw new Error('OFFSET_COMPRAS deve ser um inteiro positivo ou zero');
  }
  if (!idProduto) {
    throw new Error('ID_PRODUTO e obrigatorio');
  }
}

validarConfiguracao();

function idEmpresa(indice) {
  return `10000000-0000-0000-0000-${String(indice + 101).padStart(12, '0')}`;
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    volume: {
      executor: 'shared-iterations',
      vus: usuarios,
      iterations: totalComprasLote,
      maxDuration: duracaoMaximaLote,
      gracefulStop: '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    http_req_duration: [`p(95)<${limiteP95Ms}`],
    checks: ['rate==1'],
    compras_aceitas: [`count==${totalComprasLote}`],
    requisicoes_limitadas: ['count==0'],
    respostas_inesperadas: ['count==0'],
  },
};

export default function () {
  const inicioIteracao = Date.now();
  const indiceNoLote = exec.scenario.iterationInTest;
  const indiceGlobal = offsetCompras + indiceNoLote;
  const empresa = idEmpresa(indiceGlobal % quantidadeEmpresas);
  const identificador = `${idExecucao}-${indiceGlobal}`;

  const resposta = http.post(
    `${urlCheckout}/api/v1/compras`,
    JSON.stringify({
      idCliente: `cliente-volume-${identificador}`,
      emailCliente: `volume-${indiceGlobal % quantidadeEmpresas}@orquestrapay.local`,
      moeda: 'BRL',
      pais: 'BR',
      identificadorDispositivo: `dispositivo-volume-${identificador}`,
      tokenPagamento: 'tok_aprovado',
      metodoPagamento: 'CARTAO',
      parcelas: 1,
      itens: [{ idProduto, quantidade: 1, precoUnitario: 19.90 }],
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Empresa-Id': empresa,
        'Idempotency-Key': `volume-${identificador}`,
      },
      tags: { tipo_carga: 'volume-exato' },
    },
  );

  if (check(resposta, { 'compra aceita': (resultado) => resultado.status === 202 })) {
    comprasAceitas.add(1);
    if (String(resposta.headers['Idempotency-Replayed']).toLowerCase() === 'true') {
      comprasReprocessadas.add(1);
    }
  } else if (resposta.status === 429) {
    requisicoesLimitadas.add(1);
  } else {
    respostasInesperadas.add(1);
  }

  const intervaloPorUsuario = usuarios / taxaAlvo;
  const duracaoIteracao = (Date.now() - inicioIteracao) / 1000;
  sleep(Math.max(0, intervaloPorUsuario - duracaoIteracao));
}

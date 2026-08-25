package br.com.orquestrapay.checkout.service;

import java.util.List;

import br.com.orquestrapay.checkout.config.PropriedadesLimiteRequisicoes;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class LimitadorRequisicoes {

    private static final DefaultRedisScript<String> CONSUMIR_TOKENS = new DefaultRedisScript<>("""
            local tempo = redis.call('TIME')
            local agora = tonumber(tempo[1]) * 1000 + math.floor(tonumber(tempo[2]) / 1000)

            local function carregar(chave, capacidade, janela)
              local dados = redis.call('HMGET', chave, 'tokens', 'atualizado_em')
              local tokens = tonumber(dados[1])
              local atualizado_em = tonumber(dados[2])
              if tokens == nil then tokens = capacidade end
              if atualizado_em == nil then atualizado_em = agora end
              local taxa = capacidade / janela
              local decorrido = math.max(0, agora - atualizado_em)
              return math.min(capacidade, tokens + decorrido * taxa), taxa
            end

            local capacidade_global = tonumber(ARGV[1])
            local janela_global = tonumber(ARGV[2])
            local capacidade_empresa = tonumber(ARGV[3])
            local janela_empresa = tonumber(ARGV[4])
            local tokens_global, taxa_global = carregar(KEYS[1], capacidade_global, janela_global)
            local tokens_empresa, taxa_empresa = carregar(KEYS[2], capacidade_empresa, janela_empresa)
            local permitido = 0

            if tokens_global >= 1 and tokens_empresa >= 1 then
              tokens_global = tokens_global - 1
              tokens_empresa = tokens_empresa - 1
              permitido = 1
            end

            local espera_global = 0
            local espera_empresa = 0
            if tokens_global < 1 then espera_global = math.ceil((1 - tokens_global) / taxa_global) end
            if tokens_empresa < 1 then espera_empresa = math.ceil((1 - tokens_empresa) / taxa_empresa) end
            local espera = math.max(espera_global, espera_empresa)
            local validade = math.ceil(math.max(janela_global, janela_empresa) * 2)

            redis.call('HSET', KEYS[1], 'tokens', tokens_global, 'atualizado_em', agora)
            redis.call('HSET', KEYS[2], 'tokens', tokens_empresa, 'atualizado_em', agora)
            redis.call('PEXPIRE', KEYS[1], validade)
            redis.call('PEXPIRE', KEYS[2], validade)

            return permitido .. ':' .. math.floor(tokens_empresa) .. ':'
                   .. math.floor(tokens_global) .. ':' .. espera
            """, String.class);

    private final StringRedisTemplate redis;
    private final PropriedadesLimiteRequisicoes propriedades;

    public LimitadorRequisicoes(
            StringRedisTemplate redis,
            PropriedadesLimiteRequisicoes propriedades) {
        this.redis = redis;
        this.propriedades = propriedades;
    }

    public ResultadoLimite consumir(String idEmpresa) {
        if (!propriedades.habilitado()) {
            return new ResultadoLimite(
                    true,
                    propriedades.maximoPorJanela(),
                    propriedades.maximoGlobalPorJanela(),
                    0);
        }

        String resultado = redis.execute(
                CONSUMIR_TOKENS,
                List.of(
                        "orquestrapay:{admissao}:compras:global",
                        "orquestrapay:{admissao}:compras:empresa:" + idEmpresa),
                Integer.toString(propriedades.maximoGlobalPorJanela()),
                Long.toString(propriedades.janelaGlobal().toMillis()),
                Integer.toString(propriedades.maximoPorJanela()),
                Long.toString(propriedades.janela().toMillis()));
        if (resultado == null) {
            return new ResultadoLimite(false, 0, 0, propriedades.janelaGlobal().toMillis());
        }

        String[] campos = resultado.split(":", -1);
        if (campos.length != 4) {
            throw new IllegalStateException("Resposta invalida do limitador distribuido");
        }
        return new ResultadoLimite(
                "1".equals(campos[0]),
                Long.parseLong(campos[1]),
                Long.parseLong(campos[2]),
                Long.parseLong(campos[3]));
    }

    public PropriedadesLimiteRequisicoes propriedades() {
        return propriedades;
    }

    public record ResultadoLimite(
            boolean permitido,
            long restanteEmpresa,
            long restanteGlobal,
            long tentarNovamenteEmMillis) {
    }
}

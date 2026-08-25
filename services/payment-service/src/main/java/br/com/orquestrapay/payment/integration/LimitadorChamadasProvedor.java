package br.com.orquestrapay.payment.integration;

import java.util.List;

import br.com.orquestrapay.payment.config.PropriedadesPagamentos;
import br.com.orquestrapay.payment.config.PropriedadesProvedor;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class LimitadorChamadasProvedor {

    private static final DefaultRedisScript<String> CONSUMIR_TOKEN = new DefaultRedisScript<>("""
            local tempo = redis.call('TIME')
            local agora = tonumber(tempo[1]) * 1000 + math.floor(tonumber(tempo[2]) / 1000)
            local capacidade = tonumber(ARGV[1])
            local periodo = tonumber(ARGV[2])
            local dados = redis.call('HMGET', KEYS[1], 'tokens', 'atualizado_em')
            local tokens = tonumber(dados[1])
            local atualizado_em = tonumber(dados[2])
            if tokens == nil then tokens = capacidade end
            if atualizado_em == nil then atualizado_em = agora end

            local taxa = capacidade / periodo
            local decorrido = math.max(0, agora - atualizado_em)
            tokens = math.min(capacidade, tokens + decorrido * taxa)
            local permitido = 0
            if tokens >= 1 then
              tokens = tokens - 1
              permitido = 1
            end

            local espera = 0
            if tokens < 1 then espera = math.ceil((1 - tokens) / taxa) end
            redis.call('HSET', KEYS[1], 'tokens', tokens, 'atualizado_em', agora)
            redis.call('PEXPIRE', KEYS[1], math.ceil(periodo * 2))
            return permitido .. ':' .. math.floor(tokens) .. ':' .. espera
            """, String.class);

    private final StringRedisTemplate redis;
    private final PropriedadesPagamentos propriedades;
    private final MeterRegistry metricas;

    public LimitadorChamadasProvedor(
            StringRedisTemplate redis,
            PropriedadesPagamentos propriedades,
            MeterRegistry metricas) {
        this.redis = redis;
        this.propriedades = propriedades;
        this.metricas = metricas;
    }

    public ResultadoCota consumir(String nome, PropriedadesProvedor provedor) {
        if (!propriedades.controleProvedores().limiteDistribuidoHabilitado()) {
            return new ResultadoCota(true, provedor.maximoChamadasPorPeriodo(), 0);
        }

        try {
            String resultado = redis.execute(
                    CONSUMIR_TOKEN,
                    List.of("orquestrapay:{provedor:" + nome + "}:cota"),
                    Integer.toString(provedor.maximoChamadasPorPeriodo()),
                    Long.toString(provedor.periodoLimite().toMillis()));
            if (resultado == null) {
                throw new IllegalStateException("Resposta vazia do limitador de provedor");
            }
            String[] campos = resultado.split(":", -1);
            if (campos.length != 3) {
                throw new IllegalStateException("Resposta invalida do limitador de provedor");
            }
            var cota = new ResultadoCota(
                    "1".equals(campos[0]),
                    Long.parseLong(campos[1]),
                    Long.parseLong(campos[2]));
            metricas.counter(
                    "orquestrapay.provedor.cota",
                    "provedor", nome,
                    "resultado", cota.permitido() ? "permitido" : "limitado").increment();
            return cota;
        } catch (RedisConnectionFailureException excecao) {
            metricas.counter(
                    "orquestrapay.provedor.cota",
                    "provedor", nome,
                    "resultado", "redis-indisponivel").increment();
            if (propriedades.controleProvedores().permitirSemRedis()) {
                return new ResultadoCota(true, provedor.maximoChamadasPorPeriodo(), 0);
            }
            throw new ExcecaoLimitadorProvedor(nome, excecao);
        }
    }

    public record ResultadoCota(boolean permitido, long restante, long tentarNovamenteEmMillis) {
    }
}

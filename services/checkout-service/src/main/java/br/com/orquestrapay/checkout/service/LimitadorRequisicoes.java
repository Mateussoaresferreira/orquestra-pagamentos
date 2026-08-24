package br.com.orquestrapay.checkout.service;

import java.util.List;

import br.com.orquestrapay.checkout.config.PropriedadesLimiteRequisicoes;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class LimitadorRequisicoes {

    private static final DefaultRedisScript<Long> INCREMENTAR_COM_VALIDADE = new DefaultRedisScript<>("""
            local atual = redis.call('INCR', KEYS[1])
            if atual == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return atual
            """, Long.class);

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
            return new ResultadoLimite(true, propriedades.maximoPorJanela());
        }

        Long quantidade = redis.execute(
                INCREMENTAR_COM_VALIDADE,
                List.of("orquestrapay:limite:compras:" + idEmpresa),
                Long.toString(propriedades.janela().toMillis()));
        long atual = quantidade == null ? propriedades.maximoPorJanela() + 1L : quantidade;
        return new ResultadoLimite(
                atual <= propriedades.maximoPorJanela(),
                Math.max(0, propriedades.maximoPorJanela() - atual));
    }

    public PropriedadesLimiteRequisicoes propriedades() {
        return propriedades;
    }

    public record ResultadoLimite(boolean permitido, long restante) {
    }
}

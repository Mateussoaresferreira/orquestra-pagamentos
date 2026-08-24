package br.com.orquestrapay.platform.event;

import java.time.Clock;
import java.util.UUID;

import br.com.orquestrapay.platform.data.DatasSql;
import org.springframework.jdbc.core.simple.JdbcClient;

public class RegistroMensagens {

    private final JdbcClient banco;
    private final Clock relogio;

    public RegistroMensagens(JdbcClient banco, Clock relogio) {
        this.banco = banco;
        this.relogio = relogio;
    }

    /**
     * Registra a mensagem dentro da mesma transacao da regra de negocio.
     * Retorna falso quando este consumidor ja concluiu o mesmo evento.
     */
    public boolean iniciar(UUID idEvento, String consumidor) {
        int inseridos = banco.sql("""
                        INSERT INTO evento_processado (id_evento, consumidor, processado_em)
                        VALUES (:idEvento, :consumidor, :processadoEm)
                        ON CONFLICT (id_evento, consumidor) DO NOTHING
                        """)
                .param("idEvento", idEvento)
                .param("consumidor", consumidor)
                .param("processadoEm", DatasSql.gravar(relogio.instant()))
                .update();
        return inseridos == 1;
    }
}

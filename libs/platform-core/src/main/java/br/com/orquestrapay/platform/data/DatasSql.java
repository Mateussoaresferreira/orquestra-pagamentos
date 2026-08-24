package br.com.orquestrapay.platform.data;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class DatasSql {

    private DatasSql() {
    }

    public static OffsetDateTime gravar(Instant instante) {
        return instante == null ? null : instante.atOffset(ZoneOffset.UTC);
    }

    public static Instant ler(ResultSet resultado, String coluna) throws SQLException {
        OffsetDateTime valor = resultado.getObject(coluna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }
}

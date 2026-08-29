package br.com.orquestrapay.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;

class TesteContratoAvro {

    @Test
    void mantemFonteHelmEClasseGeradaComOMesmoEsquema() throws IOException {
        var raiz = localizarRaizProjeto();
        var esquemaFonte = lerEsquema(
                raiz.resolve("libs/event-contracts/src/main/avro/evento-saga.avsc"));
        var esquemaHelm = lerEsquema(
                raiz.resolve("infra/kubernetes/helm/orquestrapay/files/evento-saga.avsc"));

        assertThat(esquemaFonte).isEqualTo(EventoSaga.getClassSchema());
        assertThat(esquemaHelm).isEqualTo(esquemaFonte);
    }

    private Schema lerEsquema(Path caminho) throws IOException {
        return new Schema.Parser().parse(Files.readString(caminho));
    }

    private Path localizarRaizProjeto() {
        var atual = Path.of("").toAbsolutePath();
        while (atual != null) {
            if (Files.isRegularFile(atual.resolve("compose.yml"))
                    && Files.isDirectory(atual.resolve("libs/event-contracts"))) {
                return atual;
            }
            atual = atual.getParent();
        }
        throw new IllegalStateException("Nao foi possivel localizar a raiz do projeto.");
    }
}

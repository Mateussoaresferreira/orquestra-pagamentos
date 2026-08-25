package br.com.orquestrapay.checkout.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.orquestrapay.checkout.config.PropriedadesLimiteRequisicoes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class TesteControladorAdmissaoLocal {

    @Test
    void deveRecusarExcessoELiberarAVagaSomenteUmaVez() {
        var controlador = new ControladorAdmissaoLocal(
                new PropriedadesLimiteRequisicoes(true, 60, null, 300, null, false, 1),
                new SimpleMeterRegistry());

        var permissao = controlador.tentarAdmitir().orElseThrow();
        assertThat(controlador.emProcessamento()).isEqualTo(1);
        assertThat(controlador.tentarAdmitir()).isEmpty();

        permissao.close();
        permissao.close();
        assertThat(controlador.emProcessamento()).isZero();

        try (var novaPermissao = controlador.tentarAdmitir().orElseThrow()) {
            assertThat(controlador.emProcessamento()).isEqualTo(1);
        }
        assertThat(controlador.emProcessamento()).isZero();
    }
}

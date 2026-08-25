package br.com.orquestrapay.platform.event;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import br.com.orquestrapay.platform.web.ExcecaoNegocio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TesteServicoQuarentena {

    private static final Instant AGORA = Instant.parse("2026-08-25T15:00:00Z");

    @Mock
    private RepositorioEventos repositorio;

    @Test
    void deveRejeitarStatusDeConsultaInvalido() {
        assertThatThrownBy(() -> servico().listar(
                UUID.randomUUID(), "pendente", 0, 20))
                .isInstanceOf(ExcecaoNegocio.class)
                .hasMessageContaining("ATIVA, RESOLVIDA ou TODAS");
        verify(repositorio, never()).listarQuarentena(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void deveExigirMotivoOperacionalERegistrarTextoNormalizado() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idEvento = UUID.randomUUID();

        assertThatThrownBy(() -> servico().reprocessar(
                idEmpresa, idEvento, "operador", "curto"))
                .isInstanceOf(ExcecaoNegocio.class)
                .hasMessageContaining("10 e 500");
        when(repositorio.reprocessarQuarentena(
                idEmpresa,
                idEvento,
                "operador",
                "Kafka recuperado e validado",
                AGORA)).thenReturn(true);

        servico().reprocessar(
                idEmpresa,
                idEvento,
                "operador",
                "  Kafka recuperado e validado  ");

        verify(repositorio).reprocessarQuarentena(
                idEmpresa,
                idEvento,
                "operador",
                "Kafka recuperado e validado",
                AGORA);
    }

    private ServicoQuarentena servico() {
        return new ServicoQuarentena(
                repositorio,
                Clock.fixed(AGORA, ZoneOffset.UTC));
    }
}

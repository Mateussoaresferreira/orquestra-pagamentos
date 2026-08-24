package br.com.orquestrapay.inventory.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.orquestrapay.contracts.ItemCompra;
import br.com.orquestrapay.inventory.domain.StatusReserva;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class TesteIntegracaoRepositorioEstoque {

    @Container
    private static final PostgreSQLContainer BANCO = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("estoque_teste")
            .withUsername("teste")
            .withPassword("teste");

    private static RepositorioEstoque repositorio;

    @BeforeAll
    static void prepararBanco() {
        var fonteDados = new DriverManagerDataSource(BANCO.getJdbcUrl(), BANCO.getUsername(), BANCO.getPassword());
        Flyway.configure()
                .dataSource(fonteDados)
                .locations("classpath:db/migration/shared", "classpath:db/migration/service")
                .load()
                .migrate();
        repositorio = new RepositorioEstoque(JdbcClient.create(fonteDados));
    }

    @Test
    void deveReservarELiberarEstoqueSemPerderSaldo() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idProduto = UUID.randomUUID();
        UUID idReserva = UUID.randomUUID();
        UUID idCompra = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");
        var item = new ItemCompra(idProduto, 3, new BigDecimal("49.90"));

        repositorio.definirSaldo(idEmpresa, idProduto, 10, agora);
        repositorio.reservar(idEmpresa, item, agora.plusSeconds(1));
        repositorio.salvarReserva(
                idReserva,
                idEmpresa,
                idCompra,
                StatusReserva.RESERVADA,
                "Reserva de teste",
                List.of(item),
                agora.plusSeconds(1));

        var saldoReservado = repositorio.buscarSaldo(idEmpresa, idProduto).orElseThrow();
        assertThat(saldoReservado.quantidadeDisponivel()).isEqualTo(7);
        assertThat(saldoReservado.quantidadeReservada()).isEqualTo(3);
        assertThat(repositorio.reservaExiste(idEmpresa, idReserva)).isTrue();
        assertThat(repositorio.reservaExiste(UUID.randomUUID(), idReserva)).isFalse();

        var reserva = repositorio.bloquearReserva(idReserva).orElseThrow();
        repositorio.buscarItensReserva(idReserva)
                .forEach(itemReserva -> repositorio.liberar(idEmpresa, itemReserva, agora.plusSeconds(2)));
        repositorio.marcarLiberada(reserva.idReserva(), agora.plusSeconds(2));

        var saldoLiberado = repositorio.buscarSaldo(idEmpresa, idProduto).orElseThrow();
        assertThat(saldoLiberado.quantidadeDisponivel()).isEqualTo(10);
        assertThat(saldoLiberado.quantidadeReservada()).isZero();
        assertThat(repositorio.bloquearReserva(idReserva).orElseThrow().status())
                .isEqualTo(StatusReserva.LIBERADA);
    }

    @Test
    void deveRecusarLiberacaoQuandoOSaldoReservadoNaoCorrespondeAReserva() {
        UUID idEmpresa = UUID.randomUUID();
        UUID idProduto = UUID.randomUUID();
        Instant agora = Instant.parse("2026-08-23T12:00:00Z");

        repositorio.definirSaldo(idEmpresa, idProduto, 10, agora);

        var itemReserva = new RepositorioEstoque.ItemReserva(idProduto, 3);
        assertThatThrownBy(() -> repositorio.liberar(idEmpresa, itemReserva, agora.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(idProduto.toString());

        var saldo = repositorio.buscarSaldo(idEmpresa, idProduto).orElseThrow();
        assertThat(saldo.quantidadeDisponivel()).isEqualTo(10);
        assertThat(saldo.quantidadeReservada()).isZero();
    }
}

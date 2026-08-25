package br.com.orquestrapay.platform.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.seguranca")
public record PropriedadesSeguranca(
        boolean habilitada,
        String claimEmpresa,
        String claimGrupos,
        String emissor,
        Set<String> clientesId,
        String clienteMaquinaId,
        String empresaClienteMaquina) {

    public PropriedadesSeguranca {
        claimEmpresa = claimEmpresa == null || claimEmpresa.isBlank()
                ? "custom:empresa_id"
                : claimEmpresa;
        claimGrupos = claimGrupos == null || claimGrupos.isBlank()
                ? "cognito:groups"
                : claimGrupos;
        clientesId = clientesId == null
                ? Set.of()
                : clientesId.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(valor -> !valor.isBlank())
                        .collect(Collectors.toUnmodifiableSet());
        clienteMaquinaId = normalizar(clienteMaquinaId);
        empresaClienteMaquina = normalizar(empresaClienteMaquina);

        boolean possuiClienteMaquina = clienteMaquinaId != null;
        boolean possuiEmpresaMaquina = empresaClienteMaquina != null;
        if (possuiClienteMaquina != possuiEmpresaMaquina) {
            throw new IllegalArgumentException(
                    "Cliente maquina e empresa correspondente devem ser configurados juntos");
        }
        if (possuiClienteMaquina && !clientesId.contains(clienteMaquinaId)) {
            throw new IllegalArgumentException(
                    "O cliente maquina deve pertencer a lista de clientes OIDC confiaveis");
        }
        if (possuiEmpresaMaquina) {
            try {
                UUID.fromString(empresaClienteMaquina);
            } catch (IllegalArgumentException excecao) {
                throw new IllegalArgumentException(
                        "A empresa do cliente maquina deve ser um UUID valido",
                        excecao);
            }
        }
    }

    private static String normalizar(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }
}

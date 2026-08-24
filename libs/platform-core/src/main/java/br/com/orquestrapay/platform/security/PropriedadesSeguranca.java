package br.com.orquestrapay.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("orquestrapay.seguranca")
public record PropriedadesSeguranca(
        boolean habilitada,
        String claimEmpresa,
        String claimGrupos,
        String emissor,
        String clienteId) {

    public PropriedadesSeguranca {
        claimEmpresa = claimEmpresa == null || claimEmpresa.isBlank()
                ? "custom:empresa_id"
                : claimEmpresa;
        claimGrupos = claimGrupos == null || claimGrupos.isBlank()
                ? "cognito:groups"
                : claimGrupos;
    }
}

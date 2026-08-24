package br.com.orquestrapay.platform.security;

import java.util.ArrayList;
import java.util.Collection;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

public class ConversorAutoridadesJwt implements Converter<Jwt, Collection<GrantedAuthority>> {

    private final JwtGrantedAuthoritiesConverter conversorEscopos = new JwtGrantedAuthoritiesConverter();
    private final PropriedadesSeguranca propriedades;

    public ConversorAutoridadesJwt(PropriedadesSeguranca propriedades) {
        this.propriedades = propriedades;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        var autoridades = new ArrayList<GrantedAuthority>();
        Collection<GrantedAuthority> escopos = conversorEscopos.convert(jwt);
        if (escopos != null) {
            autoridades.addAll(escopos);
        }
        var grupos = jwt.getClaimAsStringList(propriedades.claimGrupos());
        if (grupos != null) {
            grupos.stream()
                    .map(String::toUpperCase)
                    .map(grupo -> grupo.replace('-', '_'))
                    .map(grupo -> new SimpleGrantedAuthority("ROLE_" + grupo))
                    .forEach(autoridades::add);
        }
        return autoridades;
    }
}

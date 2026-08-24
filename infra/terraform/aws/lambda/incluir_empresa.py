def manipular(evento, contexto):
    atributos = evento.get("request", {}).get("userAttributes", {})
    id_empresa = atributos.get("custom:empresa_id")

    if id_empresa:
        resposta = evento.setdefault("response", {})
        detalhes = resposta.setdefault("claimsAndScopeOverrideDetails", {})
        token_acesso = detalhes.setdefault("accessTokenGeneration", {})
        token_acesso["claimsToAddOrOverride"] = {
            "custom:empresa_id": id_empresa
        }

    return evento

CAMINHO_SCRIPT = "/zap/orquestrapay/tests/security/chave-idempotencia-dinamica.js"
NOME_SCRIPT = "chave-idempotencia-dinamica"


def zap_started(zap, target):
    resultado = zap.script.load(
        NOME_SCRIPT,
        "httpsender",
        "Graal.js",
        CAMINHO_SCRIPT,
        "Gera uma chave de idempotencia independente para cada sondagem do checkout",
    )
    if resultado != "OK":
        raise RuntimeError(f"Nao foi possivel carregar o HTTP Sender do ZAP: {resultado}")

    resultado = zap.script.enable(NOME_SCRIPT)
    if resultado != "OK":
        raise RuntimeError(f"Nao foi possivel habilitar o HTTP Sender do ZAP: {resultado}")

    print("HTTP Sender de idempotencia dinamica habilitado para o checkout.")

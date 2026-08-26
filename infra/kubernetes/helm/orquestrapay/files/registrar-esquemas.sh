#!/bin/sh
set -eu

: "${REGISTRO_ESQUEMAS_URL:?Informe REGISTRO_ESQUEMAS_URL}"
: "${TOPICO_CHECKOUT:?Informe TOPICO_CHECKOUT}"
: "${TOPICO_ESTOQUE:?Informe TOPICO_ESTOQUE}"
: "${TOPICO_RISCO:?Informe TOPICO_RISCO}"
: "${TOPICO_PAGAMENTO:?Informe TOPICO_PAGAMENTO}"
: "${TOPICO_RAZAO:?Informe TOPICO_RAZAO}"
: "${TOPICO_NOTIFICACAO:?Informe TOPICO_NOTIFICACAO}"
MODO=${MODO:-registrar}

if [ "$MODO" != "registrar" ] && [ "$MODO" != "verificar" ]; then
  echo "MODO deve ser registrar ou verificar." >&2
  exit 1
fi

tentativa=0
until curl --fail --silent --show-error \
    "${REGISTRO_ESQUEMAS_URL}/system/info" >/dev/null; do
  tentativa=$((tentativa + 1))
  if [ "$tentativa" -ge 60 ]; then
    echo "O registro de esquemas nao ficou disponivel a tempo." >&2
    exit 1
  fi
  sleep 2
done

conteudo=$(base64 </contratos/evento-saga.avsc | tr -d '\r\n')

buscar_versao() {
  artefato=$1
  curl --fail-with-body --silent --show-error \
    --header 'Content-Type: application/json' \
    --request POST \
    --data-binary @/contratos/evento-saga.avsc \
    "${REGISTRO_ESQUEMAS_URL}/search/versions?groupId=default&artifactId=${artefato}&canonical=true&artifactType=AVRO&limit=1"
}

conteudo_disponivel() {
  resultado=$(buscar_versao "$1")
  printf '%s' "$resultado" | grep -Eq '"count"[[:space:]]*:[[:space:]]*[1-9]'
}

aguardar_conteudo() {
  artefato=$1
  tentativa=0
  until conteudo_disponivel "$artefato"; do
    tentativa=$((tentativa + 1))
    if [ "$tentativa" -ge 60 ]; then
      echo "O contrato ${artefato} nao ficou disponivel a tempo." >&2
      exit 1
    fi
    sleep 2
  done
}

gravar_contrato() {
  artefato=$1
  corpo=$2
  destino=$3
  tentativa=0

  while [ "$tentativa" -lt 6 ]; do
    tentativa=$((tentativa + 1))
    if codigo_gravacao=$(curl --silent --show-error \
      --output /dev/null --write-out '%{http_code}' \
      --header 'Content-Type: application/json' \
      --request POST --data-binary "$corpo" "$destino"); then
      :
    else
      codigo_gravacao=000
    fi

    case "$codigo_gravacao" in
      2??)
        echo "Contrato ${artefato} disponivel."
        return
        ;;
      409)
        aguardar_conteudo "$artefato"
        echo "Contrato ${artefato} registrado por outra execucao concorrente."
        return
        ;;
      000|429|5??)
        sleep "$tentativa"
        ;;
      *)
        echo "O Registry respondeu HTTP ${codigo_gravacao} ao gravar ${artefato}." >&2
        exit 1
        ;;
    esac
  done

  echo "Nao foi possivel gravar o contrato ${artefato} apos ${tentativa} tentativas." >&2
  exit 1
}

for topico in \
  "$TOPICO_CHECKOUT" \
  "$TOPICO_ESTOQUE" \
  "$TOPICO_RISCO" \
  "$TOPICO_PAGAMENTO" \
  "$TOPICO_RAZAO" \
  "$TOPICO_NOTIFICACAO"
do
  for sufixo in "" ".dlt"
  do
    artefato="${topico}${sufixo}-value"
    if [ "$MODO" = "verificar" ]; then
      aguardar_conteudo "$artefato"
      echo "Contrato ${artefato} confirmado."
      continue
    fi

    if conteudo_disponivel "$artefato"; then
      echo "Contrato ${artefato} ja estava atualizado."
      continue
    fi

    codigo=$(curl --silent --output /dev/null --write-out '%{http_code}' \
      "${REGISTRO_ESQUEMAS_URL}/groups/default/artifacts/${artefato}")

    if [ "$codigo" = "200" ]; then
      corpo=$(printf '%s' \
        "{\"content\":{\"content\":\"${conteudo}\",\"contentType\":\"application/json\",\"encoding\":\"base64\"}}")
      destino="${REGISTRO_ESQUEMAS_URL}/groups/default/artifacts/${artefato}/versions"
    elif [ "$codigo" = "404" ]; then
      corpo=$(printf '%s' \
        "{\"artifactId\":\"${artefato}\",\"artifactType\":\"AVRO\",\"firstVersion\":{\"content\":{\"content\":\"${conteudo}\",\"contentType\":\"application/json\",\"encoding\":\"base64\"}}}")
      destino="${REGISTRO_ESQUEMAS_URL}/groups/default/artifacts"
    else
      echo "O Registry respondeu HTTP ${codigo} ao consultar ${artefato}." >&2
      exit 1
    fi

    gravar_contrato "$artefato" "$corpo" "$destino"
  done
done

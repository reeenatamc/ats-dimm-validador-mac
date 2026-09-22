#!/usr/bin/env bash
# Descarga el software oficial del SRI y prepara el validador.
# No se redistribuye nada del SRI: todo se baja de descargas.sri.gob.ec.
set -euo pipefail

DIMM_URL="https://descargas.sri.gob.ec/download/anexos/dimm/windows/Dimm-1.17-Win.exe"
PLUGIN_URL="https://descargas.sri.gob.ec/download/anexos/ats/ats.plugin.1.18.0.zip"
DIMM_SHA="8c9cd74713d921d6d79c25651ad0603b566d86a12e3aea8b1e871c8e3dd98d23"
PLUGIN_SHA="ce3fdd1da59f1fe961c0185d070905b417f34a84281a41796f66274335a3a111"

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
descargas="$raiz/descargas"
lib="$raiz/lib"
mkdir -p "$descargas" "$lib"

bajar() {  # bajar <url> <destino> <sha256>
  local url="$1" destino="$2" sha="$3"
  if [ ! -f "$destino" ]; then
    echo "==> bajando $(basename "$destino")"
    curl -fL --progress-bar -o "$destino" "$url"
  fi
  local real
  real="$(shasum -a 256 "$destino" | cut -d' ' -f1)"
  if [ "$real" != "$sha" ]; then
    echo "ERROR: $(basename "$destino") no coincide con el sha256 esperado." >&2
    echo "  esperado: $sha" >&2
    echo "  obtenido: $real" >&2
    echo "  (el SRI pudo publicar una version nueva; revisa antes de seguir)" >&2
    exit 1
  fi
  echo "    sha256 ok"
}

bajar "$PLUGIN_URL" "$descargas/ats.plugin.zip" "$PLUGIN_SHA"
bajar "$DIMM_URL"   "$descargas/Dimm-Win.exe"   "$DIMM_SHA"

echo "==> extrayendo el validador y sus catalogos"
python3 "$raiz/extraer.py" "$descargas/ats.plugin.zip" "$descargas/Dimm-Win.exe" "$lib"

echo
echo "Listo. Para revisar un ATS:"
echo "  ./validar.sh                       (abre la ventana)"
echo "  ./validar.sh <archivo.xml> <mes> <anio>"

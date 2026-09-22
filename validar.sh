#!/usr/bin/env bash
# Revisa un ATS con el validador oficial del SRI, sin abrir el DIMM.
#
#   ./validar.sh                       -> abre la ventana
#   ./validar.sh archivo.xml 8 2026    -> lo revisa en la terminal
set -uo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lib="$raiz/lib"
build="$raiz/build"

if [ $# -ne 0 ] && [ $# -ne 3 ]; then
  echo "uso: $(basename "$0") [<archivo.xml> <mes> <anio>]" >&2
  echo "     sin argumentos abre la ventana" >&2
  exit 2
fi

if [ ! -d "$lib" ] || [ -z "$(ls -A "$lib" 2>/dev/null)" ]; then
  echo "Falta preparar el validador. Corre primero:  ./setup.sh" >&2
  exit 1
fi

if ! command -v javac >/dev/null 2>&1; then
  echo "No hay compilador de Java (javac) en esta maquina." >&2
  echo "En Mac:  brew install --cask temurin@17" >&2
  echo "Luego abre una terminal nueva y vuelve a intentarlo." >&2
  exit 1
fi

cp="$build:$(find "$lib" -name '*.jar' | tr '\n' ':')"
mkdir -p "$build"

for fuente in ValidaAts VentanaAts; do
  if [ ! -f "$build/$fuente.class" ] || [ "$raiz/src/$fuente.java" -nt "$build/$fuente.class" ]; then
    compilar=si
  fi
done
if [ "${compilar:-}" = si ]; then
  javac -nowarn -d "$build" -cp "$cp" "$raiz/src"/*.java || exit 1
fi

if [ $# -eq 0 ]; then
  exec java -cp "$cp" VentanaAts
fi

java -cp "$cp" ValidaAts "$@" 2>&1 | grep -v "^log4j:\|^SLF4J:"
exit "${PIPESTATUS[0]}"

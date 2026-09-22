#!/usr/bin/env bash
# Doble clic en Finder: abre la ventana del validador.
cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1
[ -d lib ] || ./setup.sh || { echo; echo "Pulsa una tecla para cerrar."; read -r -n 1; exit 1; }
./validar.sh || { echo; echo "Pulsa una tecla para cerrar."; read -r -n 1; }

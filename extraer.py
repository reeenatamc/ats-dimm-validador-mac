#!/usr/bin/env python3
"""
Saca del software oficial del SRI las piezas que necesita el validador:

- del plugin ATS: los jars del validador (rig-ats-validacion y compania) y,
  dentro de uno de ellos, la base HSQLDB con los catalogos tributarios y su
  configuracion de ehcache
- del instalador del DIMM: un par de jars de Eclipse que el logger del SRI
  pide al arrancar

Los catalogos van a ~/.dimmData, que es la misma ruta que usa el DIMM real
(ec.gob.sri.dimm.data.impl.Util.obtenerRutaBDD).
"""
import io
import os
import struct
import sys
import zipfile

CLASES_ECLIPSE = {
    "org/eclipse/core/runtime/Plugin.class",
    "org/osgi/framework/BundleContext.class",
    "org/eclipse/core/runtime/IStatus.class",
    "org/eclipse/core/runtime/Status.class",
}
# dos versiones de slf4j en el mismo classpath se pelean (IllegalAccessError)
DESCARTAR = ("slf4j-api-1.4.2.jar", "slf4j-simple-1.4.2.jar")


def jars_del_plugin(ruta_zip, destino):
    """Los jars del plugin, y los jars que cada plugin lleva dentro de lib/."""
    n = 0
    with zipfile.ZipFile(ruta_zip) as z:
        for nombre in z.namelist():
            if not nombre.endswith(".jar"):
                continue
            datos = z.read(nombre)
            base = os.path.basename(nombre)
            if base not in DESCARTAR:
                open(os.path.join(destino, base), "wb").write(datos)
                n += 1
            try:
                with zipfile.ZipFile(io.BytesIO(datos)) as interno:
                    for dentro in interno.namelist():
                        if not dentro.endswith(".jar"):
                            continue
                        base_i = os.path.basename(dentro)
                        if base_i in DESCARTAR:
                            continue
                        open(os.path.join(destino, base_i), "wb").write(interno.read(dentro))
                        n += 1
            except zipfile.BadZipFile:
                pass
    return n


def zips_embebidos(datos):
    """
    El instalador del DIMM guarda todos sus archivos pegados uno tras otro en
    resources/packs/pack-Core. Recorremos el bloque buscando finales de zip
    (PK\\x05\\x06) y reconstruimos cada jar completo a partir de su directorio.
    """
    pos = 0
    while True:
        i = datos.find(b"PK\x05\x06", pos)
        if i < 0:
            return
        pos = i + 4
        try:
            tam_dir, ini_dir = struct.unpack("<II", datos[i + 12:i + 20])
            largo_comentario = struct.unpack("<H", datos[i + 20:i + 22])[0]
            inicio = i - tam_dir - ini_dir
            if inicio < 0 or datos[inicio:inicio + 4] != b"PK\x03\x04":
                continue
            trozo = datos[inicio:i + 22 + largo_comentario]
            yield trozo, zipfile.ZipFile(io.BytesIO(trozo))
        except Exception:
            continue


def catalogos(destino):
    """
    La base de catalogos no esta suelta: viaja dentro de uno de los jars del
    plugin (ec.gob.sri.dimm.data). La dejamos en ~/.dimmData, que es donde el
    DIMM real la busca (ec.gob.sri.dimm.data.impl.Util.obtenerRutaBDD).
    """
    casa = os.path.expanduser("~/.dimmData")
    os.makedirs(os.path.join(casa, "BD_DIMM"), exist_ok=True)
    os.makedirs(os.path.join(casa, "ehcache"), exist_ok=True)

    encontrada = False
    for nombre_jar in sorted(os.listdir(destino)):
        if not nombre_jar.endswith(".jar"):
            continue
        with zipfile.ZipFile(os.path.join(destino, nombre_jar)) as zf:
            nombres = zf.namelist()
            if "resources/bdd_dimm_anexos.script" not in nombres:
                continue
            encontrada = True
            for nombre in nombres:
                # el .lck es el candado de una sesion ajena: no se copia
                if nombre.startswith("resources/bdd_dimm_anexos.") \
                        and not nombre.endswith(".lck"):
                    ruta = os.path.join(casa, "BD_DIMM", os.path.basename(nombre))
                    open(ruta, "wb").write(zf.read(nombre))
                if nombre.endswith("rig-catalogos-ehcache.xml"):
                    ruta = os.path.join(casa, "ehcache", "rig-catalogos-ehcache.xml")
                    open(ruta, "wb").write(zf.read(nombre))
            break

    if not encontrada:
        raise SystemExit(
            "No se encontro la base de catalogos dentro del plugin del ATS.")
    if not os.path.exists(os.path.join(destino, "hsqldb.jar")):
        raise SystemExit("Falta hsqldb.jar: sin el no se puede abrir la base.")


def jars_de_eclipse(ruta_exe, destino):
    """
    El instalador del DIMM guarda sus jars pegados uno tras otro dentro de
    resources/packs/pack-Core. De ahi salen los jars de Eclipse sin los cuales
    el logger del SRI no arranca.
    """
    with zipfile.ZipFile(ruta_exe) as z:
        pack = z.read("resources/packs/pack-Core")

    eclipse = 0
    for trozo, zf in zips_embebidos(pack):
        if set(zf.namelist()) & CLASES_ECLIPSE:
            eclipse += 1
            open(os.path.join(destino, "eclipse-%02d.jar" % eclipse), "wb").write(trozo)

    if not eclipse:
        raise SystemExit("No se encontraron los jars de Eclipse en el instalador.")
    return eclipse


def main():
    plugin, exe, destino = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(destino, exist_ok=True)
    n = jars_del_plugin(plugin, destino)
    print("    jars del validador: %d" % n)
    e = jars_de_eclipse(exe, destino)
    print("    jars de eclipse   : %d" % e)
    catalogos(destino)
    print("    catalogos en      : ~/.dimmData")


if __name__ == "__main__":
    main()

import io
import os
import sys
import zipfile
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import extraer  # noqa: E402


def hacer_zip(archivos):
    """Zip en memoria: {nombre: bytes}."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for nombre, datos in archivos.items():
            z.writestr(nombre, datos)
    return buf.getvalue()


def test_zips_embebidos_encuentra_jars_pegados():
    a = hacer_zip({"a.txt": b"uno"})
    b = hacer_zip({"b.txt": b"dos"})
    pack = b"basura" + a + b"relleno" + b

    nombres = [sorted(zf.namelist()) for _, zf in extraer.zips_embebidos(pack)]

    assert nombres == [["a.txt"], ["b.txt"]]


def test_zips_embebidos_devuelve_el_zip_completo():
    a = hacer_zip({"a.txt": b"uno"})

    ((trozo, _),) = extraer.zips_embebidos(b"xx" + a + b"yy")

    assert trozo == a


def test_zips_embebidos_ignora_un_falso_fin_de_zip():
    # un PK\x05\x06 al final, sin directorio detras: struct.error, no debe reventar
    falso = b"datos PK\x05\x06"
    a = hacer_zip({"a.txt": b"uno"})

    nombres = [sorted(zf.namelist()) for _, zf in extraer.zips_embebidos(falso + a)]

    assert nombres == [["a.txt"]]


def test_zips_embebidos_exige_cabecera_local_al_inicio():
    # un fin de zip suelto (zip vacio) no tiene PK\x03\x04 donde empieza: se salta
    vacio = hacer_zip({})

    assert list(extraer.zips_embebidos(b"relleno" + vacio)) == []


def test_zips_embebidos_no_traga_otros_errores(monkeypatch):
    pack = hacer_zip({"a.txt": b"uno"})

    def revienta(*_):
        raise MemoryError

    monkeypatch.setattr(extraer.zipfile, "ZipFile", revienta)

    with pytest.raises(MemoryError):
        list(extraer.zips_embebidos(pack))


def test_jars_del_plugin_saca_anidados_y_descarta_slf4j(tmp_path):
    interno = hacer_zip({"x.class": b"1"})
    plugin = tmp_path / "plugin.zip"
    plugin.write_bytes(
        hacer_zip(
            {
                "validador.jar": interno,
                "slf4j-api-1.4.2.jar": interno,
                "lib/readme.txt": b"no es un jar",
                "otro.jar": hacer_zip(
                    {"lib/hsqldb.jar": interno, "lib/slf4j-simple-1.4.2.jar": interno}
                ),
            }
        )
    )
    destino = tmp_path / "out"
    destino.mkdir()

    n = extraer.jars_del_plugin(str(plugin), str(destino))

    assert sorted(os.listdir(destino)) == ["hsqldb.jar", "otro.jar", "validador.jar"]
    assert n == 3


def test_jars_del_plugin_cierra_y_deja_el_contenido(tmp_path):
    interno = hacer_zip({"x.class": b"1"})
    plugin = tmp_path / "plugin.zip"
    plugin.write_bytes(hacer_zip({"validador.jar": interno}))
    destino = tmp_path / "out"
    destino.mkdir()

    extraer.jars_del_plugin(str(plugin), str(destino))

    assert (destino / "validador.jar").read_bytes() == interno


@pytest.fixture
def casa(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path / "casa"))
    return tmp_path / "casa" / ".dimmData"


def jar_con_catalogos(destino, con_lck=True):
    archivos = {
        "resources/bdd_dimm_anexos.script": b"script",
        "resources/bdd_dimm_anexos.properties": b"props",
        "resources/rig-catalogos-ehcache.xml": b"<ehcache/>",
    }
    if con_lck:
        archivos["resources/bdd_dimm_anexos.lck"] = b"candado"
    (destino / "datos.jar").write_bytes(hacer_zip(archivos))


def test_catalogos_copia_la_base_sin_el_lck(tmp_path, casa):
    jar_con_catalogos(tmp_path)
    (tmp_path / "hsqldb.jar").write_bytes(b"x")

    extraer.catalogos(str(tmp_path))

    assert sorted(os.listdir(casa / "BD_DIMM")) == [
        "bdd_dimm_anexos.properties",
        "bdd_dimm_anexos.script",
    ]
    assert (casa / "BD_DIMM" / "bdd_dimm_anexos.script").read_bytes() == b"script"
    assert (casa / "ehcache" / "rig-catalogos-ehcache.xml").read_bytes() == b"<ehcache/>"


def test_catalogos_falla_si_no_hay_base(tmp_path, casa):
    (tmp_path / "otro.jar").write_bytes(hacer_zip({"a.txt": b"x"}))

    with pytest.raises(SystemExit, match="base de catalogos"):
        extraer.catalogos(str(tmp_path))


def test_catalogos_falla_si_falta_hsqldb(tmp_path, casa):
    jar_con_catalogos(tmp_path)

    with pytest.raises(SystemExit, match="hsqldb.jar"):
        extraer.catalogos(str(tmp_path))


def test_jars_de_eclipse_reconoce_las_clases_y_numera(tmp_path):
    eclipse = hacer_zip({"org/eclipse/core/runtime/Plugin.class": b"1"})
    otro = hacer_zip({"com/otro/Clase.class": b"1"})
    otro_eclipse = hacer_zip({"org/osgi/framework/BundleContext.class": b"1"})
    exe = tmp_path / "dimm.exe"
    exe.write_bytes(hacer_zip({"resources/packs/pack-Core": eclipse + otro + otro_eclipse}))
    destino = tmp_path / "out"
    destino.mkdir()

    n = extraer.jars_de_eclipse(str(exe), str(destino))

    assert n == 2
    assert sorted(os.listdir(destino)) == ["eclipse-01.jar", "eclipse-02.jar"]
    assert (destino / "eclipse-01.jar").read_bytes() == eclipse


def test_jars_de_eclipse_falla_si_no_hay_ninguno(tmp_path):
    exe = tmp_path / "dimm.exe"
    exe.write_bytes(
        hacer_zip({"resources/packs/pack-Core": hacer_zip({"com/otro/Clase.class": b"1"})})
    )

    with pytest.raises(SystemExit, match="Eclipse"):
        extraer.jars_de_eclipse(str(exe), str(tmp_path))

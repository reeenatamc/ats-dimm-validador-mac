import ec.gob.sri.anexo.ats.persistencia.api.analisis.AnalizadorATS;
import ec.gob.sri.anexo.ats.persistencia.api.analisis.UnidadInformacion;
import ec.gob.sri.anexo.ats.persistencia.catalogo.UtilCatalogos;
import ec.gob.sri.anexo.ats.validacion.VentaYVentaEstablecimientoValidador;
import ec.gob.sri.anexo.ats.validacion.util.ArgumentosValidacion;
import ec.gob.sri.anexo.catalogo.api.carga.AdministradorCatalogos;
import ec.gob.sri.anexo.catalogo.api.enums.FuenteEnum;
import ec.gob.sri.anexo.catalogo.carga.impl.AdministradorCargaCatalogosImpl;
import ec.gob.sri.anexo.validacion.modelo.ContextoErrores;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Collection;
import java.util.List;

/**
 * Corre el validador oficial del SRI (plugin ATS 1.18) sobre un archivo ATS,
 * sin abrir el DIMM: desde la linea de comandos o desde la ventana
 * (VentanaAts), que llama a revisar().
 *
 * Usa las mismas clases que el DIMM ejecuta cuando alguien pulsa "validar":
 * AnalizadorATS lee el XML por secciones y VentaYVentaEstablecimientoValidador
 * aplica las reglas de ventas. Lo unico que ponemos nosotros es el arranque,
 * porque en el DIMM eso lo hace la plataforma Eclipse.
 *
 * Por consola devuelve 0 si el anexo pasa y 1 si el SRI reporta errores.
 */
public class ValidaAts {

    private static final int TOPE_ERRORES = 100;
    private static final int LOTE = 500;

    /** Lo que una revision deja: el informe para leer y cuantos errores hubo. */
    public static class Resultado {
        public final String informe;
        public final int errores;

        Resultado(String informe, int errores) {
            this.informe = informe;
            this.errores = errores;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            VentanaAts.abrir();
            return;
        }
        if (args.length < 3) {
            System.err.println("uso: ValidaAts <archivo.xml> <mes> <anio>   (sin argumentos abre la ventana)");
            System.exit(2);
        }
        Resultado r = revisar(new File(args[0]), Integer.parseInt(args[1]),
                              Integer.parseInt(args[2]));
        System.out.println(r.informe);
        System.exit(r.errores == 0 ? 0 : 1);
    }

    /**
     * Revisa un ATS y devuelve el informe. No imprime nada: asi el mismo
     * codigo sirve para la consola y para la ventana.
     */
    public static Resultado revisar(File xml, int mes, int anio) throws Exception {
        StringBuilder out = new StringBuilder();

        prepararCatalogos();

        AnalizadorATS analizador = new AnalizadorATS(xml);
        UnidadInformacion cabecera = analizador.analizarCabecera();
        out.append("archivo      : ").append(xml.getName()).append("\n");
        out.append("periodo      : ").append(mes).append("/").append(anio).append("\n");
        String ruc = etiqueta(xml, "IdInformante");
        out.append("RUC          : ")
           .append(ruc != null ? ruc : valor(cabecera, "idInformante")).append("\n");
        out.append("totalVentas  : ").append(valor(cabecera, "totalVentas")).append("\n");

        avanzarHastaVentas(analizador, out);
        out.append("ventas en el archivo: ")
           .append(analizador.isTieneVentas() ? "SI" : "NO").append("\n");

        ContextoErrores contexto = new ContextoErrores(TOPE_ERRORES);
        contexto.setMes(mes);
        contexto.setAnio(anio);
        ArgumentosValidacion argumentos = new ArgumentosValidacion(
                Long.valueOf(1L), xml.getAbsolutePath(), null, contexto, Long.valueOf(1L));

        try {
            new VentaYVentaEstablecimientoValidador(analizador)
                    .validar(cabecera, argumentos, anio, mes, false);
        } catch (Throwable t) {
            out.append("el validador corto la revision: ").append(t).append("\n");
            for (Throwable c = t; c != null; c = c.getCause()) {
                StackTraceElement[] traza = c.getStackTrace();
                if (traza.length > 0) {
                    out.append("   ").append(c.getClass().getSimpleName())
                       .append(" en ").append(traza[0]).append("\n");
                }
            }
        }

        int errores = contexto.getTotalErros();
        out.append("\n").append("ERRORES     : ").append(errores).append("\n");
        for (Object e : contexto.getErroresAcumulados().values()) {
            out.append("  ").append(describir(e).trim()).append("\n");
        }
        int avisos = contexto.getTotalWarnings() + contexto.getListaWarningsAcumulados().size();
        out.append("ADVERTENCIAS: ").append(avisos).append("\n");
        for (Object w : contexto.getWarningsAcumulados()) {
            out.append("  ").append(describir(w).trim()).append("\n");
        }
        for (Object w : contexto.getListaWarningsAcumulados()) {
            out.append("  ").append(describir(w).trim()).append("\n");
        }

        analizador.terminarAnalisis();
        out.append("\n").append(errores == 0
                ? "RESULTADO: el anexo pasa la validacion de ventas"
                : "RESULTADO: el SRI rechaza este anexo").append("\n");
        return new Resultado(out.toString(), errores);
    }

    /**
     * El DIMM guarda los catalogos tributarios en una base HSQLDB bajo
     * ~/.dimmData y va creando un administrador por catalogo. Fuera de Eclipse
     * hay que armar eso a mano: setup.sh deja la base en su sitio y aqui la
     * conectamos y llenamos los administradores que UtilCatalogos espera.
     */
    private static void prepararCatalogos() throws Exception {
        Class.forName("org.hsqldb.jdbcDriver");
        String ruta = System.getProperty("user.home") + "/.dimmData/BD_DIMM/bdd_dimm_anexos";
        Connection cn = DriverManager.getConnection(
                "jdbc:hsqldb:file:" + ruta + ";readonly=true", "sa", "");

        AdministradorCatalogos administrador =
                AdministradorCargaCatalogosImpl.getInstancia(FuenteEnum.DIMM);
        administrador.inicializarCatalogos(cn, FuenteEnum.DIMM);

        UtilCatalogos util = UtilCatalogos.crearInstancia();
        asignar(util, "administradorCatalogos", administrador);

        for (Field campo : UtilCatalogos.class.getDeclaredFields()) {
            if (Modifier.isStatic(campo.getModifiers())) {
                continue;
            }
            Class<?> tipo = campo.getType();
            if (!tipo.getName().contains(".catalogo.carga.impl.")) {
                continue;
            }
            Object instancia = instanciar(tipo);
            if (instancia == null) {
                continue;
            }
            for (Method m : tipo.getMethods()) {
                if (m.getName().startsWith("inicializar")
                        && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == Connection.class) {
                    try {
                        m.invoke(instancia, cn);
                    } catch (Throwable ignorado) {
                        // un catalogo que no carga solo afecta a su propia regla
                    }
                }
            }
            campo.setAccessible(true);
            campo.set(util, instancia);
        }
    }

    /**
     * El contenido de una etiqueta de la cabecera, leido del archivo. El
     * analizador del SRI no devuelve todos los campos, y para mostrarlos en
     * pantalla alcanza con mirar las primeras lineas del XML.
     */
    public static String etiqueta(File xml, String nombre) {
        try {
            byte[] inicio = new byte[4096];
            java.io.InputStream entrada = new java.io.FileInputStream(xml);
            try {
                int leidos = entrada.read(inicio);
                if (leidos <= 0) {
                    return null;
                }
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "<" + nombre + ">\\s*([^<\\s][^<]*?)\\s*</" + nombre + ">",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(new String(inicio, 0, leidos, "ISO-8859-1"));
                return m.find() ? m.group(1) : null;
            } finally {
                entrada.close();
            }
        } catch (Exception ignorado) {
            return null;
        }
    }

    /**
     * Un campo de la cabecera, como texto. El analizador del SRI devuelve un
     * tipo que Java resuelve como char[], asi que lo forzamos a Object antes
     * de imprimirlo: si no, un campo vacio revienta con NullPointerException.
     */
    private static String valor(UnidadInformacion cabecera, String campo) {
        Object v = cabecera.getValorIndividual(campo);
        return v == null ? "(no consta)" : String.valueOf(v);
    }

    /** Cada administrador es un singleton, con o sin la fuente como argumento. */
    private static Object instanciar(Class<?> tipo) {
        try {
            return tipo.getMethod("getInstancia", FuenteEnum.class).invoke(null, FuenteEnum.DIMM);
        } catch (Throwable ignorado) {
            // sigue el siguiente intento
        }
        try {
            return tipo.getMethod("getInstancia").invoke(null);
        } catch (Throwable ignorado) {
            // sigue el siguiente intento
        }
        try {
            Constructor<?> c = tipo.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Throwable ignorado) {
            return null;
        }
    }

    private static void asignar(Object destino, String campo, Object valor) throws Exception {
        Field f = destino.getClass().getDeclaredField(campo);
        f.setAccessible(true);
        f.set(destino, valor);
    }

    /**
     * El analizador recorre el archivo de a una seccion. Para llegar a las
     * ventas hay que consumir antes las compras, igual que hace el DIMM.
     */
    private static void avanzarHastaVentas(AnalizadorATS analizador, StringBuilder out) {
        boolean comprasLeidas = false;
        analizador.verificarContenido();
        for (int vuelta = 0; vuelta < 50; vuelta++) {
            if (analizador.isTieneVentas() || !analizador.isTieneMasDetalles()) {
                return;
            }
            if (analizador.isTieneCompras() && !comprasLeidas) {
                List<UnidadInformacion> lote;
                int leidas = 0;
                do {
                    lote = analizador.analizarCompras(LOTE);
                    leidas += (lote == null ? 0 : lote.size());
                } while (lote != null && !lote.isEmpty());
                out.append("compras leidas      : ").append(leidas).append("\n");
                comprasLeidas = true;
            }
            analizador.verificarContenido();
        }
    }

    /** Los modelos de error del SRI no traen toString(); volcamos sus campos. */
    private static String describir(Object o) {
        if (o == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                try {
                    Object v = f.get(o);
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof Collection) {
                        for (Object x : (Collection<?>) v) {
                            sb.append(describir(x));
                        }
                    } else if (!String.valueOf(v).isEmpty()) {
                        sb.append(f.getName()).append("=").append(v).append("  ");
                    }
                } catch (Throwable ignorado) {
                    // campo inaccesible: no aporta al diagnostico
                }
            }
        }
        return sb.toString();
    }
}

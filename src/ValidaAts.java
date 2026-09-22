import ec.gob.sri.anexo.ats.persistencia.api.analisis.AnalizadorATS;
import ec.gob.sri.anexo.ats.persistencia.api.analisis.UnidadInformacion;
import ec.gob.sri.anexo.ats.persistencia.catalogo.UtilCatalogos;
import ec.gob.sri.anexo.ats.validacion.ValidacionVentas;
import ec.gob.sri.anexo.ats.validacion.VentaYVentaEstablecimientoValidador;
import ec.gob.sri.anexo.ats.validacion.util.AyudanteMensajes;
import ec.gob.sri.anexo.ats.validacion.util.ArgumentosValidacion;
import ec.gob.sri.anexo.catalogo.api.carga.AdministradorCatalogos;
import ec.gob.sri.anexo.catalogo.api.enums.FuenteEnum;
import ec.gob.sri.anexo.catalogo.carga.impl.AdministradorCargaCatalogosImpl;
import ec.gob.sri.anexo.validacion.modelo.ContextoErrores;

import ec.gob.sri.anexo.ats.persistencia.api.modelo.FechaAnexo;
import ec.gob.sri.anexo.ats.validacion.ValidacionAnulados;
import ec.gob.sri.anexo.ats.validacion.ValidacionCompras;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

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
    private static final String RUTA_XSD =
            "ec/gob/sri/dimm/ats/validacion/esquema/xsd/at_jun_2020_en_adelante.xsd";
    /** Tal cual viene en el at.xsd del mismo jar, que si la trae. */
    private static final String PARAISO_FISCAL =
            "<xsd:simpleType name=\"paraisoFiscalType\">"
            + "<xsd:restriction base=\"xsd:string\">"
            + "<xsd:pattern value=\"\\d{1,3}\"></xsd:pattern>"
            + "</xsd:restriction></xsd:simpleType>";

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

        List<String> erroresEsquema = revisarEsquema(xml);
        out.append("esquema del SRI     : ")
           .append(erroresEsquema.isEmpty() ? "pasa"
                   : erroresEsquema.size() + " error(es): el DIMM no abre el archivo")
           .append("\n");

        ContextoErrores contexto = new ContextoErrores(TOPE_ERRORES);
        contexto.setMes(mes);
        contexto.setAnio(anio);

        avanzarHastaVentas(analizador, out, contexto, anio, mes);
        out.append("ventas en el archivo: ")
           .append(analizador.isTieneVentas() ? "SI" : "NO").append("\n");
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

        // El codigo de tipo de comprobante se revisa aparte: ValidacionVentas
        // lo comprueba fila por fila, pero deja el mensaje en su propio
        // AyudanteMensajes y ese nunca llega al contexto de errores. Sin esto
        // un anexo con el codigo equivocado sale con "0 errores" y el DIMM lo
        // rechaza despues.
        List<String> erroresCodigo = revisarCodigosDeVenta(xml, mes, anio,
                                                           valor(cabecera, "totalVentas"), out);

        int errores = contexto.getTotalErros() + erroresCodigo.size()
                + erroresEsquema.size();
        out.append("\n").append("ERRORES     : ").append(errores).append("\n");
        for (Object e : contexto.getErroresAcumulados().values()) {
            out.append("  ").append(describir(e).trim()).append("\n");
        }
        for (String e : erroresCodigo) {
            out.append("  ").append(e).append("\n");
        }
        for (String e : erroresEsquema) {
            out.append("  esquema: ").append(e).append("\n");
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
                ? "RESULTADO: el anexo pasa el esquema, las compras y las ventas"
                : "RESULTADO: el SRI rechaza este anexo").append("\n");
        return new Resultado(out.toString(), errores);
    }

    /**
     * El DIMM guarda los catalogos tributarios en una base HSQLDB bajo
     * ~/.dimmData y va creando un administrador por catalogo. Fuera de Eclipse
     * hay que armar eso a mano: setup.sh deja la base en su sitio y aqui la
     * conectamos y llenamos los administradores que UtilCatalogos espera.
     */
    public static void prepararCatalogos() throws Exception {
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
     * Revisa el archivo contra el esquema oficial, que es la primera barrera
     * del DIMM: si el XML no calza, el anexo ni se abre y el error que sale no
     * nombra el campo culpable.
     *
     * El esquema vive dentro del plugin. El del periodo vigente viene roto:
     * declara paisEfecPagoParFis de tipo paraisoFiscalType y nunca define ese
     * tipo, asi que no compila. La definicion esta en el at.xsd del mismo jar
     * (\d{1,3}); la injertamos para poder usarlo.
     */
    private static List<String> revisarEsquema(File xml) {
        final List<String> errores = new ArrayList<String>();
        try {
            String esquema = leerRecurso(RUTA_XSD);
            if (esquema == null) {
                errores.add("no se encontro el esquema " + RUTA_XSD + " en el plugin");
                return errores;
            }
            if (!esquema.contains("name=\"paraisoFiscalType\"")) {
                esquema = esquema.replace("</xsd:schema>", PARAISO_FISCAL + "</xsd:schema>");
            }

            SchemaFactory fabrica =
                    SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema modelo = fabrica.newSchema(new StreamSource(
                    new java.io.StringReader(esquema)));
            Validator validador = modelo.newValidator();
            validador.setErrorHandler(new ErrorHandler() {
                public void error(SAXParseException e) {
                    errores.add("linea " + e.getLineNumber() + ": " + e.getMessage());
                }

                public void fatalError(SAXParseException e) {
                    errores.add("linea " + e.getLineNumber() + ": " + e.getMessage());
                }

                public void warning(SAXParseException e) {
                    // una advertencia del parser no invalida el anexo
                }
            });
            validador.validate(new StreamSource(xml));
        } catch (Throwable t) {
            errores.add("no se pudo revisar el esquema: " + t);
        }
        return errores;
    }

    /** Un recurso de texto del classpath, o null si el plugin no lo trae. */
    private static String leerRecurso(String ruta) throws Exception {
        InputStream entrada = ValidaAts.class.getClassLoader().getResourceAsStream(ruta);
        if (entrada == null) {
            return null;
        }
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] trozo = new byte[8192];
            int leidos;
            while ((leidos = entrada.read(trozo)) > 0) {
                buffer.write(trozo, 0, leidos);
            }
            return new String(buffer.toByteArray(), "ISO-8859-1");
        } finally {
            entrada.close();
        }
    }

    /**
     * El analizador recorre el archivo de a una seccion. Para llegar a las
     * ventas hay que consumir antes las compras, igual que hace el DIMM: de
     * paso las validamos, porque si no esta seccion nunca se revisa.
     */
    private static void avanzarHastaVentas(AnalizadorATS analizador, StringBuilder out) {
        avanzarHastaVentas(analizador, out, null, 0, 0);
    }

    private static void avanzarHastaVentas(AnalizadorATS analizador, StringBuilder out,
                                           ContextoErrores contexto, int anio, int mes) {
        boolean comprasLeidas = false;
        analizador.verificarContenido();
        for (int vuelta = 0; vuelta < 50; vuelta++) {
            if (analizador.isTieneVentas() || !analizador.isTieneMasDetalles()) {
                return;
            }
            if (analizador.isTieneCompras() && !comprasLeidas) {
                ValidacionCompras validacion = null;
                if (contexto != null) {
                    try {
                        validacion = new ValidacionCompras(1L, new FechaAnexo(anio, mes),
                                                           BigDecimal.ZERO, false);
                    } catch (Throwable t) {
                        out.append("no se pudo armar la revision de compras: ")
                           .append(t).append("\n");
                    }
                }
                List<UnidadInformacion> lote;
                int leidas = 0;
                do {
                    lote = analizador.analizarCompras(LOTE);
                    leidas += (lote == null ? 0 : lote.size());
                    if (validacion != null && lote != null && !lote.isEmpty()) {
                        try {
                            validacion.validar(contexto, lote);
                        } catch (Throwable t) {
                            out.append("la revision de compras corto: ").append(t).append("\n");
                            validacion = null;
                        }
                    }
                } while (lote != null && !lote.isEmpty());
                out.append("compras leidas      : ").append(leidas)
                   .append(validacion != null ? " (revisadas)" : "").append("\n");
                comprasLeidas = true;
            }
            analizador.verificarContenido();
        }
    }


    /**
     * Revisa el codigo de tipo de comprobante de cada fila de ventas.
     *
     * El SRI no acepta los mismos codigos a los dos lados del anexo: en
     * compras una factura es "01" y en ventas es "18", porque alli la fila no
     * es un comprobante sino el resumen del mes de un cliente. La nota de
     * credito es "04" y la de debito "05" en ambos.
     *
     * Quien decide es ValidacionVentas.validarTipoComprobante, que pregunta al
     * catalogo del DIMM por la combinacion (tipo de identificacion, codigo,
     * "2" = venta). Lo llamamos por reflexion, en vez de repetir la regla,
     * para que el veredicto y el texto sean los del SRI y no los nuestros.
     *
     * Abre su propio analizador porque el de la revision general ya consumio
     * el archivo.
     */
    private static List<String> revisarCodigosDeVenta(File xml, int mes, int anio,
                                                      String totalVentas, StringBuilder out) {
        List<String> errores = new ArrayList<String>();
        AnalizadorATS analizador = null;
        try {
            analizador = new AnalizadorATS(xml);
            analizador.analizarCabecera();
            avanzarHastaVentas(analizador, new StringBuilder());

            ValidacionVentas validacion =
                    new ValidacionVentas(anio, mes, 1L, totalVentas, false);
            Method validarTipoComprobante = ValidacionVentas.class.getDeclaredMethod(
                    "validarTipoComprobante", UnidadInformacion.class, AyudanteMensajes.class);
            validarTipoComprobante.setAccessible(true);

            int revisadas = 0;
            while (analizador.isTieneMasDetalles()) {
                List<UnidadInformacion> lote = analizador.analizarVentas(LOTE);
                if (lote == null || lote.isEmpty()) {
                    break;
                }
                for (UnidadInformacion fila : lote) {
                    revisadas++;
                    AyudanteMensajes ayudante = new AyudanteMensajes();
                    validarTipoComprobante.invoke(validacion, fila, ayudante);
                    if (ayudante.hayErrores()) {
                        for (Object mensaje : mensajesDe(ayudante)) {
                            errores.add(describir(mensaje).trim());
                        }
                    }
                }
            }
            out.append("codigos de venta revisados: ").append(revisadas).append("\n");
        } catch (Throwable t) {
            out.append("no se pudo revisar el codigo de tipo de comprobante: ")
               .append(t).append("\n");
        } finally {
            if (analizador != null) {
                try {
                    analizador.terminarAnalisis();
                } catch (Throwable ignorado) {
                    // el archivo ya quedo leido; cerrar mal no invalida la revision
                }
            }
        }
        return errores;
    }

    /** Los mensajes viven dentro del contenedor del ayudante, sin getter directo. */
    private static Collection<?> mensajesDe(AyudanteMensajes ayudante) throws Exception {
        Object contenedor = ayudante.getContenedorMensajes();
        List<Object> salida = new ArrayList<Object>();
        for (Class<?> c = contenedor.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(contenedor);
                if (v instanceof Collection) {
                    salida.addAll((Collection<?>) v);
                }
            }
        }
        return salida;
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

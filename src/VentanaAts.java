import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * Ventana para revisar un ATS sin abrir el DIMM: se elige el archivo, se
 * confirma el periodo y se pulsa Revisar. El trabajo lo hace ValidaAts, que
 * es el validador oficial del SRI; aqui solo esta la pantalla.
 *
 * Al elegir el archivo el periodo se llena solo, leyendo <anio> y <mes> de la
 * cabecera del propio ATS.
 */
public class VentanaAts {

    private static final Color VERDE = new Color(0x1B, 0x7F, 0x3B);
    private static final Color ROJO = new Color(0xB3, 0x26, 0x1E);
    private static final Color GRIS = new Color(0x55, 0x55, 0x55);

    private final JFrame ventana = new JFrame("Revisar ATS (validador del SRI)");
    private final JTextField archivo = new JTextField(28);
    private final JComboBox<String> mes = new JComboBox<String>(new String[] {
        "01 enero", "02 febrero", "03 marzo", "04 abril", "05 mayo", "06 junio",
        "07 julio", "08 agosto", "09 septiembre", "10 octubre", "11 noviembre",
        "12 diciembre" });
    private final JSpinner anio =
            new JSpinner(new SpinnerNumberModel(2026, 2000, 2100, 1));
    private final JButton revisar = new JButton("Revisar");
    private final JLabel veredicto = new JLabel(" ");
    private final JTextArea informe = new JTextArea(18, 70);

    public static void abrir() {
        abrir(null, 0, 0);
    }

    /** Con archivo y periodo, la ventana abre y revisa sola. */
    public static void abrir(final File xml, final int mes, final int anio) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                VentanaAts v = new VentanaAts();
                v.mostrar();
                if (xml != null) {
                    v.cargar(xml, mes, anio);
                    v.revisar();
                }
            }
        });
    }

    public static void main(String[] args) {
        if (args.length == 3) {
            abrir(new File(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        } else {
            abrir();
        }
    }

    private void cargar(File xml, int m, int a) {
        archivo.setText(xml.getAbsolutePath());
        revisar.setEnabled(true);
        if (m >= 1 && m <= 12) {
            mes.setSelectedIndex(m - 1);
        }
        if (a >= 2000 && a <= 2100) {
            anio.setValue(a);
        }
    }

    private void mostrar() {
        archivo.setEditable(false);
        anio.setEditor(new JSpinner.NumberEditor(anio, "#"));

        JButton elegir = new JButton("Elegir archivo...");
        elegir.addActionListener(e -> elegirArchivo());
        revisar.addActionListener(e -> revisar());
        revisar.setEnabled(false);

        JPanel arriba = new JPanel(new GridBagLayout());
        arriba.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0; c.gridy = 0;
        arriba.add(new JLabel("Archivo ATS:"), c);
        c.gridx = 1; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        arriba.add(archivo, c);
        c.gridx = 4; c.gridwidth = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0;
        arriba.add(elegir, c);

        c.gridx = 0; c.gridy = 1;
        arriba.add(new JLabel("Periodo:"), c);
        c.gridx = 1;
        arriba.add(mes, c);
        c.gridx = 2;
        arriba.add(anio, c);
        c.gridx = 4;
        arriba.add(revisar, c);

        veredicto.setFont(veredicto.getFont().deriveFont(Font.BOLD, 15f));
        veredicto.setBorder(BorderFactory.createEmptyBorder(0, 16, 8, 16));

        informe.setEditable(false);
        informe.setLineWrap(true);
        informe.setWrapStyleWord(true);
        informe.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        informe.setText("Elija el archivo del ATS y pulse Revisar.\n\n"
                + "Se aplican las mismas reglas de ventas que el DIMM: el anexo pasa\n"
                + "si el total declarado coincide con la suma que el SRI calcula.");

        JPanel cabecera = new JPanel(new BorderLayout());
        cabecera.add(arriba, BorderLayout.NORTH);
        cabecera.add(veredicto, BorderLayout.SOUTH);

        JPanel cuerpo = new JPanel(new BorderLayout());
        cuerpo.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        cuerpo.add(new JScrollPane(informe), BorderLayout.CENTER);

        ventana.setLayout(new BorderLayout());
        ventana.add(cabecera, BorderLayout.NORTH);
        ventana.add(cuerpo, BorderLayout.CENTER);
        ventana.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        ventana.pack();
        ventana.setLocationRelativeTo(null);
        ventana.setVisible(true);
    }

    private void elegirArchivo() {
        JFileChooser selector = new JFileChooser();
        selector.setFileFilter(new FileNameExtensionFilter("Anexo ATS (*.xml)", "xml"));
        if (selector.showOpenDialog(ventana) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File elegido = selector.getSelectedFile();
        archivo.setText(elegido.getAbsolutePath());
        revisar.setEnabled(true);
        leerPeriodo(elegido);
        veredicto.setText(" ");
    }

    /** El propio ATS trae el periodo en la cabecera: lo usamos de sugerencia. */
    private void leerPeriodo(File xml) {
        Integer m = numero(ValidaAts.etiqueta(xml, "Mes"));
        Integer a = numero(ValidaAts.etiqueta(xml, "Anio"));
        if (m != null && m >= 1 && m <= 12) {
            mes.setSelectedIndex(m - 1);
        }
        if (a != null && a >= 2000 && a <= 2100) {
            anio.setValue(a);
        }
    }

    private static Integer numero(String texto) {
        try {
            return texto == null ? null : Integer.valueOf(Integer.parseInt(texto.trim()));
        } catch (NumberFormatException ignorado) {
            return null;
        }
    }

    private void revisar() {
        final File xml = new File(archivo.getText());
        final int m = mes.getSelectedIndex() + 1;
        final int a = ((Number) anio.getValue()).intValue();

        revisar.setEnabled(false);
        veredicto.setForeground(GRIS);
        veredicto.setText("Revisando...");
        informe.setText("");

        new Thread(new Runnable() {
            public void run() {
                String texto;
                boolean pasa;
                try {
                    ValidaAts.Resultado r = ValidaAts.revisar(xml, m, a);
                    texto = r.informe;
                    pasa = r.errores == 0;
                } catch (Throwable t) {
                    texto = "No se pudo revisar el archivo:\n" + t;
                    pasa = false;
                }
                final String textoFinal = texto;
                final boolean pasaFinal = pasa;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        informe.setText(textoFinal);
                        informe.setCaretPosition(0);
                        veredicto.setForeground(pasaFinal ? VERDE : ROJO);
                        veredicto.setText(pasaFinal
                                ? "El anexo pasa la validacion de ventas"
                                : "El SRI rechaza este anexo");
                        revisar.setEnabled(true);
                    }
                });
            }
        }).start();
    }
}

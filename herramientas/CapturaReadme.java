import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * Genera las imagenes del README: abre la ventana, espera a que termine la
 * revision y la hace pintarse sobre un PNG. No usa la captura de pantalla del
 * sistema, asi que no pide permisos ni depende de lo que haya en pantalla.
 *
 *   java -cp <classpath> CapturaReadme <destino.png> [<archivo.xml> <mes> <anio>]
 */
public class CapturaReadme {

    public static void main(String[] args) throws Exception {
        File destino = new File(args[0]);
        if (args.length >= 4) {
            VentanaAts.abrir(new File(args[1]), Integer.parseInt(args[2]),
                             Integer.parseInt(args[3]));
        } else {
            VentanaAts.abrir();
        }

        Frame ventana = esperarVentana();
        Thread.sleep(args.length >= 4 ? 25000 : 2000);

        BufferedImage imagen = new BufferedImage(
                ventana.getWidth(), ventana.getHeight(), BufferedImage.TYPE_INT_RGB);
        ventana.paint(imagen.getGraphics());
        ImageIO.write(imagen, "png", destino);
        System.out.println("escrita: " + destino + " (" + imagen.getWidth() + "x"
                + imagen.getHeight() + ")");
        System.exit(0);
    }

    private static Frame esperarVentana() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Frame[] marcos = Frame.getFrames();
            if (marcos.length > 0 && marcos[0].isShowing()) {
                return marcos[0];
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("la ventana no llego a abrirse");
    }
}

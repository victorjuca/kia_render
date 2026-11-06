package kia.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Solo para kia-render: los temporales del motor van a KIA_TEMP (fuera de la carpeta de Drive),
 * igual que kia_temp en kia.
 */
public final class Carpetas {

    private static volatile File temp = new File(System.getProperty("java.io.tmpdir"));

    private Carpetas() {
    }

    public static void configura(File carpetaTemp) {
        temp = carpetaTemp;
    }

    public static File temp() {
        if (!temp.isDirectory()) {
            temp.mkdirs();
        }
        return temp;
    }

    public static File tempArchivo(String prefijo, String sufijo) throws IOException {
        return File.createTempFile(prefijo + "-", sufijo, temp());
    }

    /** Mueve un archivo terminado a su lugar (si es otra unidad: copia y reemplaza). */
    public static void colocaArchivo(File origen, File destino) throws IOException {
        Files.createDirectories(destino.getAbsoluteFile().getParentFile().toPath());
        try {
            Files.move(origen.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(origen.toPath(), destino.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

package kia.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProyectoTest {

    private static final List<String> ALIAS = List.of("C:\\kia_colaboradores_online\\karaoke_ia",
            "C:\\kia_colaboradores_online\\KARAOKE IA");

    @Test
    void resuelveRutaDeWindowsConAlias() {
        File f = Proyecto.resolver("C:\\kia_colaboradores_online\\KARAOKE IA\\AB12C\\songini.txt", "/data/karaoke_ia", ALIAS);
        assertEquals(new File(new File("/data/karaoke_ia", "AB12C"), "songini.txt"), f);
    }

    @Test
    void resuelveFormatoNuevoConOtraRaiz() {
        File f = Proyecto.resolver("D:\\otra\\carpeta\\ab12c\\main.mp3", "/data/karaoke_ia", ALIAS);
        assertEquals(new File(new File("/data/karaoke_ia", "AB12C"), "main.mp3"), f);
    }

    @Test
    void reconoceKeys() {
        assertTrue(Proyecto.esKey("AB12C"));
        assertTrue(Proyecto.esKey("ab12cd"));
        assertFalse(Proyecto.esKey("Los Invasores - Aguanta"));
    }

    @Test
    void nombresFinalesSinCaracteresInvalidos() {
        Proyecto p = new Proyecto(new File("/x/AB12C"));
        File[] f = p.finales(new Fila.Cancion(7, "AB12C", "AC/DC", "Back: In Black?"));
        assertEquals("ACDC - Back In Black - Karaoke - 7.mp4", f[0].getName());
        assertEquals("ACDC - Back In Black - Karaoke - sv - 7.mp4", f[1].getName());
    }

    @Test
    void segundoDeInicioDeLaLetra() {
        assertEquals(1, Portada.segundoInicio("[Eff2]\nSync0=100,150,200\n"));
        assertEquals(9, Portada.segundoInicio("Sync0=850,900\nSync1=1000\n"));
        assertEquals(0, Portada.segundoInicio("[Eff2]\nText0=hola\n"));
    }
}

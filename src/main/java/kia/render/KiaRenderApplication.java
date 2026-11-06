package kia.render;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * kia-render: toma de la fila de procesos (bitacora_proceso) los de "Renderizar KFN" (tipo 2) y los hace
 * uno a la vez, igual que la barredora de kia: video con el motor de Karaoke Studio, portada, versiones
 * Limpia y Segunda Voz, y subida a Drive.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RenderProperties.class)
public class KiaRenderApplication {

    public static void main(String[] args) {
        // el motor dibuja con Java2D: en el contenedor no hay pantalla
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(KiaRenderApplication.class, args);
    }
}

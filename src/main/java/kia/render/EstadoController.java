package kia.render;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/estado: que esta haciendo el servicio (la salud del contenedor va en /actuator/health). */
@RestController
public class EstadoController {

    private final RenderWorker worker;

    public EstadoController(RenderWorker worker) {
        this.worker = worker;
    }

    @GetMapping("/api/estado")
    public RenderWorker.Estado estado() {
        return worker.estado();
    }
}

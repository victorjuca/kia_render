package kia.render;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Barredora de kia-render: cada INTERVALO_SEG revisa la fila y toma UN proceso de Renderizar (tipo 2).
 * Con UN_PROCESO_POR_MAQUINA solo trabaja si ningun otro servicio de esta maquina tiene un proceso.
 */
@Component
public class RenderWorker {

    private static final Logger log = LoggerFactory.getLogger(RenderWorker.class);

    /** Lo que se ve en GET /api/estado. */
    public record Estado(boolean activo, String detalle, Integer proceso, String ruta, String paso, Instant inicio, Instant ultimaRevision) {
    }

    private final Fila fila;
    private final RenderService render;
    private final RenderProperties cfg;
    private final AtomicBoolean deteniendo = new AtomicBoolean(false);
    private volatile List<String> faltan = List.of();
    private volatile Fila.Proceso enCurso;
    private volatile String paso = "";
    private volatile Instant inicio, ultimaRevision;

    public RenderWorker(Fila fila, RenderService render, RenderProperties cfg) {
        this.fila = fila;
        this.render = render;
        this.cfg = cfg;
    }

    @PostConstruct
    void arranca() {
        faltan = Recursos.configura(cfg);
        if (faltan.isEmpty()) {
            Fila.Registro reg = fila.registrarMaquina();
            if (reg.nueva()) {
                log.info("Maquina registrada automaticamente en la DB: {}", cfg.getMaquinaId());
            }
            if (reg.vinculada()) {
                log.info("Proceso Renderizar vinculado a la maquina {}", cfg.getMaquinaId());
            }
            log.info("kia-render iniciado. Máquina: {} | Proyectos: {} | Temporales: {} | Drive: {}",
                    cfg.getMaquinaId(), cfg.getPathMain(), cfg.getKiaTemp(), cfg.isDriveHabilitado() ? "sí" : "NO (pruebas)");
        } else {
            log.error("Falta configurar: {} (revisa el archivo .env). No se tomarán procesos.", String.join(", ", faltan));
        }
    }

    @Scheduled(fixedDelayString = "${kia.intervalo-seg:10}", initialDelay = 3, timeUnit = TimeUnit.SECONDS)
    public void vuelta() {
        if (!faltan.isEmpty() || deteniendo.get()) {
            return;
        }
        ultimaRevision = Instant.now();
        try {
            unaVuelta();
        } catch (Exception e) {
            log.error("Error revisando la fila (se reintenta)", e);
        }
    }

    /** Toma y ejecuta un proceso. true si trabajo en algo. */
    boolean unaVuelta() throws Exception {
        Fila.Retorno proc = fila.esProcesadora();
        if (!proc.ok()) {
            log.debug("Esta máquina no procesa: {}", proc.descripcion());
            return false;
        }
        List<Fila.Proceso> pendientes = fila.pendientes(Fila.TIPO_RENDERIZAR);
        if (pendientes.isEmpty()) {
            return false;
        }
        Optional<Connection> candado = cfg.isUnProcesoPorMaquina() ? fila.tomaCandado() : Optional.empty();
        if (cfg.isUnProcesoPorMaquina() && candado.isEmpty()) {
            log.debug("Otro servicio de esta máquina tiene un proceso; se espera");
            return false;
        }
        try {
            for (Fila.Proceso p : pendientes) {
                Fila.Retorno r = fila.iniciar(p.id());
                if (!fila.asignado(r)) {
                    log.debug("Proceso {} no asignado: {}", p.id(), r.descripcion());
                    continue;
                }
                ejecuta(p);
                return true;   // un proceso a la vez
            }
            return false;
        } finally {
            candado.ifPresent(fila::sueltaCandado);
        }
    }

    private void ejecuta(Fila.Proceso p) {
        enCurso = p;
        inicio = Instant.now();
        paso = "Iniciando";
        String nombre = etiqueta(p);
        log.info("== Renderizar #{}: {}", p.id(), nombre);
        try {
            render.renderiza(p, m -> {
                paso = m;
                log.info("   #{} {}", p.id(), m);
            }, deteniendo::get);
            if (deteniendo.get()) {
                return;   // detener() ya lo marco con error
            }
            fila.terminar(p);
            log.info("== Terminado #{} {} en {} s", p.id(), nombre, Duration.between(inicio, Instant.now()).toSeconds());
        } catch (Exception e) {
            if (deteniendo.get()) {
                return;
            }
            log.error("Error en el proceso #{} ({})", p.id(), nombre, e);
            fila.error(p.id(), "Error en Renderizar (kia-render): " + e.getMessage());
        } finally {
            enCurso = null;
            paso = "";
            inicio = null;
        }
    }

    /** Ctrl+C / docker stop: si habia un proceso a medias se marca con error para poder reiniciarlo. */
    @PreDestroy
    void detener() {
        deteniendo.set(true);
        Fila.Proceso p = enCurso;
        if (p != null) {
            fila.error(p.id(), "kia-render se detuvo a la mitad del proceso; reinícialo.");
            log.warn("Detenido a la mitad del proceso #{} ({})", p.id(), etiqueta(p));
        }
    }

    /** "Artista - Cancion (KEY)" del proceso para los logs; si no se encuentra en la base, la ruta. */
    private String etiqueta(Fila.Proceso p) {
        try {
            Optional<Fila.Cancion> c = p.cancionId() != null && p.cancionId() > 0 ? fila.cancionPorId(p.cancionId()) : Optional.empty();
            if (c.isEmpty()) {
                java.io.File f = Proyecto.resolver(p.path(), cfg.getPathMain(), cfg.getPathMainAlias());
                java.io.File carpeta = f.getParentFile();
                if (carpeta != null && Proyecto.esKey(carpeta.getName())) {
                    c = fila.cancionPorKey(carpeta.getName());
                }
            }
            if (c.isPresent()) {
                return c.get().tema() + " (" + c.get().key() + ")";
            }
        } catch (Exception e) {
            log.debug("No se pudo armar el nombre de la canción del proceso {}", p.id(), e);
        }
        return p.path();
    }

    public Estado estado() {
        Fila.Proceso p = enCurso;
        String detalle = !faltan.isEmpty() ? "Falta configurar: " + String.join(", ", faltan)
                : p != null ? "Renderizando" : "Esperando procesos";
        return new Estado(faltan.isEmpty(), detalle, p == null ? null : p.id(), p == null ? null : p.path(), paso, inicio, ultimaRevision);
    }
}

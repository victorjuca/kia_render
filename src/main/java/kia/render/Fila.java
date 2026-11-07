package kia.render;

import java.sql.Array;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fila de procesos (bitacora_proceso) con las mismas funciones de la base que usa la barredora de kia:
 * sps_procesadora, valida_entrega_procesos, iniciar_proceso_v2, spu_termino_proceso y spu_error_proceso.
 */
@Component
public class Fila {

    public static final int TIPO_RENDERIZAR = 2;
    public static final int URL_LIMPIA = 10, URL_SEGUNDA_VOZ = 9;

    private static final Logger log = LoggerFactory.getLogger(Fila.class);

    public record Proceso(int id, String path, int tipo, Integer cancionId) {
    }

    public record Cancion(int id, String key, String artista, String nombre) {

        /** Nombre con el que se guardan y se suben los videos (igual que kia). */
        public String tema() {
            return artista + " - " + nombre;
        }
    }

    /** Respuesta text[] de las funciones de la base: [estado, descripcion, obj, ...]. */
    public record Retorno(String[] datos) {

        public boolean ok() {
            return datos.length > 0 && "0".equals(datos[0]);
        }

        public String descripcion() {
            return datos.length > 1 ? datos[1] : "sin respuesta";
        }

        public String get(int i) {
            return datos.length > i ? datos[i] : null;
        }
    }

    private final DataSource ds;
    private final RenderProperties cfg;

    public Fila(DataSource ds, RenderProperties cfg) {
        this.ds = ds;
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ fila

    /**
     * Si esta maquina (maquinaId) no existe en la tabla maquina, la registra automaticamente.
     * @return true si la registro ahora, false si ya existia.
     */
    public boolean registrarSiNueva() {
        try (Connection cn = ds.getConnection()) {
            // verificar si ya existe
            try (PreparedStatement ps = cn.prepareStatement(
                    "SELECT id FROM maquina WHERE mac = ?")) {
                ps.setString(1, cfg.getMaquinaId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return false;  // ya registrada
                }
            }
            // insertar
            String nombrePc;
            try { nombrePc = InetAddress.getLocalHost().getHostName(); }
            catch (Exception e) { nombrePc = "kia-render"; }
            String nombreUsuario = System.getProperty("user.name", "render");
            String so = System.getProperty("os.name", "");
            String sobrenombre = nombrePc + " (" + so + ")";
            try (PreparedStatement ps = cn.prepareStatement(
                    "INSERT INTO maquina (mac, nombre_pc, nombre_usuario, is_procesador, sobrenombre, usuario_id) " +
                    "VALUES (?, ?, ?, true, ?, 1)")) {
                ps.setString(1, cfg.getMaquinaId());
                ps.setString(2, nombrePc);
                ps.setString(3, nombreUsuario);
                ps.setString(4, sobrenombre);
                ps.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            log.warn("No se pudo verificar/registrar la maquina en la DB: {}", e.getMessage());
            return false;
        }
    }

    public Retorno esProcesadora() throws SQLException {
        return arreglo("select * from sps_procesadora(?)", cfg.getMaquinaId());
    }

    public List<Proceso> pendientes(int tipo) throws SQLException {
        List<Proceso> l = new ArrayList<>();
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "select id, path, id_tipo_proceso, cancion_id from valida_entrega_procesos(?)")) {
            ps.setString(1, cfg.getMaquinaId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt(3) == tipo) {
                        int cid = rs.getInt(4);
                        l.add(new Proceso(rs.getInt(1), rs.getString(2), tipo, rs.wasNull() ? null : cid));
                    }
                }
            }
        }
        return l;
    }

    /** Asigna el proceso a esta maquina (En Proceso) respetando los limites de maquina_proceso. */
    public Retorno iniciar(int idProceso) throws SQLException {
        return arreglo("select * from iniciar_proceso_v2(?, ?)", cfg.getMaquinaId(), idProceso);
    }

    public boolean asignado(Retorno r) {
        return r.ok() && "true".equalsIgnoreCase(r.get(3));
    }

    public void terminar(Proceso p) throws SQLException {
        arreglo("select * from spu_termino_proceso(?, ?)", p.path(), p.tipo());
    }

    public void error(int idProceso, String mensaje) {
        // bitacora_error.error es varchar(255)
        String m = mensaje == null ? "Error" : mensaje;
        if (m.length() > 255) {
            m = m.substring(0, 120) + "..." + m.substring(m.length() - 130);
        }
        try {
            arreglo("select * from spu_error_proceso(?, ?)", idProceso, m);
        } catch (SQLException e) {
            log.error("No se pudo marcar con error el proceso {}", idProceso, e);
        }
    }

    // ------------------------------------------------------------------ cancion

    public Optional<Cancion> cancionPorKey(String key) throws SQLException {
        return cancion("upper(trim(c.key)) = ?", key.toUpperCase());
    }

    public Optional<Cancion> cancionPorId(int id) throws SQLException {
        return cancion("c.id = ?", id);
    }

    private Optional<Cancion> cancion(String where, Object valor) throws SQLException {
        String sql = "SELECT c.id, upper(trim(c.key)) AS key, a.nombre AS artista, c.nombre "
                + "FROM cancion c JOIN artista a ON a.id = c.artista_id WHERE " + where;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, valor);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Cancion(rs.getInt(1), rs.getString(2), rs.getString(3).trim(), rs.getString(4).trim()));
                }
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ urls (Drive)

    /** Id de Drive registrado para la cancion y el tipo de url (10 Limpia, 9 Segunda Voz). */
    public String url(int cancionId, int tipoUrl) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select * from sps_url_cancion(?, ?)")) {
            ps.setInt(1, cancionId);
            ps.setInt(2, tipoUrl);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("url") : null;
            }
        }
    }

    /** Carpeta de Drive (tipo_url.id_drive) donde se sube ese tipo de url. */
    public String carpetaDrive(int tipoUrl) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select * from sps_tipo_url()");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (rs.getInt("id") == tipoUrl) {
                    return rs.getString("id_drive");
                }
            }
        }
        return null;
    }

    public Retorno guardaUrl(int cancionId, String url, int tipoUrl) throws SQLException {
        return arreglo("select * from spi_cancion_url(?, ?::text, ?)", cancionId, url, tipoUrl);
    }

    // ------------------------------------------------------------------ un proceso a la vez por maquina

    /**
     * Candado de la base por maquina (pg_try_advisory_lock): mientras este servicio tenga un proceso,
     * los otros servicios de la misma maquina que usen el candado (kia-worker-ia) esperan, y al reves.
     * Se libera al cerrar la conexion que regresa.
     */
    public Optional<Connection> tomaCandado() throws SQLException {
        Connection c = ds.getConnection();
        try (PreparedStatement ps = c.prepareStatement("select pg_try_advisory_lock(hashtext(?))")) {
            ps.setString(1, "kia-proceso:" + cfg.getMaquinaId());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getBoolean(1)) {
                    return Optional.of(c);
                }
            }
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        c.close();
        return Optional.empty();
    }

    public void sueltaCandado(Connection c) {
        try (c; PreparedStatement ps = c.prepareStatement("select pg_advisory_unlock(hashtext(?))")) {
            ps.setString(1, "kia-proceso:" + cfg.getMaquinaId());
            ps.execute();
        } catch (SQLException e) {
            log.warn("No se pudo soltar el candado de la maquina (se suelta al cerrar la conexion)", e);
        }
    }

    // ------------------------------------------------------------------

    private Retorno arreglo(String sql, Object... params) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                if (params[i] == null) {
                    ps.setNull(i + 1, Types.VARCHAR);
                } else {
                    ps.setObject(i + 1, params[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new Retorno(new String[0]);
                }
                Object o = rs.getObject(1);
                if (o instanceof Array a) {
                    Object[] v = (Object[]) a.getArray();
                    String[] s = new String[v.length];
                    for (int i = 0; i < v.length; i++) {
                        s[i] = v[i] == null ? null : v[i].toString();
                    }
                    return new Retorno(s);
                }
                return new Retorno(new String[]{o == null ? null : o.toString()});
            }
        }
    }
}

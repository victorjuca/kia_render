package kia.render;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import kia.claude.ProyectoSongIni;
import org.springframework.stereotype.Component;

/**
 * El Song.ini de los proyectos vive en la base (tabla songini, version_2.1.sql de kia): el motor lo lee de ahi en
 * lugar del songini.txt. Si un proyecto todavia trae songini.txt y la base no lo tiene, se sube (migracion al vuelo).
 */
@Component
public class SongIniBase implements ProyectoSongIni.Almacen {

    private final DataSource ds;
    private final Map<String, Integer> versiones = new ConcurrentHashMap<>();

    public SongIniBase(DataSource ds) {
        this.ds = ds;
    }

    @PostConstruct
    void instala() {
        ProyectoSongIni.usaAlmacen(this);
    }

    private static String keyDe(File carpeta) {
        return carpeta != null && Proyecto.esKey(carpeta.getName()) ? carpeta.getName().trim().toUpperCase() : null;
    }

    @Override
    public ProyectoSongIni.Dato lee(File carpeta) throws IOException {
        String key = keyDe(carpeta);
        if (key == null) {
            return null;
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select dato, version from sps_songini(?)")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                versiones.put(key, rs.getInt(2));
                return new ProyectoSongIni.Dato(rs.getString(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            throw new IOException("No se pudo leer el Song.ini de " + key + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void guarda(File carpeta, String iniTxt) throws IOException {
        String key = keyDe(carpeta);
        if (key == null) {
            throw new IOException("No es un proyecto del formato nuevo: " + carpeta);
        }
        int[] res = ProyectoSongIni.resumen(iniTxt);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("select spu_songini(?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, iniTxt);
            Integer v = versiones.get(key);
            if (v == null) {
                ps.setNull(3, Types.INTEGER);
            } else {
                ps.setInt(3, v);
            }
            ps.setBoolean(4, res[0] > 0);
            if (res[0] < 0) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setInt(5, res[0]);
            }
            ps.setInt(6, res[1]);
            ps.setString(7, "kia-render");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                Array a = rs.getArray(1);
                Object[] r = (Object[]) a.getArray();
                if (!"0".equals(String.valueOf(r[0]))) {
                    throw new IOException("No se guardó el Song.ini de " + key + ": " + r[1]);
                }
                versiones.put(key, Integer.parseInt(String.valueOf(r[2])));
            }
        } catch (SQLException e) {
            throw new IOException("No se pudo guardar el Song.ini de " + key + ": " + e.getMessage(), e);
        }
    }

    /** Cuando se guardo por ultima vez el Song.ini (ms), o 0 si no se sabe. */
    public long actualizado(File carpeta) {
        String key = keyDe(carpeta);
        if (key == null) {
            return 0;
        }
        // actualizado es "timestamp without time zone" en la hora del servidor de la base
        String sql = "select extract(epoch from (s.actualizado at time zone current_setting('TimeZone'))) * 1000 "
                + "from sps_songini(?) s";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    @Override
    public boolean existe(File carpeta) {
        try {
            return lee(carpeta) != null;
        } catch (IOException e) {
            return false;
        }
    }
}

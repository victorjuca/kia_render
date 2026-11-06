package kia.render;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuracion (application.yml / variables de entorno, los mismos nombres que kia-worker-ia). */
@ConfigurationProperties(prefix = "kia")
public class RenderProperties {

    /** Columna mac de la tabla maquina. Vacio en Windows: el mismo identificador que usa kia. */
    private String maquinaId = "";
    /** Carpeta de proyectos (PATH_MAIN de kia) en este equipo / contenedor. */
    private String pathMain = "C:\\kia_colaboradores_online\\karaoke_ia";
    /** Raices con las que se guardaron las rutas en bitacora_proceso, separadas por ';' (igual que kia-worker-ia). */
    private String pathMainAlias = "C:\\kia_colaboradores_online\\karaoke_ia;C:\\kia_colaboradores_online\\KARAOKE IA;"
            + "C:\\kia_colaboradores_online_beta\\karaoke_ia;C:\\kia_colaboradores_online_beta\\KARAOKE IA";
    /** Temporales (fuera de la carpeta de Drive). */
    private String kiaTemp = "C:\\kia_main\\kia_temp";
    /** Fondos de siempre y sin_audio.mp3 (los de kia/src/kia/source). */
    private String recursosDir = "recursos";
    private String ffmpeg = "ffmpeg";
    private String gdrive = "gdrive";
    /** false: deja Limpia y Segunda Voz en la carpeta del proyecto sin subirlas (para pruebas). */
    private boolean driveHabilitado = true;
    /** config.segundo_inicio_cancion_join de kia: si la letra empieza despues, la portada se sobrepone. */
    private int segundoInicioJoin = 8;
    private int intervaloSeg = 10;
    /** Candado en la base para que esta maquina corra un solo proceso a la vez entre todos los servicios. */
    private boolean unProcesoPorMaquina = true;
    private double whisperProbabilidad = 0.60;

    public String getMaquinaId() { return maquinaId; }
    public void setMaquinaId(String v) { maquinaId = v == null ? "" : v.trim(); }
    public String getPathMain() { return pathMain; }
    public void setPathMain(String v) { pathMain = v; }
    public List<String> getPathMainAlias() {
        List<String> l = new ArrayList<>();
        for (String r : pathMainAlias.split(";")) {
            if (!r.isBlank()) {
                l.add(r.trim());
            }
        }
        return l;
    }
    public void setPathMainAlias(String v) { pathMainAlias = v == null ? "" : v; }
    public String getKiaTemp() { return kiaTemp; }
    public void setKiaTemp(String v) { kiaTemp = v; }
    public String getRecursosDir() { return recursosDir; }
    public void setRecursosDir(String v) { recursosDir = v; }
    public String getFfmpeg() { return ffmpeg; }
    public void setFfmpeg(String v) { ffmpeg = v; }
    public String getGdrive() { return gdrive; }
    public void setGdrive(String v) { gdrive = v; }
    public boolean isDriveHabilitado() { return driveHabilitado; }
    public void setDriveHabilitado(boolean v) { driveHabilitado = v; }
    public int getSegundoInicioJoin() { return segundoInicioJoin; }
    public void setSegundoInicioJoin(int v) { segundoInicioJoin = v; }
    public int getIntervaloSeg() { return intervaloSeg; }
    public void setIntervaloSeg(int v) { intervaloSeg = v; }
    public boolean isUnProcesoPorMaquina() { return unProcesoPorMaquina; }
    public void setUnProcesoPorMaquina(boolean v) { unProcesoPorMaquina = v; }
    public double getWhisperProbabilidad() { return whisperProbabilidad; }
    public void setWhisperProbabilidad(double v) { whisperProbabilidad = v; }
}

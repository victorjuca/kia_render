# kia-render

Microservicio (Spring Boot 3, Java 21) que hace el proceso **Renderizar KFN (tipo 2)** de la barredora de kia,
**un proceso a la vez**, listo para Docker:

1. Toma el proceso de la fila (`bitacora_proceso`) con las mismas funciones que la barredora:
   `sps_procesadora`, `valida_entrega_procesos`, `iniciar_proceso_v2`, `spu_termino_proceso`, `spu_error_proceso`.
   La app de escritorio ve el avance igual que hoy.
2. **Video base** con el motor de Karaoke Studio (el mismo de "Exportar MP4": 1280×720, 25 fps, CRF 27),
   leyendo `songini.txt` + `main.mp3` (o el `main.kfn` de antes).
3. **Portada** (`portada.jpg`): si la letra empieza en el segundo `SEGUNDO_INICIO_JOIN` (8) o después se
   sobrepone los primeros 5 s; si no, se agrega un clip de 4 s al inicio.
4. **Limpia** (`rv_instrumental.mp3`) y **Segunda Voz** (`rv_sv_instrumental.mp3`) con el audio recorrido lo que
   dura la portada; se valida la duración (±0.2 s).
5. Los dos videos quedan en la carpeta del proyecto como `Artista - Nombre - Karaoke - <id>.mp4` y
   `... - Karaoke - sv - <id>.mp4`, y se **suben a Drive** con `gdrive` (url 10 y 9), borrando el archivo anterior.
   Si la subida falla, el siguiente intento solo sube (no vuelve a renderizar).

Todo lo intermedio se hace en `KIA_TEMP` (fuera de Drive). ffmpeg se llama con lista de argumentos (sin `cmd.exe`),
así que funciona igual en Windows y en Linux.

Solo atiende proyectos del **formato nuevo** (`karaoke_ia/<KEY>/main.mp3`); los del formato anterior se siguen
renderizando desde kia.

> Mientras se prueba, **detén la barredora de kia** en ese equipo (o quita *Renderiza KFN* de la máquina de kia)
> para que no tomen el mismo proceso. El código de kia no se cambió.

## Un proceso a la vez

- Este servicio toma **un** proceso por vuelta (cada `INTERVALO_SEG`).
- Con `UN_PROCESO_POR_MAQUINA=true` (por omisión) usa un candado en la base (`pg_try_advisory_lock`) por
  `WORKER_MAQUINA_ID`. **kia-worker-ia usa el mismo candado**: si los dos servicios usan la misma máquina,
  mientras uno remueve voz o transcribe el otro no renderiza, y al revés.

## Probar en Windows (sin Docker)

Requiere JDK 21+, Maven, ffmpeg en el PATH y gdrive (el mismo que usa kia).

```powershell
cd C:\Users\turing\Documents\claude\kia-render
copy .env.example .env          # llena la base; usa / en las rutas (C:/...)
# para la primera prueba conviene DRIVE_HABILITADO=false
mvn -DskipTests package
java -jar target\kia-render.jar
```

- Estado: <http://localhost:8080/api/estado> · salud: <http://localhost:8080/actuator/health>
- Ctrl+C a la mitad de un render marca el proceso con error para poder reiniciarlo desde kia.
- En la máquina, habilita el proceso **Renderiza KFN** (pantalla *Máquinas* de kia).

## Con Docker

```powershell
copy .env.example .env               # WORKER_MAQUINA_ID es obligatorio en Docker
docker compose up -d --build
docker compose logs -f
```

`docker-compose.yml` monta:

| En el equipo | En el contenedor | Para |
|---|---|---|
| `C:/kia_colaboradores_online/karaoke_ia` | `/data/karaoke_ia` | proyectos (PATH_MAIN) |
| `C:/kia_main/kia_temp` | `/data/kia_temp` | temporales |
| `./gdrive3` | `/root/.config/gdrive3` | cuenta de gdrive |
| `./fuentes` | `/usr/share/fonts/truetype/kia` | fuentes de las letras |

**Cuenta de gdrive** (una vez): en Windows `gdrive account export <tu-correo>` genera un `.tar`; cópialo a la carpeta
del servicio y ejecuta
`docker compose run --rm --entrypoint gdrive -v "${PWD}:/x" kia-render account import /x/<archivo>.tar`.

**Fuentes:** el motor dibuja la letra con las fuentes del Song.ini (por omisión *Arial Black*). Copia de
`C:\Windows\Fonts` a `fuentes\` las que usen tus proyectos (p. ej. `ariblk.ttf`, `arial.ttf`, `arialbd.ttf`).
Si falta alguna, el log lo avisa ("Fuentes que no están instaladas") y se usa otra.

## Motor de render

Los archivos de `src/main/java/kia/claude` listados en `motor.txt` son **copias** de `kia/src/kia/claude`
(Studio, Renderer, PreviewPanel, Mp4, …). Después de cambiar el render en kia:

```powershell
.\sincroniza-motor.ps1        # copia el motor y los recursos (fondos, sin_audio.mp3) desde kia
```

Solo son de este servicio: `KaraokeStudio.java` (vacía, sin ventanas), `MotorRender.java` (entrada sin ventanas),
y los sustitutos mínimos `kia/form/PropertiesForm.java` y `kia/util/Carpetas.java`.

## Estructura

```
src/main/java/kia/render/
  KiaRenderApplication  arranque (Spring Boot + @Scheduled)
  RenderWorker          barredora: un proceso de tipo 2 por vuelta, candado por máquina, /api/estado
  RenderService         orquesta: proyecto -> video base -> portada -> Limpia / Segunda Voz -> Drive
  Portada               portada al inicio (concat) o sobrepuesta (join)
  Drive                 gdrive files upload / delete + spi_cancion_url
  Fila                  funciones de la base (fila, canción, urls, candado)
  Proyecto              rutas del formato nuevo y traducción de rutas de Windows
  FFmpeg, Recursos, RenderProperties
src/main/java/kia/claude/   motor copiado de kia (+ MotorRender, KaraokeStudio)
recursos/                   Fondo Karaoke IA.jpg, Fondo Karaoke IA_sin_logo.jpg, sin_audio.mp3
```
"# kia_render" 

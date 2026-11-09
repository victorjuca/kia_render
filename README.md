# kia-render

Microservicio (Spring Boot 3.3, Java 21) que hace el proceso **Renderizar KFN (tipo 2)** de la barredora de kia,
**un proceso a la vez**, listo para Docker. Es una de las tres piezas en que se separan los procesos de kia:

| Servicio | Lenguaje | Hace |
|---|---|---|
| kia-worker-ia | Python | descargar canción, quitar voz, Whisper (solo en equipos con GPU) |
| **kia-render** | Java / Spring Boot | video base, portada, versiones Limpia y Segunda Voz, subida a Drive |

> Mientras se prueba, **detén la barredora de kia** en ese equipo (o quita *Renderiza KFN* de la máquina de kia)
> para que no tomen el mismo proceso. El código de kia no se cambió.

## Qué hace

1. **Toma el proceso de la fila** (`bitacora_proceso`) con las mismas funciones que la barredora:
   `sps_procesadora`, `valida_entrega_procesos`, `iniciar_proceso_v2`, `spu_termino_proceso`, `spu_error_proceso`.
   La app de escritorio ve el avance igual que hoy.
2. **Video base** con el motor de Karaoke Studio (el mismo de "Exportar MP4": 1280×720, 25 fps, CRF 27),
   leyendo `songini.txt` + `main.mp3` (o el `main.kfn` de antes).
3. **Portada** (`portada.jpg`): si la letra empieza en el segundo `SEGUNDO_INICIO_JOIN` (8) o después, se
   sobrepone en los primeros 5 s; si no, se agrega un clip de 4 s al inicio.
4. **Limpia** (`rv_instrumental.mp3`) y **Segunda Voz** (`rv_sv_instrumental.mp3`), con el audio recorrido lo que
   dura la portada; se valida la duración (±0.2 s).
5. Los dos videos quedan en la carpeta del proyecto como `Artista - Nombre - Karaoke - <id>.mp4` y
   `Artista - Nombre - Karaoke - sv - <id>.mp4`, y se **suben a Drive** con `gdrive` (url 10 y 9), borrando el
   archivo anterior. Si la subida falla, el siguiente intento solo sube (no vuelve a renderizar).

Todo lo intermedio se hace en `KIA_TEMP` (fuera de Drive). ffmpeg se llama con lista de argumentos (sin `cmd.exe`),
así que funciona igual en Windows y en Linux.

Solo atiende proyectos del **formato nuevo** (`karaoke_ia/<KEY>/main.mp3`, `portada.jpg`, …); los del formato
anterior se siguen renderizando desde kia.

### Un proceso a la vez

- Toma **un** proceso por vuelta (cada `INTERVALO_SEG`).
- Con `UN_PROCESO_POR_MAQUINA=true` (por omisión) usa un candado en la base (`pg_try_advisory_lock`) por
  `WORKER_MAQUINA_ID`. **kia-worker-ia usa el mismo candado**: si los dos servicios usan la misma máquina,
  mientras uno quita voz o transcribe el otro no renderiza, y al revés.
- En la pantalla *Máquinas* de kia, la máquina debe tener habilitado **Renderiza KFN**.

## Requisitos

| | Windows (sin Docker) | Docker |
|---|---|---|
| Java | JDK 21+ y Maven | incluido en la imagen |
| ffmpeg | en el PATH | incluido |
| gdrive 3 | el mismo que usa kia | incluido (v3.9.1), cuenta montada en `./gdrive3` |
| Fuentes | las de Windows | copiarlas a `./fuentes` (ver abajo) |
| Base | PostgreSQL de kia (servidor aparte) | igual |

## Configuración

Todo se configura con variables de entorno. Copia `.env.example` como `.env` (no se sube a git).
Fuera de Docker el `.env` se lee como `.properties`, así que usa `/` en las rutas de Windows (`C:/...`).

| Variable | Por omisión | Para qué |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | `localhost`, `5432`, `kia` | base de kia |
| `WORKER_MAQUINA_ID` | vacío | columna `mac` de la tabla `maquina`. Vacío en Windows = el mismo identificador que kia. **Obligatorio en Docker**; usa el mismo que kia-worker-ia |
| `PATH_MAIN` | `C:/kia_main/kia_proyectos` | carpeta de proyectos |
| `PATH_MAIN_ALIAS` | rutas `kia_colaboradores_online(_beta)\karaoke_ia` y `KARAOKE IA` | rutas de Windows guardadas en la base que se traducen a `PATH_MAIN` (separadas por `;`) |
| `KIA_TEMP` | `C:/kia_main/kia_temp` | temporales |
| `RECURSOS_DIR` | `recursos` | fondos y `sin_audio.mp3` |
| `FFMPEG` / `GDRIVE` | `ffmpeg` / `gdrive` | ejecutables |
| `DRIVE_HABILITADO` | `true` | `false` para probar sin subir (los videos se quedan en la carpeta del proyecto) |
| `SEGUNDO_INICIO_JOIN` | `8` | desde qué segundo la portada se sobrepone en vez de agregarse |
| `INTERVALO_SEG` | `10` | cada cuánto revisa la fila |
| `UN_PROCESO_POR_MAQUINA` | `true` | candado compartido con kia-worker-ia |
| `WHISPER_PROBABILIDAD` | `0.60` | umbral que usa el motor al leer palabras de Whisper |
| `PORT` | `8080` | puerto HTTP |

## Probar en Windows (sin Docker)

```powershell
cd C:\Users\turing\Documents\claude\kia-render
copy .env.example .env          # llena la base; para la primera prueba conviene DRIVE_HABILITADO=false
mvn -DskipTests package
java -jar target\kia-render.jar
```

Ctrl+C a la mitad de un render marca el proceso con error para poder reiniciarlo desde kia.

## Con Docker

```powershell
copy .env.example .env               # WORKER_MAQUINA_ID es obligatorio en Docker
docker compose up -d --build
docker compose logs -f
```

`docker-compose.yml` publica el puerto en **8081** y monta:

| En el equipo | En el contenedor | Para |
|---|---|---|
| `C:/kia_main/kia_proyectos` | `/data/karaoke_ia` | proyectos (`PATH_MAIN`) |
| `C:/kia_main/kia_temp` | `/data/kia_temp` | temporales |
| `./gdrive3` | `/root/.config/gdrive3` | cuenta de gdrive |
| `./fuentes` | `/usr/share/fonts/truetype/kia` | fuentes de las letras |

Las rutas de `PATH_MAIN`, `KIA_TEMP` y `RECURSOS_DIR` del `.env` se ignoran dentro del contenedor
(el compose las fija a `/data/...`).

**Cuenta de gdrive.** `gdrive3/` guarda las cuentas (`<correo>/secret.json`, `tokens.json`) y `account.json`
indica cuál está activa. Para traer una cuenta desde Windows:

```powershell
gdrive account export <correo>       # genera un .tar
docker compose run --rm --entrypoint gdrive -v "${PWD}:/x" kia-render account import /x/<archivo>.tar
docker compose run --rm --entrypoint gdrive kia-render account switch <correo>
```

`gdrive3/` tiene tokens: no se sube a git ni se comparte.

**Fuentes.** El motor dibuja la letra con las fuentes del Song.ini (por omisión *Arial Black*). Crea la carpeta
`fuentes\` y copia de `C:\Windows\Fonts` las que usen tus proyectos (p. ej. `ariblk.ttf`, `arial.ttf`,
`arialbd.ttf`). Si falta alguna, el log lo avisa ("Fuentes que no están instaladas") y se usa otra.

## API

| Ruta | Devuelve |
|---|---|
| `GET /api/estado` | estado del worker (proceso actual, último resultado) |
| `GET /actuator/health` | salud (la usa el `HEALTHCHECK` de Docker) |

Sin Docker: <http://localhost:8080/api/estado> · con Docker: <http://localhost:8081/api/estado>

## Motor de render

Los archivos de `src/main/java/kia/claude` listados en `motor.txt` son **copias** de `kia/src/kia/claude`
(Studio, Renderer, PreviewPanel, Mp4, …). Después de cambiar el render en kia:

```powershell
.\sincroniza-motor.ps1                          # kia en Documents\NetBeansProjects\avida\v1\kia
.\sincroniza-motor.ps1 -Kia "D:\otra\ruta\kia"   # si está en otro lado
```

Copia el motor y los recursos (fondos, `sin_audio.mp3`). Solo son de este servicio: `KaraokeStudio.java`
(vacía, sin ventanas), `MotorRender.java` (entrada sin ventanas) y los sustitutos mínimos
`kia/form/PropertiesForm.java` y `kia/util/Carpetas.java`. No edites a mano los archivos copiados: el siguiente
`sincroniza-motor` los sobrescribe.

## Estructura

```
src/main/java/kia/render/
  KiaRenderApplication  arranque (Spring Boot + @Scheduled)
  RenderWorker          barredora: un proceso de tipo 2 por vuelta, candado por máquina
  EstadoController      /api/estado
  RenderService         orquesta: proyecto -> video base -> portada -> Limpia / Segunda Voz -> Drive
  Portada               portada al inicio (concat) o sobrepuesta (join)
  Drive                 gdrive files upload / delete + spi_cancion_url
  Fila                  funciones de la base (fila, canción, urls, candado)
  Proyecto              rutas del formato nuevo y traducción de rutas de Windows
  FFmpeg, Recursos, RenderProperties, SongIniBase
src/main/java/kia/claude/   motor copiado de kia (+ MotorRender, KaraokeStudio)
src/test/java/kia/render/   ProyectoTest (sin base ni pantalla)
recursos/                   Fondo Karaoke IA.jpg, Fondo Karaoke IA_sin_logo.jpg, sin_audio.mp3
```

## Problemas comunes

- **No toma procesos:** revisa que la máquina tenga *Renderiza KFN* habilitado, que `WORKER_MAQUINA_ID` coincida
  con la columna `mac`, y que kia-worker-ia no tenga el candado ocupado (está quitando voz o transcribiendo).
- **"no es formato nuevo":** el proyecto no tiene `karaoke_ia/<KEY>/main.mp3`; renderízalo desde kia.
- **Duración inválida en Limpia / Segunda Voz:** el instrumental difiere más de 0.2 s del video base; vuelve a
  quitar la voz.
- **Falla la subida:** revisa la cuenta activa de gdrive; el reintento solo sube, no renderiza otra vez.
- **Letra con otra fuente:** falta el `.ttf` en `fuentes/` (Docker).

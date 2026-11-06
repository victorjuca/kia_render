# ---------- compilacion ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B -DskipTests package

# ---------- servicio ----------
FROM eclipse-temurin:21-jre
ARG GDRIVE_VERSION=3.9.1
# ffmpeg (render, portada y versiones), fontconfig + fuentes (el motor dibuja la letra con Java2D) y gdrive (subida a Drive)
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg fontconfig fonts-dejavu-core curl ca-certificates \
 && curl -fsSL "https://github.com/glotlabs/gdrive/releases/download/${GDRIVE_VERSION}/gdrive_linux-x64.tar.gz" \
    | tar -xz -C /usr/local/bin gdrive \
 && chmod +x /usr/local/bin/gdrive \
 && rm -rf /var/lib/apt/lists/*

# UTF-8: los videos se llaman "Artista - Canción - Karaoke - id.mp4" (acentos, ñ)
ENV LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    PATH_MAIN=/data/karaoke_ia \
    KIA_TEMP=/data/kia_temp \
    RECURSOS_DIR=/app/recursos \
    JAVA_TOOL_OPTIONS="-Djava.awt.headless=true -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -XX:MaxRAMPercentage=75"

WORKDIR /app
COPY recursos recursos
COPY --from=build /src/target/kia-render.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

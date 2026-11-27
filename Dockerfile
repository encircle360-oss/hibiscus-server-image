# check=skip=SecretsUsedInArgOrEnv
# SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
#
# Hibiscus Server from the vendor's official release package, run without root.
# The check above is skipped because HIBISCUS_MASTER_PASSWORD_FILE holds a path, not a secret.
#
# Updating the package: change HIBISCUS_VERSION and HIBISCUS_SHA256 together. The value is the content of
# the vendor's hibiscus-server-<version>.zip.SHA-256 file, and the workflow Signature checks the PGP
# signature of the package that has it.
ARG HIBISCUS_VERSION=2.12.4
ARG HIBISCUS_SHA256=ffe6d7e149f03f2e60915592a35d9872a0660c01338f97167d5eb5866c6ec3bb

FROM eclipse-temurin:21-jdk-noble@sha256:dc333cdac241f073941f043792f2a2f9b790bfe7cd98ef2843c08880986708de AS build
ARG HIBISCUS_VERSION
ARG HIBISCUS_SHA256

ADD --checksum=sha256:${HIBISCUS_SHA256} \
    https://www.willuhn.de/products/hibiscus-server/releases/hibiscus-server-${HIBISCUS_VERSION}.zip \
    /build/hibiscus-server.zip

WORKDIR /build/stage
# The archive holds one top-level directory "hibiscus-server". The Windows binaries are not needed on Linux.
RUN jar xf /build/hibiscus-server.zip \
 && test -f hibiscus-server/jameica-linux64.jar \
 && grep -q "name=\"hibiscus.server\" version=\"${HIBISCUS_VERSION}\"" hibiscus-server/plugins/hibiscus.server/plugin.xml \
 && rm -f hibiscus-server/jameicaserver.exe hibiscus-server/jameica-win64.jar \
 && rm -rf hibiscus-server/lib/swt/win64 \
 && find hibiscus-server -type d -exec chmod 0755 {} + \
 && find hibiscus-server -type f -exec chmod 0644 {} +

COPY launcher/HibiscusLauncher.java /build/src/HibiscusLauncher.java
RUN javac --release 21 -Xlint:all -Werror -d /build/launcher /build/src/HibiscusLauncher.java \
 && mkdir -m 0700 /build/data

FROM gcr.io/distroless/java21-debian12:nonroot@sha256:7e37784d94dccbf5ccb195c73b295f5ad00cd266512dfbac12eb9c3c28f8077d
ARG HIBISCUS_VERSION
ARG VERSION=dev

LABEL org.opencontainers.image.title="hibiscus-server" \
      org.opencontainers.image.description="Hibiscus Server (FinTS/HBCI banking server) from the vendor's release package, unofficial community image" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.licenses="GPL-2.0-or-later WITH Classpath-exception-2.0" \
      org.opencontainers.image.source="https://github.com/encircle360-oss/hibiscus-server-image" \
      org.opencontainers.image.url="https://github.com/encircle360-oss/hibiscus-server-image" \
      org.opencontainers.image.documentation="https://github.com/encircle360-oss/hibiscus-server-image#readme" \
      org.opencontainers.image.vendor="encircle360 GmbH" \
      org.opencontainers.image.authors="encircle360 GmbH and contributors <oss@encircle360.com>" \
      org.opencontainers.image.base.name="gcr.io/distroless/java21-debian12:nonroot" \
      com.encircle360.hibiscus-server.version="${HIBISCUS_VERSION}"

COPY --from=build --chown=0:0 /build/stage/hibiscus-server /opt/hibiscus
COPY --from=build --chown=0:0 /build/launcher /opt/hibiscus-launcher
COPY --chown=0:0 --chmod=0644 LICENSE SOURCES.md /usr/share/doc/hibiscus-server-image/
# A fresh named volume inherits owner and mode: the user directory is writable without root and closed
# to others, which matters because Jameica creates its own files with the default umask.
COPY --from=build --chown=65532:65532 --chmod=0700 /build/data /data

# Jameica resolves cfg/, plugins/ and lib/ relative to the working directory.
WORKDIR /opt/hibiscus

# The variable names a file of the secret mount, not a secret.
# hadolint ignore=DL3064
ENV TZ=Europe/Berlin \
    HIBISCUS_WORKDIR=/data \
    HIBISCUS_MASTER_PASSWORD_FILE=/run/secrets/hibiscus/master-password \
    JDK_JAVA_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

# 65532 is the "nonroot" account of the distroless base image.
USER 65532:65532
VOLUME ["/data"]
EXPOSE 8080

ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-Djava.io.tmpdir=/tmp", \
            "-cp", "/opt/hibiscus-launcher:/opt/hibiscus/jameica-linux64.jar", \
            "HibiscusLauncher"]

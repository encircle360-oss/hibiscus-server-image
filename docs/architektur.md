# Architektur

hibiscus-server-image ist ein Rezept für ein Image. Ins Image kommen das Release-Paket des Herstellers, unverändert bis auf die Dateien für Windows, und ein Einstiegspunkt aus diesem Repository. Diese Seite beschreibt, wie das Image entsteht, was darin steckt und was beim Start passiert. Den Betrieb beschreibt [Betrieb](betrieb.md), Tests und CI [Entwicklung](entwicklung.md).

## Das Paket des Herstellers

Das Paket `hibiscus-server-2.12.4.zip` von <https://www.willuhn.de/products/hibiscus-server/download.php> enthält:

| Bestandteil | Version laut Paket |
|---|---|
| Jameica (Plattform, `jameica.jar`) | 2.12.0 |
| Hibiscus (`plugins/hibiscus`) | 2.12.4 |
| Hibiscus Server (`plugins/hibiscus.server`) | 2.12.4 |
| `hibiscus.xmlrpc`, `hibiscus.soap`, `jameica.webadmin`, `jameica.xmlrpc`, `jameica.soap`, `jameica.sensors` | 2.11.0-nightly |
| HBCI4Java (`hbci4j-core`) | 4.1.10 |
| Jetty (in `jameica.webadmin`) | 9.4.38 |
| MariaDB Connector/J, MySQL Connector/J | 3.5.1, 9.2.0 |

Woher der Quelltext jedes Teils kommt, steht in [SOURCES.md](../SOURCES.md).

## Stufen des Dockerfile

| Stufe | Image | Was sie tut |
|---|---|---|
| `build` | `eclipse-temurin:21-jdk-noble`, per Digest | Lädt das Paket mit `ADD --checksum=sha256:…`: BuildKit bricht ab, wenn die Summe nicht `HIBISCUS_SHA256` ist. Entpackt es mit `jar`, prüft, dass `plugins/hibiscus.server/plugin.xml` die Version `HIBISCUS_VERSION` nennt, entfernt `jameicaserver.exe`, `jameica-win64.jar` und `lib/swt/win64`, setzt Verzeichnisse auf 0755 und Dateien auf 0644. Übersetzt den Einstiegspunkt mit `javac --release 21 -Xlint:all -Werror`. |
| Laufzeit | `gcr.io/distroless/java21-debian12:nonroot`, per Digest | Java 21 (Eclipse Temurin) auf Debian 12 ohne Shell und Paketmanager. Kopiert das Paket nach `/opt/hibiscus` und den Einstiegspunkt nach `/opt/hibiscus-launcher`, beide root gehörend und nur lesbar, legt `/data` für uid 65532 mit Modus 0700 an und `LICENSE` und `SOURCES.md` unter `/usr/share/doc/hibiscus-server-image/`. |

Beide Basis-Images gibt es für `linux/amd64` und `linux/arm64`, das Paket des Herstellers ist reines Java. Die CI baut jede Plattform nativ auf einem Runner dieser Plattform.

Der Kopf des `Dockerfile` schaltet die Prüfung `SecretsUsedInArgOrEnv` von BuildKit ab und hadolint die Regel DL3064 an `ENV`: `HIBISCUS_MASTER_PASSWORD_FILE` nennt den Pfad einer Datei, kein Passwort.

## Was im Image steckt

| Was | Wo oder wie |
|---|---|
| Paket des Herstellers | `/opt/hibiscus`, Arbeitsverzeichnis; Jameica liest `cfg/`, `plugins/` und `lib/` von dort |
| Einstiegspunkt | `/opt/hibiscus-launcher/HibiscusLauncher.class`, gestartet mit `java -cp /opt/hibiscus-launcher:/opt/hibiscus/jameica-linux64.jar HibiscusLauncher` |
| Benutzer | `nonroot`, uid und gid 65532 |
| Volume | `/data`, Benutzerverzeichnis von Jameica |
| Port | 8080, HTTPS |
| Umgebung | `TZ=Europe/Berlin`, `HIBISCUS_WORKDIR=/data`, `HIBISCUS_MASTER_PASSWORD_FILE=/run/secrets/hibiscus/master-password`, `JDK_JAVA_OPTIONS=-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError` |
| Größe | rund 470 MB, davon 100 MB das Paket des Herstellers |

## Labels

| Label | Inhalt |
|---|---|
| `org.opencontainers.image.title` | `hibiscus-server` |
| `org.opencontainers.image.description` | eine Zeile auf Englisch, mit dem Hinweis auf das inoffizielle Image |
| `org.opencontainers.image.version` | aus dem Build-Argument `VERSION`: das Release-Tag oder `git describe`, sonst `dev` |
| `org.opencontainers.image.licenses` | `GPL-2.0-or-later WITH Classpath-exception-2.0` |
| `org.opencontainers.image.source`, `.url`, `.documentation` | dieses Repository |
| `org.opencontainers.image.vendor`, `.authors` | encircle360 GmbH und Mitwirkende, als Herausgeber des Images |
| `org.opencontainers.image.base.name` | `gcr.io/distroless/java21-debian12:nonroot` |
| `com.encircle360.hibiscus-server.version` | die Version von Hibiscus Server, etwa `2.12.4` |

Für ein Image beider Plattformen liest die GitHub Container Registry Beschreibung, Quelle und Lizenz aus den Annotationen des Index; der Job „Publish the image“ übernimmt sie aus den Labels.

## Der Einstiegspunkt

`launcher/HibiscusLauncher.java` ist eine einzelne Klasse ohne Abhängigkeiten, die in derselben JVM wie Jameica läuft. Ein Start geht so:

1. **Streams offen halten.** Jameica schließt im nicht interaktiven Modus nach dem Start `System.out` und `System.err`. Der Einstiegspunkt ersetzt beide vorher durch Streams, die das Schließen ignorieren, damit seine eigenen Meldungen und das Log auch danach im Container-Log ankommen.
2. **Master-Passwort prüfen.** Die Datei muss lesbar sein und eine nicht leere erste Zeile haben. Jameica liest wie der Einstiegspunkt nur die erste Zeile und schneidet nichts ab, Leerzeichen gehören also zum Passwort.
3. **Benutzerverzeichnis.** Fehlt `/data/cfg`, legt er es mit 0700 an. Ist `/data` für Gruppe oder andere offen, warnt er, denn Jameica legt seine eigenen Dateien mit dem Standard-umask an, also lesbar für alle, die das Verzeichnis betreten können.
4. **Einstellungen prüfen und schreiben**, in dieser Reihenfolge: Scheduler, XML-RPC, Weboberfläche, System, Datenbank. Jede Datei wird geladen, die verwalteten Schlüssel werden gesetzt, alle anderen bleiben, und die Datei wird durch eine neue mit Modus 0600 ersetzt (neue Datei, dann `rename`). Die Dateien und Schlüssel stehen in der Tabelle des [README](../README.md#einstellungen).
5. **MariaDB vorbereiten**, wenn `HIBISCUS_DB_HOST` gesetzt ist: Der Einstiegspunkt lädt den Treiber aus `lib/mariadb` des Pakets, wartet bis zu `HIBISCUS_DB_WAIT_SECONDS` auf die Datenbank und entscheidet dann:
   - Tabelle `version` mit dem Eintrag `db` vorhanden: Schema da, nichts zu tun.
   - Tabelle `version` ohne diesen Eintrag: Abbruch, das Schema ist unvollständig.
   - Andere Tabellen ohne `version`: Abbruch, die Datenbank gehört jemand anderem.
   - Leer: Ist der Zeichensatz `utf8` oder `utf8mb3` (oder `HIBISCUS_DB_ALLOW_CHARSET=true`) und `HIBISCUS_DB_INIT_SCHEMA=true`, führt er `plugins/hibiscus/sql/mysql-create.sql` des Herstellers aus, Anweisung für Anweisung. Hibiscus legt auf MariaDB keine Tabellen an; der Hersteller beschreibt genau diesen Schritt von Hand. Schlägt eine Anweisung fehl, löscht er die Tabellen, die er angelegt hat, denn MariaDB übernimmt jedes `CREATE TABLE` sofort und kennt dafür kein Rollback.
6. **Log auf stdout.** Ab `HIBISCUS_LOG_STDOUT_LEVEL` hängt er ein Ziel an den Logger von Jameica, das auf stdout schreibt, siehe [Sicherheit](sicherheit.md#das-log-auf-stdout).
7. **Jameica starten** mit `de.willuhn.jameica.Main -d -n -f /data -w <Datei des Master-Passworts>`: Servermodus ohne Oberfläche, nie eine Rückfrage, Benutzerverzeichnis, Passwortdatei.
8. **Plugins prüfen.** Meldet der Plugin-Loader von Jameica Fehler, schreibt der Einstiegspunkt Plugin und Meldung ins Log und beendet den Container mit Exit-Code 3. Ohne das liefe Jameica mit Weboberfläche weiter, auch wenn Hibiscus etwa wegen eines Datenbankfehlers nicht gestartet ist.

Einstellungen in `/data/cfg` überschreiben die Vorgaben in `/opt/hibiscus/cfg`. Jameica schreibt beim Speichern einer Einstellung den ganzen Stand zurück; zählt also nur das Benutzerverzeichnis, und der Einstiegspunkt setzt seine Schlüssel bei jedem Start neu.

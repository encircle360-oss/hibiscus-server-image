# hibiscus-server-image

[Hibiscus Server](https://www.willuhn.de/products/hibiscus-server/) als Container-Image: der Banking-Server ohne Oberfläche für FinTS/HBCI von Olaf Willuhn, mit Jameica, Hibiscus, Weboberfläche und XML-RPC, gebaut aus dem offiziellen Release-Paket des Herstellers, ohne root, mit SBOM und Herkunftsangabe.

> **Inoffizielles Community-Projekt.** hibiscus-server-image ist ein unabhängiges Open-Source-Projekt. Es steht mit Olaf Willuhn, dem Autor von Hibiscus und Jameica, nicht in Verbindung und wird von ihm weder unterstützt noch empfohlen. Hibiscus, Hibiscus Server und Jameica sind sein Werk und seine Namen; dieses Repository nutzt sie nur, um zu sagen, welche Software im Image steckt. **Fragen zu Hibiscus selbst** (Einrichtung von Bankzugängen, TAN-Verfahren, Fehler der Software) gehören in das Forum und das Wiki des Herstellers, die seine [Support-Seite](https://www.willuhn.de/products/hibiscus-server/support.php) nennt, und in das [Wiki](https://www.willuhn.de/wiki/). **Fragen zum Image** gehören in die [Issues](https://github.com/encircle360-oss/hibiscus-server-image/issues) und [Discussions](https://github.com/encircle360-oss/hibiscus-server-image/discussions) dieses Repositories.

> **Ohne Gewähr.** Das Image wird ohne jede Gewährleistung bereitgestellt, Nutzung auf eigenes Risiko. Wer es betreibt, ist für die Sicherheit der Bankzugangsdaten und der Installation verantwortlich. Einzelheiten unter [Haftungsausschluss](#haftungsausschluss).

> **Status: erstes Release, frühes Stadium.** Das Image `ghcr.io/encircle360-oss/hibiscus-server:2.12.4-1` besteht den Image-Test der CI auf `linux/amd64` und `linux/arm64`. Kein Test hat je einen Bankzugang eingerichtet, siehe [Was getestet ist](#was-getestet-ist-und-was-nicht).

## Was hibiscus-server-image ist

- **Für wen.** Für Betreiber, die Hibiscus Server in Docker, docker compose oder Kubernetes betreiben wollen, etwa als Quelle für [bankbote](https://github.com/encircle360-oss/bankbote), das die Umsätze aus einem Hibiscus Server in ERPNext überträgt. Wer Hibiscus auf dem Desktop nutzt, braucht dieses Image nicht.
- **Das Paket des Herstellers, geprüft.** Das Image enthält `hibiscus-server-2.12.4.zip` von [willuhn.de](https://www.willuhn.de/products/hibiscus-server/download.php) unverändert, ohne die Dateien für Windows. Der Build bricht ab, wenn das Paket nicht die gepinnte SHA-256-Summe hat; die CI prüft zusätzlich die PGP-Signatur des Herstellers, siehe [Signatur](docs/sicherheit.md#signatur-des-herstellers). Bisherige Images anderer stammen von einzelnen Konten und prüfen nichts davon.
- **Ohne root, ohne Shell.** Laufzeit ist `gcr.io/distroless/java21-debian12:nonroot` (Java 21, keine Shell, kein Paketmanager), Benutzer uid 65532. Das Image läuft mit schreibgeschütztem Root-Dateisystem, ohne Capabilities und mit `no-new-privileges`.
- **Einstellungen aus Umgebungsvariablen.** Ein kleiner Einstiegspunkt in Java prüft beim Start alle Einstellungen, schreibt sie in die Konfiguration von Jameica, legt bei MariaDB das Schema des Herstellers an und startet Jameica ohne Rückfragen. Passwörter kommen nur aus Dateien.
- **`linux/amd64` und `linux/arm64`**, jede Plattform nativ gebaut und getestet.
- **SBOM und Provenance.** Zu jedem veröffentlichten Image gibt es eine Stückliste (SBOM) und eine signierte Herkunftsangabe, die `gh attestation verify` prüft.

## Schnellstart

Du brauchst Docker auf `linux/amd64` oder `linux/arm64`.

1. Lege das Master-Passwort in eine Datei. Es verschlüsselt Schlüssel, Wallet und Datenbank und ist zugleich das Passwort der Weboberfläche und aller Schnittstellen. Geht es verloren, ist die Einrichtung der Bankzugänge verloren.

   ```bash
   mkdir -m 0700 secrets
   openssl rand -base64 32 > secrets/master-password
   chmod 0444 secrets/master-password
   ```

2. Starte den Server mit der eingebetteten Datenbank H2:

   ```bash
   docker run -d --name hibiscus \
     --read-only --tmpfs /tmp:size=64m --cap-drop ALL --security-opt no-new-privileges \
     -e HIBISCUS_CERT_HOSTNAME=hibiscus.example.com \
     -v hibiscus:/data -v "$PWD/secrets:/run/secrets/hibiscus:ro" \
     -p 127.0.0.1:8080:8080 \
     ghcr.io/encircle360-oss/hibiscus-server:2
   docker logs -f hibiscus
   ```

   Nach wenigen Sekunden meldet der Einstiegspunkt `all plugins initialised`.

3. Öffne `https://hibiscus.example.com:8080/webadmin/` (der Name muss auf den Rechner zeigen, etwa über `/etc/hosts`) oder `https://localhost:8080/webadmin/`. Der Browser warnt vor dem selbst erzeugten Zertifikat; prüfe seinen Fingerabdruck, bevor du ihm vertraust, siehe [Betrieb](docs/betrieb.md#dem-zertifikat-vertrauen). Benutzername beliebig, etwa `admin`, Passwort das Master-Passwort.

Für den Dauerbetrieb mit MariaDB gibt es [examples/compose.yaml](examples/compose.yaml):

```bash
mkdir hibiscus && cd hibiscus
curl -fsSLO https://raw.githubusercontent.com/encircle360-oss/hibiscus-server-image/main/examples/compose.yaml
mkdir -m 0700 secrets
for f in master-password db-password db-root-password; do openssl rand -base64 32 > "secrets/$f"; done
chmod 0444 secrets/*
docker compose up -d
docker compose logs -f hibiscus
```

Ersetze vorher `hibiscus.example.com` in `compose.yaml` durch den Namen, unter dem der Server erreichbar ist. Beim ersten Start legt der Einstiegspunkt die 25 Tabellen von Hibiscus in der leeren Datenbank an. Kubernetes, Reverse Proxy, Sicherungen und Updates beschreibt [Betrieb](docs/betrieb.md).

## Einstellungen

Alle Einstellungen sind Umgebungsvariablen. Der Einstiegspunkt prüft jede beim Start und bricht mit dem Exit-Code 2 und einer Meldung ab, wenn eine nicht passt. Er schreibt sie bei jedem Start in die Dateien unter `/data/cfg`, nur lesbar für den Eigentümer (0600), und lässt alle anderen Schlüssel dieser Dateien stehen.

| Variable | Vorgabe | Wirkung |
|---|---|---|
| `TZ` | `Europe/Berlin` | Zeitzone der JVM; das Zeitfenster des Schedulers und das Log richten sich danach. |
| `HIBISCUS_WORKDIR` | `/data` | Benutzerverzeichnis von Jameica. |
| `HIBISCUS_MASTER_PASSWORD_FILE` | `/run/secrets/hibiscus/master-password` | Datei mit dem Master-Passwort in der ersten Zeile. Pflicht bei jedem Start. |
| `HIBISCUS_DB_HOST` | leer | Leer: eingebettete H2-Datenbank im Volume. Gesetzt: MariaDB auf diesem Host (Name, IPv4 oder IPv6 in eckigen Klammern). Ein Wechsel auf einem bestehenden Volume übernimmt keine Daten. |
| `HIBISCUS_DB_PORT` | `3306` | Port von MariaDB. |
| `HIBISCUS_DB_NAME` | `hibiscus` | Name der Datenbank. |
| `HIBISCUS_DB_USER` | `hibiscus` | Benutzer der Datenbank. |
| `HIBISCUS_DB_PASSWORD_FILE` | `/run/secrets/hibiscus/db-password` | Datei mit dem Passwort der Datenbank in der ersten Zeile; nur mit `HIBISCUS_DB_HOST`. |
| `HIBISCUS_DB_JDBC_PARAMS` | `useUnicode=Yes&characterEncoding=ISO8859_1` | Parameter der JDBC-URL (Vorgabe des Herstellers). `user` und `password` sind hier nicht erlaubt. |
| `HIBISCUS_DB_ENCRYPT_PASSWORD` | `true` | Hibiscus ersetzt das Passwort in den Einstellungen gleich nach dem Start durch einen mit dem Schlüssel von Jameica verschlüsselten Wert. |
| `HIBISCUS_DB_INIT_SCHEMA` | `true` | Legt in einer leeren Datenbank das Schema des Herstellers an. Eine Datenbank mit fremden Tabellen wird nie angefasst. |
| `HIBISCUS_DB_ALLOW_CHARSET` | `false` | Erlaubt das Anlegen des Schemas in einer Datenbank, deren Zeichensatz nicht `utf8`/`utf8mb3` ist. |
| `HIBISCUS_DB_WAIT_SECONDS` | `60` | So lange wartet der Start auf die Datenbank. |
| `HIBISCUS_SCHEDULER_INTERVAL_MINUTES` | `360` | Abstand der automatischen Abrufe. Werte unter 360 (mehr als vier Abrufe am Tag, wovon der Hersteller abrät) lehnt der Start ab. |
| `HIBISCUS_SCHEDULER_ALLOW_SHORT_INTERVAL` | `false` | Erlaubt einen Abstand unter 360 Minuten. |
| `HIBISCUS_SCHEDULER_EXCLUDE_FROM` / `_TO` | `23` / `5` | Keine automatischen Abrufe von dieser bis zu jener Stunde (Vorgabe des Herstellers). |
| `HIBISCUS_SCHEDULER_EXCLUDE_DAYS` | leer | Tage ohne Abruf, etwa `SAT,SUN`. |
| `HIBISCUS_SCHEDULER_STOP_ON_ERROR` | `true` | Nach einem fehlgeschlagenen Abruf hält der Scheduler an, bis er in der Weboberfläche oder durch einen Neustart wieder läuft. |
| `HIBISCUS_NOTIFY_URL` | leer | Adresse, an die Hibiscus nach jedem Saldo-Abruf die Daten des Kontos schickt, siehe [Bekannte Grenzen](#bekannte-grenzen). |
| `HIBISCUS_TAN_DECOUPLED_WAIT_SECONDS` | `120` | Wartezeit auf die Freigabe in einer TAN-App. |
| `HIBISCUS_XMLRPC_SERVICES` | `konto,umsatz` | Freigegebene Dienste von XML-RPC: `address`, `konto`, `umsatz`, `sepaueberweisung`, `sepalastschrift`, `sepasammellastschrift`, `sepasammelueberweisung` und weitere Namen aus der Vorgabe des Herstellers; `none` gibt keinen frei. Der Hersteller gibt alle frei. |
| `HIBISCUS_XMLRPC_ECHO` | `false` | Gibt den Testdienst `echo` frei. |
| `HIBISCUS_HTTP_PORT` | `8080` | Port der Weboberfläche und der Schnittstellen (1024 bis 65535). |
| `HIBISCUS_HTTP_ADDRESS` | leer (alle) | Adresse, an die der Server sich bindet. |
| `HIBISCUS_HTTP_TLS` | `true` | TLS mit dem selbst erzeugten Zertifikat. Ohne TLS startet das Image nur, wenn `HIBISCUS_HTTP_ADDRESS` eine Loopback-Adresse ist oder `HIBISCUS_HTTP_ALLOW_PLAINTEXT=true` gesetzt ist. Die Anmeldung ist immer an. |
| `HIBISCUS_HTTP_ALLOW_PLAINTEXT` | `false` | Erlaubt HTTP ohne TLS auf einer Adresse im Netz, etwa hinter einem Service Mesh mit mTLS. |
| `HIBISCUS_CERT_HOSTNAME` | leer | Name im Zertifikat (CN). Wirkt nur beim allerersten Start; ohne ihn nimmt Jameica den Hostnamen des Containers. Immer setzen. |
| `HIBISCUS_LOG_LEVEL` | leer (`INFO`) | Level des Logs von Jameica: `DEBUG`, `INFO`, `WARN`, `ERROR`. |
| `HIBISCUS_LOG_STDOUT_LEVEL` | `WARN` | Ab welchem Level das Log von Jameica auch auf stdout geht: `OFF`, `ERROR`, `WARN`, `INFO`, `DEBUG`. Vollständig steht es immer in `/data/jameica.log`. |
| `HIBISCUS_BACKUP_ON_SHUTDOWN` | `true` | Jameica sichert das Benutzerverzeichnis bei jedem sauberen Ende nach `/data/jameica-backup-*.zip`. |
| `HIBISCUS_BACKUP_COUNT` | `10` | Anzahl der Sicherungen, die bleiben. |
| `HIBISCUS_EXIT_ON_PLUGIN_ERROR` | `true` | Beendet den Container mit Exit-Code 3, wenn ein Plugin nicht startet, statt halb zu laufen. |
| `JDK_JAVA_OPTIONS` | `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError` | Optionen der JVM. |

Immer gesetzt und nicht einstellbar: keine Suche nach Updates (`update.check=false`), keine Freigabe von Diensten über RMI und keine Suche im Netz nach anderen Instanzen.

**Dateien und Ports.** Das Volume `/data` hält alles, was zählt: Schlüssel und Zertifikat (`cfg/jameica.keystore`), Einstellungen, Wallet mit den PINs, Schlüsseldateien der Bankzugänge, die H2-Datenbank, Log und Sicherungen. Es gehört genau einer Instanz und ist so schützenswert wie das Master-Passwort. `/tmp` muss beschreibbar sein (64 MiB genügen). Port 8080 trägt Weboberfläche (`/webadmin/`, `/hibiscus/`), XML-RPC (`/xmlrpc/`), SOAP (`/soap/`), REST (`/webadmin/rest/…`) und Sensoren (`/sensors/`), alles mit HTTP Basic Auth und dem Master-Passwort.

**Exit-Codes:** 2 Einstellung falsch (Meldung auf stderr), 3 ein Plugin startet nicht, 1 Start von Jameica fehlgeschlagen, etwa mit falschem Master-Passwort, 143 nach SIGTERM (sauberes Ende).

## Sicherheit in Kürze

- **Das Master-Passwort ist der einzige Schlüssel.** Es öffnet Weboberfläche, REST, SOAP und XML-RPC gleichermaßen, auch das Anlegen von Überweisungen. `HIBISCUS_XMLRPC_SERVICES` beschränkt nur XML-RPC. Wer das Passwort hat, kann alles, was Hibiscus kann.
- **Port 8080 gehört in kein offenes Netz.** Nur die Clients, die ihn brauchen, sollen ihn erreichen: NetworkPolicy, Firewall oder ein Reverse Proxy, der nur die nötigen Pfade durchlässt.
- **Das Volume ist der Tresor.** Es hält die Schlüssel der Bankzugänge, die Sicherungen darin ebenso. Nie zwischen Instanzen teilen.
- **Das Log auf stdout** enthält nur Warnungen und Fehler, damit die Einzelheiten der Bankdialoge nicht in der Log-Sammlung landen.

Mehr in [Sicherheit](docs/sicherheit.md): das Modell mit einem Passwort, Netzwerk-Isolation, die Prüfung der Signatur und warum die Vorgaben so sind, wie sie sind.

## Bekannte Grenzen

- **Ein Passwort für alles.** Hibiscus Server kennt keine Benutzer und keine Rechte; Benutzername egal, Passwort das Master-Passwort.
- **Jetty 9.4.38 von 2021.** Der Webserver steckt im Paket des Herstellers und lässt sich nur mit einem neuen Paket des Herstellers erneuern. Seine Community-Unterstützung ist beendet.
- **Zertifikat ohne subjectAlternativeName.** Jameica erzeugt beim ersten Start ein selbst signiertes Zertifikat, dessen CN danach feststeht. Clients, die nur SAN prüfen (Go seit 1.15, aktuelle Browser für die Namensprüfung), lehnen es ab; dann das Zertifikat pinnen oder TLS davor beenden.
- **`notify.url` prüft kein Zertifikat.** Während Hibiscus Server die Adresse aus `HIBISCUS_NOTIFY_URL` über HTTPS aufruft, akzeptiert es jedes Zertifikat. Nur in ein vertrauenswürdiges Netz schicken.
- **Bekannte Lücken in Bibliotheken des Pakets.** Der Scan mit trivy meldet für das Paket 2.12.4 rund 50 Lücken der Stufen HIGH und CRITICAL, fast alle in Bibliotheken, die der Hersteller mitliefert: Jetty 9.4.38, Bouncy Castle 1.72, Apache CXF 3.2.6 (SOAP), JSON-java, HttpComponents Core 5.2 und der PostgreSQL-Treiber 42.5.4. Ob eine davon im Betrieb von Hibiscus Server ausnutzbar ist, hat dieses Projekt nicht geprüft. Erneuern kann sie nur der Hersteller; das Image tauscht keine Bibliothek aus. Die Befunde stehen unter *Security > Code scanning* dieses Repositories.
- **Kein Bankzugang getestet.** Abruf von Umsätzen, TAN-Verfahren und Wallet hat kein Test je berührt.
- **Kein HEALTHCHECK im Image.** Ohne Shell und mit Anmeldung auf jedem Pfad bleibt nur die Prüfung des TCP-Ports, siehe [Betrieb](docs/betrieb.md#kubernetes).

## Was getestet ist und was nicht

Vor jeder Veröffentlichung, für jede Plattform nativ, startet die CI das Image mit schreibgeschütztem Root-Dateisystem, ohne Capabilities und mit `no-new-privileges` und prüft ([tests/image/run.sh](tests/image/run.sh)):

- Start mit H2: alle Plugins laufen, jeder Prozess hat uid 65532, das Zertifikat trägt `HIBISCUS_CERT_HOSTNAME`.
- Anmeldung: `/hibiscus/`, `/webadmin/`, `/xmlrpc/`, `/soap/` und `/sensors/` antworten ohne und mit falschem Passwort 401, mit dem Master-Passwort 200.
- XML-RPC: `hibiscus.xmlrpc.konto.find` liefert eine (leere) Liste, ein nicht freigegebener Dienst einen Fehler, nach der Freigabe eine Liste.
- Das Benutzerverzeichnis gehört uid 65532 mit Modus 0700, die Einstellungen des Einstiegspunkts haben 0600.
- Neustart auf demselben Volume ohne Rückfrage und mit demselben Zertifikat; ein falsches Master-Passwort beendet den Start.
- Ablehnungen: fehlende Passwortdatei, zu kurzer Abstand des Schedulers, unbekannter Dienst von XML-RPC, HTTP ohne TLS.
- MariaDB 11.8 mit `utf8mb3`: das Schema des Herstellers mit 25 Tabellen entsteht, XML-RPC antwortet, das Passwort der Datenbank steht danach nicht mehr im Klartext in den Einstellungen; eine Datenbank mit `utf8mb4` wird abgelehnt und bleibt leer.
- Kein Passwort erscheint in einem Log.

Der Einstiegspunkt hat dazu Unit-Tests mit voller Zeilen- und Zweigabdeckung ([tests/launcher](tests/launcher)), die Werkzeuge ebenso ([tests](tests)).

**Nicht getestet** ist alles mit einer Bank: Einrichtung eines Bankzugangs, Abruf von Salden und Umsätzen, TAN-Verfahren, Überweisungen, das Wallet mit PINs, der Aufruf von `notify.url`, ebenso SOAP- und REST-Aufrufe über die Anmeldung hinaus, ausgehender Netzverkehr, Updates auf ein neues Paket des Herstellers mit bestehenden Daten und der Betrieb über Wochen.

## Versionen und Updates

Releases heißen `v<Version von Hibiscus Server>-<Revision>`, etwa `v2.12.4-1`. Daraus entstehen die Image-Tags `2.12.4-1` (genau dieses Release), `2.12.4` (neueste Revision dazu), `2` und `latest`; bewegliche Tags gehen nur vorwärts. **Pinne im Betrieb das volle Tag** oder den Digest. Hibiscus passt sein Datenbankschema beim ersten Start einer neuen Version selbst an; vorher Volume und Datenbank sichern. Einzelheiten in [Versionierung](docs/versionierung.md).

## Eigener Build

```bash
git clone https://github.com/encircle360-oss/hibiscus-server-image.git
cd hibiscus-server-image
./build.sh                                     # builds hibiscus-server:local for linux/amd64
./tests/image/run.sh hibiscus-server:local     # starts it the way the CI does
```

Der Build lädt `hibiscus-server-2.12.4.zip` von willuhn.de und bricht ab, wenn die SHA-256-Summe nicht der im `Dockerfile` entspricht. **Lokal prüft nur diese Summe das Paket**, die PGP-Signatur prüft der Workflow Signature in der CI. Wer die Signatur selbst prüfen will, nimmt den Schlüssel mit dem Fingerabdruck `7314 FBDE 7D38 EE56 10D2 91B6 5A8E D9CF C0DB 6C70` und `scripts/verify-package.sh`, siehe [Sicherheit](docs/sicherheit.md#signatur-des-herstellers).

## Dokumentation

- [Architektur](docs/architektur.md): Stufen des `Dockerfile`, Inhalt des Images, Einstiegspunkt, Ablauf eines Starts
- [Betrieb](docs/betrieb.md): docker, docker compose, Kubernetes mit Probes und Ressourcen, MariaDB, Zertifikat, Sicherungen, Updates
- [Sicherheit](docs/sicherheit.md): das Modell mit einem Passwort, Netzwerk-Isolation, Signatur, Vorgaben und ihre Gründe
- [Versionierung](docs/versionierung.md): Tags, Revisionen, Release-Prozess, neue Pakete des Herstellers
- [Entwicklung](docs/entwicklung.md): Aufbau, Tests, CI, Pins
- [Quelltext](SOURCES.md): wo der Quelltext der Software im Image liegt
- [Changelog](CHANGELOG.md), [Mitwirken](CONTRIBUTING.md), [Hinweise für Coding-Agenten](AGENTS.md), [Sicherheitslücken melden](SECURITY.md)

## Mitwirken und Support

Beiträge sind willkommen: Fehler melden, den Betrieb in eigenen Umgebungen testen und zurückmelden, Dokumentation und Beispiele verbessern, Pull Requests stellen. Wie das abläuft, steht in [CONTRIBUTING.md](CONTRIBUTING.md).

- **Fragen zum Image:** [Discussions](https://github.com/encircle360-oss/hibiscus-server-image/discussions), Fehler in die [Issues](https://github.com/encircle360-oss/hibiscus-server-image/issues), Austausch auch auf [Matrix](https://matrix.to/#/#oss:encircle360.com).
- **Fragen zu Hibiscus selbst:** Forum und Wiki des Herstellers über seine [Support-Seite](https://www.willuhn.de/products/hibiscus-server/support.php). Bitte dort keine Fehler dieses Images melden.
- **Professionelle Unterstützung** für Einrichtung und Betrieb des Images: **hello@encircle360.com**

## Haftungsausschluss

hibiscus-server-image wird „wie besehen“ („AS IS“) und ohne jede Gewährleistung bereitgestellt, weder ausdrücklich noch stillschweigend, einschließlich, aber nicht beschränkt auf die Gewährleistung der Marktgängigkeit und der Eignung für einen bestimmten Zweck (Abschnitte 11 und 12 der [GNU General Public License](LICENSE)). Mit der Nutzung erkennst du an:

- Du nutzt das Image auf eigenes Risiko.
- Du bist als Betreiber für die Sicherheit der Bankzugangsdaten, des Master-Passworts, des Volumes und seiner Sicherungen und der ganzen Installation verantwortlich, ebenso für Netzwerk, Updates und Datenschutz.
- Ein grüner Test zeigt, dass der Server startet, die Anmeldung verlangt und die Schnittstellen antworten, nicht, dass Abrufe, Buchungen oder Aufträge an eine Bank richtig ausgeführt werden.
- Die Maintainer, die encircle360 GmbH und alle Mitwirkenden haften nicht für Schäden oder Verluste jeder Art, die aus der Nutzung oder der Unmöglichkeit der Nutzung entstehen, insbesondere nicht für fehlerhafte oder unberechtigte Zahlungsaufträge, verlorene Zugangsdaten oder Daten und Ausfälle. Das gilt, soweit ein Haftungsausschluss gesetzlich zulässig ist.
- Hibiscus Server und alle Bestandteile des Pakets stammen von ihrem Hersteller und anderen Projekten und stehen unter deren Lizenzen und Gewährleistungsausschlüssen.

### Namen

Hibiscus, Hibiscus Server und Jameica sind Software und Namen von Olaf Willuhn. hibiscus-server-image ist ein unabhängiges Projekt der encircle360 GmbH und der Mitwirkenden, steht mit ihm nicht in Verbindung und wird von ihm weder unterstützt noch empfohlen. Die Namen beschreiben hier nur, welche Software das Image enthält.

## Lizenz

Wie Hibiscus Server steht dieses Repository unter der **GNU General Public License Version 2 oder (nach deiner Wahl) jeder späteren Version** mit der Ausnahme für das Linken, die auch Jameica und Hibiscus gewähren; SPDX: `GPL-2.0-or-later WITH Classpath-exception-2.0`. Den Text der GPL enthält [LICENSE](LICENSE), wörtlich aus dem Paket des Herstellers. Die Ausnahme lautet wie in der Datei `LICENSE` des Pakets:

> Linking this program statically or dynamically with other modules is making a combined work based on this program. Thus, the terms and conditions of the GNU General Public License cover the whole combination.
>
> As a special exception, the copyright holders of this program give you permission to link this program with independent modules to produce an executable, regardless of the license terms of these independent modules, and to copy and distribute the resulting executable under terms of your choice, provided that you also meet, for each linked independent module, the terms and conditions of the license of that module. An independent module is a module which is not derived from or based on this program. If you modify this program, you may extend this exception to your version of the program, but you are not obligated to do so. If you do not wish to do so, delete this exception statement from your version.

Das Image enthält außerdem die Bibliotheken des Pakets und des Basis-Images unter ihren eigenen Lizenzen. Wo der Quelltext von allem liegt, steht in [SOURCES.md](SOURCES.md), die auch im Image unter `/usr/share/doc/hibiscus-server-image/` liegt.

## Maintainer

hibiscus-server-image wird von der **[encircle360 GmbH](https://encircle360.com/?ref=github-hibiscus-server-image)** entwickelt und gepflegt, zusammen mit allen Mitwirkenden, ohne Gewähr wie unter [Haftungsausschluss](#haftungsausschluss) beschrieben. Danke an Olaf Willuhn für Hibiscus und Jameica.

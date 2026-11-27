# Betrieb

Diese Seite beschreibt den Dauerbetrieb: docker compose, Kubernetes, MariaDB, das Zertifikat, Sicherungen, Updates und was im Log steht. Die Einstellungen stehen im [README](../README.md#einstellungen), die Gründe hinter den Vorgaben in [Sicherheit](sicherheit.md).

## Grundregeln

- **Eine Instanz je Volume.** `/data` hält die Schlüssel der Bankzugänge. Nie zwei Container auf dasselbe Volume, nie ein Volume in eine andere Umgebung kopieren. Jameica hält eine Sperre auf `/data/jameica.lock`, eine zweite Instanz bricht ab, aber erst, nachdem der Einstiegspunkt die Einstellungen geschrieben hat.
- **Volles Tag pinnen**, etwa `2.12.4-1`, oder den Digest, siehe [Versionierung](versionierung.md#wie-nutzer-pinnen).
- **`HIBISCUS_CERT_HOSTNAME` immer setzen.** Ohne ihn nimmt Jameica den Hostnamen des Containers, in Kubernetes den Namen des Pods. Kann Jameica den eigenen Namen nicht auflösen, will es nachfragen, was ohne Terminal scheitert; mit `--network none` veröffentlichte es dann die SOAP-Dienste nicht.
- **`/tmp` beschreibbar**, als tmpfs oder `emptyDir`; Jetty legt dort seine Arbeitsverzeichnisse an. 64 MiB genügten.

## docker compose

[examples/compose.yaml](../examples/compose.yaml) startet Hibiscus Server mit MariaDB 11.8, Passwörtern als Compose-Secrets, schreibgeschütztem Root-Dateisystem, ohne Capabilities und mit dem Port nur auf `127.0.0.1`. Die Schritte stehen im [README](../README.md#schnellstart).

## MariaDB

Der Hersteller verlangt den Zeichensatz `utf8`, nicht `utf8mb4`. In MariaDB 11.8 heißt `utf8` `utf8mb3`:

```sql
CREATE DATABASE hibiscus CHARACTER SET utf8mb3 COLLATE utf8mb3_general_ci;
CREATE USER 'hibiscus'@'%' IDENTIFIED BY 'CHANGE_ME';
GRANT ALL ON hibiscus.* TO 'hibiscus'@'%';
```

Mit dem offiziellen Image von MariaDB geht dasselbe über `MARIADB_DATABASE=hibiscus` und die Optionen `--character-set-server=utf8mb3 --collation-server=utf8mb3_general_ci`, wie im Beispiel. Bei jedem Start warnt der Einstiegspunkt, wenn die Datenbank einen anderen Zeichensatz hat, und legt das Schema darin nicht an.

Die leere Datenbank bekommt beim ersten Start das Schema des Herstellers (25 Tabellen). Das Passwort der Datenbank schreibt der Einstiegspunkt bei jedem Start in die Einstellungen, Hibiscus ersetzt es gleich danach durch einen mit dem Schlüssel von Jameica verschlüsselten Wert. Für einen Moment steht es also im Klartext in einer Datei mit Modus 0600 im Volume.

Die Datenbank braucht eine eigene Sicherung, etwa mit `mariadb-dump`; die Sicherungen von Jameica enthalten sie nicht.

## Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hibiscus
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app.kubernetes.io/name: hibiscus
  template:
    metadata:
      labels:
        app.kubernetes.io/name: hibiscus
    spec:
      automountServiceAccountToken: false
      securityContext:
        runAsNonRoot: true
        runAsUser: 65532
        runAsGroup: 65532
        fsGroup: 65532
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: hibiscus
          image: ghcr.io/encircle360-oss/hibiscus-server:2.12.4-1
          env:
            - name: HIBISCUS_CERT_HOSTNAME
              value: hibiscus.example.svc
            - name: HIBISCUS_DB_HOST
              value: mariadb.example.svc
          ports:
            - name: https
              containerPort: 8080
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          startupProbe:
            tcpSocket:
              port: https
            periodSeconds: 2
            failureThreshold: 60
          readinessProbe:
            tcpSocket:
              port: https
            periodSeconds: 10
          livenessProbe:
            tcpSocket:
              port: https
            periodSeconds: 30
            timeoutSeconds: 5
            failureThreshold: 4
          resources:
            requests:
              cpu: 100m
              memory: 384Mi
            limits:
              memory: 768Mi
          volumeMounts:
            - name: data
              mountPath: /data
            - name: tmp
              mountPath: /tmp
            - name: secrets
              mountPath: /run/secrets/hibiscus
              readOnly: true
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: hibiscus-data
        - name: tmp
          emptyDir:
            medium: Memory
            sizeLimit: 64Mi
        - name: secrets
          secret:
            secretName: hibiscus
            defaultMode: 0440
            items:
              - key: master-password
                path: master-password
              - key: db-password
                path: db-password
```

- **Eine Replik, `Recreate`.** Zwei Pods auf demselben Volume darf es auch während eines Updates nicht geben.
- **Probes über TCP.** Jeder Pfad verlangt das Master-Passwort; eine `httpGet`-Probe bräuchte es in der Pod-Spezifikation. Startet ein Plugin nicht, beendet sich der Container (Exit-Code 3), was Neustart-Richtlinie und Probes melden.
- **Ressourcen.** Gemessen ohne Bankzugang, mit H2 und mit MariaDB: Start in rund 3 Sekunden bis zum offenen Port, 210 bis 255 MiB Speicher nach dem Start, im Leerlauf unter 1 % CPU. Der Speicher wächst mit Konten und Umsätzen; nach dem Einrichten der Bankzugänge beobachten.
- **`fsGroup`** macht das Volume für die Gruppe 65532 beschreibbar; der Einstiegspunkt warnt dann, dass `/data` für die Gruppe offen ist. Wo der Treiber des Volumes es erlaubt, genügt `fsGroupChangePolicy: OnRootMismatch` und ein Volume, das uid 65532 gehört.
- **NetworkPolicy.** Eingehend nur von den Clients, die den Server brauchen, ausgehend nur DNS, die Datenbank und die Server der Banken auf Port 443, siehe [Sicherheit](sicherheit.md#netzwerk-isolation).

## Dem Zertifikat vertrauen

Jameica erzeugt beim ersten Start ein selbst signiertes Zertifikat (RSA 2048, SHA256withRSA, 20 Jahre gültig, `CN=<HIBISCUS_CERT_HOSTNAME>, O=Jameica Certificate`) und hält es in `/data/cfg/jameica.keystore`, geschützt mit dem Master-Passwort. Es bleibt, solange das Volume bleibt. Es hat **keinen subjectAlternativeName**.

Zertifikat holen und seinen Fingerabdruck mit dem vergleichen, den die Weboberfläche zeigt, bevor ein Client ihm vertraut:

```bash
keytool -printcert -sslserver hibiscus.example.com:8080 -rfc > hibiscus.pem
keytool -printcert -file hibiscus.pem | grep SHA256
curl --cacert hibiscus.pem https://hibiscus.example.com:8080/xmlrpc/ ...
```

Clients, die nur den subjectAlternativeName prüfen, lehnen das Zertifikat ab. Dann das Zertifikat oder seinen Fingerabdruck im Client pinnen oder TLS in einem Reverse Proxy beenden, der Hibiscus Server mit genau diesem Zertifikat anspricht.

## Reverse Proxy

Ein Reverse Proxy davor kann ein Zertifikat mit subjectAlternativeName liefern und nur die Pfade durchlassen, die Clients brauchen, etwa `/xmlrpc/` für bankbote. Zum Server spricht er HTTPS und vertraut genau dessen Zertifikat. Soll er ohne TLS zum Server sprechen, gehören beide in denselben Pod, mit `HIBISCUS_HTTP_TLS=false` und `HIBISCUS_HTTP_ADDRESS=127.0.0.1`; auf jeder anderen Adresse lehnt das Image HTTP ohne TLS ab, außer mit `HIBISCUS_HTTP_ALLOW_PLAINTEXT=true`.

## Sicherungen

- **Jameica** zippt bei jedem sauberen Ende das Benutzerverzeichnis (Schlüssel, Einstellungen, H2-Datenbank, Schlüsseldateien der Bankzugänge) nach `/data/jameica-backup-*.zip` und behält `HIBISCUS_BACKUP_COUNT` davon. Diese Dateien sind so schützenswert wie das Volume und liegen im selben Volume, ersetzen also keine Sicherung außerhalb.
- **Das Volume** sichert man im gestoppten Zustand oder als Snapshot. Mit H2 ist die Datenbank darin.
- **MariaDB** braucht eine eigene Sicherung.
- **Das Master-Passwort** gehört getrennt von den Sicherungen verwahrt. Ohne es lassen sich Keystore, Wallet und H2-Datenbank nicht öffnen.

## Updates

1. Release-Notes dieses Repositories und den [Changelog des Herstellers](https://www.willuhn.de/products/hibiscus-server/changelog.php) lesen.
2. Volume und Datenbank sichern.
3. Das neue volle Tag eintragen und den Container neu erzeugen.
4. Hibiscus passt sein Datenbankschema beim ersten Start einer neuen Version selbst an (`de.willuhn.sql.version.Updater` im Log). Zurück geht es nur mit der Sicherung von vorher.

## Was im Log steht

Auf stdout stehen die Meldungen des Einstiegspunkts (`[hibiscus-launcher] …`) und das Log von Jameica ab `HIBISCUS_LOG_STDOUT_LEVEL`, Vorgabe `WARN`. Das vollständige Log schreibt Jameica nach `/data/jameica.log`, gedreht bei 1 MB. Erwartete Meldungen:

- `NOTE: Picked up JDK_JAVA_OPTIONS: …` von der JVM bei jedem Start.
- `no archive server found`: Das Archiv für Anhänge von `jameica.sensors` ist nicht eingerichtet.
- `detected unclean shutdown from previous run` nach einem Kill; die verwaiste `jameica.lock` erkennt der nächste Start.
- `master password given via commandline` oder `via file`, auch wenn das Passwort aus der Datei kommt; das Passwort selbst steht nie im Log.

Mit `HIBISCUS_LOG_STDOUT_LEVEL=INFO` stehen die Informationen über die Bankdialoge auch auf stdout, siehe [Sicherheit](sicherheit.md#das-log-auf-stdout).

## Update-Prüfung und ausgehender Verkehr

Die Suche nach Updates von Jameica (`UpdateService`) ist im Paket aus und vom Einstiegspunkt erzwungen aus. Die Liste der Plugin-Repositories in `de.willuhn.jameica.services.RepositoryService.properties` nennt auch Adressen Dritter; sie wird nur benutzt, wenn jemand Plugins über die Weboberfläche installiert. Ohne Bankzugang startete der Server mit allen Plugins auch ganz ohne Netz (`--network none`); `jameica.sensors` meldet MQTT und Archiv als abgeschaltet. Den ausgehenden Verkehr mit Bankzugang hat niemand aufgezeichnet.

Ein Bankzertifikat, dem Java nicht vertraut, nimmt Hibiscus Server ohne Rückfrage nicht an: Es legt es unter `/data/untrusted/` ab, und die Verbindung scheitert. Nach Prüfung lässt es sich in der Weboberfläche (`/webadmin/`) importieren.

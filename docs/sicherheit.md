# Sicherheit

Diese Seite beschreibt, wovor das Image schützt und wovor nicht: das Modell mit einem einzigen Passwort, die Isolation im Netz, die Prüfung des Pakets, die Vorgaben und ihre Gründe. Sicherheitslücken meldest du wie in [SECURITY.md](../SECURITY.md) beschrieben.

## Ein Passwort für alles

Hibiscus Server kennt keine Benutzer und keine Rechte. Das Master-Passwort von Jameica

- verschlüsselt den Keystore mit dem Schlüssel und dem Zertifikat des Servers, das Wallet mit den PINs und die H2-Datenbank,
- ist das Passwort der HTTP-Anmeldung für die Weboberfläche (`/webadmin/`, `/hibiscus/`), REST (`/webadmin/rest/…`, auch zum Anlegen von Überweisungen), SOAP (`/soap/`, auch SEPA-Überweisungen und -Lastschriften), XML-RPC (`/xmlrpc/`) und die Sensoren (`/sensors/`); der Benutzername wird nicht geprüft.

Wer das Passwort hat, kann also alles, was Hibiscus kann, auch Zahlungsaufträge erteilen, soweit die Bank sie ohne TAN annimmt oder eine TAN-Freigabe erfolgt. `HIBISCUS_XMLRPC_SERVICES` beschränkt nur XML-RPC; REST, SOAP und Weboberfläche bleiben jedem mit dem Passwort offen. Daraus folgt:

- Das Master-Passwort kommt nur aus einer Datei (`HIBISCUS_MASTER_PASSWORD_FILE`), nie aus einer Umgebungsvariablen, die in `docker inspect` und der Pod-Spezifikation stünde.
- Ein Client, der nur Umsätze lesen soll, bekommt trotzdem das volle Passwort. Wer weniger geben will, setzt einen Reverse Proxy davor, der nur die nötigen Pfade und Methoden durchlässt und eigene Zugangsdaten verlangt.
- Das Passwort verschlüsselt Keystore, Wallet und Datenbank. Die Desktop-Oberfläche von Jameica hat einen Menüpunkt zum Ändern; ob und wie es sich im Servermodus ändern lässt, hat dieses Projekt nicht geprüft. Die Datei mit dem Passwort allein zu ändern, öffnet das bestehende Volume nicht mehr.

## Netzwerk-Isolation

Port 8080 gehört in kein offenes Netz. Der eingebaute Webserver Jetty 9.4.38 stammt von 2021 und lässt sich nur mit einem neuen Paket des Herstellers erneuern.

- **Eingehend** nur die Clients, die den Server brauchen, etwa bankbote, und die Menschen, die die Weboberfläche nutzen, am besten über ein VPN oder einen Reverse Proxy mit eigener Anmeldung. In Kubernetes eine NetworkPolicy, die nur diese Pods zulässt.
- **Ausgehend** nur DNS, die Datenbank und die FinTS-Server der Banken auf Port 443. Die Suche nach Updates ist aus; Plugins über die Weboberfläche zu installieren, sollte niemand.
- **Ohne TLS** geht das Passwort im Klartext über das Netz. Das Image lehnt `HIBISCUS_HTTP_TLS=false` deshalb ab, außer der Server bindet sich an eine Loopback-Adresse (ein TLS-Proxy im selben Pod) oder `HIBISCUS_HTTP_ALLOW_PLAINTEXT=true` sagt ausdrücklich, dass eine andere Schicht verschlüsselt, etwa ein Service Mesh mit mTLS.
- **`notify.url`:** Während Hibiscus Server die Adresse aus `HIBISCUS_NOTIFY_URL` aufruft, nimmt es jedes Zertifikat an (der `TrustMessageConsumer` von Hibiscus Server antwortet in dieser Zeit für jede Prüfung „vertrauenswürdig“). Die Nachricht enthält die Daten des Kontos. Nur in ein vertrauenswürdiges Netz schicken, nie über das Internet.

## Das Volume

`/data` hält Keystore, Wallet, Schlüsseldateien der Bankzugänge, Einstellungen mit dem verschlüsselten Passwort der Datenbank, die H2-Datenbank, Log und Sicherungen. Das Image legt `/data` für uid 65532 mit Modus 0700 an, und ein neues benanntes Volume übernimmt das. Jameica selbst legt seine Dateien mit dem Standard-umask an, also mit 0644; geschützt sind sie durch das geschlossene Verzeichnis. Der Einstiegspunkt schreibt seine eigenen Dateien immer mit 0600 und warnt beim Start, wenn `/data` für Gruppe oder andere offen ist. Sicherungen des Volumes sind so schützenswert wie das Master-Passwort.

## Das Log auf stdout

Jameica schreibt sein Log immer nach `/data/jameica.log`. Auf stdout kopiert das Image **nur Warnungen und Fehler** (`HIBISCUS_LOG_STDOUT_LEVEL=WARN`).

Das Container-Log landet beim Betreiber meist in einer zentralen Log-Sammlung, die mehr Menschen lesen dürfen als das Volume, die länger aufbewahrt und die selten so geschützt ist. Auf dem Level `INFO` schreiben Hibiscus und HBCI4Java Einzelheiten der Bankdialoge, auf `DEBUG` noch mehr. Warnungen und Fehler genügen, um im Container-Log zu sehen, dass ein Abruf scheitert; die Einzelheiten stehen im Volume. Wer mehr auf stdout will, setzt `INFO` oder `DEBUG` bewusst; `OFF` schaltet die Kopie ab. Nicht geprüft ist, was Hibiscus mit einem echten Bankzugang auf welchem Level schreibt, denn kein Test hat einen Bankzugang.

Die Meldungen des Einstiegspunkts nennen nie ein Passwort und nie die Adresse aus `HIBISCUS_NOTIFY_URL`, die Zugangsdaten enthalten kann. Der Image-Test prüft für jeden Start, dass keines der erzeugten Passwörter im Log steht.

## Signatur des Herstellers

Der Hersteller signiert jedes Paket mit PGP und nennt auf seinen Download-Seiten den Fingerabdruck seines Schlüssels: `7314 FBDE 7D38 EE56 10D2 91B6 5A8E D9CF C0DB 6C70`. Den Schlüssel selbst verweist er auf keys.openpgp.org.

**Was die CI prüft.** Der Workflow Signature (`.github/workflows/signature.yml`) läuft bei jeder Änderung am `Dockerfile`, an `keys/` oder am Skript, jeden Montag und vor jedem Release. Er ruft `scripts/verify-package.sh` auf, das

1. den Schlüssel aus `keys/hibiscus-server-signing-key.asc` nimmt, wenn das Repository diese Datei hat, sonst von `https://keys.openpgp.org/vks/v1/by-fingerprint/7314FBDE7D38EE5610D291B65A8ED9CFC0DB6C70` lädt,
2. abbricht, wenn die Schlüsseldatei nicht genau einen Hauptschlüssel mit genau diesem Fingerabdruck enthält,
3. `hibiscus-server-<Version>.zip` und `.zip.asc` von willuhn.de lädt und die Signatur gegen genau diesen Schlüssel prüft, mit `gpgv` oder, wenn der Schlüssel keine User-ID hat (keys.openpgp.org gibt User-IDs nur heraus, die ihr Inhaber bestätigt hat), mit `sqv` von Sequoia,
4. abbricht, wenn die Signatur nicht gut ist oder nicht von diesem Schlüssel stammt,
5. abbricht, wenn das geprüfte Paket nicht die SHA-256-Summe `HIBISCUS_SHA256` aus dem `Dockerfile` hat.

Der Workflow Vendor release prüft ein neues Paket genauso, bevor er einen Pull Request öffnet; schlägt die Prüfung fehl, öffnet er ein Issue statt eines Pull Requests.

**Was lokal prüft.** Beim lokalen Build prüft nur die SHA-256-Summe im `Dockerfile` das Paket (`ADD --checksum`). Sie stammt aus einem Paket, dessen Signatur die CI bestätigt hat. Wer die Signatur selbst prüfen will, braucht `gpg`, `gpgv` und für einen Schlüssel ohne User-ID `sqv` und ruft `scripts/verify-package.sh` auf.

**Den Schlüssel ins Repository legen.** Ein Maintainer kann den Schlüssel ins Repository legen, damit die CI nicht vom Schlüsselserver abhängt. Die Datei hat dann Vorrang:

1. Den Schlüssel über einen zweiten Weg besorgen, etwa von keys.openpgp.org und zusätzlich aus einer Quelle des Herstellers, und vergleichen: `gpg --show-keys --with-fingerprint datei.asc` muss den Fingerabdruck `7314 FBDE 7D38 EE56 10D2 91B6 5A8E D9CF C0DB 6C70` zeigen.
2. Die Datei als `keys/hibiscus-server-signing-key.asc` (ASCII-armored, nur dieser Schlüssel) in einem Pull Request hinzufügen, mit der Herkunft im Text des Pull Requests.
3. Der Workflow Signature läuft im Pull Request mit der neuen Datei; er schlägt fehl, wenn sie einen anderen oder weitere Schlüssel enthält.

Wechselt der Hersteller seinen Schlüssel, ändert sich der Fingerabdruck in `scripts/verify-package.sh`, im README und hier, und zwar nur in einem Pull Request, der die Quelle des neuen Fingerabdrucks nennt. Ein Test prüft, dass diese drei Dateien denselben Fingerabdruck nennen.

## Die Vorgaben und ihre Gründe

| Vorgabe | Grund |
|---|---|
| uid 65532, distroless, kein Shell | Nichts braucht root; ohne Shell und Paketmanager hat ein Angreifer im Container wenig Werkzeug. |
| Schreibgeschütztes Root-Dateisystem möglich | Nur `/data` und `/tmp` brauchen Schreibrechte. |
| TLS an, ohne TLS nur auf Loopback oder mit ausdrücklichem Schalter | Das einzige Passwort soll nie unverschlüsselt durch ein Netz gehen. |
| Anmeldung immer an | Ohne Anmeldung stünden Überweisungen jedem im Netz offen. |
| XML-RPC nur `konto,umsatz` | Lesen genügt für einen Abgleich; Aufträge über XML-RPC muss man bewusst freigeben. |
| Log auf stdout ab `WARN` | Die Einzelheiten der Bankdialoge gehören nicht in die Log-Sammlung. |
| Scheduler alle 360 Minuten, kürzer nur mit Schalter | Der Hersteller rät von mehr als vier automatischen Abrufen je Konto und Tag ab. |
| Keine Suche nach Updates, kein RMI, keine Multicast-Suche | Das Image ändert sich nur mit einem neuen Tag; der Server öffnet keine weiteren Dienste. |
| Ende mit Exit-Code 3 bei einem Fehler eines Plugins | Ein halb gestarteter Server mit Weboberfläche, aber ohne Hibiscus, fiele sonst niemandem auf. |
| Passwörter nur aus Dateien, `user` und `password` nicht in `HIBISCUS_DB_JDBC_PARAMS` | Umgebungsvariablen und URLs landen in Logs und Spezifikationen; Hibiscus verschlüsselt nur den Schlüssel mit dem Passwort. |

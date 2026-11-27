# Changelog

Alle nennenswerten Änderungen an hibiscus-server-image stehen in dieser Datei. Das Format folgt [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), die Versionen dem Schema `v<Version von Hibiscus Server>-<Revision>` aus [Versionierung](docs/versionierung.md). Ein Release bekommt einen Abschnitt `## [2.12.4-1] - JJJJ-MM-TT`; der Workflow Release übernimmt ihn in die Release-Notes.

## Unveröffentlicht

### Hinzugefügt

- Image `ghcr.io/encircle360-oss/hibiscus-server` aus dem Release-Paket `hibiscus-server-2.12.4.zip` des Herstellers (Jameica 2.12.0, Hibiscus und Hibiscus Server 2.12.4), geprüft per SHA-256 beim Build und per PGP-Signatur in der CI, auf `gcr.io/distroless/java21-debian12:nonroot` mit uid 65532, für `linux/amd64` und `linux/arm64`.
- Einstiegspunkt `launcher/HibiscusLauncher.java`: prüft alle Einstellungen aus Umgebungsvariablen, schreibt sie mit Modus 0600 ins Benutzerverzeichnis, legt in einer leeren MariaDB das Schema des Herstellers an und startet Jameica ohne Rückfragen. Vorgaben: TLS an und ohne TLS nur auf Loopback oder mit `HIBISCUS_HTTP_ALLOW_PLAINTEXT=true`, XML-RPC nur `konto` und `umsatz`, Scheduler alle 360 Minuten, Log auf stdout ab `WARN`.
- Image-Test mit H2 und MariaDB 11.8, Unit-Tests des Einstiegspunkts mit voller Zeilen- und Zweigabdeckung, Werkzeuge für Tags, Release-Prüfung und neue Pakete des Herstellers.
- Workflows für Tests, Linters, Security, Signatur, Image mit SBOM und Herkunftsangabe, Release und die wöchentliche Suche nach einem neuen Paket des Herstellers.
- Dokumentation zu Architektur, Betrieb mit docker compose und Kubernetes, Sicherheit, Versionierung und Entwicklung, dazu [SOURCES.md](SOURCES.md) mit dem Weg zum Quelltext der Software im Image.

### Bekannte Grenzen

- Kein Test hat einen Bankzugang eingerichtet; Abruf, TAN-Verfahren und Wallet sind ungeprüft.
- Das Master-Passwort ist der einzige Zugang und öffnet Weboberfläche, REST, SOAP und XML-RPC gleichermaßen.
- Jetty 9.4.38 von 2021 im Paket; erneuern kann ihn nur der Hersteller.
- Das selbst erzeugte Zertifikat hat keinen subjectAlternativeName, sein CN steht nach dem ersten Start fest.
- Beim Aufruf von `notify.url` über HTTPS akzeptiert Hibiscus Server jedes Zertifikat.

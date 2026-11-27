# Sicherheit

## Unterstützte Versionen

Sicherheitskorrekturen gibt es für den Branch `main` und das jeweils neueste Release. Ältere Releases werden nicht neu gebaut; eine Korrektur kommt als neue Revision, siehe [Versionierung](docs/versionierung.md).

## Sicherheitslücke melden

Bitte **nicht** als öffentliches Issue. Sende eine Beschreibung an **oss@encircle360.com** oder nutze die private Meldefunktion von GitHub ([„Report a vulnerability“](https://github.com/encircle360-oss/hibiscus-server-image/security/advisories/new) unter *Security*).

Hilfreich sind: betroffenes Image-Tag oder Digest, Schritte zum Nachstellen, mögliche Auswirkungen und, wenn vorhanden, ein Vorschlag zur Behebung. Keine echten Passwörter, PINs, TANs, Schlüssel, Kontodaten oder Sicherungen mitschicken.

Wir bestätigen den Eingang so schnell wie möglich, in der Regel innerhalb weniger Werktage, und stimmen die Veröffentlichung einer Korrektur mit dir ab.

## Lücken in der enthaltenen Software

Das Image enthält Software anderer: das Paket von Hibiscus Server mit Jameica, Hibiscus, den Plugins und ihren Bibliotheken, etwa Jetty, und das Basis-Image `gcr.io/distroless/java21-debian12` mit Java 21 und Debian-Paketen.

- **Hibiscus Server, Jameica und die Plugins** gehören zum Hersteller, siehe seine [Support-Seite](https://www.willuhn.de/products/hibiscus-server/support.php). Dieses Projekt ist inoffiziell und kann Lücken darin nicht beheben; es dokumentiert sie und bringt die Korrektur mit dem nächsten Paket des Herstellers ins Image.
- **Bibliotheken im Paket**, etwa Jetty 9.4.38, erneuert nur der Hersteller mit einem neuen Paket. Die Scans des Images zeigen ihre bekannten Lücken.
- **Basis-Image:** Lücken in Debian-Paketen an das [Sicherheitsteam von Debian](https://www.debian.org/security/), im Image selbst an [distroless](https://github.com/GoogleContainerTools/distroless), in Java an [Eclipse Temurin](https://adoptium.net/). Ein neues Basis-Image schlägt Dependabot vor.

## Was das Repository zusichert

- **Geprüftes Paket.** Der Build nimmt nur das Paket mit der gepinnten SHA-256-Summe, und die CI prüft die PGP-Signatur des Herstellers gegen den Schlüssel mit dem Fingerabdruck, den er veröffentlicht, siehe [Signatur](docs/sicherheit.md#signatur-des-herstellers).
- **Keine Zugangsdaten im Image.** Das Master-Passwort und das Passwort der Datenbank setzt der Betreiber über Dateien. Beispiele enthalten nur Platzhalter, Tests erzeugen ihre Passwörter bei jedem Lauf.
- **Veröffentlichen nur aus diesem Repository.** Images entstehen nur aus `main` und aus Release-Tags, mit dem `GITHUB_TOKEN` des Laufs. Pull Requests melden sich nicht an der Registry an.
- **Kein Image ohne Test.** Vor jeder Veröffentlichung startet der Image-Test das Image auf jeder Plattform, und ein Schritt prüft, dass das veröffentlichte Image das getestete ist.
- **Gepinnt und geprüft.** Actions per Commit-SHA, Basis-Images per Digest. Dependabot, gitleaks, zizmor, hadolint und trivy laufen in der CI; die Scans der Images auf Schwachstellen sind Berichte.
- **Nachvollziehbar.** Zu jedem veröffentlichten Image gibt es eine SBOM und eine Herkunftsangabe, die `gh attestation verify` prüft.

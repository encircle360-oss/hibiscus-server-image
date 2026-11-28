# Hinweise für Coding-Agenten

Der Einsatz von Coding-Agenten ist in diesem Projekt ausdrücklich erlaubt, für Maintainer wie für Mitwirkende. Verantwortlich bleibt immer der Mensch, der den Pull Request stellt und die Änderung versteht. Diese Datei ist der Einstieg. Die Regeln stehen in [CONTRIBUTING.md](CONTRIBUTING.md) und [docs/entwicklung.md](docs/entwicklung.md) und gelten für Menschen und Agenten gleichermaßen.

## Was ein Agent wissen muss

- **Projekt:** ein Rezept für ein Container-Image von Hibiscus Server, kein Fork von Hibiscus. Das Image enthält das Release-Paket des Herstellers unverändert (ohne die Dateien für Windows) und den Einstiegspunkt `launcher/HibiscusLauncher.java`. Es ist ein inoffizielles Community-Projekt; der Hersteller wird nicht kontaktiert und nicht als Unterstützer genannt.
- **Struktur:** `Dockerfile` ist das Rezept, `launcher/` der Einstiegspunkt (Java 21, nur das JDK), `scripts/verify-package.sh` prüft die Signatur des Herstellers, `hibiscus_image/` enthält die Werkzeuge (Python 3.14, nur Standardbibliothek, Tabs, `ruff`), `tests/` ihre Tests mit `pytest` und 100 % Abdeckung, `tests/launcher/` die Tests des Einstiegspunkts mit 100 % Zeilen- und Zweigabdeckung, `tests/image/` den Image-Test. Die Workflows liegen unter `.github/workflows/`, die gemeinsamen Schritte der Image-Jobs unter `.github/actions/build-and-test/`, die Dokumentation unter `docs/`.
- **Sprache:** Code, Kommentare, Meldungen, Namen von Workflow-Schritten und Commit-Nachrichten englisch. Dokumentation, Issues und Pull Requests deutsch.
- **Quellen:** Aussagen über Hibiscus Server und Jameica stammen aus dem Paket des Herstellers (Dateien, `plugin.xml`, Manifeste), seinem Quelltext auf GitHub zur passenden Version oder seinen Seiten auf willuhn.de. Was dort nicht steht, wird nicht aus dem Gedächtnis ergänzt, sondern nachgeschlagen und belegt oder als ungeprüft benannt.

## Leitplanken

1. **Test zuerst.** Erst ein Test, der fehlschlägt, dann die Änderung am Einstiegspunkt oder an den Werkzeugen. Kein Test wird abgeschwächt, übersprungen oder gelöscht, um grün zu werden. Änderungen am Image prüft der Image-Test.
2. **Keine Geheimnisse.** Keine Passwörter, PINs, TANs, Schlüssel, Inhalte von `/data` oder Sicherungen in Code, Tests, Logs, Issues oder Commits. Ein Agent fragt nie nach Zugangsdaten, liest sie nie aus und speichert sie nie. Beispiele enthalten nur Platzhalter wie `CHANGE_ME` und `example.com`, Tests erzeugen ihre Passwörter zur Laufzeit und geben sie nie aus.
3. **Nichts Internes.** Das Repository ist öffentlich und allgemein. Keine Banken, keine Kunden, keine internen Hostnamen, nichts über die Infrastruktur von Maintainern oder Betreibern.
4. **Alles gepinnt.** Actions per Commit-SHA mit der Version als Kommentar, Basis-Images per Digest, das Paket per SHA-256, Werkzeuge exakt. Jeder Pin wird an seiner Quelle geprüft, nicht geraten.
5. **Ein Pull Request, ein Thema.** Dokumentation und Changelog im selben Pull Request.
6. **Nichts Ungeprüftes nach `main`.** Die CI muss grün sein, und ein Maintainer prüft vor dem Merge. Ein neues Paket des Herstellers, ein neuer Schlüssel und Änderungen an den Vorgaben brauchen dieses Review immer.
7. **Upstream zuerst.** Keine Patches am Paket des Herstellers. Ein Fehler von Hibiscus gehört zum Hersteller; das Image dokumentiert ihn als bekannte Grenze.
8. **Signatur nicht umgehen.** Ein Paket kommt nur mit einer Summe ins `Dockerfile`, deren Paket der Workflow Signature bestätigt hat.
9. **Prüfen, was sicher prüfbar ist:** `pre-commit run --all-files`, `pytest`, `./tests/launcher/run.sh`, bei Änderungen am Image `./build.sh` und `./tests/image/run.sh hibiscus-server:local`.

## Wiederkehrende Aufgaben

- **Neues Paket des Herstellers:** den Pull Request des Workflows Vendor release prüfen oder die Schritte in [Versionierung](docs/versionierung.md#ein-neues-paket-des-herstellers) von Hand gehen, `sources.json` und die Versionen der Bestandteile in `SOURCES.md`, README und `docs/architektur.md` aus dem neuen Paket nachziehen, den Changelog ergänzen.
- **Neues Basis-Image:** den Pull Request von Dependabot prüfen; der Image-Test zeigt, ob alles läuft.
- **Release:** die Schritte in [Versionierung](docs/versionierung.md#release-prozess-für-maintainer).

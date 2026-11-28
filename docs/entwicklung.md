# Entwicklung

Diese Seite beschreibt Aufbau, Einrichtung, Tests, Stil und CI. Wie ein Beitrag abläuft, steht in [CONTRIBUTING.md](../CONTRIBUTING.md), wie das Image aufgebaut ist, in [Architektur](architektur.md).

## Aufbau des Repositories

```text
hibiscus-server-image/
├── Dockerfile                 Rezept des Images: Paket des Herstellers mit SHA-256, Basis-Images per Digest
├── launcher/
│   └── HibiscusLauncher.java  Einstiegspunkt, eine Klasse, nur das JDK
├── build.sh                   lokaler Build mit denselben Eingaben wie in der CI
├── scripts/verify-package.sh  Prüfung der PGP-Signatur eines Pakets des Herstellers
├── hibiscus_image/            Werkzeuge in Python, nur Standardbibliothek, python -m hibiscus_image
│   ├── dockerfile.py          Pins des Dockerfile lesen und ein neues Paket eintragen
│   ├── tags.py                Image-Tags aus Git-Ref, Commit und vorhandenen Releases, Prüfung der Release-Tags
│   ├── vendor.py              Download-Seite und Dateien .SHA-256 des Herstellers lesen
│   ├── summary.py             Zusammenfassung eines Builds für Workflow-Läufe und Release-Notes
│   ├── sources.py             Quelltext-Liste lesen, Quelltexte laden und prüfen, das Quelltext-Archiv schreiben
│   └── __main__.py            Kommandozeile
├── tests/                     pytest-Tests der Werkzeuge und des Repositories
│   ├── launcher/              Unit-Tests des Einstiegspunkts mit JUnit und JaCoCo
│   └── image/run.sh           Image-Test
├── examples/compose.yaml      Beispiel für docker compose mit MariaDB
├── docs/                      Dokumentation
├── SOURCES.md                 Quelltext der Software im Image, auch im Image
├── sources.json               gepinnte Quelltexte der Bestandteile des Pakets für das Quelltext-Archiv
└── .github/                   Workflows, Composite Action, Dependabot, Vorlagen
```

## Einrichtung

Du brauchst Docker mit Buildx, `curl` und für die Werkzeuge Python 3.14.

```bash
git clone https://github.com/<dein-konto>/hibiscus-server-image.git
cd hibiscus-server-image
python3.14 -m venv .venv
. .venv/bin/activate
pip install -r requirements-dev.txt
pre-commit install
```

`requirements-dev.txt` bringt coverage, pre-commit, pytest, pytest-cov, ruff und zizmor in festen Versionen. Java brauchst du lokal nicht: Die Tests des Einstiegspunkts laufen im JDK-Image der Stufe `build`.

## Tests

| Befehl | Was er prüft |
|---|---|
| `pytest` | die Werkzeuge mit 100 % Zeilen- und Zweigabdeckung (`--cov-fail-under=100`) und das Repository selbst: beide `FROM` per Digest, die Stufe `build` so, wie die Test-Skripte sie finden, Actions per Commit-SHA mit Version als Kommentar, dieselben Versionen von ruff und zizmor in `requirements-dev.txt` und `.pre-commit-config.yaml`, das gepinnte Paket in README, `SOURCES.md` und Architektur, `sources.json` passend zum Paket im `Dockerfile` und zu `SOURCES.md` (Bestandteile, Versionen, Repositories, Refs, Commits, Koordinaten, Lücken), derselbe Fingerabdruck des Schlüssels in Skript, README und Sicherheit |
| `./tests/launcher/run.sh` | den Einstiegspunkt mit 100 % Zeilen- und Zweigabdeckung, siehe unten |
| `./tests/image/run.sh IMAGE` | ein gebautes Image, so wie die CI es vor jeder Veröffentlichung prüft, siehe [README](../README.md#was-getestet-ist-und-was-nicht) |

### Tests des Einstiegspunkts

`tests/launcher/run.sh` übersetzt `launcher/HibiscusLauncher.java` und die Tests mit `javac --release 21 -Xlint:all -Werror` und führt sie mit der JUnit Platform Console (6.1.3) und dem Agenten von JaCoCo (0.8.16) aus. Unter voller Zeilen- und Zweigabdeckung schlägt der Lauf fehl; den Bericht legt er unter `build/launcher-tests/coverage/` ab.

**Warum so und nicht mit Maven oder Gradle:** Der Einstiegspunkt ist eine Klasse ohne Abhängigkeiten, die das `Dockerfile` mit `javac` übersetzt. Ein Build-Werkzeug brächte Plugins und deren Abhängigkeiten mit, die sich nur über Versionen pinnen lassen, nicht über Prüfsummen, und es übersetzte mit einem anderen Compiler als das Image. Das Skript braucht genau drei jar-Dateien, jede mit Version und SHA-256 gepinnt und einmal nach `.cache/` geladen, und läuft im JDK-Image der Stufe `build`, per Digest gepinnt, ohne Netz und als der aufrufende Benutzer, also mit demselben Compiler wie das Image.

Die Tests brauchen weder Jameica noch eine Datenbank:

- `tests/launcher/stubs/` bildet die Klassen von Jameica nach, die der Einstiegspunkt per Reflection aufruft (`de.willuhn.jameica.Main`, der Plugin-Loader, der Logger mit `Level`, `Message` und `Target`), mit den Signaturen aus dem Paket.
- `tests/launcher/driver/` ist ein JDBC-Treiber unter dem Klassennamen von MariaDB Connector/J. Das Skript packt ihn in eine eigene jar-Datei, die der Test nach `lib/mariadb/` legt, so dass der Einstiegspunkt ihn lädt wie den echten Treiber. Dahinter steht `tests/launcher/src/fakedb/FakeDatabase.java`, eine Datenbank im Speicher, die Zeichensatz, Tabellen, Ausfälle beim Verbinden und Fehler einzelner Anweisungen nachstellt.
- Jeder Test erzeugt seine Passwörter neu und prüft am Ende, dass keines in der Ausgabe steht.

## Testgetrieben arbeiten

1. **Rot:** Schreib zuerst einen Test, der ohne die Änderung fehlschlägt, und sieh ihn fehlschlagen.
2. **Grün:** Schreib die kleinste Änderung, die ihn bestehen lässt.
3. **Aufräumen:** Verbessere Namen und Struktur, während alle Tests grün bleiben.

Kein bestehender Test wird abgeschwächt, übersprungen oder gelöscht, um grün zu werden. Werkzeuge und Einstiegspunkt behalten 100 % Abdeckung. Testnamen beschreiben die Regel, die sie prüfen.

## Stil

- **Java:** zwei Leerzeichen, geschweifte Klammern auf eigener Zeile wie im Quelltext von Jameica, frei von Warnungen mit `-Xlint:all -Werror`, nur das JDK. Was der Einstiegspunkt ausgibt, nennt nie ein Passwort.
- **Python:** ruff mit der Konfiguration in `pyproject.toml` (Tabs, 110 Zeichen, doppelte Anführungszeichen, Python 3.14), nur die Standardbibliothek.
- **Shell:** `set -euo pipefail`, frei von Befunden von shellcheck.
- **Dockerfile:** frei von Befunden von hadolint und von `docker buildx build --check`; eine Ausnahme steht mit Begründung an ihrer Anweisung.
- **Englisch im Code**, auch in Kommentaren, Meldungen und Namen der Workflow-Schritte. Dokumentation, Issues und Pull Requests auf Deutsch.
- **Kommentare erklären das Warum**, nie das Was und nie die Geschichte einer Änderung.

## CI

Ein Pull Request wird erst gemergt, wenn alle Jobs grün sind. Jeder Workflow liest das Repository nur (`contents: read`); Schreibrechte bekommen einzelne Jobs, die sie brauchen. Alle Actions sind per vollem Commit-SHA gepinnt, mit der Version als Kommentar.

| Workflow | Wann | Was er tut |
|---|---|---|
| Tests (`tests.yml`) | Pull Requests, Pushes auf `main`, von Hand | `ruff check`, `ruff format --check`, `pytest`; im Job „Entry point“ `tests/launcher/run.sh` mit dem Bericht der Abdeckung als Artefakt |
| Linters (`linters.yml`) | Pull Requests, Pushes auf `main`, von Hand | pre-commit auf allen Dateien, darin shellcheck; hadolint auf dem `Dockerfile`; zizmor auf Workflows, Dependabot und pre-commit |
| Security (`security.yml`) | Pull Requests, Pushes auf `main`, montags, von Hand | gitleaks über die ganze Historie; `trivy config` über das Repository; wöchentlich und von Hand trivy über das veröffentlichte Image `latest`, je Plattform |
| Signature (`signature.yml`) | Änderungen an `Dockerfile`, `keys/` und am Skript, montags, von Hand, vor jedem Release | prüft die PGP-Signatur des gepinnten Pakets, siehe [Sicherheit](sicherheit.md#signatur-des-herstellers) |
| Image (`build.yml`) | Pull Requests und Pushes auf `main`, die das Image betreffen; von Hand; als Teil von Release | je Plattform die Composite Action `.github/actions/build-and-test`: Build ohne Cache, Image-Test, Scan mit trivy; `linux/amd64` auf `ubuntu-latest`, `linux/arm64` auf `ubuntu-26.04-arm`. Pull Requests und Forks bauen und testen nur. Pushes auf `main` und Release-Tags schieben jedes getestete Image per Digest, prüfen, dass es das getestete ist, legen je Plattform eine SBOM ab und veröffentlichen beide Images als einen Index unter den Tags mit der Herkunftsangabe |
| Sources (`sources.yml`) | Pull Requests und Pushes auf `main`, die `sources.json`, `SOURCES.md`, `Dockerfile`, das Werkzeug oder den Workflow ändern; montags; als Teil von Release; von Hand mit einem Tag | baut das Quelltext-Archiv aus `sources.json` und hält es sieben Tage als Artefakt; von Hand hängt es das Archiv an ein bestehendes Release, siehe [Versionierung](versionierung.md#quelltext-archiv-nachträglich-anhängen) |
| Release (`release.yml`) | Push eines Tags `v*` | `release-check`, Signature und Sources, Image, GitHub-Release mit SBOMs und Quelltext-Archiv |
| Vendor release (`versions.yml`) | montags 06:00 UTC, von Hand | sucht ein neues Paket des Herstellers, siehe [Versionierung](versionierung.md#ein-neues-paket-des-herstellers) |

**Was anhält und was nur berichtet.** Tests, Linters, gitleaks, der Scan der Konfiguration, Signature, Sources und im Workflow Image Build und Image-Test halten einen Pull Request an. Die Scans der Images auf Schwachstellen sind Berichte: Ihre Befunde kommen aus dem Basis-Image und den Bibliotheken des Herstellers und verschwinden mit einem neuen Build oder einem neuen Paket, deshalb halten sie kein Image an. Sie landen in Code Scanning, bei Pull Requests aus Forks im Log.

**Ohne Cache.** Jeder Build lädt das Paket neu von willuhn.de und prüft es; die Basis-Images kommen per Digest. Ein Build dauert so nur wenige Minuten, und es gibt keinen Cache, den ein anderer Lauf vergiften könnte.

**Sources nur bei Bedarf.** Ein Lauf lädt 13 Repositories und gut 30 Dateien von Maven Central und Eclipse, zusammen rund 150 MB, in etwa einer halben Minute. Maven Central drosselt Runner, die oft laden (HTTP 429). Der Workflow läuft deshalb nur, wenn sich etwas ändert, das in das Archiv eingeht, dazu jeden Montag, damit ein verschwundener Commit oder eine verschwundene Datei vor dem nächsten Release auffällt. Das Werkzeug versucht jeden Abruf mehrmals und wartet bei 429 so lange, wie der Server verlangt, höchstens zwei Minuten je Versuch. Pull Requests, Pushes und der Lauf am Montag halten die geprüften Dateien im Cache; geprüft wird jede Datei trotzdem bei jedem Lauf gegen ihre SHA-256. Ein Release und das nachträgliche Anhängen laden ohne Cache von der Quelle.

**Dependabot** schlägt jede Woche Updates vor, frühestens sieben Tage nach ihrem Erscheinen: für die Actions, die beiden Basis-Images im `Dockerfile` (eine neue Hauptversion von Java nicht), MariaDB im Beispiel (keine neue Hauptversion) und die Entwicklungswerkzeuge. Nicht erfasst sind das Image von MariaDB in `tests/image/run.sh`, die jar-Dateien in `tests/launcher/run.sh` und gitleaks in `security.yml`; sie hebt ein Maintainer von Hand an.

## Pins und wo sie stehen

| Was | Wo |
|---|---|
| Paket des Herstellers | `HIBISCUS_VERSION` und `HIBISCUS_SHA256` im `Dockerfile` |
| Basis-Images | beide `FROM` im `Dockerfile`, per Tag und Digest |
| Fingerabdruck des Schlüssels | `scripts/verify-package.sh`, README und [Sicherheit](sicherheit.md) |
| MariaDB | `tests/image/run.sh` per Tag und Digest, `examples/compose.yaml` per Tag |
| JUnit, JaCoCo | `tests/launcher/run.sh`, Version und SHA-256 |
| Quelltexte im Archiv | `sources.json`, Commit oder SHA-256 je Bestandteil |
| GitHub Actions | Workflows und `.github/actions/`, per Commit-SHA mit Version als Kommentar |
| Entwicklungswerkzeuge | `requirements-dev.txt` (`==`), Hooks in `.pre-commit-config.yaml` über `rev`, gitleaks mit Version und SHA-256 in `security.yml` |

## Commits

- Commit-Nachrichten sind englisch, ein Satz, klein geschrieben, im Imperativ, und vervollständigen den Satz „If applied, this commit will …“, etwa `add the image test`.
- Keine Ticket-Präfixe, keine Fußzeilen (Trailer).
- Branches heißen nach dem Thema. Auf `main` wird nicht direkt committet.
- Ein Thema pro Pull Request, die Dokumentation im selben Pull Request.

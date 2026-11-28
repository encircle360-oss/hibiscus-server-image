# Versionierung

Diese Seite beschreibt, wie Releases heißen, welche Image-Tags daraus entstehen, was eine Revision ändert und wie ein Release entsteht. Wie ein Update im Betrieb abläuft, steht in [Betrieb](betrieb.md#updates).

## Das Schema

Ein Release ist ein Git-Tag der Form `v<Version von Hibiscus Server>-<Revision>`, etwa `v2.12.4-1`.

- **Version** ist `HIBISCUS_VERSION` im `Dockerfile`, also die Version des Pakets des Herstellers. Jameica und die Plugins im Paket haben eigene Versionen, siehe [Architektur](architektur.md#das-paket-des-herstellers).
- **Revision** zählt die Releases zu einer Version, beginnend mit 1.

`python -m hibiscus_image release-check --ref refs/tags/<tag>` prüft das, bevor die CI ein Release baut: Die Version des Tags muss `HIBISCUS_VERSION` sein, die Revision mindestens 1.

## Image-Tags

`python -m hibiscus_image tags` leitet die Image-Tags aus Git-Ref, Commit und den vorhandenen Release-Tags ab:

| Ereignis | Image-Tags |
|---|---|
| Push auf `main` | `main`, `main-<sha7>` |
| Push des Git-Tags `v2.12.4-1` | `2.12.4-1`, dazu `2.12.4`, `2` und `latest`, soweit kein neueres Release sie trägt |
| Pull Request, Lauf von Hand oder anderer Branch | keine; das Image wird gebaut und getestet, aber nicht veröffentlicht |

Alle Tags gehören zu `ghcr.io/encircle360-oss/hibiscus-server`. Bewegliche Tags gehen nur vorwärts: Neuer ist ein Release mit höherer Version oder eine spätere Revision derselben Version. Eine späte Revision einer älteren Version, etwa `v2.12.4-3` nach `v2.13.0-1`, bekommt ihr volles Tag und `2.12.4`, aber weder `2` noch `latest`. Die CI übergibt dafür die Release-Tags mit `--existing "$(git tag -l 'v*')"`.

| Tag | Bewegt sich | Zeigt auf |
|---|---|---|
| `2.12.4-1` | nein | genau dieses Release |
| `2.12.4` | mit jeder neuen Revision zu 2.12.4 | die neueste Revision dieser Version |
| `2` | mit jedem neueren Release der Hauptversion 2 | das neueste Release der Hauptversion |
| `latest` | mit jedem neueren Release | das neueste Release |
| `main`, `main-<sha7>` | mit jedem Push auf `main` beziehungsweise nein | Builds von `main`, kein Release |

Eine neue Version von Hibiscus Server passt beim ersten Start das Datenbankschema an. Jedes bewegliche Tag kann deshalb beim nächsten Pull eine Version bringen, nach der es kein Zurück ohne Sicherung gibt.

## Was eine Revision ändert

Eine neue Revision entsteht, wenn sich bei gleichem Paket etwas anderes im Image ändert: ein neues Basis-Image (etwa mit Korrekturen aus Debian oder eine neue Patch-Version von Java 21), eine Änderung am Einstiegspunkt oder am Rezept. Mit einem neuen Paket des Herstellers beginnt die Revision wieder bei 1.

## Wie Nutzer pinnen

Für den Betrieb gehört das volle Tag in die Konfiguration, noch strenger der Digest:

```bash
docker buildx imagetools inspect ghcr.io/encircle360-oss/hibiscus-server:2.12.4-1
```

Die Ausgabe nennt unter `Digest` den Digest des Index für beide Plattformen. Woher ein Image stammt, prüft `gh attestation verify` gegen die Herkunftsangabe, die die CI für den Index und beide Images ablegt:

```bash
gh attestation verify oci://ghcr.io/encircle360-oss/hibiscus-server:2.12.4-1 --repo encircle360-oss/hibiscus-server-image
```

## Ein neues Paket des Herstellers

Der Hersteller hat keine maschinenlesbare Versionsdatei. Der Workflow Vendor release (`versions.yml`) läuft jeden Montag um 06:00 UTC und lässt sich von Hand starten. Er lädt die [Download-Seite](https://www.willuhn.de/products/hibiscus-server/download.php) und liest das neueste Release aus ihrem Link `releases/hibiscus-server-<Version>.zip`; Nightly-Builds zählen nicht. Ist es neuer als `HIBISCUS_VERSION`, lädt er die Datei `.SHA-256` des Herstellers, prüft das Paket mit `scripts/verify-package.sh` wie in [Sicherheit](sicherheit.md#signatur-des-herstellers) und

- öffnet bei guter Signatur einen Pull Request aus dem Branch `update/hibiscus-server-<Version>`, der `HIBISCUS_VERSION` und `HIBISCUS_SHA256` setzt,
- öffnet bei schlechter Signatur ein Issue und übernimmt nichts.

Von Hand geht dasselbe so:

```bash
python -m hibiscus_image newer download.php                         # the newer release, if any
python -m hibiscus_image checksum hibiscus-server-X.Y.Z.zip.SHA-256 --version X.Y.Z
./scripts/verify-package.sh --version X.Y.Z --sha256 <sha256>       # needs the key, see docs/sicherheit.md
python -m hibiscus_image bump --version X.Y.Z --sha256 <sha256>
```

Der Pull Request ist nicht fertig, wenn er sich öffnet: Die Quelltext-Liste [`sources.json`](../sources.json) und die Versionen der Bestandteile in [SOURCES.md](../SOURCES.md), im README und in [Architektur](architektur.md) zieht ein Maintainer von Hand nach, nachdem er das neue Paket angesehen hat; bis dahin schlagen die Tests fehl. Für `sources.json`:

- `package.version` auf die neue Version setzen.
- **Jameica, Hibiscus, Hibiscus Server:** Version aus `plugin.xml` und Manifest des Pakets, Ref ist der Branch mit dieser Versionsnummer, Commit der, auf den er zeigt: `git ls-remote https://github.com/willuhn/hibiscus refs/heads/<Version>`.
- **Plugins mit Nightly-Version und Bibliotheken des Herstellers ohne Tag:** Build-Datum aus `Built-Date` im Manifest und den Zeitstempeln der jar-Datei, dann der letzte Commit des Standard-Branches bis dahin, geprüft gegen `plugin.xml` und die Klassen der jar-Datei wie in [SOURCES.md](../SOURCES.md#hibiscus-server-jameica-und-die-bibliotheken-des-herstellers) beschrieben. Das lässt sich nicht mechanisch erledigen; der Befehl `bump` ändert `sources.json` deshalb nicht.
- **HBCI4Java:** der Tag `hbci4j-core-<Version>`.
- **Bibliotheken:** für jede neue oder geänderte jar-Datei die Lizenz prüfen; bei einer Copyleft-Lizenz Adresse und SHA-256 der Quelltext-Datei derselben Version eintragen, etwa `sha256sum` der `-sources.jar` von Maven Central, nachdem die jar-Datei des Pakets mit der auf Maven Central verglichen ist. Was sich nicht pinnen lässt, kommt unter `missing` und in [SOURCES.md](../SOURCES.md#was-das-archiv-nicht-enthält).
- `python -m hibiscus_image sources-archive --label test --output /tmp/sources` baut das Archiv lokal; der Workflow Sources baut es im Pull Request.

Zum Pull Request gehören außerdem der Changelog und das Lesen des [Changelogs des Herstellers](https://www.willuhn.de/products/hibiscus-server/changelog.php). Die Checks eines Pull Requests, den der Workflow geöffnet hat, starten erst, wenn ein Maintainer sie mit „Approve workflows to run“ freigibt. Damit der Workflow Pull Requests öffnen darf, muss in den Einstellungen des Repositories unter *Actions > General > Workflow permissions* „Allow GitHub Actions to create and approve pull requests“ an sein.

## Release-Prozess für Maintainer

1. **Changelog.** Die Einträge unter `## Unveröffentlicht` in [CHANGELOG.md](../CHANGELOG.md) kommen in einen Abschnitt `## [2.12.4-1] - JJJJ-MM-TT`. Der Workflow Release übernimmt ihn in die Release-Notes.
2. **Taggen**, nach dem Merge auf `main`:

   ```bash
   git switch main
   git pull
   git tag -a v2.12.4-1 -m "release v2.12.4-1"
   git push origin v2.12.4-1
   ```

3. **Veröffentlichen.** Der Push des Tags startet den Workflow Release (`release.yml`): `release-check`, dann der Workflow Signature und der Workflow Sources, der das Quelltext-Archiv `hibiscus-server-<Tag>-sources.tar.gz` aus `sources.json` baut, dann der Workflow Image mit Build, Image-Test und Scan je Plattform, dem Index unter den Tags, die dem Release zustehen, Herkunftsangabe und SBOM, zuletzt das GitHub-Release mit der Zusammenfassung von `python -m hibiscus_image summary`, dem Abschnitt aus dem Changelog, je einer SBOM für `linux/amd64` und `linux/arm64` und dem Quelltext-Archiv. Schlägt ein Schritt fehl, auch das Laden oder Prüfen eines einzigen Quelltexts, wird nichts veröffentlicht.

Ein veröffentlichtes Release wird nicht neu getaggt; eine Korrektur ist eine neue Revision.

## Quelltext-Archiv nachträglich anhängen

Ein Release ohne Quelltext-Archiv, etwa `v2.12.4-1`, das vor dem Archiv erschienen ist, bekommt es über den Workflow Attach sources (`sources-attach.yml`): unter *Actions > Attach sources > Run workflow* den Branch `main` wählen und den Tag eintragen. Am Tag selbst gibt es keine `sources.json`; der Lauf nimmt deshalb die Liste von `main`. Er gilt nur unter dieser Regel:

**Die Liste von `main` passt zu einem Tag, wenn das `Dockerfile` des Tags dasselbe Paket des Herstellers pinnt wie `main`, also dieselbe `HIBISCUS_VERSION` und dieselbe `HIBISCUS_SHA256`.** Dasselbe Paket enthält dieselben Bestandteile und hat damit dieselben Quelltexte.

Der Lauf bricht ab, wenn er nicht von `main` startet, der Tag nicht dem Schema entspricht oder eine andere Version nennt als `main` (`release-check`), der Tag oder sein Release fehlt, das Paket des Tags ein anderes ist (`same-package`) oder das Release das Archiv schon hat. Erst nach dieser Prüfung baut der Workflow Sources das Archiv mit dem geprüften Tag als Namen, ohne Cache. Der letzte Job hängt nur diese eine Datei an und ersetzt keine andere; nur er darf in das Repository schreiben. Für ein Release eines älteren Pakets passt die Liste von `main` nicht; ihm lässt sich das Archiv so nicht anhängen.

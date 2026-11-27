# Mitwirken an hibiscus-server-image

Issues und Pull Requests sind willkommen. hibiscus-server-image ist ein Container-Image von Hibiscus Server aus dem Release-Paket des Herstellers. Diese Seite beschreibt, wie ein Beitrag abläuft und welche Regeln gelten. Die Regeln gelten für Menschen und für Coding-Agenten gleichermaßen, siehe [AGENTS.md](AGENTS.md).

## Fehler und Wünsche melden

Bitte ein [Issue über die Vorlagen](https://github.com/encircle360-oss/hibiscus-server-image/issues/new/choose) anlegen. Fragen und Austausch gehen in die [Discussions](https://github.com/encircle360-oss/hibiscus-server-image/discussions) oder auf [Matrix](https://matrix.to/#/#oss:encircle360.com).

Hierher gehören Fehler im Image, im Einstiegspunkt, im Beispiel, in den Werkzeugen und in der Dokumentation. **Fragen und Fehler zu Hibiscus selbst** gehören in das Forum und das Wiki des Herstellers, die seine [Support-Seite](https://www.willuhn.de/products/hibiscus-server/support.php) nennt. Dieses Projekt ist inoffiziell; bitte melde Fehler des Images nicht beim Hersteller.

**Keine Passwörter, PINs, TANs, Schlüssel, Kontonummern, IBANs, Umsätze, Inhalte von `/data` oder Sicherungen** in Issues, Logs, Screenshots oder Pull Requests. Logs vorher durchsehen. Sicherheitslücken bitte nicht öffentlich melden, sondern wie in [SECURITY.md](SECURITY.md) beschrieben.

## Ein Pull Request

1. **Fork und Branch.** Branches heißen nach dem Thema, etwa `kubernetes-network-policy`.
2. **Test zuerst.** Jede Änderung am Einstiegspunkt oder an den Werkzeugen beginnt mit einem Test, der ohne sie fehlschlägt. Kein Test wird abgeschwächt, übersprungen oder gelöscht, um grün zu werden. Einstiegspunkt und Werkzeuge haben 100 % Testabdeckung, und das bleibt so.
3. **Quelle nennen.** Jede Aussage über Hibiscus Server braucht eine Quelle: das Paket des Herstellers, sein Quelltext zur passenden Version oder seine Seiten. Die Quelle steht im Pull Request und, wo sie bleibt, in `docs/`.
4. **Ein Thema pro Pull Request**, Dokumentation und Changelog im selben Pull Request.
5. **Lokal prüfen:** `pre-commit run --all-files`, `pytest` und `./tests/launcher/run.sh`. Wer das Image ändert, baut zusätzlich mit `./build.sh` und lässt `./tests/image/run.sh hibiscus-server:local` laufen, siehe [Entwicklung](docs/entwicklung.md).
6. **Pull Request stellen.** Die Vorlage fragt nach Änderung, Quelle und Checkliste. Die CI muss grün sein.
7. **Review.** Ein neues Paket des Herstellers, ein neuer Schlüssel und geänderte Vorgaben werden erst gemergt, wenn ein Maintainer sie geprüft hat.

## Regeln

- **Das Paket bleibt, wie es ist.** Das Image ändert keine Datei des Herstellers außer dem Entfernen der Dateien für Windows. Einstellungen kommen über den Einstiegspunkt ins Benutzerverzeichnis.
- **Alles gepinnt.** Das Paket per SHA-256, Basis-Images per Digest, GitHub Actions per vollem Commit-SHA mit der Version als Kommentar, Werkzeuge exakt.
- **Sichere Vorgaben.** Eine Vorgabe, die Sicherheit kostet, ändert sich nur mit Begründung in [Sicherheit](docs/sicherheit.md#die-vorgaben-und-ihre-gründe).
- **Englisch im Code**, auch in Kommentaren, Meldungen und Namen von Workflow-Schritten. Dokumentation, Issues und Pull Requests auf Deutsch, deutsche Fachbegriffe bleiben deutsch.
- **Kommentare erklären das Warum**, nie das Was und nie die Geschichte einer Änderung.
- **Keine echten Daten, keine Geheimnisse.** Tests erzeugen ihre Passwörter, Beispiele haben Platzhalter. Die CI erreicht die Registry nur mit dem `GITHUB_TOKEN` des Laufs.

## Commits

Commit-Nachrichten sind englisch, ein Satz, klein geschrieben, im Imperativ, und vervollständigen den Satz „If applied, this commit will …“: `add the image test`, `pin the actions by digest`. Keine Ticket-Präfixe, keine Fußzeilen.

## Umgangston

Wir gehen respektvoll miteinander um, nehmen Kritik an der Sache an und nicht persönlich, und gehen davon aus, dass alle ihr Bestes geben. Wer das nicht einhält, wird von den Maintainern angesprochen und notfalls ausgeschlossen.

## Lizenz der Beiträge

Mit deinem Beitrag stimmst du zu, dass er unter der Lizenz dieses Repositories veröffentlicht wird, der GNU General Public License Version 2 oder später mit der Ausnahme für das Linken (`GPL-2.0-or-later WITH Classpath-exception-2.0`, siehe [README](README.md#lizenz)), und bestätigst, dass du das Recht hast, ihn unter dieser Lizenz beizusteuern.

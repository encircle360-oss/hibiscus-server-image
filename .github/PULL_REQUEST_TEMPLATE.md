## Was ändert sich?

<!-- Ein Thema pro Pull Request. Was ändert sich für Betreiber, was intern? -->

## Quelle

<!-- Welche Quelle belegt die Änderung? Paket des Herstellers, sein Quelltext zur passenden Version, seine Seiten auf willuhn.de, Dokumentation eines Werkzeugs. -->

## Checkliste

- [ ] Tests zuerst: Jede Änderung am Einstiegspunkt oder an den Werkzeugen hat einen Test, der ohne sie fehlschlägt. Kein Test wurde abgeschwächt.
- [ ] README, `docs/`, `SOURCES.md` und `CHANGELOG.md` passen zur Änderung.
- [ ] Neue Pins sind an ihrer Quelle geprüft: Basis-Images per Digest, Actions per Commit-SHA mit Version als Kommentar, das Paket per SHA-256 mit geprüfter Signatur.
- [ ] Keine Passwörter, PINs, TANs, Schlüssel oder Kontodaten in Code, Tests, Beispielen oder Screenshots.
- [ ] `pre-commit run --all-files`, `pytest` und `./tests/launcher/run.sh` laufen lokal durch.
- [ ] Bei Änderungen am Image: `./build.sh` und `./tests/image/run.sh hibiscus-server:local` laufen durch, oder die CI zeigt es.

# Quelltext der Software im Image

Das Image `ghcr.io/encircle360-oss/hibiscus-server` enthält das unveränderte Release-Paket `hibiscus-server-2.12.4.zip` von Olaf Willuhn (ohne die Dateien für Windows) und den Einstiegspunkt dieses Repositories. Die GNU General Public License verlangt, dass zusammen mit Programmen in Binärform der Weg zu ihrem Quelltext mitgegeben wird. Diese Datei liegt deshalb auch im Image, unter `/usr/share/doc/hibiscus-server-image/`, neben dem Text der Lizenz.

## Einstiegspunkt und Rezept

Den Quelltext des Einstiegspunkts (`launcher/HibiscusLauncher.java`), das `Dockerfile` und alles Weitere enthält dieses Repository: <https://github.com/encircle360-oss/hibiscus-server-image>, für ein Release unter seinem Git-Tag, etwa `v2.12.4-1`.

## Hibiscus Server und Jameica

Der Hersteller veröffentlicht den Quelltext in seinen Repositories auf GitHub. Für die Versionen im Paket 2.12.4:

| Bestandteil im Paket | Version laut Paket | Quelltext |
|---|---|---|
| Jameica (`jameica.jar`, `jameica-linux64.jar`) | 2.12.0 (`plugin.xml`, Manifest von `jameica.jar`) | [willuhn/jameica](https://github.com/willuhn/jameica), Branch [`2.12.0`](https://github.com/willuhn/jameica/tree/2.12.0) |
| Hibiscus (`plugins/hibiscus`) | 2.12.4 | [willuhn/hibiscus](https://github.com/willuhn/hibiscus), Branch [`2.12.4`](https://github.com/willuhn/hibiscus/tree/2.12.4) |
| Hibiscus Server (`plugins/hibiscus.server`) | 2.12.4 | [willuhn/hibiscus.server](https://github.com/willuhn/hibiscus.server), Branch [`2.12.4`](https://github.com/willuhn/hibiscus.server/tree/2.12.4) |
| `plugins/hibiscus.xmlrpc` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/hibiscus.xmlrpc](https://github.com/willuhn/hibiscus.xmlrpc) |
| `plugins/hibiscus.soap` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/hibiscus.soap](https://github.com/willuhn/hibiscus.soap) |
| `plugins/jameica.webadmin` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/jameica.webadmin](https://github.com/willuhn/jameica.webadmin) |
| `plugins/jameica.xmlrpc` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/jameica.xmlrpc](https://github.com/willuhn/jameica.xmlrpc) |
| `plugins/jameica.soap` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/jameica.soap](https://github.com/willuhn/jameica.soap) |
| `plugins/jameica.sensors` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/jameica.sensors](https://github.com/willuhn/jameica.sensors) |
| HBCI4Java (`plugins/hibiscus/lib/hbci4j-core-4.1.10.jar`) | 4.1.10 | [hbci4j/hbci4java](https://github.com/hbci4j/hbci4java), Tag [`hbci4j-core-4.1.10`](https://github.com/hbci4j/hbci4java/tree/hbci4j-core-4.1.10) |

Die Versionen stehen in den Dateien `plugin.xml` und in den Manifesten der jar-Dateien des Pakets. Für die Plugins mit der Version 2.11.0-nightly nennt das Paket keinen Commit; ihr Quelltext liegt im Branch `master` der genannten Repositories. Für Jameica 2.12.0, Hibiscus 2.12.4 und Hibiscus Server 2.12.4 hat der Hersteller keine Git-Tags angelegt, sondern Branches mit der Versionsnummer.

## Bibliotheken im Paket

Das Paket bringt Bibliotheken anderer Projekte als jar-Dateien mit, darunter Jetty 9.4.38, Bouncy Castle 1.72, H2, MariaDB Connector/J 3.5.1, MySQL Connector/J 9.2.0, Apache XML-RPC, Velocity und Hibernate. Ihre Lizenzen liegen im Paket neben ihnen, etwa `lib/<bibliothek>/license.txt` und `plugins/hibiscus/lib/license_*.txt`, und im Image unter `/opt/hibiscus` an derselben Stelle. Die SBOM eines veröffentlichten Images listet die Pakete auf, die ein Scanner erkennt. Den Quelltext dieser Bibliotheken veröffentlichen ihre Projekte.

## Basis-Image

Die Laufzeit kommt aus `gcr.io/distroless/java21-debian12:nonroot` (Debian 12 mit Eclipse Temurin 21) des Projekts [distroless](https://github.com/GoogleContainerTools/distroless). Den Quelltext seiner Debian-Pakete gibt es über [sources.debian.org](https://sources.debian.org/), den von Temurin über [adoptium/temurin-build](https://github.com/adoptium/temurin-build) und das OpenJDK.

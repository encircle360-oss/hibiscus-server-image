# Quelltext der Software im Image

Das Image `ghcr.io/encircle360-oss/hibiscus-server` enthält das unveränderte Release-Paket `hibiscus-server-2.12.4.zip` von Olaf Willuhn (ohne die Dateien für Windows) und den Einstiegspunkt dieses Repositories. Die GNU General Public License verlangt, dass zusammen mit Programmen in Binärform der Weg zu ihrem Quelltext mitgegeben wird. Diese Datei liegt deshalb auch im Image, unter `/usr/share/doc/hibiscus-server-image/`, neben dem Text der Lizenz.

## Das Quelltext-Archiv am Release

Jedes GitHub-Release dieses Repositories ab diesem Stand hängt neben den SBOMs die Datei `hibiscus-server-<Tag>-sources.tar.gz` an, etwa `hibiscus-server-v2.12.4-1-sources.tar.gz`. Das Image selbst enthält nur diese Datei, nicht das Archiv; das Archiv liegt unter <https://github.com/encircle360-oss/hibiscus-server-image/releases> beim Release mit dem Tag des Images.

Das Archiv enthält:

- `components/` mit einer Datei je Bestandteil: für ein Git-Repository ein tar-Archiv des gepinnten Commits (`git archive`), für eine Bibliothek die Quelltext-Datei ihres Projekts, etwa die `-sources.jar` von Maven Central,
- `manifest.json` mit Bestandteil, Version, Pfaden im Paket, Lizenz, Herkunft, Ref, Commit oder SHA-256 und der SHA-256 jeder Datei im Archiv, dazu der Liste dessen, was fehlt,
- diese Datei `SOURCES.md`.

Welche Quelltexte hineingehören, steht in [`sources.json`](sources.json): je Bestandteil der Commit seines Repositories oder die Adresse und SHA-256 seiner Quelltext-Datei. Ein Commit wird mit `git fetch --depth 1 <Repository> <Commit>` geladen, und das Werkzeug bricht ab, wenn `FETCH_HEAD` nicht genau dieser Commit ist; über den Inhalt wacht damit die Adressierung von Git. Eine Datei bricht ab, wenn ihre SHA-256 nicht die gepinnte ist. Schlägt ein Bestandteil fehl, entsteht kein Archiv und kein Release. `python -m hibiscus_image sources-archive --label <Tag> --output <Verzeichnis>` baut das Archiv auch lokal.

Ein Release, das vor diesem Archiv erschienen ist, wie `v2.12.4-1`, bekommt es über den Workflow Sources nachträglich, wenn das Paket auf `main` dasselbe ist wie das des Tags, siehe [Versionierung](docs/versionierung.md#quelltext-archiv-nachträglich-anhängen).

## Einstiegspunkt und Rezept

Den Quelltext des Einstiegspunkts (`launcher/HibiscusLauncher.java`), das `Dockerfile` und alles Weitere enthält dieses Repository: <https://github.com/encircle360-oss/hibiscus-server-image>, für ein Release unter seinem Git-Tag, etwa `v2.12.4-1`. GitHub hängt jedem Release dieses Repository am Tag als „Source code“ an; das Quelltext-Archiv wiederholt es deshalb nicht.

## Hibiscus Server, Jameica und die Bibliotheken des Herstellers

Der Hersteller veröffentlicht den Quelltext in seinen Repositories auf GitHub. Für die Versionen im Paket 2.12.4:

| Bestandteil im Paket | Version laut Paket | Repository | Ref | Commit |
|---|---|---|---|---|
| Jameica (`jameica.jar`, `jameica-linux64.jar`, `plugin.xml`) | 2.12.0 | [willuhn/jameica](https://github.com/willuhn/jameica) | Branch `2.12.0` | `d4e8b0ba20af08b3cff9bdd7257349229f23f2a4` |
| Hibiscus (`plugins/hibiscus`) | 2.12.4 | [willuhn/hibiscus](https://github.com/willuhn/hibiscus) | Branch `2.12.4` | `2f6108669ad6a9b720d357be1d963af2efd655a9` |
| Hibiscus Server (`plugins/hibiscus.server`) | 2.12.4 | [willuhn/hibiscus.server](https://github.com/willuhn/hibiscus.server) | Branch `2.12.4` | `7ca698ef2ed960ed8bd0bbc0c87bf6cdad98c2fc` |
| `plugins/hibiscus.xmlrpc` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/hibiscus.xmlrpc](https://github.com/willuhn/hibiscus.xmlrpc) | Stand von `master` am Build-Datum | `556dfef174405638f16eea6a054b885bafa2ede7` |
| `plugins/hibiscus.soap` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/hibiscus.soap](https://github.com/willuhn/hibiscus.soap) | Stand von `master` am Build-Datum | `dde005b67cee6f84939016f6d05df18578184bda` |
| `plugins/jameica.webadmin` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/jameica.webadmin](https://github.com/willuhn/jameica.webadmin) | Stand von `master` am Build-Datum | `f9722e17f3dfa55e6c0718970b9452aa94cfecd2` |
| `plugins/jameica.xmlrpc` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/jameica.xmlrpc](https://github.com/willuhn/jameica.xmlrpc) | Stand von `master` am Build-Datum | `2f6a635402dfbdb4376ed0e7e5b2bd1472b5b2db` |
| `plugins/jameica.soap` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/jameica.soap](https://github.com/willuhn/jameica.soap) | Stand von `master` am Build-Datum | `ce16b5af0ad6a257d0ab37da5f6730f122947eed` |
| `plugins/jameica.sensors` | 2.11.0-nightly, gebaut am 2026-05-05 | [willuhn/jameica.sensors](https://github.com/willuhn/jameica.sensors) | Stand von `master` am Build-Datum | `5011efdae43eab346de4ff655b079ed3b509ab82` |
| HBCI4Java (`plugins/hibiscus/lib/hbci4j-core-4.1.10.jar` und die Kartenleser-Bibliotheken `*hbci4java-card*`) | 4.1.10 | [hbci4j/hbci4java](https://github.com/hbci4j/hbci4java) | Tag `hbci4j-core-4.1.10` | `67e401f1b2ce461cbe449ad303464454116f75dd` |
| de.willuhn.util (`lib/de_willuhn_util/de_willuhn_util.jar`) | 2.8, Build 219 | [willuhn/util](https://github.com/willuhn/util) | Tag `V_2_8_BUILD_219` | `c991cf681919d5dfe1634a3f164b245bb684bb1c` |
| de.willuhn.datasource (`lib/de_willuhn_ds/de_willuhn_ds.jar`) | 2.8.1, Build 221, gebaut am 2025-08-28 | [willuhn/datasource](https://github.com/willuhn/datasource) | Stand von `master` am Build-Datum | `e5a9a48ead34a387ad01951e79eef5f4eab6d68e` |
| OBanToo (`plugins/hibiscus/lib/obantoo-bin-2.1.12.jar`) | 2.1.12, gebaut am 2026-02-04 | [willuhn/obantoo](https://github.com/willuhn/obantoo) | Stand von `main` am Build-Datum | `902a6df35dcdb4462ac688718a85bd76aae30b14` |

Die Versionen stehen in den Dateien `plugin.xml` und in den Manifesten der jar-Dateien des Pakets. So sind die Commits gewählt:

- **Jameica 2.12.0, Hibiscus 2.12.4, Hibiscus Server 2.12.4:** Der Hersteller legt keine Git-Tags an, sondern Branches mit der Versionsnummer. Gepinnt ist der Commit, auf den der Branch am 2026-11-28 zeigte. Die `plugin.xml` des Pakets ist mit der des Commits byteweise gleich, und zu jeder Klasse in `jameica.jar`, `hibiscus.jar` und `hibiscus.server.jar` gibt es die Quelldatei im Commit und umgekehrt.
- **Die Plugins mit der Version 2.11.0-nightly, de.willuhn.datasource und OBanToo:** Das Paket nennt keinen Commit. Die Manifeste nennen das Build-Datum (`Built-Date`), die Zeitstempel in den jar-Dateien die Uhrzeit, für die Plugins 2026-05-05 gegen 10:39 Uhr (MESZ, aus dem Vergleich mit den Commits desselben Morgens geschlossen). Gepinnt ist der letzte Commit des Standard-Branches bis zu diesem Zeitpunkt. **Das ist der Stand des Repositories am Build-Datum, nicht ein Commit, den der Hersteller genannt hat.** Geprüft ist: Die `plugin.xml` des Pakets ist mit der des Commits byteweise gleich, die Klassen in der jar-Datei und die Quelldateien im Commit entsprechen einander eins zu eins (bis auf Tests und `package-info.java`), und die Bibliotheken unter `lib/` der Plugins sind dieselben. Nicht geprüft ist, dass die Klassen genau aus diesem Quelltext übersetzt wurden; lokale Änderungen beim Build lassen sich so nicht ausschließen. Bei fünf der sechs Plugins ist der gepinnte Commit am 2026-11-28 noch der neueste. Bei `hibiscus.soap` ändert der nächste Commit (2026-05-22) nur den Klassenpfad auf HBCI4Java 4.1.11; das Paket enthält 4.1.10.
- **de.willuhn.util:** Der Tag `V_2_8_BUILD_219` passt zur Version und Build-Nummer im Manifest.
- **HBCI4Java:** Der Tag nennt die Version. Die Klassen unter `org/kapott/hbci/sepa/jaxb/` erzeugt der Build aus den XSD-Dateien unter `src/main/resources/`; sie haben keine eigene Quelldatei. Die Kartenleser-Bibliotheken des Pakets sind die Dateien unter `chipcard/lib/` des Tags, ihr Quelltext liegt unter `chipcard/src/`.
- **Im Repository von Jameica** liegen unter `lib.src/` außerdem die Quelltexte von PaperClips 1.0.4 (`lib/paperclips/net.sf.paperclips_1.0.4.jar`, EPL-1.0, ohne den Teil `net.sf.paperclips.ui`), SWTCalendar (`lib/swtcalendar/swtcalendar.jar`, LGPL-2.1) und NanoXML 2.2.3 (`lib/nanoxml/nanoxml-2.2.3.jar`); das Archiv enthält sie mit Jameica. Zu jeder Klasse dieser drei jar-Dateien gibt es dort die Quelldatei.

## Bibliotheken anderer Projekte mit Quelltext im Archiv

Das Archiv enthält den Quelltext der Bibliotheken unter einer Copyleft-Lizenz (GPL, LGPL, AGPL, MPL, EPL, CDDL, CPL), soweit ihr Projekt ihn für genau die mitgelieferte Version an einer prüfbaren Stelle veröffentlicht. Eine Bibliothek mit doppelter Lizenz, deren eine Wahl freizügig ist, steht unten bei den freizügigen. Jede jar-Datei in dieser Tabelle ist byteweise gleich mit der jar-Datei derselben Koordinaten auf Maven Central beziehungsweise derselben Datei auf der Download-Seite von Eclipse, außer H2 1.4.199 (siehe unten).

| Bibliothek | Datei im Paket | Version | Lizenz | Quelle im Archiv |
|---|---|---|---|---|
| H2 | `lib/h2/migration-h2/enabled/h2-2.3.232.jar` | 2.3.232 | MPL-2.0 oder EPL-1.0 | `com.h2database:h2:2.3.232` |
| H2 | `lib/h2/migration-h2/disabled/h2-1.4.199.jar`, `lib/h2/migration-h2/enabled/h2-1.4.199-fork.jar` | 1.4.199 | MPL-2.0 oder EPL-1.0 | `com.h2database:h2:1.4.199`, vom Hersteller geändert, siehe unten |
| MariaDB Connector/J | `lib/mariadb/mariadb-java-client-3.5.1.jar` | 3.5.1 | LGPL-2.1-or-later | `org.mariadb.jdbc:mariadb-java-client:3.5.1` |
| MySQL Connector/J | `lib/mysql/mysql-connector-j-9.2.0.jar` | 9.2.0 | GPL-2.0 mit Universal FOSS Exception 1.0 | `com.mysql:mysql-connector-j:9.2.0` |
| Rhino | `lib/mozilla_rhino/rhino-1.7.14.jar` | 1.7.14 | MPL-2.0 | `org.mozilla:rhino:1.7.14` |
| Rhino Engine | `lib/mozilla_rhino/rhino-engine-1.7.14.jar` | 1.7.14 | MPL-2.0 | `org.mozilla:rhino-engine:1.7.14` |
| SWT (Linux x86_64) | `lib/swt/linux64/swt.jar` | 3.131.0 | EPL-2.0 | `org.eclipse.platform:org.eclipse.swt.gtk.linux.x86_64:3.131.0` |
| Eclipse Core Commands | `lib/swt/org.eclipse.core.commands-3.10.300.jar` | 3.10.300 | EPL-2.0 | `org.eclipse.platform:org.eclipse.core.commands:3.10.300` |
| Eclipse Equinox Common | `lib/swt/org.eclipse.equinox.common-3.17.0.jar` | 3.17.0 | EPL-2.0 | `org.eclipse.platform:org.eclipse.equinox.common:3.17.0` |
| JFace | `lib/swt/org.eclipse.jface-3.28.0.jar` | 3.28.0 | EPL-2.0 | `org.eclipse.platform:org.eclipse.jface:3.28.0` |
| Equinox OSGi | `lib/swt/org.eclipse.osgi-3.18.200.jar` | 3.18.200 | EPL-2.0 | `org.eclipse.platform:org.eclipse.osgi:3.18.200` |
| Eclipse UI Forms | `lib/swt/org.eclipse.ui.forms_3.6.100.v20140422-1825.jar` | 3.6.100.v20140422-1825 | EPL-1.0 | [Quell-Bundle aus Eclipse 4.4](https://archive.eclipse.org/eclipse/updates/4.4/R-4.4-201406061215/plugins/org.eclipse.ui.forms.source_3.6.100.v20140422-1825.jar) |
| SWTChart | `plugins/hibiscus/lib/swtchart/org.eclipse.swtchart_0.14.0.202301260747.jar` | 0.14.0.202301260747 | EPL-2.0 | [Quell-Bundle von SWTChart 0.14.0](https://download.eclipse.org/swtchart/releases/0.14.0/repository/plugins/org.eclipse.swtchart.source_0.14.0.202301260747.jar) |
| SWTChart Extensions | `plugins/hibiscus/lib/swtchart/org.eclipse.swtchart.extensions_0.14.0.202301260747.jar` | 0.14.0.202301260747 | EPL-2.0 | [Quell-Bundle von SWTChart Extensions 0.14.0](https://download.eclipse.org/swtchart/releases/0.14.0/repository/plugins/org.eclipse.swtchart.extensions.source_0.14.0.202301260747.jar) |
| iText | `plugins/hibiscus/lib/itextpdf-5.5.13.4.jar` | 5.5.13.4 | AGPL-3.0 | `com.itextpdf:itextpdf:5.5.13.4` |
| iText PDF/A | `plugins/hibiscus/lib/itext-pdfa-5.5.13.4.jar` | 5.5.13.4 | AGPL-3.0 | `com.itextpdf:itext-pdfa:5.5.13.4` |
| Hibernate ORM | `plugins/jameica.sensors/lib/hibernate-core-5.6.15.Final.jar` | 5.6.15.Final | LGPL-2.1-or-later | `org.hibernate:hibernate-core:5.6.15.Final` |
| Hibernate Commons Annotations | `plugins/jameica.sensors/lib/hibernate-commons-annotations-5.1.2.Final.jar` | 5.1.2.Final | LGPL-2.1-or-later | `org.hibernate.common:hibernate-commons-annotations:5.1.2.Final` |
| JavaMail | `plugins/jameica.sensors/lib/javax.mail-1.6.2.jar` | 1.6.2 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `com.sun.mail:javax.mail:1.6.2` |
| JBoss Transaction API 1.2 | `plugins/jameica.sensors/lib/jboss-transaction-api_1.2_spec-1.1.1.Final.jar` | 1.1.1.Final | CDDL-1.0 oder GPL-2.0 mit Classpath Exception | `org.jboss.spec.javax.transaction:jboss-transaction-api_1.2_spec:1.1.1.Final` |
| StAX Ex | `plugins/jameica.sensors/lib/stax-ex-1.8.jar` | 1.8 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `org.jvnet.staxex:stax-ex:1.8` |
| JavaBeans Activation | `lib/java_ee/activation-1.1.1.jar` | 1.1.1 | CDDL-1.0 | `javax.activation:activation:1.1.1` |
| istack Commons Runtime | `lib/java_ee/istack-commons-runtime-3.0.5.jar` | 3.0.5 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `com.sun.istack:istack-commons-runtime:3.0.5` |
| Common Annotations | `lib/java_ee/javax.annotation-api-1.3.2.jar`, `plugins/jameica.webadmin/lib/javax.annotation-api-1.3.2.jar` | 1.3.2 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `javax.annotation:javax.annotation-api:1.3.2` |
| JAXB API | `lib/java_ee/jaxb-api-2.4.0-b180725.0427.jar` | 2.4.0-b180725.0427 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `javax.xml.bind:jaxb-api:2.4.0-b180725.0427` |
| JAXB Runtime | `lib/java_ee/jaxb-runtime-2.4.0-b180725.0644.jar` | 2.4.0-b180725.0644 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `org.glassfish.jaxb:jaxb-runtime:2.4.0-b180725.0644` |
| TXW2 | `lib/java_ee/txw2-2.4.0-b180725.0644.jar` | 2.4.0-b180725.0644 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `org.glassfish.jaxb:txw2:2.4.0-b180725.0644` |
| SOAP with Attachments API | `plugins/jameica.soap/lib/javax.xml.soap-api-1.4.0.jar`, `plugins/jameica.webadmin/lib/javax.xml.soap-api-1.4.0.jar` | 1.4.0 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `javax.xml.soap:javax.xml.soap-api:1.4.0` |
| WSDL4J | `plugins/jameica.soap/lib/wsdl4j-1.6.3.jar` | 1.6.3 | CPL-1.0 | `wsdl4j:wsdl4j:1.6.3` |
| Web Services Metadata API | `plugins/jameica.webadmin/lib/javax.jws-api-1.1.jar` | 1.1 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `javax.jws:javax.jws-api:1.1` |
| Servlet API | `plugins/jameica.webadmin/lib/javax.servlet-api-3.1.0.jar` | 3.1.0 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `javax.servlet:javax.servlet-api:3.1.0` |
| JAXB API | `plugins/jameica.webadmin/lib/jaxb-api-2.3.1.jar` | 2.3.1 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `javax.xml.bind:jaxb-api:2.3.1` |
| JAX-WS API | `plugins/jameica.webadmin/lib/jaxws-api-2.3.1.jar` | 2.3.1 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `javax.xml.ws:jaxws-api:2.3.1` |
| JavaBeans Activation API | `plugins/jameica.webadmin/lib/javax.activation-api-1.2.0.jar` | 1.2.0 | CDDL-1.1 oder GPL-2.0 mit Classpath Exception | `javax.activation:javax.activation-api:1.2.0` |
| Eclipse Compiler for Java | `plugins/jameica.webadmin/lib/ecj-3.19.0.jar` | 3.19.0 | EPL-2.0 | `org.eclipse.jdt:ecj:3.19.0` |

Die Lizenzen stehen in den POM-Dateien auf Maven Central, in den Manifesten und Lizenzdateien der jar-Dateien und im Paket neben den Bibliotheken, etwa `lib/<bibliothek>/license.txt` und `plugins/hibiscus/lib/license_*.txt`, im Image unter `/opt/hibiscus` an derselben Stelle.

## Bibliotheken unter freizügigen Lizenzen

Für diese Bibliotheken verlangt ihre Lizenz keinen Quelltext; das Archiv enthält ihn nicht. Ihre Projekte veröffentlichen ihn, die meisten unter denselben Koordinaten auf Maven Central.

- **Apache-2.0:** Apache Commons CLI 1.3.1, Collections 3.2.2, Lang 2.6, Logging 1.2, FileUpload 1.4 und IO 2.2; Velocity 1.7 und ORO 2.0.8; Apache XML-RPC 3.1.3 (Client, Common), 3.1 (Server) und WS Commons Util 1.0.2; XML Commons Resolver 1.1 und 1.2, Xalan Serializer 2.7.0; Apache CXF 3.2.6, Neethi 3.1.1, XmlSchema 2.2.3; Woodstox 5.1.0; HttpClient 5.2 und HttpCore 5.2; PDFBox, FontBox und PDFBox IO 3.0.2; Guava 31.1 und failureaccess 1.0.1; Jackson 2.14.1 und 2.15.2; Super CSV 2.4.0; Byte Buddy 1.12.18, ClassMate 1.5.1, Jandex 2.4.2, JBoss Logging 3.4.3, rrd4j 3.3.1; FastInfoset 1.2.15; Apache Jasper und EL 8.5.54 (`org.mortbay.jasper`).
- **Doppelte Lizenz mit freizügiger Wahl:** Jetty 9.4.38 mit `apache-jsp` und `jetty-schemas` 3.1.2 (Apache-2.0 oder EPL), Java Persistence API 2.2 (EPL-1.0 oder EDL-1.0), Eclipse Paho MQTT 1.2.5 (EPL-2.0 oder EDL-1.0).
- **BSD und EDL-1.0 (eine BSD-3-Clause-Lizenz):** Jakarta Activation 2.1.3, Angus Activation 2.0.2, Jakarta XML Binding 4.0.2, JAXB 4.0.5 (Core, Runtime, TXW2), istack Commons Runtime 4.1.2; ASM 5.2 und 9.0; PostgreSQL JDBC 42.5.4 (BSD-2-Clause); Stax2 API 4.1; ANTLR 2.7.7.
- **MIT und ähnliche:** Bouncy Castle 1.72, SLF4J 1.7.36 und 2.0.0-alpha1, QR Code generator 1.8.0, ICU4J 52.1 (ICU-Lizenz), JSON-java 20210307 (JSON-Lizenz), XML Commons External 1.0.b2 (`xml-apis`, Apache-2.0 mit W3C- und SAX-Teilen).

## Was das Archiv nicht enthält

- `h2-migration-changes`: `lib/h2/migration-h2/disabled/h2-1.4.199.jar` und `lib/h2/migration-h2/enabled/h2-1.4.199-fork.jar` sind nicht die jar-Datei von H2 1.4.199 auf Maven Central. Drei Einträge unterscheiden sich (`org/h2/mvstore/StreamStore.class`, `org/h2/util/data.zip`, das Manifest), und in der Fork-Datei hat der Hersteller die Pakete von `org.h2` nach `org.h14199` verschoben. Das Archiv enthält den Quelltext von H2 1.4.199; die Änderungen des Herstellers sind nirgends veröffentlicht, auch nicht in seinen Repositories.
- `paperclips-ui`: `lib/paperclips/net.sf.paperclips.ui_1.0.4.jar` (EPL-1.0). Der Quelltext von PaperClips unter `lib.src/` von Jameica enthält das Paket `net.sf.paperclips.ui` nicht, und eine andere Quelle für diese Version ist nicht bekannt.
- `libtowitoko`: `plugins/hibiscus/lib/libtowitoko-2.0.7.so` und `plugins/hibiscus/lib/libtowitoko-2.0.7-amd64.so`, ein Treiber für Kartenleser von Towitoko unter der GPL (`towitoko_info.xml`). Die Seite, die das Paket nennt, gibt es nicht mehr, und eine Quelle mit genau diesem Build ist nicht bekannt.
- `libhbci4java-card-freebsd`: `plugins/hibiscus/lib/libhbci4java-card-freebsd-64.so` liegt nicht unter `chipcard/lib/` des Tags von HBCI4Java. Vermutlich ist sie aus `chipcard/src/` gebaut, das im Archiv liegt; geprüft ist das nicht.
- `libhbci4java-sizrdh`: `plugins/hibiscus/lib/libhbci4java-sizrdh-linux-gcc2.so`, `plugins/hibiscus/lib/libhbci4java-sizrdh-linux-gcc3.so` und `plugins/hibiscus/lib/hbci4java-sizrdh-win32.dll`, eine Bibliothek für Schlüsseldateien im SIZ-RDH-Format. Das Repository von HBCI4Java enthält am Tag keinen Quelltext dafür; ihre Lizenz nennt das Paket nicht.
- `libct`: `plugins/hibiscus/lib/libct.so`, ein Kartenleser-Treiber seines Herstellers unter einer proprietären Lizenz (`kaan_info.xml`). Einen Quelltext gibt es nicht.
- Dass die Klassen der Plugins mit 2.11.0-nightly, von de.willuhn.datasource und OBanToo aus genau den gepinnten Commits übersetzt wurden, ist nicht bewiesen, siehe oben.

Die Dateien für Windows, die das Image entfernt (`jameicaserver.exe`, `jameica-win64.jar`, `lib/swt/win64`), sind nicht im Image und deshalb nicht im Archiv; die übrigen Dateien für andere Systeme als Linux liegen im Paket und im Image.

## Basis-Image

Die Laufzeit kommt aus `gcr.io/distroless/java21-debian12:nonroot` (Debian 12 mit Eclipse Temurin 21) des Projekts [distroless](https://github.com/GoogleContainerTools/distroless). Den Quelltext seiner Debian-Pakete gibt es über [sources.debian.org](https://sources.debian.org/) und für jede je veröffentlichte Version über [snapshot.debian.org](https://snapshot.debian.org/), den von Temurin über [adoptium/temurin-build](https://github.com/adoptium/temurin-build) und das OpenJDK. Das Release hängt diese Quelltexte nicht an: Sie wären um ein Vielfaches größer als die Software des Pakets, und Debian und das OpenJDK veröffentlichen sie selbst dauerhaft.

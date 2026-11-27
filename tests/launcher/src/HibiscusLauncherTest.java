// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.willuhn.jameica.Main;
import de.willuhn.jameica.system.Application;
import de.willuhn.logging.Level;
import de.willuhn.logging.Logger;
import de.willuhn.logging.Message;
import fakedb.FakeDatabase;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HibiscusLauncherTest
{
  private static final String SCHEMA = String.join("\n",
      "-- vendor schema",
      "CREATE TABLE konto (",
      "       id int(10) AUTO_INCREMENT",
      "     , PRIMARY KEY (id)",
      ") ENGINE=InnoDB;",
      "",
      "CREATE TABLE version (",
      "       id int(10) AUTO_INCREMENT",
      ") ENGINE=InnoDB;",
      "CREATE TABLE umsatz (id int(10)) ENGINE=InnoDB;",
      "ALTER TABLE umsatz ADD INDEX (id);",
      "INSERT INTO version (name,version) values ('db',71);",
      "");

  @TempDir
  Path tmp;

  private Path home;
  private Path data;
  private Path cfg;
  private Path masterPassword;
  private Path dbPassword;
  private String masterSecret;
  private String dbSecret;
  private Map<String, String> env;
  private ByteArrayOutputStream out;
  private ByteArrayOutputStream err;

  @BeforeEach
  void setUp() throws IOException
  {
    home = tmp.resolve("opt");
    Files.createDirectories(home.resolve("lib/mariadb"));
    Files.copy(Paths.get(System.getProperty("fakedb.jar")), home.resolve("lib/mariadb/mariadb-java-client-0.0.0.jar"));
    Files.createDirectories(home.resolve("plugins/hibiscus/sql"));
    Files.writeString(home.resolve("plugins/hibiscus/sql/mysql-create.sql"), SCHEMA, StandardCharsets.ISO_8859_1);

    data = tmp.resolve("data");
    cfg = data.resolve("cfg");
    Path secrets = Files.createDirectories(tmp.resolve("secrets"));
    masterSecret = "master-" + UUID.randomUUID();
    dbSecret = "db-" + UUID.randomUUID();
    masterPassword = Files.writeString(secrets.resolve("master-password"), masterSecret + "\n");
    dbPassword = Files.writeString(secrets.resolve("db-password"), dbSecret + "\r\nsecond line\n");

    env = new HashMap<>();
    env.put("HIBISCUS_WORKDIR", data.toString());
    env.put("HIBISCUS_MASTER_PASSWORD_FILE", masterPassword.toString());

    out = new ByteArrayOutputStream();
    err = new ByteArrayOutputStream();
    Main.lastArgs = null;
    Main.failure = null;
    Application.initErrors = null;
    Logger.targets.clear();
    FakeDatabase.reset();
  }

  @AfterEach
  void noSecretInOutput()
  {
    String all = out + "\n" + err;
    assertFalse(all.contains(masterSecret), "the master password reached the output");
    assertFalse(all.contains(dbSecret), "the database password reached the output");
  }

  private HibiscusLauncher launcher()
  {
    HibiscusLauncher l = new HibiscusLauncher(env, home, new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    l.retryDelayMillis = 0;
    return l;
  }

  private int run()
  {
    return launcher().run();
  }

  private Properties settings(String file) throws IOException
  {
    return HibiscusLauncher.load(cfg.resolve(file));
  }

  private String mode(Path p) throws IOException
  {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
  }

  private void assertRefused(String message)
  {
    assertEquals(HibiscusLauncher.EXIT_CONFIGURATION, run());
    assertTrue(err.toString().contains("configuration error: "), err.toString());
    assertTrue(err.toString().contains(message), err.toString());
    assertNull(Main.lastArgs, "Jameica must not start after a refusal");
  }

  private void useMariaDb()
  {
    env.put("HIBISCUS_DB_HOST", "db.example.com");
    env.put("HIBISCUS_DB_PASSWORD_FILE", dbPassword.toString());
  }

  // start

  @Test
  void startsJameicaInNonInteractiveServerModeWithThePasswordFile()
  {
    assertEquals(0, run());
    assertEquals(List.of("-d", "-n", "-f", data.toString(), "-w", masterPassword.toString()), List.of(Main.lastArgs));
    assertTrue(out.toString().contains("all plugins initialised"));
  }

  @Test
  void writesEverySettingsFileReadableForTheOwnerOnly() throws IOException
  {
    env.put("HIBISCUS_CERT_HOSTNAME", "hibiscus.example.com");
    assertEquals(0, run());
    assertEquals("rwx------", mode(data));
    assertEquals("rwx------", mode(cfg));
    for (String file : List.of(HibiscusLauncher.CFG_DB, HibiscusLauncher.CFG_PAYMENT, HibiscusLauncher.CFG_XMLRPC,
        HibiscusLauncher.CFG_WEBADMIN, HibiscusLauncher.CFG_UPDATE, HibiscusLauncher.CFG_CALLBACK, HibiscusLauncher.CFG_SYSTEM))
      assertEquals("rw-------", mode(cfg.resolve(file)), file);
  }

  @Test
  void replacesAWiderFileAndKeepsTheKeysItDoesNotManage() throws IOException
  {
    Files.createDirectories(cfg);
    Path file = cfg.resolve(HibiscusLauncher.CFG_WEBADMIN);
    Files.writeString(file, "listener.http.port=9999\nother.key=kept\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    Files.writeString(cfg.resolve(HibiscusLauncher.CFG_WEBADMIN + ".tmp"), "stale");
    assertEquals(0, run());
    assertEquals("rw-------", mode(file));
    assertEquals("kept", settings(HibiscusLauncher.CFG_WEBADMIN).getProperty("other.key"));
    assertEquals("8080", settings(HibiscusLauncher.CFG_WEBADMIN).getProperty("listener.http.port"));
    assertFalse(Files.exists(cfg.resolve(HibiscusLauncher.CFG_WEBADMIN + ".tmp")));
  }

  @Test
  void warnsAboutAUserDirectoryOpenToOthers() throws IOException
  {
    Files.createDirectories(data, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-x---")));
    assertEquals(0, run());
    assertTrue(out.toString().contains("WARNING: user directory " + data + " is open to group or others"), out.toString());
  }

  @Test
  void keepsQuietAboutAPrivateUserDirectory()
  {
    assertEquals(0, run());
    assertFalse(out.toString().contains("is open to group or others"));
  }

  @Test
  void startsAgainOnAnExistingUserDirectory()
  {
    assertEquals(0, run());
    assertEquals(0, run());
  }

  @Test
  void endsWithThePluginStatusWhenAPluginFailedToInitialise()
  {
    Map<Application.Manifest, Throwable> errors = new LinkedHashMap<>();
    errors.put(new Application.Manifest("hibiscus"), new IllegalStateException("database not found"));
    errors.put(new Application.Manifest("jameica.soap"), null);
    Application.initErrors = errors;
    assertEquals(HibiscusLauncher.EXIT_PLUGIN, run());
    assertTrue(out.toString().contains("ERROR: plugin hibiscus failed to initialise: database not found"));
    assertTrue(out.toString().contains("ERROR: plugin jameica.soap failed to initialise: unknown"));
  }

  @Test
  void keepsRunningAfterAPluginErrorWhenTheOperatorSaysSo()
  {
    Application.initErrors = Map.of(new Application.Manifest("hibiscus"), new IllegalStateException("x"));
    env.put("HIBISCUS_EXIT_ON_PLUGIN_ERROR", "false");
    assertEquals(0, run());
  }

  @Test
  void treatsAnEmptyErrorListAsSuccess()
  {
    Application.initErrors = Map.of();
    assertEquals(0, run());
  }

  @Test
  void reportsAFailedJameicaStartWithItsCause()
  {
    Main.failure = new IllegalStateException("wrong master password");
    assertEquals(HibiscusLauncher.EXIT_FAILURE, run());
    assertTrue(err.toString().contains("start failed: java.lang.IllegalStateException: wrong master password"), err.toString());
  }

  @Test
  void reportsAnUnusableUserDirectory() throws IOException
  {
    Files.writeString(data, "not a directory");
    assertEquals(HibiscusLauncher.EXIT_FAILURE, run());
    assertTrue(err.toString().contains("start failed: "), err.toString());
  }

  @Test
  void refusesAUserDirectoryItCannotWrite() throws IOException
  {
    Files.createDirectories(data, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r-x------")));
    try
    {
      assertRefused("user directory " + data + " is not writable");
    }
    finally
    {
      Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void mainExitsWithTheCodeOfARefusal()
  {
    int[] code = { -1 };
    PrintStream stdout = System.out;
    PrintStream stderr = System.err;
    HibiscusLauncher.exit = c -> code[0] = c;
    HibiscusLauncher.environment = Map.of("HIBISCUS_MASTER_PASSWORD_FILE", tmp.resolve("missing").toString());
    try
    {
      HibiscusLauncher.main(new String[0]);
      assertEquals(HibiscusLauncher.EXIT_CONFIGURATION, code[0]);
      code[0] = -1;
      HibiscusLauncher.environment = env;
      HibiscusLauncher.main(new String[0]);
      assertEquals(-1, code[0], "a running server must not exit");
      assertTrue(System.out instanceof HibiscusLauncher.KeepOpenPrintStream);
    }
    finally
    {
      System.setOut(stdout);
      System.setErr(stderr);
      HibiscusLauncher.exit = System::exit;
      HibiscusLauncher.environment = System.getenv();
    }
  }

  @Test
  void streamsStayOpenWhenJameicaClosesThem()
  {
    PrintStream inner = new PrintStream(out, true, StandardCharsets.UTF_8);
    PrintStream stream = new HibiscusLauncher.KeepOpenPrintStream(inner);
    stream.close();
    stream.println("still there");
    assertTrue(out.toString().contains("still there"));
    assertFalse(stream.checkError());
  }

  // password files

  @Test
  void refusesAMissingMasterPasswordFileWithoutStartingJameica()
  {
    env.put("HIBISCUS_MASTER_PASSWORD_FILE", tmp.resolve("missing").toString());
    assertRefused("HIBISCUS_MASTER_PASSWORD_FILE: " + tmp.resolve("missing") + " is missing or not readable");
  }

  @Test
  void refusesADirectoryAsPasswordFile()
  {
    env.put("HIBISCUS_MASTER_PASSWORD_FILE", tmp.toString());
    assertRefused("is missing or not readable");
  }

  @Test
  void refusesAPasswordFileItCannotRead() throws IOException
  {
    Files.setPosixFilePermissions(masterPassword, PosixFilePermissions.fromString("---------"));
    assertRefused("is missing or not readable");
  }

  @Test
  void refusesAnEmptyPasswordFileAndAnEmptyFirstLine() throws IOException
  {
    Files.writeString(masterPassword, "");
    assertRefused("first line of " + masterPassword + " is empty");
    Files.writeString(masterPassword, "\n" + masterSecret + "\n");
    err.reset();
    assertRefused("first line of " + masterPassword + " is empty");
  }

  @Test
  void readsTheFirstLineUntrimmedLikeJameica() throws IOException
  {
    Path file = Files.writeString(tmp.resolve("pw"), " spaced \r\nnext\n");
    assertEquals(" spaced ", HibiscusLauncher.firstLine(file));
  }

  // database: H2

  @Test
  void usesTheEmbeddedDatabaseWithoutAHost() throws IOException
  {
    Files.createDirectories(cfg);
    Files.writeString(cfg.resolve(HibiscusLauncher.CFG_DB), "database.driver=" + HibiscusLauncher.DRIVER_MYSQL
        + "\ndatabase.driver.mysql.password=old\ndatabase.driver.mysql.jdbcurl=x\ndatabase.driver.mysql.username=u\nencrypt=true\n");
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_DB);
    assertEquals(HibiscusLauncher.DRIVER_H2, p.getProperty("database.driver"));
    assertNull(p.getProperty("database.driver.mysql.password"));
    assertNull(p.getProperty("database.driver.mysql.jdbcurl"));
    assertNull(p.getProperty("database.driver.mysql.username"));
    assertNull(p.getProperty("encrypt"));
    assertTrue(out.toString().contains("WARNING: switching database driver from " + HibiscusLauncher.DRIVER_MYSQL + " to embedded H2"));
  }

  @Test
  void warnsOnlyWhenTheDriverChanges()
  {
    assertEquals(0, run());
    assertEquals(0, run());
    assertFalse(out.toString().contains("WARNING: switching"));
  }

  // database: MariaDB

  @Test
  void createsTheVendorSchemaInAnEmptyDatabase() throws IOException
  {
    useMariaDb();
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_DB);
    assertEquals(HibiscusLauncher.DRIVER_MYSQL, p.getProperty("database.driver"));
    assertEquals("jdbc:mariadb://db.example.com:3306/hibiscus?useUnicode=Yes&characterEncoding=ISO8859_1",
        p.getProperty("database.driver.mysql.jdbcurl"));
    assertEquals("hibiscus", p.getProperty("database.driver.mysql.username"));
    assertEquals(dbSecret, p.getProperty("database.driver.mysql.password"));
    assertEquals("true", p.getProperty("encrypt"));
    assertEquals(dbSecret, FakeDatabase.lastProperties.getProperty("password"));
    assertEquals("hibiscus", FakeDatabase.lastProperties.getProperty("user"));
    assertEquals(List.of("konto", "version", "umsatz"), List.copyOf(FakeDatabase.tables));
    assertEquals(1, FakeDatabase.versionRows);
    assertEquals(5, FakeDatabase.executed.size());
    assertEquals(1, FakeDatabase.closedConnections);
    assertTrue(out.toString().contains("creating schema from plugins/hibiscus/sql/mysql-create.sql (5 statements)"), out.toString());
    assertTrue(out.toString().contains("database: MariaDB db.example.com:3306/hibiscus as hibiscus"));
  }

  @Test
  void buildsTheUrlFromTheSettings() throws IOException
  {
    useMariaDb();
    env.put("HIBISCUS_DB_HOST", "[fd00::5]");
    env.put("HIBISCUS_DB_PORT", "3307");
    env.put("HIBISCUS_DB_NAME", "bank_1");
    env.put("HIBISCUS_DB_USER", "reader");
    env.put("HIBISCUS_DB_JDBC_PARAMS", "");
    env.put("HIBISCUS_DB_ENCRYPT_PASSWORD", "no");
    env.put("HIBISCUS_DB_INIT_SCHEMA", "1");
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_DB);
    assertEquals("jdbc:mariadb://[fd00::5]:3307/bank_1", p.getProperty("database.driver.mysql.jdbcurl"));
    assertEquals("jdbc:mariadb://[fd00::5]:3307/bank_1", FakeDatabase.lastUrl);
    assertEquals("reader", p.getProperty("database.driver.mysql.username"));
    assertEquals("false", p.getProperty("encrypt"));
  }

  @Test
  void warnsWhenSwitchingFromTheEmbeddedDatabase() throws IOException
  {
    assertEquals(0, run());
    useMariaDb();
    assertEquals(0, run());
    assertTrue(out.toString().contains("WARNING: switching database driver from " + HibiscusLauncher.DRIVER_H2 + " to MariaDB"));
  }

  @Test
  void leavesAnExistingSchemaAlone()
  {
    useMariaDb();
    FakeDatabase.tables.add("version");
    FakeDatabase.versionRows = 1;
    assertEquals(0, run());
    assertTrue(FakeDatabase.executed.isEmpty());
    assertTrue(out.toString().contains("database schema present"));
  }

  @Test
  void refusesAVersionTableWithoutSchemaVersion()
  {
    useMariaDb();
    FakeDatabase.tables.add("version");
    assertRefused("the schema is incomplete");
  }

  @Test
  void refusesADatabaseWithForeignTables()
  {
    useMariaDb();
    FakeDatabase.tables.add("customers");
    assertRefused("database contains 1 tables but no Hibiscus version table");
    assertTrue(FakeDatabase.executed.isEmpty());
  }

  @Test
  void refusesAnEmptyDatabaseWhenSchemaCreationIsOff()
  {
    useMariaDb();
    env.put("HIBISCUS_DB_INIT_SCHEMA", "false");
    assertRefused("HIBISCUS_DB_INIT_SCHEMA=false");
  }

  @Test
  void refusesToCreateTheSchemaInAnotherCharacterSet()
  {
    useMariaDb();
    FakeDatabase.charset = "utf8mb4";
    assertRefused("refusing to create the schema in a utf8mb4 database");
    assertTrue(out.toString().contains("WARNING: database default character set is utf8mb4"));
    assertTrue(FakeDatabase.executed.isEmpty());
  }

  @Test
  void createsTheSchemaInAnotherCharacterSetWhenAllowed()
  {
    useMariaDb();
    FakeDatabase.charset = "latin1";
    env.put("HIBISCUS_DB_ALLOW_CHARSET", "true");
    assertEquals(0, run());
    assertEquals(1, FakeDatabase.versionRows);
  }

  @Test
  void treatsAnUnknownCharacterSetAsForeign()
  {
    useMariaDb();
    FakeDatabase.charset = null;
    assertRefused("refusing to create the schema in a null database");
  }

  @Test
  void acceptsUtf8AndUtf8mb3Only()
  {
    assertTrue(HibiscusLauncher.isVendorCharset("utf8"));
    assertTrue(HibiscusLauncher.isVendorCharset("UTF8MB3"));
    assertFalse(HibiscusLauncher.isVendorCharset("utf8mb4"));
    assertFalse(HibiscusLauncher.isVendorCharset(null));
  }

  @Test
  void dropsTheTablesItCreatedWhenTheSchemaFails()
  {
    useMariaDb();
    FakeDatabase.failOn = "ALTER TABLE";
    assertRefused("creating the schema failed, the database is empty again: syntax error near ALTER TABLE");
    assertTrue(FakeDatabase.tables.isEmpty(), FakeDatabase.tables.toString());
    assertEquals(List.of("DROP TABLE IF EXISTS umsatz", "DROP TABLE IF EXISTS version", "DROP TABLE IF EXISTS konto"),
        FakeDatabase.executed.subList(FakeDatabase.executed.size() - 3, FakeDatabase.executed.size()));
  }

  @Test
  void waitsForTheDatabase()
  {
    useMariaDb();
    FakeDatabase.failingConnects = 2;
    assertEquals(0, run());
    assertTrue(out.toString().contains("database not reachable yet (connection refused), retrying"));
  }

  @Test
  void givesUpWhenTheDatabaseStaysUnreachable()
  {
    useMariaDb();
    env.put("HIBISCUS_DB_WAIT_SECONDS", "0");
    FakeDatabase.failingConnects = 1;
    assertRefused("database not reachable within 0 s: connection refused");
  }

  @Test
  void refusesAUrlTheDriverDoesNotAccept()
  {
    useMariaDb();
    FakeDatabase.acceptsUrl = false;
    assertRefused("JDBC driver does not accept the URL");
  }

  @Test
  void refusesWithoutTheMariaDbDriver() throws IOException
  {
    useMariaDb();
    Files.delete(home.resolve("lib/mariadb/mariadb-java-client-0.0.0.jar"));
    assertRefused("no MariaDB JDBC driver found in lib/mariadb");
    Files.delete(home.resolve("lib/mariadb"));
    err.reset();
    assertRefused("no MariaDB JDBC driver found in lib/mariadb");
  }

  @Test
  void refusesASchemaScriptWithoutTerminator() throws IOException
  {
    useMariaDb();
    Files.writeString(home.resolve("plugins/hibiscus/sql/mysql-create.sql"), "CREATE TABLE konto (id int)\n");
    assertRefused("schema script ends without a terminating ';'");
  }

  @Test
  void refusesInvalidDatabaseSettings()
  {
    useMariaDb();
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("HIBISCUS_DB_HOST=db;drop", "HIBISCUS_DB_HOST must be a host name");
    cases.put("HIBISCUS_DB_HOST=fd00::5", "IPv6 address in brackets");
    cases.put("HIBISCUS_DB_PORT=x", "HIBISCUS_DB_PORT is not a number: x");
    cases.put("HIBISCUS_DB_PORT=0", "HIBISCUS_DB_PORT out of range: 0");
    cases.put("HIBISCUS_DB_PORT=65536", "HIBISCUS_DB_PORT out of range: 65536");
    cases.put("HIBISCUS_DB_NAME=a-b", "HIBISCUS_DB_NAME contains unsupported characters");
    cases.put("HIBISCUS_DB_JDBC_PARAMS=useSsl=true&password=x", "HIBISCUS_DB_JDBC_PARAMS must not set password");
    cases.put("HIBISCUS_DB_JDBC_PARAMS=User=x", "HIBISCUS_DB_JDBC_PARAMS must not set User");
    cases.put("HIBISCUS_DB_WAIT_SECONDS=-1", "HIBISCUS_DB_WAIT_SECONDS must not be negative");
    cases.put("HIBISCUS_DB_ENCRYPT_PASSWORD=maybe", "HIBISCUS_DB_ENCRYPT_PASSWORD must be true or false: maybe");
    cases.put("HIBISCUS_DB_PASSWORD_FILE=" + tmp.resolve("missing"), "HIBISCUS_DB_PASSWORD_FILE: ");
    for (Map.Entry<String, String> c : cases.entrySet())
    {
      String[] kv = c.getKey().split("=", 2);
      String before = env.put(kv[0], kv[1]);
      err.reset();
      assertRefused(c.getValue());
      env.put(kv[0], before == null ? "" : before);
      if (before == null)
        env.remove(kv[0]);
    }
  }

  @Test
  void allowsOtherJdbcParameters()
  {
    HibiscusLauncher.rejectCredentialParameters("useSsl=true&passwordCharacterEncoding=UTF-8&userx");
  }

  @Test
  void splitsTheVendorScriptIntoStatements()
  {
    List<String> statements = HibiscusLauncher.splitSql("-- head\r\nCREATE TABLE a (\r\n  id int\r\n);\r\n\r\n  -- note\nINSERT INTO a VALUES (1);\n");
    assertEquals(List.of("CREATE TABLE a (\n  id int\n)", "INSERT INTO a VALUES (1)"), statements);
    assertEquals(List.of(), HibiscusLauncher.splitSql("-- only a comment\n\n"));
    assertThrows(IllegalStateException.class, () -> HibiscusLauncher.splitSql("SELECT 1"));
  }

  // scheduler

  @Test
  void rendersTheSchedulerDefaults() throws IOException
  {
    Files.createDirectories(cfg);
    Files.writeString(cfg.resolve(HibiscusLauncher.CFG_PAYMENT), "notify.url=https://old.example.com\n");
    assertEquals(0, run());
    env.put("HIBISCUS_NOTIFY_URL", "https://hooks.example.com/hibiscus");
    assertEquals(0, run());
    assertEquals("https://hooks.example.com/hibiscus", settings(HibiscusLauncher.CFG_PAYMENT).getProperty("notify.url"));
    env.remove("HIBISCUS_NOTIFY_URL");
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_PAYMENT);
    assertEquals("360", p.getProperty("scheduler.interval.minutes"));
    assertEquals("23", p.getProperty("scheduler.exclude.from"));
    assertEquals("5", p.getProperty("scheduler.exclude.to"));
    for (int day = 1; day <= 7; day++)
      assertEquals("false", p.getProperty("scheduler.exclude.day." + day));
    assertEquals("true", p.getProperty("scheduler.stoponerror"));
    assertNull(p.getProperty("notify.url"));
    assertEquals("120", p.getProperty("tan.decoupled.wait.seconds"));
    assertTrue(out.toString().contains("notify.url unset"));
  }

  @Test
  void rendersTheSchedulerSettings() throws IOException
  {
    env.put("HIBISCUS_SCHEDULER_INTERVAL_MINUTES", " 720 ");
    env.put("HIBISCUS_SCHEDULER_EXCLUDE_FROM", "0");
    env.put("HIBISCUS_SCHEDULER_EXCLUDE_TO", "23");
    env.put("HIBISCUS_SCHEDULER_EXCLUDE_DAYS", "sat, SUN,,");
    env.put("HIBISCUS_SCHEDULER_STOP_ON_ERROR", "0");
    env.put("HIBISCUS_NOTIFY_URL", "http://hooks.example.com/hibiscus");
    env.put("HIBISCUS_TAN_DECOUPLED_WAIT_SECONDS", "300");
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_PAYMENT);
    assertEquals("720", p.getProperty("scheduler.interval.minutes"));
    assertEquals("0", p.getProperty("scheduler.exclude.from"));
    assertEquals("23", p.getProperty("scheduler.exclude.to"));
    assertEquals("true", p.getProperty("scheduler.exclude.day.1"));
    assertEquals("true", p.getProperty("scheduler.exclude.day.7"));
    assertEquals("false", p.getProperty("scheduler.exclude.day.2"));
    assertEquals("false", p.getProperty("scheduler.stoponerror"));
    assertEquals("http://hooks.example.com/hibiscus", p.getProperty("notify.url"));
    assertEquals("300", p.getProperty("tan.decoupled.wait.seconds"));
    assertFalse(out.toString().contains("hooks.example.com"), "the notify URL can carry credentials");
  }

  @Test
  void allowsAShortIntervalOnlyWithTheOverride() throws IOException
  {
    env.put("HIBISCUS_SCHEDULER_INTERVAL_MINUTES", "60");
    assertRefused("HIBISCUS_SCHEDULER_INTERVAL_MINUTES=60 allows more than four fetches a day");
    env.put("HIBISCUS_SCHEDULER_ALLOW_SHORT_INTERVAL", "yes");
    assertEquals(0, run());
    assertEquals("60", settings(HibiscusLauncher.CFG_PAYMENT).getProperty("scheduler.interval.minutes"));
  }

  @Test
  void refusesInvalidSchedulerSettings()
  {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("HIBISCUS_SCHEDULER_INTERVAL_MINUTES=0", "HIBISCUS_SCHEDULER_INTERVAL_MINUTES must be positive");
    cases.put("HIBISCUS_SCHEDULER_INTERVAL_MINUTES=-5", "HIBISCUS_SCHEDULER_INTERVAL_MINUTES must be positive");
    cases.put("HIBISCUS_SCHEDULER_INTERVAL_MINUTES=6h", "HIBISCUS_SCHEDULER_INTERVAL_MINUTES is not a number: 6h");
    cases.put("HIBISCUS_SCHEDULER_EXCLUDE_FROM=24", "HIBISCUS_SCHEDULER_EXCLUDE_FROM must be an hour between 0 and 23");
    cases.put("HIBISCUS_SCHEDULER_EXCLUDE_TO=-1", "HIBISCUS_SCHEDULER_EXCLUDE_TO must be an hour between 0 and 23");
    cases.put("HIBISCUS_SCHEDULER_EXCLUDE_DAYS=MON,FUN", "unknown day FUN (use MON..SUN)");
    cases.put("HIBISCUS_NOTIFY_URL=ftp://example.com", "HIBISCUS_NOTIFY_URL must start with http:// or https://");
    cases.put("HIBISCUS_TAN_DECOUPLED_WAIT_SECONDS=0", "HIBISCUS_TAN_DECOUPLED_WAIT_SECONDS must be positive");
    cases.put("HIBISCUS_SCHEDULER_STOP_ON_ERROR=on", "HIBISCUS_SCHEDULER_STOP_ON_ERROR must be true or false: on");
    assertAllRefused(cases);
  }

  private void assertAllRefused(Map<String, String> cases)
  {
    for (Map.Entry<String, String> c : cases.entrySet())
    {
      String[] kv = c.getKey().split("=", 2);
      env.put(kv[0], kv[1]);
      err.reset();
      assertRefused(c.getValue());
      env.remove(kv[0]);
    }
  }

  @Test
  void mapsDayNamesToJameicaDayNumbers()
  {
    assertEquals(List.of(1, 2, 7), List.copyOf(HibiscusLauncher.days("SUN,mon,Sat")));
    assertTrue(HibiscusLauncher.days("").isEmpty());
  }

  // XML-RPC

  @Test
  void sharesOnlyTheConfiguredXmlRpcServices() throws IOException
  {
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_XMLRPC);
    for (String id : HibiscusLauncher.XMLRPC_SERVICES)
      assertEquals(Boolean.toString(id.equals("konto") || id.equals("umsatz")), p.getProperty("hibiscus.xmlrpc." + id + ".shared"), id);
    assertEquals("false", p.getProperty("jameica.xmlrpc.echo.shared"));
    assertTrue(out.toString().contains("xml-rpc: shared services [konto, umsatz]"));
  }

  @Test
  void sharesNoXmlRpcServiceWithNone() throws IOException
  {
    env.put("HIBISCUS_XMLRPC_SERVICES", " none ");
    env.put("HIBISCUS_XMLRPC_ECHO", "true");
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_XMLRPC);
    for (String id : HibiscusLauncher.XMLRPC_SERVICES)
      assertEquals("false", p.getProperty("hibiscus.xmlrpc." + id + ".shared"), id);
    assertEquals("true", p.getProperty("jameica.xmlrpc.echo.shared"));
  }

  @Test
  void acceptsServiceIdsInAnyCase() throws IOException
  {
    env.put("HIBISCUS_XMLRPC_SERVICES", "Address,, SEPAUEBERWEISUNG");
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_XMLRPC);
    assertEquals("true", p.getProperty("hibiscus.xmlrpc.address.shared"));
    assertEquals("true", p.getProperty("hibiscus.xmlrpc.sepaueberweisung.shared"));
    assertEquals("false", p.getProperty("hibiscus.xmlrpc.konto.shared"));
  }

  @Test
  void refusesAnUnknownXmlRpcService()
  {
    env.put("HIBISCUS_XMLRPC_SERVICES", "konto,transfer");
    assertRefused("unknown XML-RPC service in HIBISCUS_XMLRPC_SERVICES: transfer");
  }

  // web interface

  @Test
  void rendersTheListenerWithTlsAndAuthentication() throws IOException
  {
    Files.createDirectories(cfg);
    Files.writeString(cfg.resolve(HibiscusLauncher.CFG_WEBADMIN), "listener.http.address=10.0.0.1\nlistener.http.auth=false\n");
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_WEBADMIN);
    assertEquals("8080", p.getProperty("listener.http.port"));
    assertNull(p.getProperty("listener.http.address"));
    assertEquals("true", p.getProperty("listener.http.ssl"));
    assertEquals("true", p.getProperty("listener.http.auth"));
  }

  @Test
  void bindsTheListenerToTheConfiguredAddressAndPort() throws IOException
  {
    env.put("HIBISCUS_HTTP_PORT", "8443");
    env.put("HIBISCUS_HTTP_ADDRESS", "0.0.0.0");
    assertEquals(0, run());
    Properties p = settings(HibiscusLauncher.CFG_WEBADMIN);
    assertEquals("8443", p.getProperty("listener.http.port"));
    assertEquals("0.0.0.0", p.getProperty("listener.http.address"));
  }

  @Test
  void refusesPlainHttpOnANetworkAddress()
  {
    env.put("HIBISCUS_HTTP_TLS", "false");
    assertRefused("HIBISCUS_HTTP_TLS=false sends the master password without encryption");
    env.put("HIBISCUS_HTTP_ADDRESS", "10.0.0.1");
    err.reset();
    assertRefused("HIBISCUS_HTTP_TLS=false sends the master password without encryption");
  }

  @Test
  void allowsPlainHttpOnLoopbackOrWithTheOverride() throws IOException
  {
    env.put("HIBISCUS_HTTP_TLS", "false");
    env.put("HIBISCUS_HTTP_ADDRESS", "127.0.0.1");
    assertEquals(0, run());
    assertEquals("false", settings(HibiscusLauncher.CFG_WEBADMIN).getProperty("listener.http.ssl"));
    env.remove("HIBISCUS_HTTP_ADDRESS");
    env.put("HIBISCUS_HTTP_ALLOW_PLAINTEXT", "true");
    assertEquals(0, run());
    assertEquals("false", settings(HibiscusLauncher.CFG_WEBADMIN).getProperty("listener.http.ssl"));
  }

  @Test
  void recognisesLoopbackAddresses()
  {
    assertTrue(HibiscusLauncher.isLoopback("localhost"));
    assertTrue(HibiscusLauncher.isLoopback("::1"));
    assertTrue(HibiscusLauncher.isLoopback("127.0.0.1"));
    assertTrue(HibiscusLauncher.isLoopback("127.10.20.30"));
    assertFalse(HibiscusLauncher.isLoopback(""));
    assertFalse(HibiscusLauncher.isLoopback("0.0.0.0"));
    assertFalse(HibiscusLauncher.isLoopback("127.0.0.1.example.com"));
  }

  @Test
  void refusesInvalidListenerSettings()
  {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("HIBISCUS_HTTP_PORT=80", "HIBISCUS_HTTP_PORT must be between 1024 and 65535");
    cases.put("HIBISCUS_HTTP_PORT=65536", "HIBISCUS_HTTP_PORT must be between 1024 and 65535");
    cases.put("HIBISCUS_HTTP_ADDRESS=0.0.0.0/0", "HIBISCUS_HTTP_ADDRESS must be a host name or an IP address");
    cases.put("HIBISCUS_HTTP_TLS=off", "HIBISCUS_HTTP_TLS must be true or false: off");
    assertAllRefused(cases);
  }

  // system

  @Test
  void rendersTheSystemSettings() throws IOException
  {
    env.put("HIBISCUS_BACKUP_ON_SHUTDOWN", "false");
    env.put("HIBISCUS_BACKUP_COUNT", "3");
    env.put("HIBISCUS_LOG_LEVEL", "warn");
    env.put("HIBISCUS_CERT_HOSTNAME", "hibiscus.example.com");
    assertEquals(0, run());
    assertEquals("false", settings(HibiscusLauncher.CFG_UPDATE).getProperty("update.check"));
    assertEquals("hibiscus.example.com", settings(HibiscusLauncher.CFG_CALLBACK).getProperty("jameica.hostname"));
    Properties s = settings(HibiscusLauncher.CFG_SYSTEM);
    assertEquals("false", s.getProperty("jameica.system.rmi.shareservices"));
    assertEquals("false", s.getProperty("jameica.system.multicastlookup"));
    assertEquals("false", s.getProperty("jameica.system.backup.enabled"));
    assertEquals("3", s.getProperty("jameica.system.backup.count"));
    assertEquals("WARN", s.getProperty("jameica.system.log.level"));
  }

  @Test
  void leavesTheHostNameAndLogLevelToJameicaWhenUnset() throws IOException
  {
    assertEquals(0, run());
    assertFalse(Files.exists(cfg.resolve(HibiscusLauncher.CFG_CALLBACK)));
    Properties s = settings(HibiscusLauncher.CFG_SYSTEM);
    assertNull(s.getProperty("jameica.system.log.level"));
    assertEquals("true", s.getProperty("jameica.system.backup.enabled"));
    assertEquals("10", s.getProperty("jameica.system.backup.count"));
  }

  @Test
  void refusesInvalidSystemSettings()
  {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("HIBISCUS_CERT_HOSTNAME=host name", "HIBISCUS_CERT_HOSTNAME contains unsupported characters");
    cases.put("HIBISCUS_BACKUP_COUNT=0", "HIBISCUS_BACKUP_COUNT must be at least 1");
    cases.put("HIBISCUS_LOG_LEVEL=TRACE", "HIBISCUS_LOG_LEVEL must be DEBUG, INFO, WARN or ERROR");
    cases.put("HIBISCUS_LOG_STDOUT_LEVEL=ALL", "HIBISCUS_LOG_STDOUT_LEVEL must be OFF, ERROR, WARN, INFO or DEBUG");
    assertAllRefused(cases);
  }

  // log on stdout

  @Test
  void copiesWarningsAndErrorsOfJameicaToStdoutByDefault() throws Exception
  {
    assertEquals(0, run());
    assertEquals(1, Logger.targets.size());
    Logger.write(new Message(Level.INFO, "fetched statements of account DE00"));
    Logger.write(new Message(Level.WARN, "bank answered with a warning"));
    Logger.write(new Message(Level.ERROR, "synchronisation failed"));
    assertFalse(out.toString().contains("DE00"));
    assertTrue(out.toString().contains("[WARN] bank answered with a warning"));
    assertTrue(out.toString().contains("[ERROR] synchronisation failed"));
    assertTrue(out.toString().contains("Jameica log to stdout from level WARN"));
  }

  @Test
  void copiesTheConfiguredLevelAndPassesTargetErrorsOn() throws Exception
  {
    env.put("HIBISCUS_LOG_STDOUT_LEVEL", "info");
    assertEquals(0, run());
    Logger.write(new Message(Level.DEBUG, "dialog details"));
    Logger.write(new Message(Level.INFO, "info line"));
    assertFalse(out.toString().contains("dialog details"));
    assertTrue(out.toString().contains("[INFO] info line"));
    assertThrows(IOException.class, () -> Logger.write(new Message(Level.ERROR, "FAIL")));
    Logger.targets.get(0).close();
  }

  @Test
  void copiesNothingWithOff()
  {
    env.put("HIBISCUS_LOG_STDOUT_LEVEL", "OFF");
    assertEquals(0, run());
    assertTrue(Logger.targets.isEmpty());
  }
}

// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Container entry point for Hibiscus Server.
 *
 * Renders the settings that are managed through environment variables into the
 * Jameica user directory, then starts Jameica in the same JVM in non-interactive
 * server mode with the master password read from a file.
 */
public final class HibiscusLauncher
{
  static final int EXIT_CONFIGURATION = 2;
  static final int EXIT_PLUGIN = 3;
  static final int EXIT_FAILURE = 1;

  static final String CFG_DB        = "de.willuhn.jameica.hbci.rmi.HBCIDBService.properties";
  static final String CFG_PAYMENT   = "de.willuhn.jameica.hbci.payment.Plugin.properties";
  static final String CFG_XMLRPC    = "de.willuhn.jameica.xmlrpc.Plugin.properties";
  static final String CFG_WEBADMIN  = "de.willuhn.jameica.webadmin.Plugin.properties";
  static final String CFG_UPDATE    = "de.willuhn.jameica.services.UpdateService.properties";
  static final String CFG_CALLBACK  = "de.willuhn.jameica.system.ApplicationCallback.properties";
  static final String CFG_SYSTEM    = "de.willuhn.jameica.system.Config.properties";

  static final String DRIVER_MYSQL  = "de.willuhn.jameica.hbci.server.DBSupportMySqlImpl";
  static final String DRIVER_H2     = "de.willuhn.jameica.hbci.server.DBSupportH2Impl";

  // The vendor advises at most four automatic fetches per account and day.
  static final int MIN_INTERVAL_MINUTES = 360;

  // Service ids of the bundled XML-RPC plugins, including ids that only the
  // vendor's default configuration still mentions, so every one is set explicitly.
  static final String[] XMLRPC_SERVICES = {
    "address", "konto", "umsatz", "sepaueberweisung", "sepalastschrift",
    "sepasammellastschrift", "sepasammelueberweisung", "ueberweisung",
    "lastschrift", "sammellastschrift", "sammelueberweisung"
  };

  private static final String[] DAY_NAMES = { "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT" };

  private static final String[] STDOUT_LEVELS = { "OFF", "ERROR", "WARN", "INFO", "DEBUG" };

  private static final Pattern CREATE_TABLE = Pattern.compile("^\\s*CREATE\\s+TABLE\\s+(\\w+)", Pattern.CASE_INSENSITIVE);

  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_FILE =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY_DIR =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

  static IntConsumer exit = System::exit;
  static Map<String, String> environment = System.getenv();

  private final Map<String, String> env;
  private final Path home;
  private final PrintStream out;
  private final PrintStream err;
  long retryDelayMillis = 3000;

  HibiscusLauncher(Map<String, String> env, Path home, PrintStream out, PrintStream err)
  {
    this.env = env;
    this.home = home;
    this.out = out;
    this.err = err;
  }

  public static void main(String[] args)
  {
    // In non-interactive mode Jameica closes System.out and System.err once it
    // has started ("detaching terminal"); streams that ignore close() keep the
    // container log open for Jameica and for the messages of this entry point.
    PrintStream out = new KeepOpenPrintStream(System.out);
    PrintStream err = new KeepOpenPrintStream(System.err);
    System.setOut(out);
    System.setErr(err);

    // Jameica resolves cfg/, plugins/ and lib/ relative to the working directory.
    int code = new HibiscusLauncher(environment, Paths.get("").toAbsolutePath(), out, err).run();
    if (code != 0)
      exit.accept(code);
  }

  /**
   * Validates and renders the settings, starts Jameica and returns the exit code
   * for the container, 0 while Jameica keeps running.
   */
  int run()
  {
    try
    {
      Path workdir = Paths.get(env("HIBISCUS_WORKDIR", "/data")).toAbsolutePath();
      Path passwordFile = Paths.get(env("HIBISCUS_MASTER_PASSWORD_FILE", "/run/secrets/hibiscus/master-password"));

      requireSecretFile(passwordFile, "HIBISCUS_MASTER_PASSWORD_FILE");
      createPrivateDirectory(workdir);
      if (!Files.isWritable(workdir))
        throw new IllegalStateException("user directory " + workdir + " is not writable for user " + user());
      if (!PosixFilePermissions.toString(Files.getPosixFilePermissions(workdir)).endsWith("------"))
        log("WARNING: user directory " + workdir + " is open to group or others; Jameica creates its own files"
            + " readable for them, among them the keystore and the log");

      Path cfg = workdir.resolve("cfg");
      createPrivateDirectory(cfg);

      String stdoutLevel = stdoutLevel();
      renderScheduler(cfg);
      renderXmlRpc(cfg);
      renderWebadmin(cfg);
      renderSystem(cfg);
      renderDatabase(cfg);

      if (!stdoutLevel.equals("OFF"))
        attachStdoutLog(stdoutLevel);

      String[] jameicaArgs = { "-d", "-n", "-f", workdir.toString(), "-w", passwordFile.toString() };
      log("starting Jameica server mode, user directory " + workdir + ", Jameica log to stdout from level " + stdoutLevel);
      Class<?> main = Class.forName("de.willuhn.jameica.Main");
      Method m = main.getMethod("main", String[].class);
      m.invoke(null, (Object) jameicaArgs);

      if (bool("HIBISCUS_EXIT_ON_PLUGIN_ERROR", true))
        return pluginErrors();
      return 0;
    }
    catch (IllegalArgumentException | IllegalStateException e)
    {
      err.println("[hibiscus-launcher] configuration error: " + e.getMessage());
      return EXIT_CONFIGURATION;
    }
    catch (Exception e)
    {
      Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
      err.println("[hibiscus-launcher] start failed: " + cause);
      return EXIT_FAILURE;
    }
  }

  // Jameica keeps running with a web interface when a plugin (for example
  // Hibiscus after a database error) fails to initialise; the container exits
  // instead so the orchestrator reports the failure.
  private int pluginErrors() throws Exception
  {
    Class<?> app = Class.forName("de.willuhn.jameica.system.Application");
    Object loader = app.getMethod("getPluginLoader").invoke(null);
    Map<?, ?> errors = (Map<?, ?>) loader.getClass().getMethod("getInitErrors").invoke(loader);
    if (errors == null || errors.isEmpty())
    {
      log("all plugins initialised");
      return 0;
    }
    for (Map.Entry<?, ?> e : errors.entrySet())
    {
      Object manifest = e.getKey();
      String name = String.valueOf(manifest.getClass().getMethod("getName").invoke(manifest));
      Throwable t = (Throwable) e.getValue();
      log("ERROR: plugin " + name + " failed to initialise: " + (t == null ? "unknown" : t.getMessage()));
    }
    return EXIT_PLUGIN;
  }

  void renderDatabase(Path cfg) throws Exception
  {
    Path file = cfg.resolve(CFG_DB);
    Properties p = load(file);
    String host = env("HIBISCUS_DB_HOST", "");

    if (host.isEmpty())
    {
      String previous = p.getProperty("database.driver");
      if (previous != null && !DRIVER_H2.equals(previous))
        log("WARNING: switching database driver from " + previous + " to embedded H2; data in the previous database is not migrated");
      p.setProperty("database.driver", DRIVER_H2);
      p.remove("database.driver.mysql.jdbcurl");
      p.remove("database.driver.mysql.username");
      p.remove("database.driver.mysql.password");
      p.remove("encrypt");
      store(file, p, "database: embedded H2");
      log("database: embedded H2 in user directory");
      return;
    }

    if (!host.matches("[A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.]+\\]"))
      throw new IllegalArgumentException("HIBISCUS_DB_HOST must be a host name, an IPv4 address or an IPv6 address in brackets");
    String port = env("HIBISCUS_DB_PORT", "3306");
    int portNumber = parseInt("HIBISCUS_DB_PORT", port);
    if (portNumber < 1 || portNumber > 65535)
      throw new IllegalArgumentException("HIBISCUS_DB_PORT out of range: " + port);
    String name = env("HIBISCUS_DB_NAME", "hibiscus");
    if (!name.matches("[A-Za-z0-9_$]+"))
      throw new IllegalArgumentException("HIBISCUS_DB_NAME contains unsupported characters");
    String user = env("HIBISCUS_DB_USER", "hibiscus");
    String params = env("HIBISCUS_DB_JDBC_PARAMS", "useUnicode=Yes&characterEncoding=ISO8859_1");
    rejectCredentialParameters(params);
    int waitSeconds = parseInt("HIBISCUS_DB_WAIT_SECONDS", env("HIBISCUS_DB_WAIT_SECONDS", "60"));
    if (waitSeconds < 0)
      throw new IllegalArgumentException("HIBISCUS_DB_WAIT_SECONDS must not be negative");
    boolean encrypt = bool("HIBISCUS_DB_ENCRYPT_PASSWORD", true);
    boolean initSchema = bool("HIBISCUS_DB_INIT_SCHEMA", true);
    boolean allowCharset = bool("HIBISCUS_DB_ALLOW_CHARSET", false);
    Path passwordFile = Paths.get(env("HIBISCUS_DB_PASSWORD_FILE", "/run/secrets/hibiscus/db-password"));
    requireSecretFile(passwordFile, "HIBISCUS_DB_PASSWORD_FILE");
    String password = firstLine(passwordFile);

    String url = "jdbc:mariadb://" + host + ":" + portNumber + "/" + name + (params.isEmpty() ? "" : "?" + params);

    String previous = p.getProperty("database.driver");
    if (previous != null && !DRIVER_MYSQL.equals(previous))
      log("WARNING: switching database driver from " + previous + " to MariaDB; data in the previous database is not migrated");

    p.setProperty("database.driver", DRIVER_MYSQL);
    p.setProperty("database.driver.mysql.jdbcurl", url);
    p.setProperty("database.driver.mysql.username", user);
    p.setProperty("database.driver.mysql.password", password);
    // With encrypt=true Hibiscus replaces the clear-text value with an
    // "encrypted:" value bound to the keystore on first use.
    p.setProperty("encrypt", Boolean.toString(encrypt));
    store(file, p, "database: MariaDB");
    log("database: MariaDB " + host + ":" + portNumber + "/" + name + " as " + user);

    prepareMariaDb(url, user, password, waitSeconds, initSchema, allowCharset);
  }

  // The password comes from its file only; in the JDBC URL it would end up in
  // the settings in clear text for good, since Hibiscus encrypts only the password key.
  static void rejectCredentialParameters(String params)
  {
    for (String pair : params.split("&"))
    {
      String key = pair.split("=", 2)[0].trim();
      if (key.equalsIgnoreCase("user") || key.equalsIgnoreCase("password"))
        throw new IllegalArgumentException("HIBISCUS_DB_JDBC_PARAMS must not set " + key
            + "; use HIBISCUS_DB_USER and HIBISCUS_DB_PASSWORD_FILE");
    }
  }

  // Hibiscus does not create its tables on MySQL/MariaDB; the vendor's
  // instructions run plugins/hibiscus/sql/mysql-create.sql by hand. This does
  // the same, once, on an empty database.
  void prepareMariaDb(String url, String user, String password, int waitSeconds, boolean initSchema, boolean allowCharset) throws Exception
  {
    Path jar = findMariaDbDriver();
    try (URLClassLoader cl = new URLClassLoader(new URL[] { jar.toUri().toURL() }, HibiscusLauncher.class.getClassLoader()))
    {
      Driver driver = (Driver) Class.forName("org.mariadb.jdbc.Driver", true, cl).getDeclaredConstructor().newInstance();
      Properties props = new Properties();
      props.setProperty("user", user);
      props.setProperty("password", password);

      // A resource that comes from a method gets a null check in try-with-resources
      // that no test can reach; this connection is never null.
      Connection c = connect(driver, url, props, waitSeconds);
      try
      {
        String charset = queryString(c, "SELECT default_character_set_name FROM information_schema.schemata WHERE schema_name = DATABASE()");
        boolean utf8 = isVendorCharset(charset);
        if (!utf8)
          log("WARNING: database default character set is " + charset + "; the vendor requires utf8 (utf8mb3), not utf8mb4");

        int tables = queryInt(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()");
        int versionTable = queryInt(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'version'");

        if (versionTable == 1)
        {
          if (queryInt(c, "SELECT COUNT(*) FROM version WHERE name = 'db'") == 0)
            throw new IllegalStateException("database has a Hibiscus version table without a schema version; the schema is incomplete");
          log("database schema present");
          return;
        }
        if (tables > 0)
          throw new IllegalStateException("database contains " + tables + " tables but no Hibiscus version table; refusing to initialise it");
        if (!initSchema)
          throw new IllegalStateException("database is empty and HIBISCUS_DB_INIT_SCHEMA=false; run plugins/hibiscus/sql/mysql-create.sql first");
        if (!utf8 && !allowCharset)
          throw new IllegalStateException("refusing to create the schema in a " + charset
              + " database; create it with CHARACTER SET utf8 COLLATE utf8_general_ci or set HIBISCUS_DB_ALLOW_CHARSET=true");

        Path script = home.resolve(Paths.get("plugins", "hibiscus", "sql", "mysql-create.sql"));
        List<String> statements = splitSql(Files.readString(script, StandardCharsets.ISO_8859_1));
        log("database is empty, creating schema from " + home.relativize(script) + " (" + statements.size() + " statements)");
        createSchema(c, statements);
        log("database schema created");
      }
      finally
      {
        c.close();
      }
    }
  }

  // MariaDB commits every CREATE TABLE on its own, so a failed run cannot be
  // rolled back; the tables created so far are dropped instead, which is safe
  // because the database was empty before.
  static void createSchema(Connection c, List<String> statements) throws SQLException
  {
    List<String> created = new ArrayList<>();
    try (Statement st = c.createStatement())
    {
      try
      {
        for (String sql : statements)
        {
          st.execute(sql);
          Matcher m = CREATE_TABLE.matcher(sql);
          if (m.find())
            created.add(m.group(1));
        }
      }
      catch (SQLException e)
      {
        for (int i = created.size() - 1; i >= 0; i--)
          st.execute("DROP TABLE IF EXISTS " + created.get(i));
        throw new IllegalStateException("creating the schema failed, the database is empty again: " + e.getMessage(), e);
      }
    }
  }

  static boolean isVendorCharset(String charset)
  {
    return charset != null && (charset.equalsIgnoreCase("utf8") || charset.equalsIgnoreCase("utf8mb3"));
  }

  Connection connect(Driver driver, String url, Properties props, int waitSeconds) throws Exception
  {
    long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
    while (true)
    {
      try
      {
        Connection c = driver.connect(url, props);
        if (c == null)
          throw new IllegalStateException("JDBC driver does not accept the URL");
        return c;
      }
      catch (SQLException e)
      {
        if (System.currentTimeMillis() >= deadline)
          throw new IllegalStateException("database not reachable within " + waitSeconds + " s: " + e.getMessage());
        log("database not reachable yet (" + e.getMessage() + "), retrying");
        Thread.sleep(retryDelayMillis);
      }
    }
  }

  private static String queryString(Connection c, String sql) throws SQLException
  {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql))
    {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static int queryInt(Connection c, String sql) throws SQLException
  {
    return Integer.parseInt(queryString(c, sql));
  }

  // The vendor script has one statement per ";" at a line end and "--" line comments only.
  static List<String> splitSql(String script)
  {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String line : script.split("\r?\n"))
    {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("--"))
        continue;
      current.append(line).append('\n');
      if (trimmed.endsWith(";"))
      {
        String sql = current.toString().trim();
        result.add(sql.substring(0, sql.length() - 1));
        current.setLength(0);
      }
    }
    if (current.length() > 0)
      throw new IllegalStateException("schema script ends without a terminating ';'");
    return result;
  }

  private Path findMariaDbDriver() throws IOException
  {
    Path dir = home.resolve(Paths.get("lib", "mariadb"));
    List<Path> jars = new ArrayList<>();
    if (Files.isDirectory(dir))
    {
      DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "mariadb-java-client-*.jar");
      try
      {
        ds.forEach(jars::add);
      }
      finally
      {
        ds.close();
      }
    }
    jars.sort(null);
    if (jars.isEmpty())
      throw new IllegalStateException("no MariaDB JDBC driver found in lib/mariadb");
    return jars.get(0).toAbsolutePath();
  }

  void renderScheduler(Path cfg) throws IOException
  {
    Path file = cfg.resolve(CFG_PAYMENT);
    Properties p = load(file);

    String raw = env("HIBISCUS_SCHEDULER_INTERVAL_MINUTES", Integer.toString(MIN_INTERVAL_MINUTES));
    int interval = parseInt("HIBISCUS_SCHEDULER_INTERVAL_MINUTES", raw);
    if (interval < 1)
      throw new IllegalArgumentException("HIBISCUS_SCHEDULER_INTERVAL_MINUTES must be positive");
    if (interval < MIN_INTERVAL_MINUTES && !bool("HIBISCUS_SCHEDULER_ALLOW_SHORT_INTERVAL", false))
      throw new IllegalArgumentException("HIBISCUS_SCHEDULER_INTERVAL_MINUTES=" + interval
          + " allows more than four fetches a day; set HIBISCUS_SCHEDULER_ALLOW_SHORT_INTERVAL=true to override");
    p.setProperty("scheduler.interval.minutes", Integer.toString(interval));

    int from = hour("HIBISCUS_SCHEDULER_EXCLUDE_FROM", 23);
    int to = hour("HIBISCUS_SCHEDULER_EXCLUDE_TO", 5);
    p.setProperty("scheduler.exclude.from", Integer.toString(from));
    p.setProperty("scheduler.exclude.to", Integer.toString(to));

    Set<Integer> excludedDays = days(env("HIBISCUS_SCHEDULER_EXCLUDE_DAYS", ""));
    for (int day = 1; day <= 7; day++)
      p.setProperty("scheduler.exclude.day." + day, Boolean.toString(excludedDays.contains(day)));

    p.setProperty("scheduler.stoponerror", Boolean.toString(bool("HIBISCUS_SCHEDULER_STOP_ON_ERROR", true)));

    String notify = env("HIBISCUS_NOTIFY_URL", "");
    if (notify.isEmpty())
      p.remove("notify.url");
    else
    {
      if (!notify.startsWith("http://") && !notify.startsWith("https://"))
        throw new IllegalArgumentException("HIBISCUS_NOTIFY_URL must start with http:// or https://");
      p.setProperty("notify.url", notify);
    }

    int wait = parseInt("HIBISCUS_TAN_DECOUPLED_WAIT_SECONDS", env("HIBISCUS_TAN_DECOUPLED_WAIT_SECONDS", "120"));
    if (wait < 1)
      throw new IllegalArgumentException("HIBISCUS_TAN_DECOUPLED_WAIT_SECONDS must be positive");
    p.setProperty("tan.decoupled.wait.seconds", Integer.toString(wait));

    store(file, p, "scheduler and notification");
    log("scheduler: every " + interval + " min, paused " + from + ":00-" + to + ":00, excluded days " + excludedDays
        + ", notify.url " + (notify.isEmpty() ? "unset" : "set"));
  }

  void renderXmlRpc(Path cfg) throws IOException
  {
    Path file = cfg.resolve(CFG_XMLRPC);
    Properties p = load(file);

    Set<String> enabled = new LinkedHashSet<>();
    for (String s : env("HIBISCUS_XMLRPC_SERVICES", "konto,umsatz").split(","))
    {
      String id = s.trim().toLowerCase(Locale.ROOT);
      if (id.isEmpty() || id.equals("none"))
        continue;
      if (!List.of(XMLRPC_SERVICES).contains(id))
        throw new IllegalArgumentException("unknown XML-RPC service in HIBISCUS_XMLRPC_SERVICES: " + id);
      enabled.add(id);
    }
    for (String id : XMLRPC_SERVICES)
      p.setProperty("hibiscus.xmlrpc." + id + ".shared", Boolean.toString(enabled.contains(id)));
    p.setProperty("jameica.xmlrpc.echo.shared", Boolean.toString(bool("HIBISCUS_XMLRPC_ECHO", false)));

    store(file, p, "shared XML-RPC services");
    log("xml-rpc: shared services " + enabled);
  }

  void renderWebadmin(Path cfg) throws IOException
  {
    Path file = cfg.resolve(CFG_WEBADMIN);
    Properties p = load(file);

    int port = parseInt("HIBISCUS_HTTP_PORT", env("HIBISCUS_HTTP_PORT", "8080"));
    if (port < 1024 || port > 65535)
      throw new IllegalArgumentException("HIBISCUS_HTTP_PORT must be between 1024 and 65535 for a non-root process");
    p.setProperty("listener.http.port", Integer.toString(port));

    String address = env("HIBISCUS_HTTP_ADDRESS", "");
    if (address.isEmpty())
      p.remove("listener.http.address");
    else
    {
      if (!address.matches("[A-Za-z0-9.:_-]+"))
        throw new IllegalArgumentException("HIBISCUS_HTTP_ADDRESS must be a host name or an IP address");
      p.setProperty("listener.http.address", address);
    }

    boolean ssl = bool("HIBISCUS_HTTP_TLS", true);
    if (!ssl && !isLoopback(address) && !bool("HIBISCUS_HTTP_ALLOW_PLAINTEXT", false))
      throw new IllegalArgumentException("HIBISCUS_HTTP_TLS=false sends the master password without encryption;"
          + " bind the listener to a loopback address with HIBISCUS_HTTP_ADDRESS or set HIBISCUS_HTTP_ALLOW_PLAINTEXT=true");
    p.setProperty("listener.http.ssl", Boolean.toString(ssl));
    p.setProperty("listener.http.auth", "true");

    store(file, p, "web interface listener");
    log("web: port " + port + ", address " + (address.isEmpty() ? "all" : address) + ", tls " + ssl + ", auth true");
  }

  static boolean isLoopback(String address)
  {
    return address.equals("localhost") || address.equals("::1") || address.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
  }

  void renderSystem(Path cfg) throws IOException
  {
    Path update = cfg.resolve(CFG_UPDATE);
    Properties u = load(update);
    u.setProperty("update.check", "false");
    store(update, u, "online update check");

    String hostname = env("HIBISCUS_CERT_HOSTNAME", "");
    if (!hostname.isEmpty())
    {
      if (!hostname.matches("[A-Za-z0-9.-]+"))
        throw new IllegalArgumentException("HIBISCUS_CERT_HOSTNAME contains unsupported characters");
      Path callback = cfg.resolve(CFG_CALLBACK);
      Properties c = load(callback);
      c.setProperty("jameica.hostname", hostname);
      store(callback, c, "certificate host name");
    }

    Path system = cfg.resolve(CFG_SYSTEM);
    Properties s = load(system);
    s.setProperty("jameica.system.rmi.shareservices", "false");
    s.setProperty("jameica.system.multicastlookup", "false");
    s.setProperty("jameica.system.backup.enabled", Boolean.toString(bool("HIBISCUS_BACKUP_ON_SHUTDOWN", true)));
    int backups = parseInt("HIBISCUS_BACKUP_COUNT", env("HIBISCUS_BACKUP_COUNT", "10"));
    if (backups < 1)
      throw new IllegalArgumentException("HIBISCUS_BACKUP_COUNT must be at least 1");
    s.setProperty("jameica.system.backup.count", Integer.toString(backups));
    String level = env("HIBISCUS_LOG_LEVEL", "");
    if (!level.isEmpty())
    {
      String l = level.toUpperCase(Locale.ROOT);
      if (!l.matches("DEBUG|INFO|WARN|ERROR"))
        throw new IllegalArgumentException("HIBISCUS_LOG_LEVEL must be DEBUG, INFO, WARN or ERROR");
      s.setProperty("jameica.system.log.level", l);
    }
    store(system, s, "system");
  }

  String stdoutLevel()
  {
    String level = env("HIBISCUS_LOG_STDOUT_LEVEL", "WARN").toUpperCase(Locale.ROOT);
    if (!List.of(STDOUT_LEVELS).contains(level))
      throw new IllegalArgumentException("HIBISCUS_LOG_STDOUT_LEVEL must be OFF, ERROR, WARN, INFO or DEBUG");
    return level;
  }

  // Jameica's own log always goes to jameica.log in the user directory. The
  // copy on stdout lands in the log collection of the operator, so it carries
  // only messages from the configured level on.
  void attachStdoutLog(String threshold) throws Exception
  {
    Class<?> logger = Class.forName("de.willuhn.logging.Logger");
    Class<?> targetType = Class.forName("de.willuhn.logging.targets.Target");
    Class<?> levelType = Class.forName("de.willuhn.logging.Level");
    Method value = levelType.getMethod("getValue");
    int minimum = (int) value.invoke(levelType.getField(threshold).get(null));
    Object stream = Class.forName("de.willuhn.logging.targets.OutputStreamTarget").getConstructor(OutputStream.class).newInstance(out);

    Object filtered = Proxy.newProxyInstance(targetType.getClassLoader(), new Class<?>[] { targetType }, (proxy, method, args) -> {
      if (method.getName().equals("write"))
      {
        Object level = args[0].getClass().getMethod("getLevel").invoke(args[0]);
        if ((int) value.invoke(level) < minimum)
          return null;
      }
      try
      {
        return method.invoke(stream, args);
      }
      catch (InvocationTargetException e)
      {
        throw e.getCause();
      }
    });
    logger.getMethod("addTarget", targetType).invoke(null, filtered);
  }

  static final class KeepOpenPrintStream extends PrintStream
  {
    KeepOpenPrintStream(PrintStream delegate)
    {
      super(delegate, true, StandardCharsets.UTF_8);
    }

    @Override
    public void close()
    {
      flush();
    }
  }

  static Properties load(Path file) throws IOException
  {
    Properties p = new Properties();
    if (Files.exists(file))
    {
      try (InputStream in = Files.newInputStream(file))
      {
        p.load(in);
      }
    }
    return p;
  }

  // The settings can hold the database password, so every file is replaced by
  // one that only the owner can read, whatever mode the previous file had.
  static void store(Path file, Properties p, String comment) throws IOException
  {
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.deleteIfExists(tmp);
    Files.createFile(tmp, OWNER_ONLY_FILE);
    try (OutputStream out = Files.newOutputStream(tmp))
    {
      p.store(out, "managed by the container entry point: " + comment);
    }
    // A rename within one directory, so it is atomic on every POSIX file system.
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void createPrivateDirectory(Path dir) throws IOException
  {
    if (!Files.isDirectory(dir))
      Files.createDirectories(dir, OWNER_ONLY_DIR);
  }

  static void requireSecretFile(Path file, String variable) throws IOException
  {
    if (!Files.isRegularFile(file) || !Files.isReadable(file))
      throw new IllegalStateException(variable + ": " + file + " is missing or not readable for user " + user());
    if (firstLine(file).isEmpty())
      throw new IllegalStateException(variable + ": first line of " + file + " is empty");
  }

  // Jameica reads only the first line of the master password file, untrimmed,
  // so the same rule applies to both password files.
  static String firstLine(Path file) throws IOException
  {
    try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8))
    {
      String line = r.readLine();
      return line == null ? "" : line;
    }
  }

  static Set<Integer> days(String raw)
  {
    Set<Integer> result = new LinkedHashSet<>();
    for (String s : raw.split(","))
    {
      String d = s.trim().toUpperCase(Locale.ROOT);
      if (d.isEmpty())
        continue;
      int index = List.of(DAY_NAMES).indexOf(d);
      if (index < 0)
        throw new IllegalArgumentException("HIBISCUS_SCHEDULER_EXCLUDE_DAYS: unknown day " + s.trim() + " (use MON..SUN)");
      result.add(index + 1);
    }
    return result;
  }

  private int hour(String variable, int def)
  {
    int h = parseInt(variable, env(variable, Integer.toString(def)));
    if (h < 0 || h > 23)
      throw new IllegalArgumentException(variable + " must be an hour between 0 and 23");
    return h;
  }

  static int parseInt(String variable, String value)
  {
    try
    {
      return Integer.parseInt(value.trim());
    }
    catch (NumberFormatException e)
    {
      throw new IllegalArgumentException(variable + " is not a number: " + value);
    }
  }

  boolean bool(String variable, boolean def)
  {
    String v = env(variable, Boolean.toString(def)).toLowerCase(Locale.ROOT);
    if (v.equals("true") || v.equals("yes") || v.equals("1"))
      return true;
    if (v.equals("false") || v.equals("no") || v.equals("0"))
      return false;
    throw new IllegalArgumentException(variable + " must be true or false: " + v);
  }

  String env(String name, String def)
  {
    String v = env.get(name);
    return v == null ? def : v.trim();
  }

  private static String user()
  {
    return System.getProperty("user.name", "?");
  }

  private void log(String message)
  {
    out.println("[hibiscus-launcher] " + message);
  }
}

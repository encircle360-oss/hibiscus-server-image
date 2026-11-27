// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
package fakedb;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An in-memory stand-in for one MariaDB database, enough for the queries of the entry point. */
public final class FakeDatabase
{
  private static final Pattern CREATE = Pattern.compile("CREATE TABLE (\\w+)", Pattern.CASE_INSENSITIVE);
  private static final Pattern DROP = Pattern.compile("DROP TABLE IF EXISTS (\\w+)", Pattern.CASE_INSENSITIVE);

  public static String charset;
  public static Set<String> tables;
  public static int versionRows;
  public static int failingConnects;
  public static boolean acceptsUrl;
  public static String failOn;
  public static List<String> executed;
  public static String lastUrl;
  public static Properties lastProperties;
  public static int closedConnections;

  private FakeDatabase()
  {
  }

  /** Resets to an empty, reachable utf8mb3 database. */
  public static void reset()
  {
    charset = "utf8mb3";
    tables = new LinkedHashSet<>();
    versionRows = 0;
    failingConnects = 0;
    acceptsUrl = true;
    failOn = null;
    executed = new ArrayList<>();
    lastUrl = null;
    lastProperties = null;
    closedConnections = 0;
  }

  public static Connection connect(String url, Properties info) throws SQLException
  {
    lastUrl = url;
    lastProperties = info;
    if (failingConnects > 0)
    {
      failingConnects--;
      throw new SQLException("connection refused");
    }
    if (!acceptsUrl)
      return null;
    return (Connection) Proxy.newProxyInstance(FakeDatabase.class.getClassLoader(), new Class<?>[] { Connection.class },
        (proxy, method, args) -> switch (method.getName())
        {
          case "createStatement" -> statement();
          case "close" -> {
            closedConnections++;
            yield null;
          }
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  private static Statement statement()
  {
    return (Statement) Proxy.newProxyInstance(FakeDatabase.class.getClassLoader(), new Class<?>[] { Statement.class },
        (proxy, method, args) -> switch (method.getName())
        {
          case "executeQuery" -> result(query((String) args[0]));
          case "execute" -> {
            execute((String) args[0]);
            yield false;
          }
          case "close" -> null;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  private static String query(String sql)
  {
    if (sql.contains("default_character_set_name"))
      return charset;
    if (sql.contains("table_name = 'version'"))
      return tables.contains("version") ? "1" : "0";
    if (sql.contains("information_schema.tables"))
      return Integer.toString(tables.size());
    if (sql.contains("FROM version"))
      return Integer.toString(versionRows);
    throw new UnsupportedOperationException(sql);
  }

  private static void execute(String sql) throws SQLException
  {
    executed.add(sql);
    if (failOn != null && sql.contains(failOn))
      throw new SQLException("syntax error near " + failOn);
    Matcher create = CREATE.matcher(sql);
    Matcher drop = DROP.matcher(sql);
    if (create.find())
      tables.add(create.group(1).toLowerCase(Locale.ROOT));
    else if (drop.find())
      tables.remove(drop.group(1).toLowerCase(Locale.ROOT));
    else if (sql.startsWith("INSERT INTO version"))
      versionRows++;
  }

  private static ResultSet result(String value)
  {
    boolean[] read = { false };
    return (ResultSet) Proxy.newProxyInstance(FakeDatabase.class.getClassLoader(), new Class<?>[] { ResultSet.class },
        (proxy, method, args) -> switch (method.getName())
        {
          case "next" -> {
            boolean first = !read[0] && value != null;
            read[0] = true;
            yield first;
          }
          case "getString" -> value;
          case "close" -> null;
          default -> throw new UnsupportedOperationException(method.getName());
        });
  }
}

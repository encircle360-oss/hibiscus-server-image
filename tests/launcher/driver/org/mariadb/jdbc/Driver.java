// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
package org.mariadb.jdbc;

import fakedb.FakeDatabase;
import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A JDBC driver under the class name of MariaDB Connector/J. The test packs it
 * into a jar of its own, so the entry point loads it the way it loads the real
 * driver; the database behind it is FakeDatabase on the test class path.
 */
public class Driver implements java.sql.Driver
{
  @Override
  public Connection connect(String url, Properties info) throws SQLException
  {
    return FakeDatabase.connect(url, info);
  }

  @Override
  public boolean acceptsURL(String url)
  {
    return url.startsWith("jdbc:mariadb:");
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
  {
    return new DriverPropertyInfo[0];
  }

  @Override
  public int getMajorVersion()
  {
    return 0;
  }

  @Override
  public int getMinorVersion()
  {
    return 0;
  }

  @Override
  public boolean jdbcCompliant()
  {
    return false;
  }

  @Override
  public Logger getParentLogger()
  {
    return Logger.getGlobal();
  }
}

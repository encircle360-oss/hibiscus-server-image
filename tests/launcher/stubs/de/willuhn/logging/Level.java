// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
package de.willuhn.logging;

/** The levels of Jameica's logger with the values of de_willuhn_util. */
public class Level
{
  public static final Level TRACE = new Level("TRACE", 0);
  public static final Level DEBUG = new Level("DEBUG", 1);
  public static final Level INFO = new Level("INFO", 100);
  public static final Level WARN = new Level("WARN", 300);
  public static final Level ERROR = new Level("ERROR", 500);

  private final String name;
  private final int value;

  public Level(String name, int value)
  {
    this.name = name;
    this.value = value;
  }

  public int getValue()
  {
    return value;
  }

  public String getName()
  {
    return name;
  }
}

// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
package de.willuhn.logging;

/** One log message of Jameica's logger. */
public class Message
{
  private final Level level;
  private final String text;

  public Message(Level level, String text)
  {
    this.level = level;
    this.text = text;
  }

  public Level getLevel()
  {
    return level;
  }

  @Override
  public String toString()
  {
    return "[" + level.getName() + "] " + text;
  }
}

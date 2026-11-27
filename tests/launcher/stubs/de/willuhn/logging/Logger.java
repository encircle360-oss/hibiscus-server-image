// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
package de.willuhn.logging;

import de.willuhn.logging.targets.Target;
import java.util.ArrayList;
import java.util.List;

/** Stands in for Jameica's logger: keeps the targets and writes a message to each. */
public class Logger
{
  public static final List<Target> targets = new ArrayList<>();

  public static void addTarget(Target target)
  {
    targets.add(target);
  }

  public static void write(Message message) throws Exception
  {
    for (Target t : targets)
      t.write(message);
  }
}

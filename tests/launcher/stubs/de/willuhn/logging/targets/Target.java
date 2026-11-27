// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
package de.willuhn.logging.targets;

import de.willuhn.logging.Message;

/** The target interface of Jameica's logger, as in de_willuhn_util. */
public interface Target
{
  void write(Message message) throws Exception;

  void close() throws Exception;
}

// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
package de.willuhn.logging.targets;

import de.willuhn.logging.Message;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes each message as one line; fails on a message whose text says so, to test error passing. */
public class OutputStreamTarget implements Target
{
  private final OutputStream out;

  public OutputStreamTarget(OutputStream out)
  {
    this.out = out;
  }

  @Override
  public void write(Message message) throws Exception
  {
    if (message.toString().endsWith("FAIL"))
      throw new IOException("target failed");
    out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void close()
  {
  }
}

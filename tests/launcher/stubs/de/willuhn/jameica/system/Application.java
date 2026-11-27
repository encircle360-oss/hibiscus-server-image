// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
package de.willuhn.jameica.system;

import java.util.Map;

/** Stands in for Jameica's application with the plugin loader that the entry point asks for errors. */
public class Application
{
  public static Map<Manifest, Throwable> initErrors;

  public static PluginLoader getPluginLoader()
  {
    return new PluginLoader();
  }

  public static class PluginLoader
  {
    public Map<Manifest, Throwable> getInitErrors()
    {
      return initErrors;
    }
  }

  public static class Manifest
  {
    private final String name;

    public Manifest(String name)
    {
      this.name = name;
    }

    public String getName()
    {
      return name;
    }
  }
}

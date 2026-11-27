// SPDX-License-Identifier: GPL-2.0-or-later WITH Classpath-exception-2.0
package de.willuhn.jameica;

/** Stands in for Jameica's entry point: records the arguments, or fails on request. */
public class Main
{
  public static String[] lastArgs;
  public static RuntimeException failure;

  public static void main(String[] args)
  {
    if (failure != null)
      throw failure;
    lastArgs = args;
  }
}

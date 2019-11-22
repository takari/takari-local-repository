package io.takari.filemanager;

/*******************************************************************************
 * Copyright (c) 2010-2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Benjamin Hanzelmann
 */
public class ForkJvm {

  private List<String> classPathEntries = new ArrayList<String>(4);

  private File workingDirectory = new File(".");

  private List<String> parameters = new LinkedList<String>();

  public void addParameter(String parameter) {
    parameters.add(parameter);
  }

  /**
   * Adds the source JAR of the specified class/interface to the class path of the forked JVM.
   * 
   * @param type The class/interface to add, may be <code>null</code>.
   */
  public void addClassPathEntry(Class<?> type) {
    addClassPathEntry(getClassSource(type));
  }

  /**
   * Adds the specified path to the class path of the forked JVM.
   * 
   * @param path The path to add, may be <code>null</code>.
   */
  public void addClassPathEntry(String path) {
    if (path != null) {
      this.classPathEntries.add(path);
    }
  }

  /**
   * Adds the specified path to the class path of the forked JVM.
   * 
   * @param path The path to add, may be <code>null</code>.
   */
  public void addClassPathEntry(File path) {
    if (path != null) {
      this.classPathEntries.add(path.getAbsolutePath());
    }
  }

  /**
   * Gets the JAR file or directory that contains the specified class.
   * 
   * @param type The class/interface to find, may be <code>null</code>.
   * @return The absolute path to the class source location or <code>null</code> if unknown.
   */
  private static File getClassSource(Class<?> type) {
    if (type != null) {
      String classResource = type.getName().replace('.', '/') + ".class";
      return getResourceSource(classResource, type.getClassLoader());
    }
    return null;
  }

  /**
   * Gets the JAR file or directory that contains the specified resource.
   * 
   * @param resource The absolute name of the resource to find, may be <code>null</code>.
   * @param loader The class loader to use for searching the resource, may be <code>null</code>.
   * @return The absolute path to the resource location or <code>null</code> if unknown.
   */
  private static File getResourceSource(String resource, ClassLoader loader) {
    if (resource != null) {
      URL url;
      if (loader != null) {
        url = loader.getResource(resource);
      } else {
        url = ClassLoader.getSystemResource(resource);
      }
      return getResourceRoot(url, resource);
    }
    return null;
  }

  private static File getResourceRoot(URL url, String resource) {
    String str = url.getPath();
    str = str.replace(resource, "");
    try {
      str = URLDecoder.decode(str, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("JVM broken", e);
    }
    return new File(str);
  }

  public Process run(String mainClass) throws IOException, InterruptedException {
    List<String> cmd = new LinkedList<String>();
    cmd.add(getDefaultExecutable());

    cmd.add("-cp");
    StringBuilder classpath = new StringBuilder();
    for (int i = 0; i < classPathEntries.size(); i++) {
      if (i != 0) {
        classpath.append(File.pathSeparator);
      }
      classpath.append(classPathEntries.get(i));
    }
    cmd.add(classpath.toString());

    cmd.add(mainClass);

    cmd.addAll(parameters);

    ProcessBuilder builder = new ProcessBuilder(cmd);
    builder.directory(workingDirectory);
    builder.redirectErrorStream(true);
    Process process = builder.start();

    return process;

  }

  /**
   * Gets the absolute path to the JVM executable.
   * 
   * @return The absolute path to the JVM executable.
   */
  private static String getDefaultExecutable() {
    return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
  }

  public void setWorkingDirectory(File workingDirectory) {
    this.workingDirectory = workingDirectory;
  }

  public void setParameters(String... parameters) {
    this.parameters = Arrays.asList(parameters);
  }

  public static void flush(Process p) throws IOException {
    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
    String line;
    while ((line = r.readLine()) != null) {
      System.out.println(line);
    }
    r.close();
  }

}

package io.takari.filemanager;

/*******************************************************************************
 * Copyright (c) 2010-2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * Locks two files at once in a forked JVM.
 * 
 * @author Benjamin Bentmann
 */
public class ExternalProcessFileLocks {

  private final File file1;

  private final File file2;

  public static void main(String[] args) throws Exception {
    String path1 = args[0];
    String path2 = args[1];

    File file1 = new File(path1 + ".aetherlock");
    File file2 = new File(path2 + ".aetherlock");

    file1.getParentFile().mkdirs();
    file2.getParentFile().mkdirs();

    // lock first file
    RandomAccessFile raf1 = new RandomAccessFile(file1, "rw");
    FileLock lock1 = raf1.getChannel().lock();

    // signal acquisition of first lock to parent process
    File touchFile = getTouchFile(path1);
    touchFile.createNewFile();

    // lock second file
    RandomAccessFile raf2 = new RandomAccessFile(file2, "rw");
    FileLock lock2 = raf2.getChannel().lock();

    lock1.release();
    raf1.close();

    lock2.release();
    raf2.close();
  }

  public ExternalProcessFileLocks(File file1, File file2) {
    this.file1 = file1;
    this.file2 = file2;
  }

  private static File getTouchFile(String path) {
    return new File(path + ".touch");
  }

  public Process lockFiles() throws InterruptedException, IOException {
    ForkJvm jvm = new ForkJvm();
    jvm.addClassPathEntry(getClass());
    jvm.setParameters(file1.getAbsolutePath(), file2.getAbsolutePath());
    Process p = jvm.run(getClass().getName());
    p.getOutputStream().close();
    return p;
  }

  public long awaitLock1() {
    File touchFile = getTouchFile(file1.getAbsolutePath());

    for (long start = System.currentTimeMillis(); System.currentTimeMillis() - start < 10 * 1000;) {
      if (touchFile.exists()) {
        long now = System.currentTimeMillis();
        touchFile.delete();
        return now;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        // ignored
      }
    }

    throw new IllegalStateException("External lock on " + file1 + " wasn't aquired in time");
  }

}

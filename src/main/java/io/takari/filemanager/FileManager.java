package io.takari.filemanager;

/*******************************************************************************
 * Copyright (c) 2010-2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import io.takari.filemanager.FileManager.ProgressListener;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * A LockManager holding external locks, locking files between OS processes (e.g. via {@link Lock}.
 * 
 * @author Benjamin Hanzelmann
 */
public interface FileManager {

  /**
   * Obtain a lock object that may be used to lock the target file for reading. This method must not lock that file
   * right immediately (see {@link Lock#lock()}).
   * 
   * @param target the file to lock, never {@code null}.
   * @return a lock object, never {@code null}.
   */
  Lock readLock(File target);

  /**
   * Obtain a lock object that may be used to lock the target file for writing. This method must not lock that file
   * right immediately (see {@link Lock#lock()}).
   * 
   * @param target the file to lock, never {@code null}.
   * @return a lock object, never {@code null}.
   */
  Lock writeLock(File target);

  //
  // This will become a concurrent/process safe file manager and we'll move many of the methods from the LockingFileProcessor and then
  // make the LockingFileProcessor a thin wrapper around our default FileManager
  //  
  boolean mkdirs(File directory);

  void write(File target, String data) throws IOException;

  void write(File target, InputStream source) throws IOException;

  void move(File source, File target) throws IOException;

  void copy(File source, File target) throws IOException;

  long copy(File source, File target, ProgressListener listener) throws IOException;

  /**
   * A listener object that is notified for every progress made while copying files.
   * 
   * @see FileProcessor#copy(File, File, ProgressListener)
   */
  public interface ProgressListener {
    void progressed(ByteBuffer buffer) throws IOException;
  }
}

package io.takari.aether.concurrency;

/*******************************************************************************
 * Copyright (c) 2010-2014 Takari, Inc., Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import io.takari.filemanager.FileManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.aether.spi.io.FileProcessor;

/**
 * A {@link FileProcessor} implementation that delegates all important operations to {@link DefaultFileLockManager}.
 * 
 * @author Jason van Zyl
 */
@Named
@Singleton
public class LockingFileProcessor implements FileProcessor {
  
  private FileManager fileManager;

  @Inject
  public LockingFileProcessor(FileManager fileManager) {
    this.fileManager = fileManager;
  }

  @Override
  public boolean mkdirs(File directory) {
    return fileManager.mkdirs(directory);
  }

  @Override
  public void write(File target, String data) throws IOException {
    fileManager.write(target, data);
    
  }

  @Override
  public void write(File target, InputStream source) throws IOException {
    fileManager.write(target, source);
  }

  @Override
  public void move(File source, File target) throws IOException {
    fileManager.move(source, target);    
  }

  @Override
  public void copy(File source, File target) throws IOException {
    fileManager.copy(source, target);
  }

  @Override
  public long copy(File source, File target, ProgressListener listener) throws IOException {
    return fileManager.copy(source, target, new ProgressListenerAdapter(listener));
  }
  
  static class ProgressListenerAdapter implements io.takari.filemanager.FileManager.ProgressListener {

    private ProgressListener listener;
    
    public ProgressListenerAdapter(ProgressListener listener) {
      this.listener = listener;
    }

    @Override
    public void progressed(ByteBuffer buffer) throws IOException {
      listener.progressed(buffer);      
    }
  }
}

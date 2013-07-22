package io.tesla.aether.concurrency;

/*******************************************************************************
 * Copyright (c) 2011-2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import io.tesla.filelock.FileLockManager;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.SyncContext;
import org.eclipse.aether.impl.SyncContextFactory;
import org.eclipse.aether.spi.locator.Service;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.spi.log.Logger;
import org.eclipse.aether.spi.log.LoggerFactory;
import org.eclipse.aether.spi.log.NullLoggerFactory;

/**
 * A synchronization context factory that employs OS-level file locks to control access to artifacts/metadatas.
 */
@Component(role = SyncContextFactory.class)
public class LockingSyncContextFactory implements SyncContextFactory, Service {

  @Requirement(role = LoggerFactory.class)
  private Logger logger = NullLoggerFactory.LOGGER;

  @Requirement
  private FileLockManager fileLockManager;

  /**
   * Sets the logger factory to use for this component.
   * 
   * @param loggerFactory The logger to use, may be {@code null} to disable logging.
   * @return This component for chaining, never {@code null}.
   */
  public LockingSyncContextFactory setLoggerFactory(LoggerFactory loggerFactory) {
    this.logger = NullLoggerFactory.getSafeLogger(loggerFactory, getClass());
    return this;
  }

  void setLogger(LoggerFactory loggerFactory) {
    // plexus support
    setLoggerFactory(loggerFactory);
  }

  public LockingSyncContextFactory setFileLockManager(FileLockManager fileLockManager) {
    this.fileLockManager = fileLockManager;
    return this;
  }

  public void initService(ServiceLocator locator) {
    setLoggerFactory(locator.getService(LoggerFactory.class));
    setFileLockManager(locator.getService(FileLockManager.class));
  }

  public SyncContext newInstance(RepositorySystemSession session, boolean shared) {
    return new LockingSyncContext(shared, session, fileLockManager, logger);
  }

}

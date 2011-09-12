package org.eclipse.tesla.aether.concurrency;

/*******************************************************************************
 * Copyright (c) 2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.SyncContext;
import org.sonatype.aether.impl.SyncContextFactory;
import org.sonatype.aether.spi.locator.Service;
import org.sonatype.aether.spi.locator.ServiceLocator;
import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.spi.log.NullLogger;

/**
 * A synchronization context factory that employs OS-level file locks to control access to artifacts/metadatas.
 */
@Component( role = SyncContextFactory.class )
public class LockingSyncContextFactory
    implements SyncContextFactory, Service
{

    @Requirement
    private Logger logger = NullLogger.INSTANCE;

    @Requirement
    private FileLockManager fileLockManager;

    /**
     * Sets the logger to use for this component.
     * 
     * @param logger The logger to use, may be {@code null} to disable logging.
     * @return This component for chaining, never {@code null}.
     */
    public LockingSyncContextFactory setLogger( Logger logger )
    {
        this.logger = ( logger != null ) ? logger : NullLogger.INSTANCE;
        return this;
    }

    public LockingSyncContextFactory setFileLockManager( FileLockManager fileLockManager )
    {
        this.fileLockManager = fileLockManager;
        return this;
    }

    public void initService( ServiceLocator locator )
    {
        setLogger( locator.getService( Logger.class ) );
        setFileLockManager( locator.getService( FileLockManager.class ) );
    }

    public SyncContext newInstance( RepositorySystemSession session, boolean shared )
    {
        return new LockingSyncContext( shared, session, fileLockManager, logger );
    }

}

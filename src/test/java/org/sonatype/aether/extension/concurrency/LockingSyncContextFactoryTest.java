package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.impl.SyncContext;
import org.sonatype.aether.impl.SyncContextFactory;
import org.sonatype.aether.locking.DefaultFileLockManager;
import org.sonatype.aether.test.impl.SysoutLogger;
import org.sonatype.aether.test.impl.TestRepositorySystemSession;
import org.sonatype.aether.test.util.TestFileUtils;
import org.sonatype.aether.test.util.impl.StubArtifact;

/**
 * 
 */
public class LockingSyncContextFactoryTest
{

    private SyncContextFactory factory;

    private RepositorySystemSession session;

    private LockingSycnContextFactory newFactory()
    {
        LockingSycnContextFactory factory = new LockingSycnContextFactory();
        factory.setFileLockManager( new DefaultFileLockManager() );
        factory.setLogger( new SysoutLogger() );
        return factory;
    }

    @Before
    public void setup()
        throws Exception
    {
        session = new TestRepositorySystemSession();
        factory = newFactory();
    }

    @After
    public void tearDown()
        throws Exception
    {
        if ( session != null )
        {
            TestFileUtils.delete( session.getLocalRepository().getBasedir() );
        }
        session = null;
        factory = null;
    }

    @Test
    public void testContextNullSafe()
    {
        SyncContext context = factory.newInstance( session, false );
        context.acquire( null, null );
    }

    @Test
    public void testUnbalancedRelease()
    {
        SyncContext context = factory.newInstance( session, false );
        context.release();

        context = factory.newInstance( session, false );
        context.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
        context.release();
    }

}

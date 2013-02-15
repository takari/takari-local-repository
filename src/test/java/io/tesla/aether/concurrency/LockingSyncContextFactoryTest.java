package io.tesla.aether.concurrency;

/*******************************************************************************
 * Copyright (c) 2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import static org.junit.Assert.assertTrue;
import io.tesla.aether.concurrency.DefaultFileLockManager;
import io.tesla.aether.concurrency.LockingSyncContextFactory;

import java.util.Arrays;
import java.util.List;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.SyncContext;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.SyncContextFactory;
import org.eclipse.aether.internal.test.util.TestFileUtils;
import org.eclipse.aether.internal.test.util.TestLoggerFactory;
import org.eclipse.aether.internal.test.util.TestUtils;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import edu.umd.cs.mtc.MultithreadedTestCase;
import edu.umd.cs.mtc.TestFramework;

/**
 * 
 */
public class LockingSyncContextFactoryTest
{

    private SyncContextFactory factory;

    private RepositorySystemSession session;

    private LockingSyncContextFactory newFactory()
    {
        LockingSyncContextFactory factory = new LockingSyncContextFactory();
        factory.setFileLockManager( new DefaultFileLockManager() );
        factory.setLoggerFactory( new TestLoggerFactory() );
        return factory;
    }

    @Before
    public void setup()
        throws Exception
    {
        session = TestUtils.newSession();
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
        throws Exception
    {
        SyncContext context = factory.newInstance( session, false );
        context.acquire( null, null );
    }

    @Test
    public void testUnbalancedReleaseHasNoEffect()
        throws Exception
    {
        SyncContext context = factory.newInstance( session, false );
        context.close();

        context = factory.newInstance( session, false );
        context.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ), null );
        context.close();
        context.close();
    }

    @Test
    public void testReacquisitionOfSameResources()
        throws Exception
    {
        SyncContext context = factory.newInstance( session, false );
        context.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ),
                         Arrays.asList( new DefaultMetadata( "test.xml", Metadata.Nature.RELEASE ) ) );
        context.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ),
                         Arrays.asList( new DefaultMetadata( "test.xml", Metadata.Nature.RELEASE ) ) );
        context.close();
    }

    @Test
    public void testNestedContextsSharedShared()
        throws Exception
    {
        SyncContext outer = factory.newInstance( session, true );
        SyncContext inner = factory.newInstance( session, true );
        outer.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ), null );
        inner.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ), null );
        inner.close();
        outer.close();
    }

    @Test
    public void testNestedContextsExclusiveExclusive()
        throws Exception
    {
        SyncContext outer = factory.newInstance( session, false );
        SyncContext inner = factory.newInstance( session, false );
        outer.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ), null );
        inner.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ), null );
        inner.close();
        outer.close();
    }

    @Test
    public void testNestedContextsExclusiveShared()
        throws Exception
    {
        SyncContext outer = factory.newInstance( session, false );
        SyncContext inner = factory.newInstance( session, true );
        outer.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ), null );
        inner.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ), null );
        inner.close();
        outer.close();
    }

    @Test
    public void testNestedContextsSharedExclusive()
        throws Exception
    {
        SyncContext outer = factory.newInstance( session, true );
        SyncContext inner = factory.newInstance( session, false );
        outer.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ), null );
        try
        {
            inner.acquire( Arrays.asList( new DefaultArtifact( "g:a:1" ) ), null );
            inner.close();
        }
        catch ( IllegalStateException e )
        {
            assertTrue( true );
        }
        outer.close();
    }

    @Test
    @SuppressWarnings( "unused" )
    public void testBlockingWriteVsWrite()
        throws Throwable
    {
        final List<Artifact> artifacts = Arrays.<Artifact> asList( new DefaultArtifact( "g:a:1" ) );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws Exception
            {
                SyncContext context = factory.newInstance( session, false );
                context.acquire( artifacts, null );
                waitForTick( 2 );
                context.close();
            }

            public void thread2()
                throws Exception
            {
                waitForTick( 1 );
                SyncContext context = factory.newInstance( session, false );
                context.acquire( artifacts, null );
                assertTick( 2 );
                context.close();
            }
        } );
    }

    @Test
    @SuppressWarnings( "unused" )
    public void testBlockingReadVsWrite()
        throws Throwable
    {
        final List<Artifact> artifacts = Arrays.<Artifact> asList( new DefaultArtifact( "g:a:1" ) );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws Exception
            {
                SyncContext context = factory.newInstance( session, true );
                context.acquire( artifacts, null );
                waitForTick( 2 );
                context.close();
            }

            public void thread2()
                throws Exception
            {
                waitForTick( 1 );
                SyncContext context = factory.newInstance( session, false );
                context.acquire( artifacts, null );
                assertTick( 2 );
                context.close();
            }
        } );
    }

    @Test
    @SuppressWarnings( "unused" )
    public void testBlockingWriteVsRead()
        throws Throwable
    {
        final List<Artifact> artifacts = Arrays.<Artifact> asList( new DefaultArtifact( "g:a:1" ) );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws Exception
            {
                SyncContext context = factory.newInstance( session, false );
                context.acquire( artifacts, null );
                waitForTick( 2 );
                context.close();
            }

            public void thread2()
                throws Exception
            {
                waitForTick( 1 );
                SyncContext context = factory.newInstance( session, true );
                context.acquire( artifacts, null );
                assertTick( 2 );
                context.close();
            }
        } );
    }

}

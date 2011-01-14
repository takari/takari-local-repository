package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.SyncContext;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.impl.SyncContextFactory;
import org.sonatype.aether.locking.DefaultFileLockManager;
import org.sonatype.aether.metadata.Metadata;
import org.sonatype.aether.test.impl.SysoutLogger;
import org.sonatype.aether.test.impl.TestRepositorySystemSession;
import org.sonatype.aether.test.util.TestFileUtils;
import org.sonatype.aether.test.util.impl.StubArtifact;
import org.sonatype.aether.test.util.impl.StubMetadata;

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
        context.release();

        context = factory.newInstance( session, false );
        context.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
        context.release();
        context.release();
    }

    @Test
    public void testReacquisitionOfSameResources()
        throws Exception
    {
        SyncContext context = factory.newInstance( session, false );
        context.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ),
                         Arrays.asList( new StubMetadata( "test.xml", Metadata.Nature.RELEASE ) ) );
        context.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ),
                         Arrays.asList( new StubMetadata( "test.xml", Metadata.Nature.RELEASE ) ) );
        context.release();
    }

    @Test
    public void testNestedContextsSharedShared()
        throws Exception
    {
        SyncContext outer = factory.newInstance( session, true );
        SyncContext inner = factory.newInstance( session, true );
        outer.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
        inner.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
        inner.release();
        outer.release();
    }

    @Test
    public void testNestedContextsExclusiveExclusive()
        throws Exception
    {
        SyncContext outer = factory.newInstance( session, false );
        SyncContext inner = factory.newInstance( session, false );
        outer.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
        inner.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
        inner.release();
        outer.release();
    }

    @Test
    public void testNestedContextsExclusiveShared()
        throws Exception
    {
        SyncContext outer = factory.newInstance( session, false );
        SyncContext inner = factory.newInstance( session, true );
        outer.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
        inner.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
        inner.release();
        outer.release();
    }

    @Test
    public void testNestedContextsSharedExclusive()
        throws Exception
    {
        SyncContext outer = factory.newInstance( session, true );
        SyncContext inner = factory.newInstance( session, false );
        outer.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
        try
        {
            inner.acquire( Arrays.asList( new StubArtifact( "g:a:1" ) ), null );
            inner.release();
        }
        catch ( IllegalStateException e )
        {
            assertTrue( true );
        }
        outer.release();
    }

    @Test
    @SuppressWarnings( "unused" )
    public void testBlockingWriteVsWrite()
        throws Throwable
    {
        final List<Artifact> artifacts = Arrays.<Artifact> asList( new StubArtifact( "g:a:1" ) );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws Exception
            {
                SyncContext context = factory.newInstance( session, false );
                context.acquire( artifacts, null );
                waitForTick( 2 );
                context.release();
            }

            public void thread2()
                throws Exception
            {
                waitForTick( 1 );
                SyncContext context = factory.newInstance( session, false );
                context.acquire( artifacts, null );
                assertTick( 2 );
                context.release();
            }
        } );
    }

    @Test
    @SuppressWarnings( "unused" )
    public void testBlockingReadVsWrite()
        throws Throwable
    {
        final List<Artifact> artifacts = Arrays.<Artifact> asList( new StubArtifact( "g:a:1" ) );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws Exception
            {
                SyncContext context = factory.newInstance( session, true );
                context.acquire( artifacts, null );
                waitForTick( 2 );
                context.release();
            }

            public void thread2()
                throws Exception
            {
                waitForTick( 1 );
                SyncContext context = factory.newInstance( session, false );
                context.acquire( artifacts, null );
                assertTick( 2 );
                context.release();
            }
        } );
    }

    @Test
    @SuppressWarnings( "unused" )
    public void testBlockingWriteVsRead()
        throws Throwable
    {
        final List<Artifact> artifacts = Arrays.<Artifact> asList( new StubArtifact( "g:a:1" ) );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws Exception
            {
                SyncContext context = factory.newInstance( session, false );
                context.acquire( artifacts, null );
                waitForTick( 2 );
                context.release();
            }

            public void thread2()
                throws Exception
            {
                waitForTick( 1 );
                SyncContext context = factory.newInstance( session, true );
                context.acquire( artifacts, null );
                assertTick( 2 );
                context.release();
            }
        } );
    }

}

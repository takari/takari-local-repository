package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import static org.junit.Assert.*;
import static org.sonatype.aether.test.impl.RecordingRepositoryListener.Type.*;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.aether.RepositoryEvent;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.extension.concurrency.LockManager.Lock;
import org.sonatype.aether.impl.internal.DefaultFileProcessor;
import org.sonatype.aether.installation.InstallRequest;
import org.sonatype.aether.installation.InstallResult;
import org.sonatype.aether.installation.InstallationException;
import org.sonatype.aether.metadata.Metadata;
import org.sonatype.aether.metadata.Metadata.Nature;
import org.sonatype.aether.repository.LocalRepositoryManager;
import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.test.impl.RecordingRepositoryListener;
import org.sonatype.aether.test.impl.RecordingRepositoryListener.EventWrapper;
import org.sonatype.aether.test.impl.TestFileProcessor;
import org.sonatype.aether.test.impl.TestRepositorySystemSession;
import org.sonatype.aether.test.util.TestFileUtils;
import org.sonatype.aether.test.util.impl.StubArtifact;
import org.sonatype.aether.test.util.impl.StubMetadata;

import edu.umd.cs.mtc.MultithreadedTestCase;
import edu.umd.cs.mtc.TestFramework;

@SuppressWarnings( "unused" )
public class LockingInstallerTest
{

    private Artifact artifact;

    private Metadata metadata;

    private TestRepositorySystemSession session;

    private String localArtifactPath;

    private String localMetadataPath;

    private LockingInstaller installer;

    private InstallRequest request;

    private RecordingRepositoryListener listener;

    private Logger logger;

    private DefaultLockManager lockManager;

    private DefaultFileLockManager fileLockManager;

    private File localArtifactFile;

    private File localMetadataFile;

    private Process process;

    @Before
    public void setup()
        throws IOException
    {
        artifact = new StubArtifact( "gid:aid:jar:ver" );
        artifact = artifact.setFile( TestFileUtils.createTempFile( "artifact".getBytes(), 1 ) );
        metadata =
            new StubMetadata( "gid", "aid", "ver", "type", Nature.RELEASE_OR_SNAPSHOT,
                              TestFileUtils.createTempFile( "metadata".getBytes(), 1 ) );

        session = new TestRepositorySystemSession();
        localArtifactPath = session.getLocalRepositoryManager().getPathForLocalArtifact( artifact );
        localMetadataPath = session.getLocalRepositoryManager().getPathForLocalMetadata( metadata );
        localArtifactFile = new File( session.getLocalRepository().getBasedir(), localArtifactPath );
        localMetadataFile = new File( session.getLocalRepository().getBasedir(), localMetadataPath );
        lockManager = new DefaultLockManager();
        fileLockManager = new DefaultFileLockManager();
        installer =
            new LockingInstaller().setFileProcessor( TestFileProcessor.INSTANCE ).setLockManager( lockManager ).setFileLockManager( fileLockManager );
        // logger = new SyserrLogger();
        // installer.setLogger( logger );
        request = new InstallRequest();
        listener = new RecordingRepositoryListener();
        session.setRepositoryListener( listener );
        TestFileUtils.delete( session.getLocalRepository().getBasedir() );
    }

    @After
    public void teardown()
        throws IOException, InterruptedException
    {
        if ( process != null )
        {
            ForkJvm.flush( process );
            process.waitFor();
        }
        TestFileUtils.delete( session.getLocalRepository().getBasedir() );
    }


    @Test
    public void testSuccessfulInstall()
        throws InstallationException, UnsupportedEncodingException, IOException
    {
        File artifactFile =
            new File( session.getLocalRepositoryManager().getRepository().getBasedir(), localArtifactPath );
        File metadataFile =
            new File( session.getLocalRepositoryManager().getRepository().getBasedir(), localMetadataPath );

        artifactFile.delete();
        metadataFile.delete();

        request.addArtifact( artifact );
        request.addMetadata( metadata );

        InstallResult result = installer.install( session, request );

        assertTrue( artifactFile.exists() );
        TestFileUtils.assertContent( "artifact".getBytes( "UTF-8" ), artifactFile );

        assertTrue( metadataFile.exists() );
        TestFileUtils.assertContent( "metadata".getBytes( "UTF-8" ), metadataFile );

        assertEquals( result.getRequest(), request );

        assertEquals( result.getArtifacts().size(), 1 );
        assertTrue( result.getArtifacts().contains( artifact ) );

        assertEquals( result.getMetadata().size(), 1 );
        assertTrue( result.getMetadata().contains( metadata ) );
    }

    @Test( expected = InstallationException.class )
    public void testNullArtifactFile()
        throws InstallationException
    {
        InstallRequest request = new InstallRequest();
        request.addArtifact( artifact.setFile( null ) );

        installer.install( session, request );
    }

    @Test( expected = InstallationException.class )
    public void testNullMetadataFile()
        throws InstallationException
    {
        InstallRequest request = new InstallRequest();
        request.addMetadata( metadata.setFile( null ) );

        installer.install( session, request );
    }

    @Test( expected = InstallationException.class )
    public void testArtifactExistsAsDir()
        throws InstallationException
    {
        String path = session.getLocalRepositoryManager().getPathForLocalArtifact( artifact );
        File file = new File( session.getLocalRepository().getBasedir(), path );
        assertFalse( file.getAbsolutePath() + " is a file, not directory", file.isFile() );
        assertFalse( file.getAbsolutePath() + " already exists", file.exists() );
        assertTrue( "failed to setup test: could not create " + file.getAbsolutePath(),
                    file.mkdirs() || file.isDirectory() );

        request.addArtifact( artifact );
        installer.install( session, request );
    }

    @Test( expected = InstallationException.class )
    public void testMetadataExistsAsDir()
        throws InstallationException
    {
        String path = session.getLocalRepositoryManager().getPathForLocalMetadata( metadata );
        assertTrue( "failed to setup test: could not create " + path,
                    new File( session.getLocalRepository().getBasedir(), path ).mkdirs() );

        request.addMetadata( metadata );
        installer.install( session, request );
    }

    @Test
    public void testSuccessfulArtifactEvents()
        throws InstallationException
    {
        InstallRequest request = new InstallRequest();
        request.addArtifact( artifact );

        installer.install( session, request );
        checkEvents( "Repository Event problem", artifact, false );
    }

    @Test
    public void testSuccessfulMetadataEvents()
        throws InstallationException
    {
        InstallRequest request = new InstallRequest();
        request.addMetadata( metadata );

        installer.install( session, request );
        checkEvents( "Repository Event problem", metadata, false );
    }

    @Test
    public void testFailingEventsNullArtifactFile()
    {
        checkFailedEvents( "null artifact file", this.artifact.setFile( null ) );
    }

    @Test
    public void testFailingEventsNullMetadataFile()
    {
        checkFailedEvents( "null metadata file", this.metadata.setFile( null ) );
    }

    @Test
    public void testFailingEventsArtifactExistsAsDir()
    {
        String path = session.getLocalRepositoryManager().getPathForLocalArtifact( artifact );
        assertTrue( "failed to setup test: could not create " + path,
                    new File( session.getLocalRepository().getBasedir(), path ).mkdirs() );
        checkFailedEvents( "target exists as dir", artifact );
    }

    @Test
    public void testFailingEventsMetadataExistsAsDir()
    {
        String path = session.getLocalRepositoryManager().getPathForLocalMetadata( metadata );
        assertTrue( "failed to setup test: could not create " + path,
                    new File( session.getLocalRepository().getBasedir(), path ).mkdirs() );
        checkFailedEvents( "target exists as dir", metadata );
    }

    private void checkFailedEvents( String msg, Metadata metadata )
    {
        InstallRequest request = new InstallRequest().addMetadata( metadata );
        msg = "Repository events problem (case: " + msg + ")";

        try
        {
            installer.install( session, request );
            fail( "expected exception" );
        }
        catch ( InstallationException e )
        {
            checkEvents( msg, metadata, true );
        }

    }

    private void checkEvents( String msg, Metadata metadata, boolean failed )
    {
        List<EventWrapper> events = listener.getEvents();
        assertEquals( msg, 2, events.size() );
        EventWrapper wrapper = events.get( 0 );
        assertEquals( msg, METADATA_INSTALLING, wrapper.getType() );

        RepositoryEvent event = wrapper.getEvent();
        assertEquals( msg, metadata, event.getMetadata() );
        assertNull( msg, event.getException() );

        wrapper = events.get( 1 );
        assertEquals( msg, METADATA_INSTALLED, wrapper.getType() );
        event = wrapper.getEvent();
        assertEquals( msg, metadata, event.getMetadata() );
        if ( failed )
        {
            assertNotNull( msg, event.getException() );
        }
        else
        {
            assertNull( msg, event.getException() );
        }
    }

    private void checkFailedEvents( String msg, Artifact artifact )
    {
        InstallRequest request = new InstallRequest().addArtifact( artifact );
        msg = "Repository events problem (case: " + msg + ")";

        try
        {
            installer.install( session, request );
            fail( "expected exception" );
        }
        catch ( InstallationException e )
        {
            checkEvents( msg, artifact, true );
        }
    }

    private void checkEvents( String msg, Artifact artifact, boolean failed )
    {
        List<EventWrapper> events = listener.getEvents();
        assertEquals( msg, 2, events.size() );
        EventWrapper wrapper = events.get( 0 );
        assertEquals( msg, ARTIFACT_INSTALLING, wrapper.getType() );

        RepositoryEvent event = wrapper.getEvent();
        assertEquals( msg, artifact, event.getArtifact() );
        assertNull( msg, event.getException() );

        wrapper = events.get( 1 );
        assertEquals( msg, ARTIFACT_INSTALLED, wrapper.getType() );
        event = wrapper.getEvent();
        assertEquals( msg, artifact, event.getArtifact() );
        if ( failed )
        {
            assertNotNull( msg + " > expected exception", event.getException() );
        }
        else
        {
            assertNull( msg + " > " + event.getException(), event.getException() );
        }
    }

    @Test
    public void testDoNotUpdateUnchangedArtifact()
        throws InstallationException, IOException
    {
        request.addArtifact( artifact );
        installer.install( session, request );

        installer.setFileProcessor( new DefaultFileProcessor()
        {
            @Override
            public long copy( File src, File target, ProgressListener listener )
                throws IOException
            {
                throw new IOException( "copy called" );
            }
        } );

        request = new InstallRequest();
        request.addArtifact( artifact );
        long lastModified = localArtifactFile.lastModified();
        byte[] content = TestFileUtils.getContent( localArtifactFile );
        installer.install( session, request );
        assertEquals( "artifact file was changed", lastModified, localArtifactFile.lastModified() );
        TestFileUtils.assertContent( content, localArtifactFile );
    }

    @Test
    public void testSetArtifactTimestamps()
        throws InstallationException
    {
        artifact.getFile().setLastModified( artifact.getFile().lastModified() - 60000 );

        request.addArtifact( artifact );

        installer.install( session, request );

        assertEquals( "artifact timestamp was not set to src file", artifact.getFile().lastModified(),
                      localArtifactFile.lastModified() );

        request = new InstallRequest();

        request.addArtifact( artifact );

        artifact.getFile().setLastModified( artifact.getFile().lastModified() - 60000 );

        installer.install( session, request );

        assertEquals( "artifact timestamp was not set to src file", artifact.getFile().lastModified(),
                      localArtifactFile.lastModified() );
    }

    @Test
    public void testConcurrentInstall()
        throws Throwable
    {
        TestFramework.runOnce( new MultithreadedTestCase()
        {

            public void thread1()
                throws InstallationException, IOException
            {
                waitForTick( 1 );
                request = new InstallRequest();
                Artifact a = artifact.setFile( TestFileUtils.createTempFile( "firstInstall" ) );

                request.addArtifact( a );
                request.addMetadata( metadata.setFile( TestFileUtils.createTempFile( "firstInstall" ) ) );
                installer.install( session, request );
            }

            public void thread2()
                throws IOException, InstallationException
            {
                waitForTick( 2 );
                request = new InstallRequest();
                request.addArtifact( artifact.setVersion( "unlockedArtifact" ).setFile( TestFileUtils.createTempFile( "secondInstall" ) ) );
                request.addMetadata( metadata.setFile( TestFileUtils.createTempFile( "secondInstall" ) ) );
                installer.install( session, request );
                assertTick( 3 );
            }

            public void thread3()
                throws LockingException
            {
                File f = new File( session.getLocalRepository().getBasedir(), localArtifactPath );
                Lock lock = lockManager.writeLock( f );
                lock.lock();
                waitForTick( 3 );
                lock.unlock();
            }
        } );
    }

    @Test
    public void testStaging()
        throws Throwable
    {
        TestFramework.runOnce( new MultithreadedTestCase()
        {
            private Artifact a;

            private File target;

            private File basedir = session.getLocalRepository().getBasedir();

            private LocalRepositoryManager lrm = session.getLocalRepositoryManager();

            @Override
            public void initialize()
            {
                try
                {
                    File file = TestFileUtils.createTempFile( "afterInstall" );
                    a = new StubArtifact( "gid:aid:ext:blockme" ).setFile( file );
                    request.addArtifact( artifact );
                    request.addArtifact( a );

                    target = new File( basedir, lrm.getPathForLocalArtifact( a ) );
                    TestFileUtils.write( "beforeInstall", target );

                    session.setRepositoryListener( new RecordingRepositoryListener()
                    {

                        @Override
                        public void artifactInstalling( RepositoryEvent event )
                        {
                            if ( "blockme".equals( event.getArtifact().getVersion() ) )
                            {
                                synchronized ( a )
                                {
                                    try
                                    {
                                        a.wait();
                                    }
                                    catch ( InterruptedException e )
                                    {
                                        e.printStackTrace();
                                        fail( "interrupted" );
                                    }
                                }
                            }
                            super.artifactInstalling( event );
                        }

                    } );
                }
                catch ( Exception e1 )
                {
                    e1.printStackTrace();
                    fail( e1.getMessage() );
                }
            }

            public void threadInstaller()
            {
                try
                {
                    installer.install( session, request );
                }
                catch ( InstallationException e )
                {
                    e.printStackTrace();
                    fail( "installation exception" );
                }
                assertTick( 2 );
            }

            public void threadChecker()
                throws IOException
            {
                File realArtifact = new File( basedir, lrm.getPathForLocalArtifact( artifact ) );
                waitForTick( 1 );
                TestFileUtils.assertContent( "beforeInstall", target );
                assertFalse( "installer writes directly", realArtifact.exists() );
                waitForTick( 3 );
                TestFileUtils.assertContent( "artifact", realArtifact );
                TestFileUtils.assertContent( "afterInstall", target );
            }

            public void threadManager()
            {
                waitForTick( 2 );
                synchronized ( a )
                {
                    a.notifyAll();
                }
            }
        } );
    }

    @Test
    public void testLockingArtifact()
        throws InterruptedException, IOException, InstallationException
    {
        int wait = 1500;
        ExternalFileLock ext = new ExternalFileLock();
        request.addArtifact( artifact );

        File lockfile = new File( session.getLocalRepository().getBasedir(), "LockingInstaller_FileLock_gid" );
        process = ext.lockFile( lockfile.getAbsolutePath(), wait );
        long start = System.currentTimeMillis();

        // give external lock time to initialize
        Thread.sleep( 500 );

        installer.install( session, request );

        long end = System.currentTimeMillis();
        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );
        assertTrue( message, end > start + ( wait - 100 ) );
    }

    @Test
    public void testLockingMetadata()
        throws InstallationException, InterruptedException, IOException
    {
        int wait = 1500;
        ExternalFileLock ext = new ExternalFileLock();
        request.addMetadata( metadata );

        File lockfile = new File( session.getLocalRepository().getBasedir(), "LockingInstaller_FileLock_gid" );
        process = ext.lockFile( lockfile.getAbsolutePath(), wait );

        long start = System.currentTimeMillis();

        // give external lock time to initialize
        Thread.sleep( 500 );

        installer.install( session, request );

        long end = System.currentTimeMillis();

        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );
        assertTrue( message, end > start + ( wait - 100 ) );
    }

    /**
     * @author Benjamin Hanzelmann
     *
     */
    private final class SyserrLogger
        implements Logger
    {
        public boolean isDebugEnabled()
        {
            return true;
        }
    
        public void debug( String msg, Throwable error )
        {
            debug( msg );
            error.printStackTrace();
    
        }
    
        public void debug( String msg )
        {
            System.err.println( msg );
        }
    }
}

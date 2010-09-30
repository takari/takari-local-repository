package org.sonatype.aether.extension.installer;


/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, 
 * and you may not use this file except in compliance with the Apache License Version 2.0. 
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the Apache License Version 2.0 is distributed on an 
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sonatype.aether.test.impl.RecordingRepositoryListener.Type.ARTIFACT_INSTALLED;
import static org.sonatype.aether.test.impl.RecordingRepositoryListener.Type.ARTIFACT_INSTALLING;
import static org.sonatype.aether.test.impl.RecordingRepositoryListener.Type.METADATA_INSTALLED;
import static org.sonatype.aether.test.impl.RecordingRepositoryListener.Type.METADATA_INSTALLING;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.aether.RepositoryEvent;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.extension.installer.LockManager.Lock;
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
        lockManager = new DefaultLockManager();
        installer = new LockingInstaller().setFileProcessor( TestFileProcessor.INSTANCE ).setLockManager( lockManager );
        logger = new Logger()
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
        };
        installer.setLogger( logger );
        request = new InstallRequest();
        listener = new RecordingRepositoryListener();
        session.setRepositoryListener( listener );
        TestFileUtils.deleteDir( session.getLocalRepository().getBasedir() );
    }

    @After
    public void teardown()
    {
        TestFileUtils.deleteDir( session.getLocalRepository().getBasedir() );
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

    @SuppressWarnings( "unused" )
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
            {
                File f = new File( session.getLocalRepository().getBasedir(), localArtifactPath );
                Lock lock = lockManager.writeLock( f );
                lock.lock();
                waitForTick( 3 );
                lock.unlock();
            }
        } );
    }

    @SuppressWarnings( "unused" )
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
                    System.err.println( request.getArtifacts() );
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
}

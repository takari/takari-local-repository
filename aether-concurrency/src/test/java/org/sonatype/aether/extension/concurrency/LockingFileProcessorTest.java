package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.aether.spi.io.FileProcessor.ProgressListener;
import org.sonatype.aether.test.util.TestFileUtils;

import edu.umd.cs.mtc.MultithreadedTestCase;
import edu.umd.cs.mtc.TestFramework;

/**
 * @author Benjamin Hanzelmann
 */
public class LockingFileProcessorTest
{

    private static final class BlockingProgressListener
        implements ProgressListener
    {
        private Object lock;

        public BlockingProgressListener( Object lock )
        {
            this.lock = lock;
        }

        public void progressed( ByteBuffer buffer )
            throws IOException
        {
            synchronized ( lock )
            {
                try
                {
                    lock.wait();
                }
                catch ( InterruptedException e )
                {
                    e.printStackTrace();
                    throw new IOException( e.getMessage() );
                }
            }
        }
    }

    private File targetDir;

    private LockingFileProcessor fileProcessor;

    private Process process;

    @Before
    public void setup()
        throws IOException
    {
        targetDir = TestFileUtils.createTempDir( getClass().getSimpleName() );
        fileProcessor = new LockingFileProcessor();
        fileProcessor.setLockManager( new DefaultLockManager() ).setFileLockManager( new DefaultFileLockManager() );
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
        TestFileUtils.delete( targetDir );
        fileProcessor = null;
    }

    @Test
    public void testCopy()
        throws IOException
    {
        File file = TestFileUtils.createTempFile( "testCopy\nasdf" );
        File target = new File( targetDir, "testCopy.txt" );

        fileProcessor.copy( file, target, null );

        assertContent( file, "testCopy\nasdf".getBytes( "UTF-8" ) );

        file.delete();
    }

    private void assertContent( File file, byte[] content )
        throws IOException
    {
        RandomAccessFile in = null;
        try
        {
            in = new RandomAccessFile( file, "r" );
            byte[] buffer = new byte[(int) in.length()];
            in.readFully( buffer );
            assertArrayEquals( "content did not match", content, buffer );
        }
        finally
        {
            in.close();
        }
    }

    @Test
    public void testOverwrite()
        throws IOException
    {
        File file = TestFileUtils.createTempFile( "testOverwrite\nasdf" );

        for ( int i = 0; i < 5; i++ )
        {
            File target = new File( targetDir, "testOverwrite.txt" );
            fileProcessor.copy( file, target, null );
            assertContent( file, "testOverwrite\nasdf".getBytes( "UTF-8" ) );
        }

        file.delete();
    }

    @SuppressWarnings( "unused" )
    @Test
    public void testBlockingCopyExistingWriteLockOnSrc()
        throws Throwable
    {
        TestFramework.runOnce( new MultithreadedTestCase()
        {
            private File locked;

            private File unlocked;

            private Object lock;

            private BlockingProgressListener listener;

            public void thread1()
            {
                try
                {
                    fileProcessor.copy( TestFileUtils.createTempFile( "contents" ), locked, listener );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }
                assertTick( 2 );
            }

            public void thread2()
            {
                waitForTick( 1 );
                try
                {
                    fileProcessor.copy( locked, unlocked, null );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }

                assertTick( 2 );
            }

            public void thread3()
            {
                waitForTick( 2 );
                synchronized ( lock )
                {
                    lock.notifyAll();
                }

            }

            @Override
            public void initialize()
            {
                lock = new Object();
                listener = new BlockingProgressListener( lock );

                try
                {
                    locked = TestFileUtils.createTempFile( "some content" );
                    unlocked = TestFileUtils.createTempFile( "another file" );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "could not create temp files" );
                }
            }
        } );
    }

    @SuppressWarnings( "unused" )
    @Test
    public void testBlockingCopyExistingWriteLockOnTarget()
        throws Throwable
    {
        TestFramework.runOnce( new MultithreadedTestCase()
        {
            private File locked;

            private File unlocked;

            private Object lock;

            private ProgressListener listener;

            public void thread1()
            {
                try
                {
                    fileProcessor.copy( TestFileUtils.createTempFile( "contents" ), locked, listener );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }
                assertTick( 2 );
            }

            public void thread2()
            {
                waitForTick( 1 );

                try
                {
                    fileProcessor.copy( unlocked, locked, null );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }

                assertTick( 2 );
            }

            public void thread3()
            {
                waitForTick( 2 );
                synchronized ( lock )
                {
                    lock.notifyAll();
                }
            }

            @Override
            public void initialize()
            {
                lock = new Object();
                listener = new BlockingProgressListener( lock );

                try
                {
                    locked = TestFileUtils.createTempFile( "some content" );
                    unlocked = TestFileUtils.createTempFile( "another file" );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "could not create temp files" );
                }
            }

        } );

    }

    @SuppressWarnings( "unused" )
    @Test
    public void testBlockingCopyExistingReadLockOnTarget()
        throws Throwable
    {
        TestFramework.runOnce( new MultithreadedTestCase()
        {
            private File locked;

            private File unlocked;

            private Object lock;

            private BlockingProgressListener listener;

            public void thread1()
            {
                try
                {
                    fileProcessor.copy( locked, TestFileUtils.createTempFile( "contents" ), listener );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }
                assertTick( 2 );
            }

            public void thread2()
            {
                waitForTick( 1 );

                try
                {
                    fileProcessor.copy( unlocked, locked, null );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }

                assertTick( 2 );
            }

            public void thread3()
            {
                waitForTick( 2 );
                synchronized ( lock )
                {
                    lock.notifyAll();
                }

            }

            @Override
            public void initialize()
            {
                lock = new Object();
                listener = new BlockingProgressListener( lock );

                try
                {
                    locked = TestFileUtils.createTempFile( "some content" );
                    unlocked = TestFileUtils.createTempFile( "another file" );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "could not create temp files" );
                }
            }
        } );
    }

    @SuppressWarnings( "unused" )
    @Test
    public void testDoNotBlockExistingReadLockOnSrc()
        throws Throwable
    {
        TestFramework.runOnce( new MultithreadedTestCase()
        {
            private File locked;

            private File unlocked;

            private Object lock;

            private ReadLock readLock;

            private ProgressListener listener;

            public void thread1()
            {
                try
                {
                    fileProcessor.copy( locked, TestFileUtils.createTempFile( "contents" ), listener );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }
                assertTick( 2 );
            }

            public void thread2()
            {
                waitForTick( 1 );

                try
                {
                    fileProcessor.copy( locked, unlocked, null );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }

                assertTick( 1 );
                waitForTick( 2 );
            }

            public void thread3()
            {
                waitForTick( 2 );
                synchronized ( lock )
                {
                    lock.notifyAll();
                }

            }

            @Override
            public void initialize()
            {
                lock = new Object();
                listener = new BlockingProgressListener( lock );

                try
                {
                    locked = TestFileUtils.createTempFile( "some content" );
                    unlocked = TestFileUtils.createTempFile( "another file" );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "could not create temp files" );
                }
            }
        } );
    }

    @SuppressWarnings( "unused" )
    @Test
    public void testLockCanonicalFile()
        throws Throwable
    {
        TestFramework.runOnce( new MultithreadedTestCase()
        {
            private File locked;

            private File unlocked;

            private Object lock;

            private ProgressListener listener;

            public void thread1()
            {
                try
                {
                    fileProcessor.copy( locked, TestFileUtils.createTempFile( "contents" ), listener );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }
                assertTick( 2 );
            }

            public void thread2()
            {
                waitForTick( 1 );

                try
                {
                    fileProcessor.copy( unlocked, locked, null );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "testBlockingCopy failed (write lock on src file): " + e.getMessage() );
                }

                assertTick( 2 );
            }

            public void thread3()
            {
                waitForTick( 2 );
                synchronized ( lock )
                {
                    lock.notifyAll();
                }

            }

            @Override
            public void initialize()
            {
                lock = new Object();
                listener = new BlockingProgressListener( lock );

                try
                {
                    locked = TestFileUtils.createTempFile( "some content" );
                    unlocked = TestFileUtils.createTempFile( "another file" );
                }
                catch ( IOException e )
                {
                    e.printStackTrace();
                    fail( "could not create temp files" );
                }
            }
        } );

    }

    @Test
    public void testCopyEmptyFile()
        throws IOException
    {
        File file = TestFileUtils.createTempFile( "" );
        File target = new File( "target/testCopyEmptyFile" );
        target.delete();
        fileProcessor.copy( file, target, null );
        assertTrue( "empty file was not copied", target.exists() && target.length() == 0 );
        target.delete();
    }

    @Test
    public void testExternalLockOnEmptySource()
        throws InterruptedException, IOException
    {
        int wait = 1500;
        ExternalProcessFileLock ext = new ExternalProcessFileLock();

        File srcFile = TestFileUtils.createTempFile( "" );
        File targetFile = TestFileUtils.createTempFile( "" );

        process = ext.lockFile( srcFile.getAbsolutePath(), wait );

        long start = System.currentTimeMillis();

        // give external lock time to initialize
        Thread.sleep( 500 );

        fileProcessor.copy( srcFile, targetFile, null );

        long end = System.currentTimeMillis();

        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );

        assertTrue( message, end > start + ( wait - 100 ) );

    }

    @Test
    public void testExternalLockOnEmptyTarget()
        throws InterruptedException, IOException
    {
        int wait = 1500;
        ExternalProcessFileLock ext = new ExternalProcessFileLock();

        File srcFile = TestFileUtils.createTempFile( "" );
        File targetFile = TestFileUtils.createTempFile( "" );

        process = ext.lockFile( targetFile.getAbsolutePath(), wait );

        long start = System.currentTimeMillis();

        // give external lock time to initialize
        Thread.sleep( 500 );

        fileProcessor.copy( srcFile, targetFile, null );

        long end = System.currentTimeMillis();

        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );
        assertTrue( message, end > start + ( wait - 100 ) );
    }

    @Test
    public void testWriteFile()
        throws IOException
    {
        File file = TestFileUtils.createTempFile( "" );
        file.delete();
        fileProcessor.write( file, "12345678" );
        assertTrue( "empty file was not copied", file.isFile() && file.length() == 8 );
        file.delete();
    }

    @Test
    public void testFailTargetIsDirectory()
        throws IOException
    {
        File src = TestFileUtils.createTempFile( "testFailTargetIsDirectory" );
        File target = TestFileUtils.createTempDir();
        try {
            fileProcessor.copy( src, target, null );
            fail( "Expected FileNotFoundException (target is dir)" );
        }
        catch ( FileNotFoundException e )
        {
            // expected
        }
        finally
        {
            TestFileUtils.delete( src );
            TestFileUtils.delete( target );
        }
    }

    @Test
    public void testFailSrcIsDirectory()
        throws IOException
    {
        File src = TestFileUtils.createTempDir();
        File target = TestFileUtils.createTempFile( "testFailSrcIsDirectory" );
        try
        {
            fileProcessor.copy( src, target, null );
            fail( "Expected FileNotFoundException (src is dir)" );
        }
        catch ( FileNotFoundException e )
        {
            // expected
        }
        finally
        {
            TestFileUtils.delete( src );
            TestFileUtils.delete( target );
        }
    }
}

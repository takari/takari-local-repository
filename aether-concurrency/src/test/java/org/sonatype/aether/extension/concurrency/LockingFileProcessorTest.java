package org.sonatype.aether.extension.concurrency;

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

import static org.junit.Assert.*;

import java.io.File;
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
    {
        targetDir = new File( "target/test-FileUtils" );
        fileProcessor = new LockingFileProcessor();
        fileProcessor.setLockManager( new DefaultLockManager() );
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
        File file = TestFileUtils.createTempFile( "testCopy\nasdf" );

        for ( int i = 0; i < 5; i++ )
        {
            File target = new File( targetDir, "testCopy.txt" );
            fileProcessor.copy( file, target, null );
            assertContent( file, "testCopy\nasdf".getBytes( "UTF-8" ) );
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
    public void testExternalLockOnSrc()
        throws InterruptedException, IOException
    {
        int wait = 1500;
        ExternalFileLock ext = new ExternalFileLock();

        File srcFile = TestFileUtils.createTempFile( "lockedFile" );
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
    public void testExternalLockOnTarget()
        throws InterruptedException, IOException
    {
        int wait = 1500;
        ExternalFileLock ext = new ExternalFileLock();

        File srcFile = TestFileUtils.createTempFile( "lockedFile" );
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

}

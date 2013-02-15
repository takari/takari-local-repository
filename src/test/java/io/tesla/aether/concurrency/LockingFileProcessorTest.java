package io.tesla.aether.concurrency;

/*******************************************************************************
 * Copyright (c) 2010-2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import io.tesla.aether.concurrency.DefaultFileLockManager;
import io.tesla.aether.concurrency.FileLockManager.Lock;
import io.tesla.aether.concurrency.LockingFileProcessor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;

import org.eclipse.aether.internal.test.util.TestFileUtils;
import org.eclipse.aether.spi.io.FileProcessor.ProgressListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

    private DefaultFileLockManager lockManager;

    @Before
    public void setup()
        throws IOException
    {
        targetDir = TestFileUtils.createTempDir( getClass().getSimpleName() );
        fileProcessor = new LockingFileProcessor();
        lockManager = new DefaultFileLockManager();
        fileProcessor.setFileLockManager( lockManager );
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
        String contents = "testCopy\nasdf";

        File file = TestFileUtils.createTempFile( contents );
        File target = new File( targetDir, "testCopy.txt" );

        fileProcessor.copy( file, target, null );

        TestFileUtils.assertContent( contents, target );

        file.delete();
    }

    @Test
    public void testOverwrite()
        throws IOException
    {
        String contents = "testOverwrite\nasdf";

        File file = TestFileUtils.createTempFile( contents );

        for ( int i = 0; i < 5; i++ )
        {
            File target = new File( targetDir, "testOverwrite.txt" );
            fileProcessor.copy( file, target, null );
            TestFileUtils.assertContent( contents, target );
        }

        file.delete();
    }

    @Test
    public void testOverwriteBiggerFile()
        throws IOException
    {
        String contents = "src";

        File src = TestFileUtils.createTempFile( contents );
        File dst = TestFileUtils.createTempFile( "destination-file-with-greater-length-than-new-contents" );

        fileProcessor.copy( src, dst, null );

        TestFileUtils.assertContent( contents, dst );
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
                    fail( "testLockCanonicalFile failed (write lock on src file): " + e.getMessage() );
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

        File srcFile = TestFileUtils.createTempFile( "" );
        File targetFile = TestFileUtils.createTempFile( "" );

        ExternalProcessFileLock ext = new ExternalProcessFileLock( srcFile );
        process = ext.lockFile( wait );

        long start = ext.awaitLock();

        fileProcessor.copy( srcFile, targetFile, null );

        long end = System.currentTimeMillis();

        assertEquals( 0, process.waitFor() );

        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );

        assertTrue( message, end - start > wait );
    }

    @Test
    public void testExternalLockOnEmptyTarget()
        throws InterruptedException, IOException
    {
        int wait = 1500;

        File srcFile = TestFileUtils.createTempFile( "" );
        File targetFile = TestFileUtils.createTempFile( "" );

        ExternalProcessFileLock ext = new ExternalProcessFileLock( targetFile );
        process = ext.lockFile( wait );

        long start = ext.awaitLock();

        fileProcessor.copy( srcFile, targetFile, null );

        long end = System.currentTimeMillis();

        assertEquals( 0, process.waitFor() );

        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );
        assertTrue( message, end - start > wait );
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
        try
        {
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

    /**
     * Checks for graceful operation when the target file of a write operation is currently read by some 3rd party
     * process which doesn't do any locking of the file.
     */
    @Test
    public void testCopyWhenDestinationFileIsCurrentlyReadByOtherProcess()
        throws Exception
    {
        String contents = "testCopyWhenDestinationFileIsCurrentlyReadByOtherProcess";

        File source = TestFileUtils.createTempFile( contents );

        File target = TestFileUtils.createTempFile( contents );

        final RandomAccessFile raf = new RandomAccessFile( target, "r" );
        final AtomicBoolean read = new AtomicBoolean();
        try
        {
            fileProcessor.copy( source, new File( target.getCanonicalPath() )
            {
                boolean failed;

                @Override
                public File getCanonicalFile()
                    throws IOException
                {
                    return this;
                }

                @Override
                public int hashCode()
                {
                    /*
                     * NOTE: This exploits implementation details of the DefaultFileLockManager which will first acquire
                     * the file lock and then update a hash map with the canonical file as key, thereby invoking this
                     * method. If there's a more robust way to read the file after the lock has been acquired, you're
                     * invited to replace this code.
                     */
                    try
                    {
                        read.set( true );
                        raf.read();
                    }
                    catch ( IOException e )
                    {
                        if ( !failed )
                        {
                            failed = true;
                            throw new AssertionError( e );
                        }
                    }
                    return super.hashCode();
                }
            }, null );
        }
        finally
        {
            raf.close();
        }

        assertTrue( "Custom java.io.File hasn't been called, test setup invalid", read.get() );
    }

    @Test
    public void testMoveCurrentThreadHoldsWriteLock()
        throws IOException
    {
        File src = TestFileUtils.createTempFile( "src" );
        File target = TestFileUtils.createTempFile( "target" );
        Lock lock = lockManager.writeLock( src );
        lock.lock();

        try
        {
            fileProcessor.move( src, target );
            TestFileUtils.assertContent( "src", target );
            assertEquals( false, src.exists() );
        }
        finally
        {
            lock.unlock();
            TestFileUtils.delete( src );
            TestFileUtils.delete( target );
        }
    }

    @Test
    public void testConcurrentlyMoveToSameDestinationWhichGetsReadByThirdPartyWithoutLocking()
        throws Exception
    {
        final File target = TestFileUtils.createTempFile( "test" );
        final Collection<Throwable> errors = Collections.synchronizedList( new ArrayList<Throwable>() );
        final CountDownLatch latch = new CountDownLatch( 3 );

        Runnable runnable = new Runnable()
        {

            public void run()
            {
                try
                {
                    for ( int i = 100; i > 0; i-- )
                    {
                        try
                        {
                            File source = TestFileUtils.createTempFile( "test" );
                            fileProcessor.move( source, target );
                        }
                        catch ( Exception e )
                        {
                            errors.add( e );
                            e.printStackTrace();
                        }
                    }
                }
                finally
                {
                    latch.countDown();
                }
            }

        };

        Thread threads[] = new Thread[(int) latch.getCount()];
        threads[0] = new Thread()
        {
            public void run()
            {
                while ( latch.getCount() > 1 )
                {
                    try
                    {
                        TestFileUtils.assertContent( "test", target );
                    }
                    catch ( Exception e )
                    {
                        errors.add( e );
                        e.printStackTrace();
                    }
                    catch ( AssertionError e )
                    {
                        errors.add( e );
                        e.printStackTrace();
                    }
                }
                latch.countDown();
            }
        };
        for ( int i = 1; i < threads.length; i++ )
        {
            threads[i] = new Thread( runnable );
        }
        for ( int i = 0; i < threads.length; i++ )
        {
            threads[i].start();
        }
        latch.await();

        assertEquals( Collections.emptyList(), errors );
    }

}

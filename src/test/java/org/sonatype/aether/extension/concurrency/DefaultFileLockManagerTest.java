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
import java.io.IOException;
import java.nio.channels.FileChannel;

import org.junit.Before;
import org.junit.Test;
import org.sonatype.aether.extension.concurrency.DefaultFileLockManager.IndirectFileLock;
import org.sonatype.aether.extension.concurrency.FileLockManager.ExternalFileLock;
import org.sonatype.aether.extension.concurrency.LockManager.Lock;
import org.sonatype.aether.test.impl.SysoutLogger;
import org.sonatype.aether.test.util.TestFileUtils;

import edu.umd.cs.mtc.MultithreadedTestCase;
import edu.umd.cs.mtc.TestFramework;

@SuppressWarnings( "unused" )
public class DefaultFileLockManagerTest
{
    private DefaultFileLockManager manager;

    private File dir;

    private Process process;

    @Before
    public void setup()
        throws IOException
    {
        manager = new DefaultFileLockManager( new SysoutLogger() );
        this.dir = TestFileUtils.createTempDir( getClass().getSimpleName() );
    }

    @Test
    public void testExternalLockTryReadLock()
        throws InterruptedException, IOException
    {
        int wait = 1500;

        File file = TestFileUtils.createTempFile( "" );

        ExternalFileLock lock = manager.readLock( file );

        ExternalProcessFileLock ext = new ExternalProcessFileLock( file );
        process = ext.lockFile( wait );

        long start = ext.awaitLock();

        lock.lock();

        long end = System.currentTimeMillis();

        lock.unlock();

        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );

        assertTrue( message, end - start > wait );
    }

    @Test
    public void testExternalLockTryWriteLock()
        throws InterruptedException, IOException
    {
        int wait = 1500;

        File file = TestFileUtils.createTempFile( "" );

        ExternalProcessFileLock ext = new ExternalProcessFileLock( file );
        process = ext.lockFile( wait );

        ExternalFileLock lock = manager.writeLock( file );

        long start = ext.awaitLock();

        lock.lock();

        long end = System.currentTimeMillis();

        lock.unlock();

        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );
        assertTrue( message, end - start > wait );
    }

    @Test
    public void testUpgradeSharedToExclusiveLock()
        throws Throwable
    {
        final File file = TestFileUtils.createTempFile( "" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws IOException
            {
                ExternalFileLock lock = manager.readLock( file );
                lock.lock();
                assertTrue( "read lock is not shared", lock.isShared() );
                waitForTick( 2 );
                lock.unlock();
            }

            public void thread2()
                throws IOException
            {
                waitForTick( 1 );
                ExternalFileLock lock = manager.writeLock( file );
                lock.lock();
                assertTick( 2 );
                assertTrue( "read lock did not upgrade to exclusive", !lock.isShared() );
                lock.unlock();
            }
        } );
    }

    @Test
    public void testCanonicalFileLock()
        throws Exception
    {
        File file1 = TestFileUtils.createTempFile( "testCanonicalFileLock" );
        File file2 = new File( file1.getParent() + File.separator + ".", file1.getName() );

        IndirectFileLock lock1 = (IndirectFileLock) manager.readLock( file1 );
        IndirectFileLock lock2 = (IndirectFileLock) manager.readLock( file2 );

        lock1.lock();
        lock2.lock();

        FileChannel channel1 = lock1.getRandomAccessFile().getChannel();
        FileChannel channel2 = lock2.getRandomAccessFile().getChannel();

        assertNotSame( channel1, channel2 );
        assertSame( lock1.getLock(), lock2.getLock() );

        lock1.unlock();
        assertNull( lock1.getRandomAccessFile() );
        assertFalse( channel1.isOpen() );

        assertTrue( lock2.getLock().isValid() );
        assertNotNull( lock2.getRandomAccessFile() );
        assertTrue( channel2.isOpen() );

        lock2.unlock();
        assertNull( lock2.getRandomAccessFile() );
        assertFalse( channel2.isOpen() );
    }

    @Test
    public void testSafeUnlockOfNonAcquiredLock()
        throws IOException
    {
        File file = TestFileUtils.createTempFile( "" );

        ExternalFileLock lock = manager.readLock( file );
        lock.unlock();
    }

    @Test
    public void testMultipleLocksSameThread()
        throws Throwable
    {
        final File a = TestFileUtils.createTempFile( "a" );
        final File b = TestFileUtils.createTempFile( "b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            private IndirectFileLock r1;

            private IndirectFileLock r2;

            private IndirectFileLock w1;

            private IndirectFileLock w2;

            public void thread1()
                throws IOException
            {
                r1 = (IndirectFileLock) manager.readLock( a );
                r2 = (IndirectFileLock) manager.readLock( a );
                w1 = (IndirectFileLock) manager.writeLock( b );
                w2 = (IndirectFileLock) manager.writeLock( b );
                try
                {

                    r1.lock();
                    r2.lock();
                    w1.lock();
                    w2.lock();

                    assertSame( r1.getLock(), r2.getLock() );
                    assertEquals( true, r1.getLock().isValid() );
                    assertEquals( true, r2.getLock().isValid() );

                    assertSame( w1.getLock(), w2.getLock() );
                    assertEquals( true, w1.getLock().isValid() );
                    assertEquals( true, w2.getLock().isValid() );

                    r1.unlock();
                    assertEquals( true, r2.getLock().isValid() );
                    r2.unlock();
                    w1.unlock();
                    assertEquals( true, w2.getLock().isValid() );
                    w2.unlock();
                }
                finally
                {
                    if ( w1 != null )
                    {
                        w1.unlock();
                    }
                    if ( w2 != null )
                    {
                        w2.unlock();
                    }
                    if ( r1 != null )
                    {
                        r1.unlock();
                    }
                    if ( r2 != null )
                    {
                        r2.unlock();
                    }
                }
            }

        } );

    }

}

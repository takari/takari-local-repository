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
import org.sonatype.aether.extension.concurrency.DefaultFileLockManager.DefaultFileLock;
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
        ExternalProcessFileLock ext = new ExternalProcessFileLock();

        File file = TestFileUtils.createTempFile( "" );

        ExternalFileLock lock = manager.readLock( file );

        process = ext.lockFile( file.getAbsolutePath(), wait );

        long start = System.currentTimeMillis();

        // give external lock time to initialize
        Thread.sleep( 500 );

        lock.lock();

        long end = System.currentTimeMillis();

        lock.unlock();

        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );

        assertTrue( message, end > start + ( wait - 100 ) );

    }

    @Test
    public void testExternalLockTryWriteLock()
        throws InterruptedException, IOException
    {
        int wait = 1500;
        ExternalProcessFileLock ext = new ExternalProcessFileLock();

        File file = TestFileUtils.createTempFile( "" );

        process = ext.lockFile( file.getAbsolutePath(), wait );

        ExternalFileLock lock = manager.writeLock( file );

        long start = System.currentTimeMillis();

        // give external lock time to initialize
        Thread.sleep( 500 );

        lock.lock();

        long end = System.currentTimeMillis();

        lock.unlock();

        String message = "expected " + wait + "ms wait, real delta: " + ( end - start );
        assertTrue( message, end > start + ( wait - 100 ) );
    }

    @Test
    public void testUpgradeSharedToExclusiveLock()
        throws IOException
    {
        File file = TestFileUtils.createTempFile( "" );

        DefaultFileLock lock = (DefaultFileLock) manager.readLock( file );
        lock.lock();
        assertTrue( "read lock is not shared", lock.getLock().isShared() );
        lock = (DefaultFileLock) manager.writeLock( file );
        lock.lock();
        assertTrue( "read lock did not upgrade to exclusive", !lock.getLock().isShared() );
        lock.unlock();
    }

    @Test
    public void testCanonicalFileLock()
        throws Exception
    {
        File file1 = TestFileUtils.createTempFile( "testCanonicalFileLock" );
        File file2 = new File( file1.getParent() + File.separator + ".", file1.getName() );

        ExternalFileLock lock1 = manager.writeLock( file1 );
        ExternalFileLock lock2 = manager.writeLock( file2 );
        lock1.lock();
        FileChannel channel = lock1.channel();

        lock2.lock();
        assertEquals( channel, lock2.channel() );

        lock1.unlock();
        assertTrue( channel.isOpen() );
        assertTrue( lock2.channel().isOpen() );

        lock2.unlock();
        assertFalse( "manager failed to unlock, channel still open", channel.isOpen() );
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
            private DefaultFileLock r1;

            private DefaultFileLock r2;

            private DefaultFileLock w1;

            private DefaultFileLock w2;

            public void thread1()
                throws IOException
            {
                r1 = (DefaultFileLock) manager.readLock( a );
                r2 = (DefaultFileLock) manager.readLock( a );
                w1 = (DefaultFileLock) manager.writeLock( b );
                w2 = (DefaultFileLock) manager.writeLock( b );
                try
                {

                    r1.lock();
                    r2.lock();
                    w1.lock();
                    w2.lock();

                    assertEquals( true, r1.getLock().isValid() );
                    assertEquals( true, r2.getLock().isValid() );
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

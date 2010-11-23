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

import org.junit.Before;
import org.junit.Test;
import org.sonatype.aether.extension.concurrency.LockManager.Lock;
import org.sonatype.aether.test.util.TestFileUtils;

import edu.umd.cs.mtc.MultithreadedTestCase;
import edu.umd.cs.mtc.TestFramework;

@SuppressWarnings( "unused" )
public class DefaultLockManagerTest
{
    private DefaultLockManager manager;

    private File dir;

    @Before
    public void setup()
        throws IOException
    {
        manager = new DefaultLockManager();
        this.dir = TestFileUtils.createTempDir( getClass().getSimpleName() );
    }

    @Test
    public void testUnlockCanonicalFile()
        throws Throwable
    {
        final File a = new File( dir, "a/b" );
        final File b = new File( dir, "a/./b" );

        Lock lockA = manager.readLock( a );
        Lock lockB = manager.readLock( b );
        lockA.lock();
        lockB.lock();
        lockA.unlock();
        lockB.unlock();

        assertEquals( 0, manager.count.size() );
    }

    @Test
    public void testLockCanonicalFile()
        throws Throwable
    {
        final File a = new File( dir, "a/b" );
        final File b = new File( dir, "a/./b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws IOException
            {
                Lock lock = manager.writeLock( a );
                lock.lock();
                waitForTick( 3 );
                lock.unlock();
            }

            public void thread2()
                throws IOException
            {
                waitForTick( 1 );
                Lock lock = manager.writeLock( b );
                lock.lock();
                assertTick( 3 );
            }
        } );
    }

    @Test
    public void testWriteBlocksRead()
        throws Throwable
    {
        final File a = new File( dir, "a/b" );
        final File b = new File( dir, "a/b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws IOException
            {
                Lock lock = manager.writeLock( a );
                lock.lock();
                waitForTick( 2 );
                lock.unlock();
            }

            public void thread2()
                throws IOException
            {
                waitForTick( 1 );
                Lock lock = manager.readLock( b );
                lock.lock();
                assertTick( 2 );
                lock.unlock();
            }
        } );
    }

    @Test
    public void testReadDoesNotBlockRead()
        throws Throwable
    {
        final File a = new File( dir, "a/b" );
        final File b = new File( dir, "a/b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws IOException
            {
                Lock lock = manager.readLock( a );
                lock.lock();
                waitForTick( 2 );
                lock.unlock();
            }

            public void thread2()
                throws IOException
            {
                waitForTick( 1 );
                Lock lock = manager.readLock( b );
                lock.lock();
                assertTick( 1 );
                lock.unlock();
            }
        } );
    }

    @Test
    public void testReadBlocksWrite()
        throws Throwable
    {
        final File a = new File( dir, "a/b" );
        final File b = new File( dir, "a/b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws IOException
            {
                Lock lock = manager.readLock( a );
                lock.lock();
                waitForTick( 2 );
                lock.unlock();
            }

            public void thread2()
                throws IOException
            {
                waitForTick( 1 );
                Lock lock = manager.writeLock( b );
                lock.lock();
                assertTick( 2 );
                lock.unlock();
            }
        } );
    }

    @Test
    public void testWriteBlocksWrite()
        throws Throwable
    {
        final File a = new File( dir, "a/b" );
        final File b = new File( dir, "a/b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws IOException
            {
                Lock lock = manager.writeLock( a );
                lock.lock();
                waitForTick( 2 );
                lock.unlock();
            }

            public void thread2()
                throws IOException
            {
                waitForTick( 1 );
                Lock lock = manager.writeLock( b );
                lock.lock();
                assertTick( 2 );
                lock.unlock();
            }
        } );
    }

    @Test
    public void testNoLockLeakage()
    {
        final File a = new File( dir, "a/b" );
        final File b = new File( dir, "a/c" );
        manager.readLock( a );
        manager.writeLock( b );
        assertEquals( 0, manager.count.size() );
    }

    @Test
    public void testNoPrematureLocking()
        throws Throwable
    {
        final File a = new File( dir, "a/b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
                throws IOException
            {
                Lock lock = manager.readLock( a );
                waitForTick( 2 );
            }

            public void thread2()
                throws IOException
            {
                waitForTick( 1 );
                Lock lock = manager.writeLock( a );
                lock.lock();
                assertTick( 1 );
                lock.unlock();
            }
        } );
    }

    @Test
    public void testMultipleLocksSameThread()
        throws Throwable
    {
        final File a = new File( dir, "a/b" );
        final File b = new File( dir, "a/c" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            private Lock r1;

            private Lock r2;

            private Lock w1;

            private Lock w2;

            public void thread1()
                throws IOException
            {
                r1 = manager.readLock( a );
                r2 = manager.readLock( a );
                w1 = manager.writeLock( b );
                w2 = manager.writeLock( b );
                try
                {

                r1.lock();
                r2.lock();
                w1.lock();
                w2.lock();

                assertEquals( 2, manager.count.size() );
                assertEquals( 2, manager.count.get( a.getCanonicalFile() ).get() );
                assertEquals( 2, manager.count.get( b.getCanonicalFile() ).get() );
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

        assertEquals( 0, manager.count.size() );
    }
}

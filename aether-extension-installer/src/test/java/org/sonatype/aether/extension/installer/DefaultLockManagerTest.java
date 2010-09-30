package org.sonatype.aether.extension.installer;

import java.io.File;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.junit.Before;
import org.junit.Test;

import edu.umd.cs.mtc.MultithreadedTestCase;
import edu.umd.cs.mtc.TestFramework;

@SuppressWarnings( "unused" )
public class DefaultLockManagerTest
{
    private DefaultLockManager manager;

    @Before
    public void setup()
    {
        manager = new DefaultLockManager();
    }

    @Test
    public void testCanonicalFile()
        throws Throwable
    {
        final File a = new File( "target", "a/b" );
        final File b = new File( "target", "a/./b" );
        
        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
            {
                WriteLock lock = manager.writeLock( a );
                lock.lock();
                waitForTick( 3 );
                lock.unlock();
            }

            public void thread2()
            {
                waitForTick( 1 );
                WriteLock lock = manager.writeLock( b );
                lock.lock();
                assertTick( 3 );
            }
        } );
    }

    @Test
    public void testWriteBlocksRead()
        throws Throwable
    {
        final File a = new File( "target", "a/b" );
        final File b = new File( "target", "a/b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
            {
                WriteLock lock = manager.writeLock( a );
                lock.lock();
                waitForTick( 2 );
                lock.unlock();
            }

            public void thread2()
            {
                waitForTick( 1 );
                ReadLock lock = manager.readLock( b );
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
        final File a = new File( "target", "a/b" );
        final File b = new File( "target", "a/b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
            {
                ReadLock lock = manager.readLock( a );
                lock.lock();
                waitForTick( 2 );
                lock.unlock();
            }

            public void thread2()
            {
                waitForTick( 1 );
                ReadLock lock = manager.readLock( b );
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
        final File a = new File( "target", "a/b" );
        final File b = new File( "target", "a/b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
            {
                ReadLock lock = manager.readLock( a );
                lock.lock();
                waitForTick( 2 );
                lock.unlock();
            }

            public void thread2()
            {
                waitForTick( 1 );
                WriteLock lock = manager.writeLock( b );
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
        final File a = new File( "target", "a/b" );
        final File b = new File( "target", "a/b" );

        TestFramework.runOnce( new MultithreadedTestCase()
        {
            public void thread1()
            {
                WriteLock lock = manager.writeLock( a );
                lock.lock();
                waitForTick( 2 );
                lock.unlock();
            }

            public void thread2()
            {
                waitForTick( 1 );
                WriteLock lock = manager.writeLock( b );
                lock.lock();
                assertTick( 2 );
                lock.unlock();
            }
        } );
    }

}

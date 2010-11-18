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

    }
}

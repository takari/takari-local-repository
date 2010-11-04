package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.codehaus.plexus.component.annotations.Component;

/**
 * @author Benjamin Hanzelmann
 */
@Component( role = LockManager.class )
public class DefaultLockManager
    implements LockManager
{
    private Map<File, ReentrantReadWriteLock> locks = new HashMap<File, ReentrantReadWriteLock>();

    private Map<File, AtomicInteger> count = new HashMap<File, AtomicInteger>();

    public Lock readLock( File file )
    {
        ReentrantReadWriteLock lock = lookup( file );

        return new DefaultLock( this, lock, file, false );
    }

    public Lock writeLock( File file )
    {
        ReentrantReadWriteLock lock = lookup( file );
        return new DefaultLock( this, lock, file, true );
    }

    private ReentrantReadWriteLock lookup( File file )
    {
        ReentrantReadWriteLock lock = null;

        try
        {
            file = file.getCanonicalFile();
        }
        catch ( IOException e )
        {
            // best effort - use absolute file
            file = file.getAbsoluteFile();
        }

        synchronized ( locks )
        {
            if ( ( lock = locks.get( file ) ) == null )
            {
                lock = new ReentrantReadWriteLock( true );
                locks.put( file, lock );
            }
            AtomicInteger c = count.get( file );
            if ( c == null )
            {
                c = new AtomicInteger( 1 );
                count.put( file, c );
            }
            else
            {
                c.incrementAndGet();
            }
        }
        return lock;
    }
    
    private void remove( File file )
    {
        synchronized ( locks )
        {
            AtomicInteger c = count.get( file );
            if ( c != null && c.decrementAndGet() == 0 )
            {
                count.remove( file );
                locks.remove( file );
            }
        }
    }

    public static class DefaultLock
        implements Lock
    {
        private ReentrantReadWriteLock lock;

        private DefaultLockManager manager;

        private File file;

        private ReadLock rLock;

        private WriteLock wLock;

        private boolean write;

        private DefaultLock( DefaultLockManager manager, ReentrantReadWriteLock lock, File file, boolean write )
        {
            this.lock = lock;
            this.manager = manager;
            this.file = file;
            this.write = write;
        }

        public void lock()
        {
            lookup();
            if ( write )
            {
                wLock.lock();
            }
            else
            {
                rLock.lock();
            }
        }

        private void lookup()
        {
            manager.lookup( file );
            if ( write )
            {
                wLock = lock.writeLock();
            }
            else
            {
                rLock = lock.readLock();
            }
        }

        public void unlock()
        {
            if ( wLock != null )
            {
                wLock.unlock();
            }
            else if ( rLock != null )
            {
                rLock.unlock();
            }
            
            manager.remove( file );
        }

    }

}

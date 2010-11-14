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
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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

    private Map<File, FileLock> filelocks = new HashMap<File, FileLock>();

    private Map<File, AtomicInteger> count = new HashMap<File, AtomicInteger>();

    public Lock readLock( File file )
        throws LockingException
    {
        ReentrantReadWriteLock lock = lookup( file, false );

        return new DefaultLock( this, lock, file, false );
    }

    public Lock writeLock( File file )
        throws LockingException
    {
        ReentrantReadWriteLock lock = lookup( file, true );
        return new DefaultLock( this, lock, file, true );
    }

    private ReentrantReadWriteLock lookup( File file, boolean write )
        throws LockingException
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
                filelocks.put( file, newFileLock( file, write ) );
                AtomicInteger c = count.get( file );
                if ( c == null )
                {
                    c = new AtomicInteger( 1 );
                    count.put( file, c );
                }
                else
                {
                    c.incrementAndGet();
                    FileLock fileLock = filelocks.get( file );
                    if ( write && fileLock.isShared() )
                    {
                        // try
                        // {
                        // filelocks.remove( file ).release();
                        // }
                        // catch ( IOException e )
                        // {
                        // throw new LockingException( "Could not unlock " + file.getAbsolutePath(), e );
                        // }
                        filelocks.put( file, newFileLock( fileLock.channel(), write ) );
                    }
                }
            }
        }
        return lock;
    }

    public FileLock newFileLock( File file, boolean write )
        throws LockingException
    {
        RandomAccessFile raf;
        try
        {
            String mode;
            FileChannel channel;
            if ( write )
            {
                file.getParentFile().mkdirs();
                mode = "rw";
            }
            else
            {
                mode = "r";
            }
            raf = new RandomAccessFile( file, mode );
            channel = raf.getChannel();

            return newFileLock( channel, write );
        }
        catch ( LockingException e )
        {
            Throwable t = e;
            if ( t.getCause() instanceof IOException )
            {
                t = t.getCause();
            }

            throw new LockingException( "Could not lock " + file.getAbsolutePath(), e );
        }
        catch ( IOException e )
        {
            throw new LockingException( "Could not lock " + file.getAbsolutePath(), e );
        }
    }

    public FileLock newFileLock( FileChannel channel, boolean write )
        throws LockingException
    {
        try
        {
            // lock only file size http://bugs.sun.com/view_bug.do?bug_id=6628575
            return channel.lock( 0, Math.max( 1, channel.size() ), !write );
        }
        catch ( IOException e )
        {
            throw new LockingException( "Could not lock " + channel.toString(), e );
        }
    }

    private void remove( File file )
        throws LockingException
    {
        synchronized ( locks )
        {
            AtomicInteger c = count.get( file );
            if ( c != null && c.decrementAndGet() == 0 )
            {
                count.remove( file );
                locks.remove( file );
                try
                {
                    FileLock lock = filelocks.remove( file );
                    lock.release();
                    lock.channel().close();
                }
                catch ( IOException e )
                {
                    throw new LockingException( "Could not unlock " + file.getAbsolutePath(), e );
                }
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
            throws LockingException
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
            throws LockingException
        {
            manager.lookup( file, write );
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
            throws LockingException
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

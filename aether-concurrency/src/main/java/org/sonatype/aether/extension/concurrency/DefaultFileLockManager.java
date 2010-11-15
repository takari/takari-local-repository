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

import org.codehaus.plexus.component.annotations.Component;
import org.sonatype.aether.extension.concurrency.FileLockManager.AetherFileLock;

/**
 * A lock manager using {@link AetherFileLock}s for inter-process locking.
 * 
 * @author Benjamin Hanzelmann
 */
@Component( role = FileLockManager.class )
public class DefaultFileLockManager
    implements FileLockManager
{
    private Map<File, FileLock> filelocks = new HashMap<File, FileLock>();

    private Map<File, AtomicInteger> count = new HashMap<File, AtomicInteger>();

    public AetherFileLock readLock( File file )
    {
        return new DefaultFileLock( this, file, false );
    }

    public AetherFileLock writeLock( File file )
    {
        return new DefaultFileLock( this, file, true );
    }

    private FileLock lookup( File file, boolean write )
        throws LockingException
    {
        FileLock fileLock = null;

        synchronized ( filelocks )
        {
            if ( ( fileLock = filelocks.get( file ) ) == null )
            {
                fileLock = newFileLock( file, write );
                filelocks.put( file, fileLock );
            }
            else if ( write && fileLock.isShared() )
            {
                try
                {
                    filelocks.remove( file ).release();
                    fileLock.channel().close();
                    fileLock = fileLock;
                }
                catch ( IOException e )
                {
                    throw new LockingException( "Could not unlock " + file.getAbsolutePath(), e );
                }

                filelocks.put( file, fileLock );
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
        return fileLock;
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

    private void remove( File file )
        throws LockingException
    {
        synchronized ( filelocks )
        {
            AtomicInteger c = count.get( file );
            if ( c != null && c.decrementAndGet() == 0 )
            {
                count.remove( file );
                try
                {
                    FileLock lock = filelocks.remove( file );
                    if ( lock.channel().isOpen() )
                    {
                        lock.release();
                        lock.channel().close();
                    }
                }
                catch ( IOException e )
                {
                    throw new LockingException( "Could not unlock " + file.getAbsolutePath(), e );
                }
            }
        }
    }

    public static class DefaultFileLock
        implements AetherFileLock
    {
        private DefaultFileLockManager manager;

        private File file;

        private boolean write;

        private FileLock lock;

        private DefaultFileLock( DefaultFileLockManager manager, File file, boolean write )
        {
            this.manager = manager;
            this.file = file;
            this.write = write;
        }

        public void lock()
            throws LockingException
        {
            lookup();
        }

        private void lookup()
            throws LockingException
        {
            lock = manager.lookup( file, write );
        }

        public void unlock()
            throws LockingException
        {
            manager.remove( file );
        }

        public FileChannel channel()
        {
            return lock.channel();
        }

    }

}

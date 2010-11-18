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
import org.codehaus.plexus.component.annotations.Requirement;
import org.sonatype.aether.extension.concurrency.FileLockManager.ExternalFileLock;
import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.spi.log.NullLogger;

/**
 * A lock manager using {@link ExternalFileLock}s for inter-process locking.
 * 
 * @author Benjamin Hanzelmann
 */
@Component( role = FileLockManager.class )
public class DefaultFileLockManager
    implements FileLockManager
{
    @Requirement
    private Logger logger = NullLogger.INSTANCE;

    /**
     * Construct with given
     * 
     * @param logger
     */
    public DefaultFileLockManager( Logger logger )
    {
        super();
        setLogger( logger );
    }

    /**
     * Enable default constructor.
     */
    public DefaultFileLockManager()
    {
        super();
    }

    private Map<File, FileLock> filelocks = new HashMap<File, FileLock>();

    private Map<File, AtomicInteger> count = new HashMap<File, AtomicInteger>();

    public ExternalFileLock readLock( File file )
    {
        return new DefaultFileLock( this, file, false );
    }

    public ExternalFileLock writeLock( File file )
    {
        return new DefaultFileLock( this, file, true );
    }

    private FileLock lookup( File file, boolean write )
        throws IOException
    {
        FileLock fileLock = null;


        try
        {
            file = file.getCanonicalFile();
        }
        catch ( IOException e )
        {
            // best effort - use absolute file
            file = file.getAbsoluteFile();
        }

        synchronized ( filelocks )
        {
            if ( ( fileLock = filelocks.get( file ) ) == null )
            {
                fileLock = newFileLock( file, write );
                filelocks.put( file, fileLock );
            }
            else if ( write && fileLock.isShared() )
            {
                filelocks.remove( file ).release();
                fileLock.channel().close();

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
        throws IOException
    {
        RandomAccessFile raf;
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

        // lock only file size http://bugs.sun.com/view_bug.do?bug_id=6628575
        return channel.lock( 0, Math.max( 1, channel.size() ), !write );
    }

    private void remove( File file )
        throws IOException
    {
        synchronized ( filelocks )
        {
            AtomicInteger c = count.get( file );
            if ( c != null && c.decrementAndGet() == 0 )
            {
                count.remove( file );
                FileLock lock = filelocks.remove( file );
                if ( lock.channel().isOpen() )
                {
                    lock.release();
                    lock.channel().close();
                }
            }
        }
    }

    public static class DefaultFileLock
        implements ExternalFileLock
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
            throws IOException
        {
            lookup();
        }

        private void lookup()
            throws IOException
        {
            lock = manager.lookup( file, write );
        }

        public void unlock()
            throws IOException
        {
            manager.remove( file );
        }

        public FileChannel channel()
        {
            if ( lock != null )
            {
                return lock.channel();
            }
            return null;
        }

    }

    public void setLogger( Logger logger )
    {
        this.logger = logger;
    }

}

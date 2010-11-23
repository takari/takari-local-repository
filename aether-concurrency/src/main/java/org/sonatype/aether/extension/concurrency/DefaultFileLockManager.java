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

    private final Map<File, FileLock> filelocks = new HashMap<File, FileLock>();

    private final Map<File, AtomicInteger> count = new HashMap<File, AtomicInteger>();

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

        boolean upgrade = false;

        synchronized ( filelocks )
        {
            fileLock = filelocks.get( file );
            if ( fileLock == null || !fileLock.isValid() )
            {
                fileLock = newFileLock( file, write );
                filelocks.put( file, fileLock );
                incrementRef( file );
            }
            else if ( write && fileLock.isShared() )
            {
                upgrade = true;
            }
            else
            {
                incrementRef( file );
            }
        }

        while ( upgrade && fileLock.isShared() )
        {
            synchronized ( fileLock )
            {
                try
                {
                    fileLock.wait();
                }
                catch ( InterruptedException e )
                {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            fileLock = lookup( file, write );
        }
        return fileLock;
    }

    public void incrementRef( File file )
    {
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

    private FileLock newFileLock( File file, boolean write )
        throws IOException
    {
        RandomAccessFile raf;
        String mode;
        FileChannel channel;
        if ( write )
        {
            FileUtils.mkdirs( file.getParentFile() );
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
            raf.close();
            throw e;
        }
    }

    private void remove( File file )
        throws IOException
    {
        synchronized ( filelocks )
        {
            AtomicInteger c = count.get( file );
            if ( c == null )
            {
                logger.warn( String.format( "Unable to retrieve the lock for file %s", file.getAbsolutePath() ) );
            }
            else if ( c.decrementAndGet() == 0 )
            {
                count.remove( file );
                FileLock lock = filelocks.remove( file );
                if ( lock.channel().isOpen() )
                {
                    lock.release();
                    lock.channel().close();
                }
                synchronized ( lock )
                {
                    lock.notify();
                }
            }
        }
    }

    /**
     * A Lock class using canonical files to obtain {@link FileLock}s via
     * {@link DefaultFileLockManager#lookup(File, boolean)}.
     * 
     * @author Benjamin Hanzelmann
     */
    public static class DefaultFileLock
        implements ExternalFileLock
    {

        private final DefaultFileLockManager manager;

        private final File file;

        private final boolean write;

        private FileLock lock;

        /**
         * Creates a Lock object with the canonical (or, if that fails, the absolute) file.
         * 
         * @param manager the FileLockManager to use, may not be {@code null}.
         * @param file the file to lock. This will be changed to the canonical (or, if that fails, the absolute) file.
         * @param write denotes if the lock is for read- or write-access.
         */
        private DefaultFileLock( DefaultFileLockManager manager, File file, boolean write )
        {
            try
            {
                file = file.getCanonicalFile();
            }
            catch ( IOException e )
            {
                // best effort - use absolute file
                file = file.getAbsoluteFile();
            }

            this.manager = manager;
            this.file = file;
            this.write = write;
        }

        /**
         * Uses {@link DefaultFileLockManager#lookup(File, boolean)} to lock.
         */
        public void lock()
            throws IOException
        {
            lock = manager.lookup( file, write );
        }

        /**
         * Uses {@link DefaultFileLockManager#remove(File)} to unlock.
         */
        public void unlock()
            throws IOException
        {
            if ( lock != null )
            {
                manager.remove( file );
                lock = null;
            }
        }

        /**
         * Returns the FileChannel associated with the used {@link FileLock}.
         * 
         * @return the FileChannel associated with the used {@link FileLock}.
         */
        public FileChannel channel()
        {
            if ( lock != null )
            {
                return lock.channel();
            }
            return null;
        }

        /**
         * Exposed for testing purposes.
         * 
         * @return the FileLock in use, may be {@code null}.
         */
        protected FileLock getLock()
        {
            return lock;
        }

    }

    /**
     * Set the logger to use.
     * 
     * @param logger The logger to use. If {@code null}, disable logging.
     */
    public void setLogger( Logger logger )
    {
        this.logger = ( logger != null ) ? logger : NullLogger.INSTANCE;
    }

}

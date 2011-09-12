package org.eclipse.tesla.aether.concurrency;

/*******************************************************************************
 * Copyright (c) 2010-2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.spi.log.NullLogger;

/**
 * Manages an {@code *.aetherlock} file. <strong>Note:</strong> This class is not thread-safe and requires external
 * synchronization.
 */
class LockFile
{

    private final Logger logger;

    private final File dataFile;

    private final File lockFile;

    private FileLock fileLock;

    private RandomAccessFile raFile;

    private int refCount;

    private Thread owner;

    private final Map<Thread, AtomicInteger> clients = new HashMap<Thread, AtomicInteger>();

    public LockFile( File dataFile, Logger logger )
    {
        this.logger = ( logger != null ) ? logger : NullLogger.INSTANCE;

        this.dataFile = dataFile;

        if ( dataFile.isDirectory() )
        {
            lockFile = new File( dataFile, ".aetherlock" );
        }
        else
        {
            lockFile = new File( dataFile.getPath() + ".aetherlock" );
        }
    }

    public File getDataFile()
    {
        return dataFile;
    }

    public boolean lock( boolean write )
        throws IOException
    {
        if ( isInvalid() )
        {
            throw new IllegalStateException( "lock for " + dataFile + " has been invalidated" );
        }

        if ( isClosed() )
        {
            open( write );

            return true;
        }
        else if ( isReentrant( write ) )
        {
            incRefCount();

            return true;
        }
        else if ( isAlreadyHoldByCurrentThread() )
        {
            throw new IllegalStateException( "Cannot acquire " + ( write ? "write" : "read" ) + " lock on " + dataFile
                + " for thread " + Thread.currentThread() + " which already holds incompatible lock" );
        }

        return false;
    }

    public void unlock()
        throws IOException
    {
        if ( decRefCount() <= 0 )
        {
            close();
        }
    }

    FileLock getFileLock()
    {
        return fileLock;
    }

    public boolean isInvalid()
    {
        return refCount < 0;
    }

    public boolean isShared()
    {
        if ( fileLock == null )
        {
            throw new IllegalStateException( "lock not acquired" );
        }
        return fileLock.isShared();
    }

    private boolean isClosed()
    {
        return fileLock == null;
    }

    private boolean isReentrant( boolean write )
    {
        if ( isShared() )
        {
            return !write;
        }
        else
        {
            return Thread.currentThread() == owner;
        }
    }

    private boolean isAlreadyHoldByCurrentThread()
    {
        return clients.get( Thread.currentThread() ) != null;
    }

    private void open( boolean write )
        throws IOException
    {
        refCount = 1;

        owner = write ? Thread.currentThread() : null;

        clients.put( Thread.currentThread(), new AtomicInteger( 1 ) );

        RandomAccessFile raf = null;
        FileLock lock = null;
        boolean interrupted = false;

        try
        {
            while ( true )
            {
                raf = FileUtils.open( lockFile, "rw" );

                try
                {
                    lock = raf.getChannel().lock( 0, 1, !write );

                    if ( lock == null )
                    {
                        /*
                         * Probably related to http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6979009, lock()
                         * erroneously returns null when the thread got interrupted and the channel silently closed.
                         */
                        throw new FileLockInterruptionException();
                    }

                    break;
                }
                catch ( FileLockInterruptionException e )
                {
                    /*
                     * NOTE: We want to lock that file and this isn't negotiable, so whatever felt like interrupting our
                     * thread, try again later, we have work to get done. And since the interrupt closed the channel, we
                     * need to start with a fresh file handle.
                     */

                    interrupted |= Thread.interrupted();

                    FileUtils.close( raf, null );
                }
                catch ( IOException e )
                {
                    FileUtils.close( raf, null );

                    // EVIL: parse message of IOException to find out if it's a (probably erroneous) 'deadlock
                    // detection' (linux kernel does not account for different threads holding the locks for the
                    // same process)
                    if ( isPseudoDeadlock( e ) )
                    {
                        logger.debug( "OS detected pseudo deadlock for " + lockFile + ", retrying locking" );
                        try
                        {
                            Thread.sleep( 100 );
                        }
                        catch ( InterruptedException e1 )
                        {
                            interrupted = true;
                        }
                    }
                    else
                    {
                        delete();
                        throw e;
                    }
                }
            }
        }
        finally
        {
            /*
             * NOTE: We want to ignore the interrupt but other code might want/need to react to it, so restore the
             * interrupt flag.
             */
            if ( interrupted )
            {
                Thread.currentThread().interrupt();
            }
        }

        raFile = raf;
        fileLock = lock;
    }

    private boolean isPseudoDeadlock( IOException e )
    {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase( Locale.ENGLISH ).contains( "deadlock" );
    }

    private void close()
        throws IOException
    {
        refCount = -1;

        if ( fileLock != null )
        {
            try
            {
                if ( fileLock.isValid() )
                {
                    fileLock.release();
                }
            }
            catch ( IOException e )
            {
                logger.warn( "Failed to release lock on " + lockFile + ": " + e );
            }
            finally
            {
                fileLock = null;
            }
        }

        if ( raFile != null )
        {
            try
            {
                raFile.close();
            }
            finally
            {
                raFile = null;
                delete();
            }
        }
    }

    private void delete()
    {
        if ( lockFile != null )
        {
            if ( !lockFile.delete() && lockFile.exists() )
            {
                // NOTE: This happens naturally when some other thread locked it in the meantime
                lockFile.deleteOnExit();
            }
        }
    }

    private int incRefCount()
    {
        AtomicInteger clientRefCount = clients.get( Thread.currentThread() );
        if ( clientRefCount == null )
        {
            clients.put( Thread.currentThread(), new AtomicInteger( 1 ) );
        }
        else
        {
            clientRefCount.incrementAndGet();
        }

        return ++refCount;
    }

    private int decRefCount()
    {
        AtomicInteger clientRefCount = clients.get( Thread.currentThread() );
        if ( clientRefCount != null && clientRefCount.decrementAndGet() <= 0 )
        {
            clients.remove( Thread.currentThread() );
        }

        return --refCount;
    }

    @Override
    protected void finalize()
        throws Throwable
    {
        try
        {
            close();
        }
        finally
        {
            super.finalize();
        }
    }

}

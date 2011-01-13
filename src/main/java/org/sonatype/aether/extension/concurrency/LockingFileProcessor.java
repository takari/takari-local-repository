package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010-2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.sonatype.aether.locking.FileLockManager;
import org.sonatype.aether.locking.FileLockManager.Lock;
import org.sonatype.aether.spi.io.FileProcessor;
import org.sonatype.aether.spi.locator.Service;
import org.sonatype.aether.spi.locator.ServiceLocator;
import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.spi.log.NullLogger;

/**
 * A utility class helping with file-based operations.
 * 
 * @author Benjamin Hanzelmann
 */
@Component( role = FileProcessor.class, hint = "default" )
public class LockingFileProcessor
    implements FileProcessor, Service
{

    private static Boolean IS_SET_LAST_MODIFIED_SAFE;

    @Requirement
    private Logger logger = NullLogger.INSTANCE;

    @Requirement
    private FileLockManager fileLockManager;

    public LockingFileProcessor()
    {
        // enable default constructor
    }

    public LockingFileProcessor( FileLockManager fileLockManager )
    {
        setFileLockManager( fileLockManager );
    }

    private void close( Closeable closeable )
    {
        if ( closeable != null )
        {
            try
            {
                closeable.close();
            }
            catch ( IOException e )
            {
                logger.warn( "Failed to close file: " + e );
            }
        }
    }

    private void unlock( Lock lock )
    {
        if ( lock != null )
        {
            try
            {
                lock.unlock();
            }
            catch ( IOException e )
            {
                logger.warn( "Failed to unlock file " + lock.getFile() + ": " + e );
            }
        }
    }

    /**
     * Thread-safe variant of {@link File#mkdirs()}. Adapted from Java 6.
     */
    public boolean mkdirs( File directory )
    {
        if ( directory == null )
        {
            return false;
        }

        if ( directory.exists() )
        {
            return false;
        }
        if ( directory.mkdir() )
        {
            return true;
        }

        File canonDir = null;
        try
        {
            canonDir = directory.getCanonicalFile();
        }
        catch ( IOException e )
        {
            return false;
        }

        File parentDir = canonDir.getParentFile();
        return ( parentDir != null && ( mkdirs( parentDir ) || parentDir.exists() ) && canonDir.mkdir() );
    }

    /**
     * Copy src- to target-file. Creates the necessary directories for the target file. In case of an error, the created
     * directories will be left on the file system.
     * <p>
     * This method performs R/W-locking on the given files to provide concurrent access to files without data
     * corruption, and will honor {@link FileLock}s from an external process.
     * 
     * @param source the file to copy from, must not be {@code null}.
     * @param target the file to copy to, must not be {@code null}.
     * @param listener the listener to notify about the copy progress, may be {@code null}.
     * @return the number of copied bytes.
     * @throws IOException if an I/O error occurs.
     */
    public long copy( File source, File target, ProgressListener listener )
        throws IOException
    {
        Lock sourceLock = fileLockManager.readLock( source );
        Lock targetLock = fileLockManager.writeLock( target );

        try
        {
            mkdirs( target.getParentFile() );

            sourceLock.lock();
            targetLock.lock();

            return copy( sourceLock.getRandomAccessFile(), targetLock.getRandomAccessFile(), listener );
        }
        finally
        {
            unlock( sourceLock );
            unlock( targetLock );
        }
    }

    private long copy( RandomAccessFile rafIn, RandomAccessFile rafOut, ProgressListener listener )
        throws IOException
    {
        ByteBuffer buffer = ByteBuffer.allocate( 1024 * 32 );
        byte[] array = buffer.array();

        long total = 0;

        for ( ;; )
        {
            int bytes = rafIn.read( array );
            if ( bytes < 0 )
            {
                rafOut.setLength( rafOut.getFilePointer() );
                break;
            }

            rafOut.write( array, 0, bytes );

            total += bytes;

            if ( listener != null && bytes > 0 )
            {
                try
                {
                    buffer.rewind();
                    buffer.limit( bytes );
                    listener.progressed( buffer );
                }
                catch ( Exception e )
                {
                    logger.debug( "Failed to invoke copy progress listener", e );
                }
            }
        }

        return total;
    }

    /**
     * Write the given data to a file. UTF-8 is assumed as encoding for the data.
     * 
     * @param file The file to write to, must not be {@code null}. This file will be truncated.
     * @param data The data to write, may be {@code null}.
     * @throws IOException if an I/O error occurs.
     */
    public void write( File file, String data )
        throws IOException
    {
        Lock lock = fileLockManager.writeLock( file );

        try
        {
            mkdirs( file.getParentFile() );

            lock.lock();

            RandomAccessFile raf = lock.getRandomAccessFile();

            raf.seek( 0 );
            if ( data != null )
            {
                raf.write( data.getBytes( "UTF-8" ) );
            }

            raf.setLength( raf.getFilePointer() );
        }
        finally
        {
            unlock( lock );
        }
    }

    public void move( File source, File target )
        throws IOException
    {
        /*
         * NOTE: For graceful collaboration with concurrent readers don't attempt to delete the target file, if it
         * already exists, it's safer to just overwrite it, especially when the contents doesn't actually change.
         */

        /*
         * NOTE: We're about to remove/delete the source file so be sure to acquire an exclusive lock for the source.
         */

        Lock sourceLock = fileLockManager.writeLock( source );
        Lock targetLock = fileLockManager.writeLock( target );

        try
        {
            mkdirs( target.getParentFile() );

            sourceLock.lock();
            targetLock.lock();

            if ( !source.renameTo( target ) )
            {
                copy( sourceLock.getRandomAccessFile(), targetLock.getRandomAccessFile(), null );

                /*
                 * NOTE: On Windows and before JRE 1.7, File.setLastModified() opens the file without any sharing
                 * enabled (cf. evaluation of Sun bug 6357599). This means while setLastModified() is executing, no
                 * other thread/process can open the file "because it is being used by another process". The read
                 * accesses to files can't always be guarded by locks, take for instance class loaders reading JARs, so
                 * we must avoid calling setLastModified() completely on the affected platforms to enable safe
                 * concurrent IO. The setLastModified() call below while the file is still open is generally ineffective
                 * as the OS will update the timestamp after closing the file (at least Windows does so). But its
                 * failure allows us to detect the problematic platforms. The destination file not having the same
                 * timestamp as the source file isn't overly beauty but shouldn't actually matter in real life either.
                 */
                if ( IS_SET_LAST_MODIFIED_SAFE == null )
                {
                    IS_SET_LAST_MODIFIED_SAFE = Boolean.valueOf( target.setLastModified( source.lastModified() ) );
                    logger.debug( "Updates of file modification timestamp are safe: " + IS_SET_LAST_MODIFIED_SAFE );
                }

                close( targetLock.getRandomAccessFile() );

                if ( IS_SET_LAST_MODIFIED_SAFE.booleanValue() )
                {
                    target.setLastModified( source.lastModified() );
                }

                // NOTE: Close the file handle to enable its deletion but don't release the lock yet.
                close( sourceLock.getRandomAccessFile() );

                source.delete();
            }
        }
        finally
        {
            unlock( sourceLock );
            unlock( targetLock );
        }
    }

    /**
     * Sets the logger to use for this component.
     * 
     * @param logger The logger to use, may be {@code null} to disable logging.
     * @return This component for chaining, never {@code null}.
     */
    public LockingFileProcessor setLogger( Logger logger )
    {
        this.logger = ( logger != null ) ? logger : NullLogger.INSTANCE;
        return this;
    }

    /**
     * Sets the LockManager to use.
     * 
     * @param lockManager The LockManager to use, may not be {@code null}.
     */
    public void setFileLockManager( FileLockManager lockManager )
    {
        if ( lockManager == null )
        {
            throw new IllegalArgumentException( "LockManager may not be null." );
        }
        this.fileLockManager = lockManager;
    }

    public void initService( ServiceLocator locator )
    {
        setFileLockManager( locator.getService( FileLockManager.class ) );
        setLogger( locator.getService( Logger.class ) );
    }

}

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
                logger.warn( "Failed to unlock file: " + e );
            }
        }
    }

    /**
     * @see FileUtils#mkdirs(File)
     */
    public boolean mkdirs( File directory )
    {
        return FileUtils.mkdirs( directory );
    }

    /**
     * Copy src- to target-file. Creates the necessary directories for the target file. In case of an error, the created
     * directories will be left on the file system.
     * <p>
     * This method performs R/W-locking on the given files to provide concurrent access to files without data
     * corruption, and will honor {@link FileLock}s from an external process.
     * 
     * @param src the file to copy from, must not be {@code null}.
     * @param target the file to copy to, must not be {@code null}.
     * @param listener the listener to notify about the copy progress, may be {@code null}.
     * @return the number of copied bytes.
     * @throws IOException if an I/O error occurs.
     */
    public long copy( File src, File target, ProgressListener listener )
        throws IOException
    {
        Lock srcLock = fileLockManager.readLock( src );
        Lock targetLock = fileLockManager.writeLock( target );

        try
        {
            mkdirs( target.getParentFile() );

            srcLock.lock();
            targetLock.lock();

            ByteBuffer buffer = ByteBuffer.allocate( 1024 * 32 );
            byte[] array = buffer.array();

            long total = 0;

            for ( RandomAccessFile rafIn = srcLock.getRandomAccessFile(), rafOut = targetLock.getRandomAccessFile();; )
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
        finally
        {
            unlock( srcLock );
            unlock( targetLock );
        }
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

        if ( !source.renameTo( target ) )
        {
            copy( source, target, null );

            target.setLastModified( source.lastModified() );

            source.delete();
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

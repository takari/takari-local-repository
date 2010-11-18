package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.WritableByteChannel;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.sonatype.aether.extension.concurrency.FileLockManager.ExternalFileLock;
import org.sonatype.aether.extension.concurrency.LockManager.Lock;
import org.sonatype.aether.spi.io.FileProcessor;
import org.sonatype.aether.spi.locator.Service;
import org.sonatype.aether.spi.locator.ServiceLocator;

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
    private LockManager lockManager;

    @Requirement
    private FileLockManager fileLockManager;

    public LockingFileProcessor()
    {
        // enable default constructor
    }

    public LockingFileProcessor( LockManager lockManager, FileLockManager fileLockManager )
    {
        setLockManager( lockManager );
        setFileLockManager( fileLockManager );
    }

    private static void close( Closeable closeable )
    {
        if ( closeable != null )
        {
            try
            {
                closeable.close();
            }
            catch ( IOException e )
            {
                // too bad but who cares
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

        Lock readLock = lockManager.readLock( src );
        Lock writeLock = lockManager.writeLock( target );

        ExternalFileLock srcLock = fileLockManager.readLock( src );
        ExternalFileLock targetLock = fileLockManager.writeLock( target );

        boolean writeAcquired = false;
        boolean readAcquired = false;
        try
        {
            readLock.lock();
            readAcquired = true;
            writeLock.lock();
            writeAcquired = true;


            mkdirs( target.getParentFile() );

            srcLock.lock();
            targetLock.lock();

            FileChannel srcChannel = srcLock.channel();// in.getChannel();

            FileChannel outChannel = targetLock.channel();// out.getChannel();
            outChannel.truncate( 0 );

            WritableByteChannel realChannel = outChannel;
            if ( listener != null )
            {
                realChannel = new ProgressingChannel( outChannel, listener );
            }

            return copy( srcChannel, realChannel );
        }
        finally
        {
            release( srcLock );
            release( targetLock );

            close( srcLock.channel() );
            close( targetLock.channel() );


            if ( readAcquired )
            {
                readLock.unlock();
            }
            if ( writeAcquired )
            {
                writeLock.unlock();
            }


        }
    }

    /**
     * Copy src- to target-channel.
     * 
     * @param src the channel to copy from, must not be {@code null}.
     * @param target the channel to copy to, must not be {@code null}.
     * @return the number of copied bytes.
     * @throws IOException if an I/O error occurs.
     */
    private static long copy( FileChannel src, WritableByteChannel target )
        throws IOException
    {
        long total = 0;

        try
        {
            long size = src.size();

            // copy large files in chunks to not run into Java Bug 4643189
            // http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4643189
            // use even smaller chunks to work around bug with SMB shares
            // http://forums.sun.com/thread.jspa?threadID=439695
            long chunk = ( 64 * 1024 * 1024 ) - ( 32 * 1024 );

            do
            {
                total += src.transferTo( total, chunk, target );
            }
            while ( total < size );
        }
        finally
        {
            close( src );
            close( target );
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
        Lock writeLock = lockManager.writeLock( file );
        ExternalFileLock lock = fileLockManager.writeLock( file );

        FileChannel channel = null;
        boolean writeAcquired = false;
        try
        {
            mkdirs( file.getParentFile() );

            lock.lock();

            channel = lock.channel(); // out.getChannel();

            writeLock.lock();
            writeAcquired = true;

            channel.truncate( 0 );
            if ( data != null )
            {
                channel.write( ByteBuffer.wrap( data.getBytes( "UTF-8" ) ) );
            }
        }
        finally
        {
            release( lock );

            close( channel );

            if ( writeAcquired )
            {
                writeLock.unlock();
            }
        }
    }

    private static void release( ExternalFileLock lock )
    {
        try
        {
            lock.unlock();
        }
        catch ( IOException e )
        {
            // too bad
        }

    }

    public void move( File source, File target )
        throws IOException
    {
        target.delete();

        if ( !source.renameTo( target ) )
        {
            copy( source, target, null );

            source.delete();
        }
    }

    private static final class ProgressingChannel
        implements WritableByteChannel
    {
        private final FileChannel delegate;

        private final ProgressListener listener;

        public ProgressingChannel( FileChannel delegate, ProgressListener listener )
        {
            this.delegate = delegate;
            this.listener = listener;
        }

        public boolean isOpen()
        {
            return delegate.isOpen();
        }

        public void close()
            throws IOException
        {
            delegate.close();
        }

        public int write( ByteBuffer src )
            throws IOException
        {
            ByteBuffer eventBuffer = src.asReadOnlyBuffer();

            int count = delegate.write( src );
            listener.progressed( eventBuffer );

            return count;
        }
    }

    /**
     * Sets the LockManager to use.
     * 
     * @param lockManager The LockManager to use, may not be {@code null}.
     */
    public LockingFileProcessor setLockManager( LockManager lockManager )
    {
        if ( lockManager == null )
        {
            throw new IllegalArgumentException( "LockManager may not be null." );
        }
        this.lockManager = lockManager;
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
        setLockManager( locator.getService( LockManager.class ) );
        setFileLockManager( locator.getService( FileLockManager.class ) );
    }

}

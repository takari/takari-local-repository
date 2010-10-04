package org.sonatype.aether.extension.concurrency;

/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, 
 * and you may not use this file except in compliance with the Apache License Version 2.0. 
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the Apache License Version 2.0 is distributed on an 
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.WritableByteChannel;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
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


    public LockingFileProcessor()
    {
        // enable default constructor
    }

    public LockingFileProcessor( LockManager lockManager )
    {
        setLockManager( lockManager );
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

    private static void release( FileLock lock )
    {
        if ( lock != null )
        {
            try
            {
                lock.release();
            }
            catch ( IOException e )
            {
                // tried everything
            }
        }
    }

    /**
     * Thread-safe variant of {@link File#mkdirs()}. Adapted from Java 6. Creates the directory named by the given
     * abstract pathname, including any necessary but nonexistent parent directories. Note that if this operation fails
     * it may have succeeded in creating some of the necessary parent directories.
     * 
     * @param directory The directory to create, may be {@code null}.
     * @return {@code true} if and only if the directory was created, along with all necessary parent directories;
     *         {@code false} otherwise
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

        RandomAccessFile in = null;
        RandomAccessFile out = null;
        boolean writeAcquired = false;
        boolean readAcquired = false;
        FileLock targetLock = null;
        FileLock srcLock = null;
        try
        {
            readLock.lock();
            readAcquired = true;
            writeLock.lock();
            writeAcquired = true;

            boolean lockSrc;
            String mode;
            // FileLock is only possible if src is writable
            if ( ( lockSrc = src.canWrite() ) )
            {
                mode = "rw";
            }
            else
            {
                mode = "r";
            }
            in = new RandomAccessFile( src, mode );

            File targetDir = target.getParentFile();
            if ( targetDir != null )
            {
                mkdirs( targetDir );
            }

            FileChannel srcChannel = in.getChannel();
            if ( lockSrc )
            {
                srcLock = srcChannel.lock();
            }

            out = new RandomAccessFile( target, "rw" );
            FileChannel outChannel = out.getChannel();

            targetLock = outChannel.lock();

            out.setLength( 0 );

            WritableByteChannel realChannel = outChannel;
            if ( listener != null )
            {
                realChannel = new ProgressingChannel( outChannel, listener );
            }

            return copy( srcChannel, realChannel );
        }
        finally
        {
            close( in );
            close( out );

            release( targetLock );
            release( srcLock );

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

        RandomAccessFile out = null;
        FileChannel channel = null;
        FileLock lock = null;
        boolean writeAcquired = false;
        try
        {
            out = new RandomAccessFile( file, "rw" );
            channel = out.getChannel();

            writeLock.lock();
            writeAcquired = true;
            out.setLength( 0 );
            lock = channel.lock();
            if ( data == null )
            {
                channel.truncate( 0 );
            }
            else
            {
                out.write( data.getBytes( "UTF-8" ) );
            }
        }
        finally
        {
            close( channel );
            close( out );

            release( lock );

            if ( writeAcquired )
            {
                writeLock.unlock();
            }
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
    public void setLockManager( LockManager lockManager )
    {
        if ( lockManager == null )
        {
            throw new IllegalArgumentException( "LockManager may not be null." );
        }
        this.lockManager = lockManager;
    }

    public void initService( ServiceLocator locator )
    {
        setLockManager( locator.getService( LockManager.class ) );
    }

}

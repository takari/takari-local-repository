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

import org.sonatype.aether.locking.FileLockManager;
import org.sonatype.aether.locking.FileLockManager.Lock;
import org.sonatype.aether.spi.log.Logger;

/**
 * @author Benjamin Hanzelmann
 */
class FileUtils
{

    /**
     * Thread-safe variant of {@link File#mkdirs()}. Adapted from Java 6. Creates the directory named by the given
     * abstract pathname, including any necessary but nonexistent parent directories. Note that if this operation fails
     * it may have succeeded in creating some of the necessary parent directories.
     * 
     * @param directory The directory to create, may be {@code null}.
     * @return {@code true} if and only if the directory was created, along with all necessary parent directories;
     *         {@code false} otherwise
     */
    public static boolean mkdirs( File directory )
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

    public static void close( Closeable closeable, Logger logger )
    {
        if ( closeable != null )
        {
            try
            {
                closeable.close();
            }
            catch ( IOException e )
            {
                if ( logger != null )
                {
                    logger.warn( "Failed to close file: " + e );
                }
            }
        }
    }

    /**
     * Locking variant of {@link File#setLastModified(long)}.
     * 
     * @param file the file that should have its last modified time set, must not be {@code null}.
     * @param time the time to set.
     * @param lockManager the lock manager to use, must not be {@link NullPointerException}.
     * @return {@code true} if and only if {@link File#setLastModified(long)} succeeded; false otherwise.
     * @throws IOException if {@link Lock#lock()} or {@link Lock#unlock()} throw an IOException.
     */
    public static boolean setLastModified( File file, long time, FileLockManager lockManager )
        throws IOException
    {
        Lock lock = lockManager.writeLock( file );
        IOException exception = null;
        try
        {
            lock.lock();
            return file.setLastModified( time );
        }
        catch ( IOException e )
        {
            exception = e;
            throw e;
        }
        finally
        {
            try
            {
                lock.unlock();
            }
            catch ( IOException e )
            {
                if ( exception != null )
                {
                    throw e;
                }
            }

        }

    }

}

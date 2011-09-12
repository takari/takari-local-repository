package org.sonatype.aether.locking;

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

    public static RandomAccessFile open( File file, String mode )
        throws IOException
    {
        boolean interrupted = false;

        try
        {
            mkdirs( file.getParentFile() );

            return new RandomAccessFile( file, mode );
        }
        catch ( IOException e )
        {
            /*
             * NOTE: I've seen failures (on Windows) when opening the file which I can't really explain
             * ("access denied", "locked"). Assuming those are bad interactions with OS-level processes (e.g. indexing,
             * anti-virus), let's just retry before giving up due to a potentially spurious problem.
             */
            for ( int i = 3; i >= 0; i-- )
            {
                try
                {
                    Thread.sleep( 10 );
                }
                catch ( InterruptedException e1 )
                {
                    interrupted = true;
                }
                try
                {
                    return new RandomAccessFile( file, mode );
                }
                catch ( IOException ie )
                {
                    // ignored, we eventually rethrow the original error
                }
            }

            throw e;
        }
        finally
        {
            if ( interrupted )
            {
                Thread.currentThread().interrupt();
            }
        }
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

}

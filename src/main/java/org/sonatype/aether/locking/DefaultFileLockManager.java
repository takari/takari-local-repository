package org.sonatype.aether.locking;

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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.sonatype.aether.spi.locator.Service;
import org.sonatype.aether.spi.locator.ServiceLocator;
import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.spi.log.NullLogger;

/**
 * Offers advisory file locking independently of the platform. With regard to concurrent readers that don't use any file
 * locking (i.e. 3rd party code accessing files), mandatory locking (as seen on Windows) must be avoided as this would
 * immediately kill the unaware readers. To emulate advisory locking, this implementation uses a dedicated lock file
 * (*.aetherlock) next to the actual file. The inter-process file locking is performed on this lock file, thereby
 * keeping the data file free from locking.
 * 
 * @author Benjamin Bentmann
 */
@Component( role = FileLockManager.class )
public class DefaultFileLockManager
    implements FileLockManager, Service
{

    @Requirement
    private Logger logger = NullLogger.INSTANCE;

    private static final ConcurrentMap<File, LockFile> lockFiles = new ConcurrentHashMap<File, LockFile>( 64 );

    public DefaultFileLockManager()
    {
        // enables no-arg constructor
    }

    public DefaultFileLockManager( Logger logger )
    {
        setLogger( logger );
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

    public void initService( ServiceLocator locator )
    {
        setLogger( locator.getService( Logger.class ) );
    }

    public Lock readLock( File target )
    {
        return new IndirectFileLock( normalize( target ), false );
    }

    public Lock writeLock( File target )
    {
        return new IndirectFileLock( normalize( target ), true );
    }

    private File normalize( File file )
    {
        try
        {
            return file.getCanonicalFile();
        }
        catch ( IOException e )
        {
            logger.warn( "Failed to normalize pathname for lock on " + file + ": " + e );
            return file.getAbsoluteFile();
        }
    }

    LockFile lock( File file, boolean write )
        throws IOException
    {
        boolean interrupted = false;

        try
        {
            while ( true )
            {
                LockFile lockFile = lockFiles.get( file );

                if ( lockFile == null )
                {
                    lockFile = new LockFile( file, logger );

                    LockFile existing = lockFiles.putIfAbsent( file, lockFile );
                    if ( existing != null )
                    {
                        lockFile = existing;
                    }
                }

                synchronized ( lockFile )
                {
                    if ( lockFile.isInvalid() )
                    {
                        continue;
                    }
                    else if ( lockFile.lock( write ) )
                    {
                        return lockFile;
                    }

                    try
                    {
                        lockFile.wait();
                    }
                    catch ( InterruptedException e )
                    {
                        interrupted = true;
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
    }

    void unlock( LockFile lockFile )
        throws IOException
    {
        synchronized ( lockFile )
        {
            try
            {
                lockFile.unlock();
            }
            finally
            {
                if ( lockFile.isInvalid() )
                {
                    lockFiles.remove( lockFile.getDataFile(), lockFile );
                    lockFile.notifyAll();
                }
            }
        }
    }

    class IndirectFileLock
        implements Lock
    {

        private final File file;

        private final boolean write;

        private final Throwable stackTrace;

        private RandomAccessFile raFile;

        private LockFile lockFile;

        private int nesting;

        public IndirectFileLock( File file, boolean write )
        {
            this.file = file;
            this.write = write;
            this.stackTrace = new IllegalStateException();
        }

        public synchronized void lock()
            throws IOException
        {
            if ( lockFile == null )
            {
                open();
                nesting = 1;
            }
            else
            {
                nesting++;
            }
        }

        private void open()
            throws IOException
        {
            lockFile = DefaultFileLockManager.this.lock( file, write );
        }

        public synchronized void unlock()
            throws IOException
        {
            nesting--;
            if ( nesting <= 0 )
            {
                close();
            }
        }

        private void close()
            throws IOException
        {
            try
            {
                if ( raFile != null )
                {
                    try
                    {
                        raFile.close();
                    }
                    finally
                    {
                        raFile = null;
                    }
                }
            }
            finally
            {
                if ( lockFile != null )
                {
                    try
                    {
                        DefaultFileLockManager.this.unlock( lockFile );
                    }
                    catch ( IOException e )
                    {
                        logger.warn( "Failed to release lock for " + file + ": " + e );
                    }
                    finally
                    {
                        lockFile = null;
                    }
                }
            }
        }

        public RandomAccessFile getRandomAccessFile()
            throws IOException
        {
            if ( raFile == null && lockFile != null && lockFile.getFileLock().isValid() )
            {
                raFile = FileUtils.open( file, write ? "rw" : "r" );
            }
            return raFile;
        }

        public boolean isShared()
        {
            if ( lockFile == null )
            {
                throw new IllegalStateException( "lock not acquired" );
            }
            return lockFile.isShared();
        }

        public FileLock getLock()
        {
            if ( lockFile == null )
            {
                return null;
            }
            return lockFile.getFileLock();
        }

        @Override
        protected void finalize()
            throws Throwable
        {
            try
            {
                if ( lockFile != null )
                {
                    logger.warn( "Lock on file " + file + " has not been properly released", stackTrace );
                }
                close();
            }
            finally
            {
                super.finalize();
            }
        }

        public File getFile()
        {
            return file;
        }
    }

}

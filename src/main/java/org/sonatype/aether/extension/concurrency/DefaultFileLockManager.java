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
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.util.HashMap;
import java.util.Map;

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

    private final Map<File, LockFile> lockFiles = new HashMap<File, LockFile>( 64 );

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

    public ExternalFileLock readLock( File target )
    {
        return new IndirectFileLock( normalize( target ), false );
    }

    public ExternalFileLock writeLock( File target )
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
                LockFile lockFile;

                synchronized ( lockFiles )
                {
                    lockFile = lockFiles.get( file );

                    if ( lockFile == null )
                    {
                        lockFile = new LockFile( file, write );

                        lockFiles.put( file, lockFile );

                        return lockFile;
                    }
                    else if ( lockFile.isReentrant( write ) )
                    {
                        lockFile.incRefCount();

                        return lockFile;
                    }
                }

                synchronized ( lockFile )
                {
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

    void unlock( File file )
        throws IOException
    {
        LockFile closedFile = null;

        try
        {
            synchronized ( lockFiles )
            {
                LockFile lockFile = lockFiles.get( file );

                if ( lockFile != null )
                {
                    if ( lockFile.decRefCount() <= 0 )
                    {
                        lockFiles.remove( file );

                        closedFile = lockFile;
                        lockFile.close();
                    }
                }
                else
                {
                    logger.debug( "Unbalanced unlock on " + file );
                }
            }
        }
        finally
        {
            if ( closedFile != null )
            {
                synchronized ( closedFile )
                {
                    closedFile.notifyAll();
                }
            }
        }
    }

    RandomAccessFile open( File file, String mode )
        throws IOException
    {
        boolean interrupted = false;

        try
        {
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

    class IndirectFileLock
        implements ExternalFileLock
    {

        private final File file;

        private final boolean write;

        private RandomAccessFile raFile;

        private LockFile lockFile;

        private int nesting;

        public IndirectFileLock( File file, boolean write )
        {
            this.file = file;
            this.write = write;
        }

        public synchronized void lock()
            throws IOException
        {
            if ( raFile == null )
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

            raFile = DefaultFileLockManager.this.open( file, write ? "rw" : "r" );
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
                    RandomAccessFile tmp = raFile;
                    raFile = null;
                    tmp.close();
                }
            }
            finally
            {
                if ( lockFile != null )
                {
                    lockFile = null;

                    try
                    {
                        DefaultFileLockManager.this.unlock( file );
                    }
                    catch ( IOException e )
                    {
                        logger.warn( "Failed to release lock for " + file + ": " + e );
                    }
                }
            }
        }

        public RandomAccessFile getRandomAccessFile()
        {
            return raFile;
        }

        public boolean isShared()
        {
            if ( lockFile == null )
            {
                throw new IllegalStateException( "lock not acquired" );
            }
            return lockFile.fileLock.isShared();
        }

        public FileLock getLock()
        {
            if ( lockFile == null )
            {
                return null;
            }
            return lockFile.fileLock;
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

    class LockFile
    {

        final File lockFile;

        final FileLock fileLock;

        final RandomAccessFile raFile;

        private final Thread owner;

        private int refCount;

        LockFile( File dataFile, boolean write )
            throws IOException
        {
            refCount = 1;

            owner = write ? Thread.currentThread() : null;

            if ( dataFile.isDirectory() )
            {
                lockFile = new File( dataFile, ".aetherlock" );
            }
            else
            {
                lockFile = new File( dataFile.getPath() + ".aetherlock" );
            }

            FileUtils.mkdirs( lockFile.getParentFile() );

            RandomAccessFile raf = null;
            FileLock lock = null;
            boolean interrupted = false;

            try
            {
                while ( true )
                {
                    raf = DefaultFileLockManager.this.open( lockFile, "rw" );

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
                         * NOTE: We want to lock that file and this isn't negotiable, so whatever felt like interrupting
                         * our thread, try again later, we have work to get done. And since the interrupt closed the
                         * channel, we need to start with a fresh file handle.
                         */

                        interrupted |= Thread.interrupted();

                        FileUtils.close( raf, null );
                    }
                    catch ( IOException e )
                    {
                        FileUtils.close( raf, null );
                        delete();
                        throw e;
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

            fileLock = lock;
            raFile = raf;
        }

        void close()
            throws IOException
        {
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
            }

            if ( raFile != null )
            {
                try
                {
                    raFile.close();
                }
                finally
                {
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
                    lockFile.deleteOnExit();
                }
            }
        }

        boolean isReentrant( boolean write )
        {
            if ( !write && fileLock.isShared() )
            {
                return true;
            }
            else if ( write && !fileLock.isShared() && Thread.currentThread() == owner )
            {
                return true;
            }
            return false;
        }

        int incRefCount()
        {
            return ++refCount;
        }

        int decRefCount()
        {
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

}

package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.SyncContext;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.locking.FileLockManager;
import org.sonatype.aether.locking.FileLockManager.Lock;
import org.sonatype.aether.metadata.Metadata;
import org.sonatype.aether.repository.LocalRepository;
import org.sonatype.aether.repository.LocalRepositoryManager;
import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.spi.log.NullLogger;

/**
 * 
 */
class LockingSyncContext
    implements SyncContext
{

    private static final char SEPARATOR = '~';

    private final Logger logger;

    private final FileLockManager fileLockManager;

    private final LocalRepositoryManager localRepoMan;

    private final boolean shared;

    private final Map<String, Lock> locks = new LinkedHashMap<String, Lock>();

    public LockingSyncContext( boolean shared, RepositorySystemSession session, FileLockManager fileLockManager,
                               Logger logger )
    {
        this.shared = shared;
        this.logger = ( logger != null ) ? logger : NullLogger.INSTANCE;
        this.fileLockManager = fileLockManager;
        this.localRepoMan = session.getLocalRepositoryManager();
    }

    public void acquire( Collection<? extends Artifact> artifacts, Collection<? extends Metadata> metadatas )
    {
        Collection<String> paths = new TreeSet<String>();
        addArtifactPaths( paths, artifacts );
        addMetadataPaths( paths, metadatas );

        File basedir = getLockBasedir();

        for ( String path : paths )
        {
            File file = new File( basedir, path );

            Lock lock = locks.get( path );
            if ( lock == null )
            {
                if ( shared )
                {
                    lock = fileLockManager.readLock( file );
                }
                else
                {
                    lock = fileLockManager.writeLock( file );
                }

                locks.put( path, lock );

                try
                {
                    lock.lock();
                }
                catch ( IOException e )
                {
                    logger.warn( "Failed to lock file " + lock.getFile() + ": " + e );
                }
            }
        }
    }

    private File getLockBasedir()
    {
        LocalRepository localRepo = localRepoMan.getRepository();

        File basedir = new File( localRepo.getBasedir(), ".locks" );

        return basedir;
    }

    private void addArtifactPaths( Collection<String> paths, Collection<? extends Artifact> artifacts )
    {
        if ( artifacts != null )
        {
            for ( Artifact artifact : artifacts )
            {
                String path = getPath( artifact );
                paths.add( path );
            }
        }
    }

    private String getPath( Artifact artifact )
    {
        // NOTE: Don't use LRM.getPath*() as those paths could be different across processes, e.g. due to staging LRMs.

        StringBuilder path = new StringBuilder( 128 );

        path.append( artifact.getGroupId() ).append( SEPARATOR );
        path.append( artifact.getArtifactId() ).append( SEPARATOR );
        path.append( artifact.getBaseVersion() );

        return path.toString();
    }

    private void addMetadataPaths( Collection<String> paths, Collection<? extends Metadata> metadatas )
    {
        if ( metadatas != null )
        {
            for ( Metadata metadata : metadatas )
            {
                String path = getPath( metadata );
                paths.add( path );
            }
        }
    }

    private String getPath( Metadata metadata )
    {
        // NOTE: Don't use LRM.getPath*() as those paths could be different across processes, e.g. due to staging.

        StringBuilder path = new StringBuilder( 128 );

        if ( metadata.getGroupId().length() > 0 )
        {
            path.append( metadata.getGroupId() );

            if ( metadata.getArtifactId().length() > 0 )
            {
                path.append( SEPARATOR ).append( metadata.getArtifactId() );

                if ( metadata.getVersion().length() > 0 )
                {
                    path.append( SEPARATOR ).append( metadata.getVersion() );
                }
            }
        }

        return path.toString();
    }

    public void release()
    {
        for ( Lock lock : locks.values() )
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
        locks.clear();
    }

}

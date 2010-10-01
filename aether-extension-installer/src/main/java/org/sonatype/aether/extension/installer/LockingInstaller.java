package org.sonatype.aether.extension.installer;

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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.sonatype.aether.RepositoryException;
import org.sonatype.aether.RepositoryListener;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.extension.installer.LockManager.Lock;
import org.sonatype.aether.impl.Installer;
import org.sonatype.aether.impl.LocalRepositoryMaintainer;
import org.sonatype.aether.impl.MetadataGenerator;
import org.sonatype.aether.impl.MetadataGeneratorFactory;
import org.sonatype.aether.installation.InstallRequest;
import org.sonatype.aether.installation.InstallResult;
import org.sonatype.aether.installation.InstallationException;
import org.sonatype.aether.metadata.MergeableMetadata;
import org.sonatype.aether.metadata.Metadata;
import org.sonatype.aether.repository.LocalArtifactRegistration;
import org.sonatype.aether.repository.LocalRepositoryManager;
import org.sonatype.aether.spi.io.FileProcessor;
import org.sonatype.aether.spi.locator.Service;
import org.sonatype.aether.spi.locator.ServiceLocator;
import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.spi.log.NullLogger;
import org.sonatype.aether.util.listener.DefaultRepositoryEvent;

/**
 * This installer provides safe concurrent access to the local repository. It uses a {@link LockManager} to obtain
 * exclusive access to the install targets, serializing install requests with the same artifacts or metadata. It also
 * takes care to prevent corruption of files in the local repository by staging the changes and keep the critical phase
 * as short as possible.
 * 
 * @author Benjamin Bentmann
 * @author Benjamin Hanzelmann
 */
@Component( role = Installer.class, hint = "default" )
public class LockingInstaller
    implements Installer, Service
{

    @Requirement
    private Logger logger = NullLogger.INSTANCE;

    @Requirement
    private FileProcessor fileProcessor;

    @Requirement( role = LocalRepositoryMaintainer.class )
    private List<LocalRepositoryMaintainer> localRepositoryMaintainers = new ArrayList<LocalRepositoryMaintainer>();

    @Requirement( role = MetadataGeneratorFactory.class )
    private List<MetadataGeneratorFactory> metadataFactories = new ArrayList<MetadataGeneratorFactory>();

    @Requirement
    private LockManager lockManager;

    private Map<InstallRequest, List<Lock>> locked =
        Collections.synchronizedMap( new HashMap<InstallRequest, List<Lock>>() );

    private Map<InstallRequest, Map<String, FileLock>> filelocked =
        Collections.synchronizedMap( new HashMap<InstallRequest, Map<String, FileLock>>() );

    private static final Comparator<MetadataGeneratorFactory> COMPARATOR = new Comparator<MetadataGeneratorFactory>()
    {

        public int compare( MetadataGeneratorFactory o1, MetadataGeneratorFactory o2 )
        {
            return o2.getPriority() - o1.getPriority();
        }

    };

    private Map<Artifact, LocalArtifactRegistration> registrations = new HashMap<Artifact, LocalArtifactRegistration>();

    public LockingInstaller()
    {
        // enables default constructor
    }

    public LockingInstaller( Logger logger, FileProcessor fileProcessor,
                             List<MetadataGeneratorFactory> metadataFactories,
                             List<LocalRepositoryMaintainer> localRepositoryMaintainers, LockManager lockManager )
    {
        setLogger( logger );
        setFileProcessor( fileProcessor );
        setLockManager( lockManager );
        setMetadataFactories( metadataFactories );
        setLocalRepositoryMaintainers( localRepositoryMaintainers );
    }

    public void initService( ServiceLocator locator )
    {
        setLogger( locator.getService( Logger.class ) );
        setFileProcessor( locator.getService( FileProcessor.class ) );
        setLockManager( locator.getService( LockManager.class ) );
        setLocalRepositoryMaintainers( locator.getServices( LocalRepositoryMaintainer.class ) );
        setMetadataFactories( locator.getServices( MetadataGeneratorFactory.class ) );
    }

    public LockingInstaller setLogger( Logger logger )
    {
        this.logger = ( logger != null ) ? logger : NullLogger.INSTANCE;
        return this;
    }

    public LockingInstaller setFileProcessor( FileProcessor fileProcessor )
    {
        if ( fileProcessor == null )
        {
            throw new IllegalArgumentException( "file processor has not been specified" );
        }
        this.fileProcessor = fileProcessor;
        return this;
    }

    public LockingInstaller addLocalRepositoryMaintainer( LocalRepositoryMaintainer maintainer )
    {
        if ( maintainer == null )
        {
            throw new IllegalArgumentException( "local repository maintainer has not been specified" );
        }
        this.localRepositoryMaintainers.add( maintainer );
        return this;
    }

    public LockingInstaller setLocalRepositoryMaintainers( List<LocalRepositoryMaintainer> maintainers )
    {
        if ( maintainers == null )
        {
            this.localRepositoryMaintainers = new ArrayList<LocalRepositoryMaintainer>();
        }
        else
        {
            this.localRepositoryMaintainers = maintainers;
        }
        return this;
    }

    public LockingInstaller setLockManager( LockManager lockManager )
    {
        this.lockManager = lockManager;
        return this;
    }

    public LockingInstaller addMetadataGeneratorFactory( MetadataGeneratorFactory factory )
    {
        if ( factory == null )
        {
            throw new IllegalArgumentException( "metadata generator factory has not been specified" );
        }
        metadataFactories.add( factory );
        return this;
    }

    public LockingInstaller setMetadataFactories( List<MetadataGeneratorFactory> metadataFactories )
    {
        if ( metadataFactories == null )
        {
            this.metadataFactories = new ArrayList<MetadataGeneratorFactory>();
        }
        else
        {
            this.metadataFactories = metadataFactories;
        }
        return this;
    }

    public InstallResult install( RepositorySystemSession session, InstallRequest request )
        throws InstallationException
    {
        lockAll( session, request );

        InstallResult result = new InstallResult( request );

        List<MetadataGenerator> generators = getMetadataGenerators( session, request );

        List<Artifact> artifacts = new ArrayList<Artifact>( request.getArtifacts() );

        IdentityHashMap<Metadata, Object> processedMetadata = new IdentityHashMap<Metadata, Object>();

        try
        {
            try
            {
                for ( MetadataGenerator generator : generators )
                {
                    for ( Metadata metadata : generator.prepare( artifacts ) )
                    {
                        lock( session, request, metadata );
                        install( session, metadata );
                        processedMetadata.put( metadata, null );
                        result.addMetadata( metadata );
                    }
                }

                for ( int i = 0; i < artifacts.size(); i++ )
                {
                    Artifact artifact = artifacts.get( i );

                    for ( MetadataGenerator generator : generators )
                    {
                        artifact = generator.transformArtifact( artifact );
                    }

                    artifacts.set( i, artifact );

                    install( session, artifact );
                    result.addArtifact( artifact );
                }

                for ( MetadataGenerator generator : generators )
                {
                    for ( Metadata metadata : generator.finish( artifacts ) )
                    {
                        lock( session, request, metadata );
                        install( session, metadata );
                        processedMetadata.put( metadata, null );
                        result.addMetadata( metadata );
                    }
                }

                for ( Metadata metadata : request.getMetadata() )
                {
                    if ( !processedMetadata.containsKey( metadata ) )
                    {
                        install( session, metadata );
                        result.addMetadata( metadata );
                    }
                }

            }
            catch ( InstallationException e )
            {
                LocalRepositoryManager lrm = session.getLocalRepositoryManager();
                for ( Artifact artifact : request.getArtifacts() )
                {
                    File dstFile = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalArtifact( artifact ) );
                    artifactInstalled( session, artifact, dstFile, e );
                }
                for ( Metadata metadata : request.getMetadata() )
                {
                    File dstFile = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalMetadata( metadata ) );
                    metadataInstalled( session, metadata, dstFile, e );
                }
                throw e;
            }
            promote( session, result );
            return result;
        }
        finally
        {
            cleanup( session, result );
            unlock( request );
        }
    }

    private void install( RepositorySystemSession session, Artifact artifact )
        throws InstallationException
    {
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();

        File srcFile = artifact.getFile();

        File dstFile = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalArtifact( artifact ) );

        File stagedFile = stage( dstFile );

        artifactInstalling( session, artifact, dstFile );

        try
        {
            boolean copy =
                "pom".equals( artifact.getExtension() ) || srcFile.lastModified() != dstFile.lastModified()
                    || srcFile.length() != dstFile.length();
            logger.debug( String.format( "compare:\n%s\n%s\n%s\n%s", srcFile.lastModified(), dstFile.lastModified(),
                                         srcFile.length(), dstFile.length() ) );

            if ( copy )
            {
                logger.debug( String.format( "install: %s -> %s", srcFile, stagedFile ) );
                fileProcessor.copy( srcFile, stagedFile, null );
                stagedFile.setLastModified( srcFile.lastModified() );
            }
            else
            {
                logger.debug( "Skipped re-installing " + srcFile + " to " + dstFile + ", seems unchanged" );
            }

            registrations.put( artifact, new LocalArtifactRegistration( artifact ) );
        }
        catch ( Exception e )
        {
            throw new InstallationException( "Failed to install artifact " + artifact + ": " + e.getMessage(), e );
        }
    }

    private void install( RepositorySystemSession session, Metadata metadata )
        throws InstallationException
    {

        LocalRepositoryManager lrm = session.getLocalRepositoryManager();

        File dstFile = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalMetadata( metadata ) );

        metadataInstalling( session, metadata, dstFile );

        try
        {
            if ( metadata instanceof MergeableMetadata )
            {
                logger.debug( String.format( "merge: %s -> %s", dstFile, stage( dstFile ) ) );
                ( (MergeableMetadata) metadata ).merge( dstFile, stage( dstFile ) );
            }
            else
            {
                logger.debug( String.format( "copy: %s -> %s", metadata.getFile(), stage( dstFile ) ) );
                fileProcessor.copy( metadata.getFile(), stage( dstFile ), null );
            }
        }
        catch ( Exception e )
        {
            throw new InstallationException( "Failed to install metadata " + metadata + ": " + e.getMessage(), e );
        }
    }

    private void promote( RepositorySystemSession session, InstallResult result )
        throws InstallationException
    {
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();

        try
        {
            for ( Artifact a : result.getArtifacts() )
            {
                logger.debug( "promoting artifact: " + a );
                File dstFile = null;
                Exception exception = null;
                try
                {
                    dstFile = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalArtifact( a ) );
                    File transFile = stage( dstFile );

                    sanity( dstFile, transFile );
                    mark( dstFile );

                    // no temporary -> unchanged src file, no error
                    logger.debug( String.format( "move: %s -> %s", transFile, dstFile ) );
                    if ( transFile.exists() && !transFile.renameTo( dstFile ) )
                    {
                        logger.debug( String.format( "copy: %s -> %s", transFile, dstFile ) );
                        fileProcessor.copy( transFile, dstFile, null );
                        dstFile.setLastModified( transFile.lastModified() );
                    }
                    logger.debug( "tstamp: " + dstFile.lastModified() );

                    lrm.add( session, registrations.get( a ) );

                    if ( !localRepositoryMaintainers.isEmpty() )
                    {
                        DefaultLocalRepositoryEvent event = new DefaultLocalRepositoryEvent( session, a, dstFile );
                        for ( LocalRepositoryMaintainer maintainer : localRepositoryMaintainers )
                        {
                            maintainer.artifactInstalled( event );
                        }
                    }
                }
                catch ( Exception e )
                {
                    exception = e;
                    throw e;
                }
                finally
                {
                    artifactInstalled( session, a, dstFile, exception );
                }
            }
            for ( Metadata m : result.getMetadata() )
            {
                logger.debug( "promoting metadata: " + m );
                File dstFile = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalMetadata( m ) );
                Exception exception = null;
                try
                {
                    File transFile = stage( dstFile );

                    sanity( dstFile, transFile );
                    mark( dstFile );

                    logger.debug( String.format( "move: %s -> %s", transFile, dstFile ) );
                    if ( !transFile.renameTo( dstFile ) )
                    {
                        logger.debug( String.format( "copy: %s -> %s", transFile, dstFile ) );
                        fileProcessor.copy( transFile, dstFile, null );
                    }
                }
                catch ( Exception e )
                {
                    exception = e;
                    throw e;
                }
                finally
                {
                    metadataInstalled( session, m, dstFile, exception );
                }
            }
        }
        catch ( Exception e )
        {
            try
            {
                rollback( session, result );
            }
            catch ( Exception next )
            {
                throw new InstallationException( "Rollback failed for " + result.toString() + ": " + e.getMessage(), e );
            }
            throw new InstallationException( "Installation failed for " + result.toString() + ": " + e.getMessage(), e );
        }
    }

    private void rollback( RepositorySystemSession session, InstallResult result )
        throws IOException, RepositoryException
    {
        logger.debug( "Starting rollback for " + result );
        boolean failures = false;

        LocalRepositoryManager lrm = session.getLocalRepositoryManager();
        File basedir = lrm.getRepository().getBasedir();

        for ( Artifact a : result.getArtifacts() )
        {
            File dstFile = new File( basedir, lrm.getPathForLocalArtifact( a ) );

            if ( backupFile( dstFile ).exists() && !backupFile( dstFile ).renameTo( dstFile ) )
            {
                failures = true;
            }
            if ( deleteMarker( dstFile ).exists() && dstFile.exists() && !dstFile.delete() )
            {
                failures = true;
            }
        }
        for ( Metadata m : result.getMetadata() )
        {
            File dstFile = new File( basedir, lrm.getPathForLocalMetadata( m ) );

            if ( backupFile( dstFile ).exists() && !backupFile( dstFile ).renameTo( dstFile ) )
            {
                failures = true;
            }
            if ( deleteMarker( dstFile ).exists() && dstFile.exists() && !dstFile.delete() )
            {
                failures = true;
            }
        }
        if ( failures )
        {
            throw new IOException( "Installation failed: " + result );
        }
    }

    private void cleanup( RepositorySystemSession session, InstallResult result )
    {
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();
        File basedir = lrm.getRepository().getBasedir();

        for ( Artifact a : result.getArtifacts() )
        {
            File dstFile = new File( basedir, lrm.getPathForLocalArtifact( a ) );

            backupFile( dstFile ).delete();
            deleteMarker( dstFile ).delete();
            stage( dstFile ).delete();
        }
        for ( Metadata m : result.getMetadata() )
        {
            File dstFile = new File( basedir, lrm.getPathForLocalMetadata( m ) );

            backupFile( dstFile ).delete();
            deleteMarker( dstFile ).delete();
            stage( dstFile ).delete();
        }
    }

    private void sanity( File realFile, File transFile )
        throws InstallationException
    {
        if ( realFile.isDirectory() )
        {
            throw new InstallationException( "Install path is a directory " + realFile.getAbsolutePath() );
        }
        if ( transFile.isDirectory() )
        {
            throw new InstallationException( "Temporary path is a directory " + transFile.getAbsolutePath() );
        }
    }

    private void mark( File file )
        throws IOException
    {
        if ( file.exists() && stage( file ).exists() )
        {
            boolean renamed = file.renameTo( backupFile( file ) );

            if ( !renamed )
            {
                throw new IOException( "could not backup " + file.getAbsolutePath() );
            }
        }
        else
        {
            deleteMarker( file ).createNewFile();
        }
    }

    private File stage( File dstFile )
    {
        return new File( dstFile.getAbsolutePath() + ".tmp" );
    }

    private File backupFile( File transFile )
    {
        return new File( transFile.getAbsolutePath() + ".backup" );
    }

    private File deleteMarker( File transFile )
    {
        return new File( transFile.getAbsolutePath() + ".delete" );
    }

    private List<MetadataGenerator> getMetadataGenerators( RepositorySystemSession session, InstallRequest request )
    {
        List<MetadataGeneratorFactory> factories = new ArrayList<MetadataGeneratorFactory>( this.metadataFactories );
        Collections.sort( factories, COMPARATOR );

        List<MetadataGenerator> generators = new ArrayList<MetadataGenerator>();

        for ( MetadataGeneratorFactory factory : factories )
        {
            MetadataGenerator generator = factory.newInstance( session, request );
            if ( generator != null )
            {
                generators.add( generator );
            }
        }

        return generators;
    }

    private void artifactInstalling( RepositorySystemSession session, Artifact artifact, File dstFile )
    {
        RepositoryListener listener = session.getRepositoryListener();
        if ( listener != null )
        {
            DefaultRepositoryEvent event = new DefaultRepositoryEvent( session, artifact );
            event.setRepository( session.getLocalRepositoryManager().getRepository() );
            event.setFile( dstFile );
            listener.artifactInstalling( event );
        }
    }

    private void artifactInstalled( RepositorySystemSession session, Artifact artifact, File dstFile,
                                    Exception exception )
    {
        RepositoryListener listener = session.getRepositoryListener();
        if ( listener != null )
        {
            DefaultRepositoryEvent event = new DefaultRepositoryEvent( session, artifact );
            event.setRepository( session.getLocalRepositoryManager().getRepository() );
            event.setFile( dstFile );
            event.setException( exception );
            listener.artifactInstalled( event );
        }
    }

    private void metadataInstalling( RepositorySystemSession session, Metadata metadata, File dstFile )
    {
        RepositoryListener listener = session.getRepositoryListener();
        if ( listener != null )
        {
            DefaultRepositoryEvent event = new DefaultRepositoryEvent( session, metadata );
            event.setRepository( session.getLocalRepositoryManager().getRepository() );
            event.setFile( dstFile );
            listener.metadataInstalling( event );
        }
    }

    private void metadataInstalled( RepositorySystemSession session, Metadata metadata, File dstFile,
                                    Exception exception )
    {
        RepositoryListener listener = session.getRepositoryListener();
        if ( listener != null )
        {
            DefaultRepositoryEvent event = new DefaultRepositoryEvent( session, metadata );
            event.setRepository( session.getLocalRepositoryManager().getRepository() );
            event.setFile( dstFile );
            event.setException( exception );
            listener.metadataInstalled( event );
        }
    }

    private synchronized void lockAll( RepositorySystemSession session, InstallRequest request )
        throws InstallationException
    {
        if ( locked.containsKey( request ) )
        {
            throw new IllegalStateException( String.format( "Given InstallRequest is already processing (%s, %s) ",
                                                            request.getArtifacts(), request.getMetadata() ) );
        }

        Collection<Artifact> artifacts = request.getArtifacts();
        Collection<Metadata> metadata = request.getMetadata();
        List<Lock> locks = new LinkedList<Lock>();
        Map<String, FileLock> filelocks = new HashMap<String, FileLock>();

        try
        {
            for ( Artifact a : artifacts )
            {
                filelock( session, filelocks, a.getGroupId() );
                lock( session, locks, a );
            }
            for ( Metadata m : metadata )
            {
                filelock( session, filelocks, m.getGroupId() );
                lock( session, locks, m );
            }
            locked.put( request, locks );
            filelocked.put( request, filelocks );
        }
        catch ( RuntimeException t )
        {
            unlock( locks );
            unlock( filelocks );
            throw t;
        }
    }

    private void filelock( RepositorySystemSession session, InstallRequest request, String gid )
        throws InstallationException
    {
        Map<String, FileLock> filelocks = filelocked.get( request );
        filelock( session, filelocks, gid );

    }

    private void filelock( RepositorySystemSession session, Map<String, FileLock> map, String gid )
        throws InstallationException
    {
        if ( !map.containsKey( gid ) )
        {
            File gidFile = new File( session.getLocalRepository().getBasedir(), "LockingInstaller_FileLock_" + gid );
            FileLock lock = null;
            try
            {
                if ( !gidFile.exists() )
                {
                    fileProcessor.mkdirs( gidFile.getParentFile() );
                    gidFile.createNewFile();
                }
                RandomAccessFile raf = null;
                raf = new RandomAccessFile( gidFile, "rw" );
                lock = raf.getChannel().lock();
                map.put( gid, lock );
            }
            catch ( IOException e )
            {
                if ( lock != null )
                {
                    try
                    {
                        lock.release();
                    }
                    catch ( IOException e1 )
                    {
                        logger.debug( String.format( "Exception while releasing file-lock for '%s', file '%s'", gid,
                                                     gidFile ), e1 );
                    }
                }
                throw new InstallationException( "Could not file-lock " + gid, e );
            }
        }
    }

    /**
     * Lock file for given metadata, internally and via {@link FileLock}.
     */
    private void lock( RepositorySystemSession session, InstallRequest request, Metadata m )
        throws InstallationException
    {
        lock( session, locked.get( request ), m );
        filelock( session, request, m.getGroupId() );
    }

    private void lock( RepositorySystemSession session, List<Lock> locks, Metadata m )
    {
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();
        File file = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalMetadata( m ) );

        lock( locks, file );
    }

    private void lock( RepositorySystemSession session, List<Lock> locks, Artifact a )
    {
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();
        File file = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalArtifact( a ) );

        lock( locks, file );
    }

    private void lock( List<Lock> locks, File file )
    {
        Lock l = lockManager.writeLock( file );
        l.lock();
        locks.add( l );
    }

    private void unlock( List<Lock> locks )
    {
        for ( Lock writeLock : locks )
        {
            writeLock.unlock();
        }
    }

    private void unlock( Map<String, FileLock> filelocks )
    {
        for ( FileLock lock : filelocks.values() )
        {
            try
            {
                lock.release();
            }
            catch ( IOException e )
            {
                logger.debug( String.format( "Exception while releasing file-lock '%s'", lock ), e );
            }
        }
    }

    private void unlock( InstallRequest request )
    {
        unlock( locked.remove( request ) );
        unlock( filelocked.remove( request ) );
    }

}

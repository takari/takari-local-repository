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
import org.sonatype.aether.RepositoryEvent.EventType;
import org.sonatype.aether.RepositoryException;
import org.sonatype.aether.RepositoryListener;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.extension.concurrency.LockManager.Lock;
import org.sonatype.aether.impl.Installer;
import org.sonatype.aether.impl.LocalRepositoryEvent;
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

    private static final Comparator<MetadataGeneratorFactory> COMPARATOR = new Comparator<MetadataGeneratorFactory>()
    {

        public int compare( MetadataGeneratorFactory o1, MetadataGeneratorFactory o2 )
        {
            return o2.getPriority() - o1.getPriority();
        }

    };

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
        InstallerContext ctx = new InstallerContext();

        lockAll( session, request, ctx );

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
                        lock( session, metadata, ctx );
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

                    install( session, artifact, ctx );
                    result.addArtifact( artifact );
                }

                for ( MetadataGenerator generator : generators )
                {
                    for ( Metadata metadata : generator.finish( artifacts ) )
                    {
                        lock( session, metadata, ctx );
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
            promote( session, result, ctx );
            return result;
        }
        finally
        {
            cleanup( session, result );
            unlock( ctx );
        }
    }

    private void install( RepositorySystemSession session, Artifact artifact, InstallerContext ctx )
        throws InstallationException
    {
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();

        File srcFile = artifact.getFile();

        File dstFile = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalArtifact( artifact ) );

        File stagedFile = stage( artifact, dstFile );

        artifactInstalling( session, artifact, dstFile );

        try
        {
            boolean copy =
                "pom".equals( artifact.getExtension() ) || srcFile.lastModified() != dstFile.lastModified()
                    || srcFile.length() != dstFile.length();

            if ( copy )
            {
                fileProcessor.copy( srcFile, stagedFile, null );
                stagedFile.setLastModified( srcFile.lastModified() );
            }
            else
            {
                logger.debug( "Skipped re-installing " + srcFile + " to " + dstFile + ", seems unchanged" );
            }

            ctx.getRegistrations().put( artifact, new LocalArtifactRegistration( artifact ) );
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
                ( (MergeableMetadata) metadata ).merge( dstFile, stage( metadata, dstFile ) );
            }
            else
            {
                fileProcessor.copy( metadata.getFile(), stage( metadata, dstFile ), null );
            }
        }
        catch ( Exception e )
        {
            throw new InstallationException( "Failed to install metadata " + metadata + ": " + e.getMessage(), e );
        }
    }

    private void promote( RepositorySystemSession session, InstallResult result, InstallerContext ctx )
        throws InstallationException
    {
        LocalRepositoryManager lrm = session.getLocalRepositoryManager();

        try
        {
            for ( Artifact a : result.getArtifacts() )
            {
                File dstFile = null;
                Exception exception = null;
                try
                {
                    dstFile = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalArtifact( a ) );
                    File transFile = stage( a, dstFile );

                    sanity( dstFile, transFile );
                    mark( a, dstFile );

                    // no temporary -> unchanged src file, no error
                    if ( transFile.exists() && !transFile.renameTo( dstFile ) )
                    {
                        fileProcessor.copy( transFile, dstFile, null );
                        dstFile.setLastModified( transFile.lastModified() );
                    }

                    lrm.add( session, ctx.getRegistrations().get( a ) );

                    if ( !localRepositoryMaintainers.isEmpty() )
                    {
                        DefaultLocalRepositoryEvent event =
                            new DefaultLocalRepositoryEvent( LocalRepositoryEvent.EventType.ARTIFACT_INSTALLED,
                                                             session, a, dstFile );
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
                File dstFile = new File( lrm.getRepository().getBasedir(), lrm.getPathForLocalMetadata( m ) );
                Exception exception = null;
                try
                {
                    File transFile = stage( m, dstFile );

                    sanity( dstFile, transFile );
                    mark( m, dstFile );

                    if ( !transFile.renameTo( dstFile ) )
                    {
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
                throw new InstallationException( "Installation and Rollback failed for " + result.toString() + ": "
                    + e.getMessage(), e );
            }
            throw new InstallationException( "Installation failed for " + result.toString() + ": " + e.getMessage(), e );
        }
    }

    private void rollback( RepositorySystemSession session, InstallResult result )
        throws IOException, RepositoryException
    {
        boolean failures = false;

        LocalRepositoryManager lrm = session.getLocalRepositoryManager();
        File basedir = lrm.getRepository().getBasedir();

        for ( Artifact a : result.getArtifacts() )
        {
            File dstFile = new File( basedir, lrm.getPathForLocalArtifact( a ) );

            if ( backupFile( a, dstFile ).exists() && !backupFile( a, dstFile ).renameTo( dstFile ) )
            {
                failures = true;
            }
            if ( deleteMarker( a, dstFile ).exists() && dstFile.exists() && !dstFile.delete() )
            {
                failures = true;
            }
        }
        for ( Metadata m : result.getMetadata() )
        {
            File dstFile = new File( basedir, lrm.getPathForLocalMetadata( m ) );

            if ( backupFile( m, dstFile ).exists() && !backupFile( m, dstFile ).renameTo( dstFile ) )
            {
                failures = true;
            }
            if ( deleteMarker( m, dstFile ).exists() && dstFile.exists() && !dstFile.delete() )
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

            backupFile( a, dstFile ).delete();
            deleteMarker( a, dstFile ).delete();
            stage( a, dstFile ).delete();
        }
        for ( Metadata m : result.getMetadata() )
        {
            File dstFile = new File( basedir, lrm.getPathForLocalMetadata( m ) );

            backupFile( m, dstFile ).delete();
            deleteMarker( m, dstFile ).delete();
            stage( m, dstFile ).delete();
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

    private void mark( Object ctx, File file )
        throws IOException
    {
        if ( file.exists() && stage( ctx, file ).exists() )
        {
            File backupFile = backupFile( ctx, file );
            boolean renamed = file.renameTo( backupFile );

            if ( !renamed )
            {
                logger.debug( String.format( "Could not rename %s to %s, copying instead.", file,
                                             backupFile ) );
                fileProcessor.copy( file, backupFile( ctx, file ), null );
            }
        }
        else
        {
            deleteMarker( ctx, file ).createNewFile();
        }
    }

    private File stage( Object ctx, File dstFile )
    {
        return new File( dstFile.getAbsolutePath() + "." + ctx.hashCode() );
    }

    private File backupFile( Object ctx, File transFile )
    {
        return new File( transFile.getAbsolutePath() + "." + ctx.hashCode() + ".backup" );
    }

    private File deleteMarker( Object ctx, File transFile )
    {
        return new File( transFile.getAbsolutePath() + "." + ctx.hashCode() + ".delete" );
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
            DefaultRepositoryEvent event = new DefaultRepositoryEvent( EventType.ARTIFACT_INSTALLING, session );
            event.setArtifact( artifact );
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
            DefaultRepositoryEvent event = new DefaultRepositoryEvent( EventType.ARTIFACT_INSTALLED, session );
            event.setArtifact( artifact );
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
            DefaultRepositoryEvent event = new DefaultRepositoryEvent( EventType.METADATA_INSTALLING, session );
            event.setMetadata( metadata );
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
            DefaultRepositoryEvent event = new DefaultRepositoryEvent( EventType.METADATA_INSTALLED, session );
            event.setMetadata( metadata );
            event.setRepository( session.getLocalRepositoryManager().getRepository() );
            event.setFile( dstFile );
            event.setException( exception );
            listener.metadataInstalled( event );
        }
    }

    private synchronized void lockAll( RepositorySystemSession session, InstallRequest request, InstallerContext ctx )
        throws InstallationException
    {
        Collection<Artifact> artifacts = request.getArtifacts();
        Collection<Metadata> metadata = request.getMetadata();

        List<Lock> locks = ctx.getLocks();
        Map<String, FileLock> filelocks = ctx.getFilelocks();

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
        }
        catch ( RuntimeException t )
        {
            unlock( locks );
            unlock( filelocks );
            throw t;
        }
    }

    private void filelock( RepositorySystemSession session, String gid, InstallerContext ctx )
        throws InstallationException
    {
        Map<String, FileLock> filelocks = ctx.getFilelocks();
        filelock( session, filelocks, gid );

    }

    private void filelock( RepositorySystemSession session, Map<String, FileLock> map, String gid )
        throws InstallationException
    {
        if ( !map.containsKey( gid ) )
        {
            File gidFile = gidFile( session, gid );
            FileLock lock = null;
            try
            {
                if ( !gidFile.exists() )
                {
                    fileProcessor.mkdirs( gidFile.getParentFile() );
                    gidFile.createNewFile();
                }
                RandomAccessFile raf = new RandomAccessFile( gidFile, "rw" );
                // lock only file size http://bugs.sun.com/view_bug.do?bug_id=6628575
                lock = raf.getChannel().lock( 0, 1, false );
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
                        logger.debug( String.format( "Exception while cleaning up on error (trying to release file-lock for '%s', fIle '%s')",
                                                     gid, gidFile ), e1 );
                    }
                }
                throw new InstallationException( "Could not file-lock " + gid, e );
            }
        }
    }

    /**
     * Lock file for given metadata, internally and via {@link FileLock}.
     */
    private void lock( RepositorySystemSession session, Metadata m, InstallerContext ctx )
        throws InstallationException
    {
        lock( session, ctx.getLocks(), m );
        filelock( session, m.getGroupId(), ctx );
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
                lock.channel().close();
            }
            catch ( IOException e )
            {
                logger.debug( String.format( "Exception while releasing file-lock '%s'", lock ), e );
            }
        }
    }

    private void unlock( InstallerContext ctx )
    {
        unlock( ctx.getLocks() );
        unlock( ctx.getFilelocks() );
    }

    private File gidFile( RepositorySystemSession session, String gid )
    {
        return new File( session.getLocalRepository().getBasedir(), "LockingInstaller_FileLock_" + gid );
    }

    private class InstallerContext
    {
        private List<Lock> locks = new LinkedList<Lock>();
    
        private Map<String, FileLock> filelocks = new HashMap<String, FileLock>();
    
        private Map<Artifact, LocalArtifactRegistration> registrations =
            new HashMap<Artifact, LocalArtifactRegistration>();
    
        public List<Lock> getLocks()
        {
            return locks;
        }
    
        public Map<String, FileLock> getFilelocks()
        {
            return filelocks;
        }
    
        public Map<Artifact, LocalArtifactRegistration> getRegistrations()
        {
            return registrations;
        }
    }

}

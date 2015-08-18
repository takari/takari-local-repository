package io.takari.aether.localrepo;

/*******************************************************************************
 * Copyright (c) 2010, 2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype, Inc. - initial API and implementation
 *******************************************************************************/

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.LocalArtifactRegistration;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalArtifactResult;
import org.eclipse.aether.repository.LocalMetadataRegistration;
import org.eclipse.aether.repository.LocalMetadataRequest;
import org.eclipse.aether.repository.LocalMetadataResult;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * These are implementation details for enhanced local repository manager, subject to change without prior notice.
 * Repositories from which a cached artifact was resolved are tracked in a properties file named
 * <code>_remote.repositories</code>, with content key as filename&gt;repo_id and value as empty string. If a file has
 * been installed in the repository, but not downloaded from a remote repository, it is tracked as empty repository id
 * and always resolved. For example:
 * 
 * <pre>
 * artifact-1.0.pom>=
 * artifact-1.0.jar>=
 * artifact-1.0.pom>central=
 * artifact-1.0.jar>central=
 * artifact-1.0.zip>central=
 * artifact-1.0-classifier.zip>central=
 * artifact-1.0.pom>my_repo_id=
 * </pre>
 * 
 */
public class TakariLocalRepositoryManager implements LocalRepositoryManager {

  public static final String REPOSITORY_URI = ".repositoryUri";
  private static final String LOCAL_REPO_ID = "";
  private final String trackingFilename;
  private final TrackingFileManager trackingFileManager;
  private final LocalRepository localRepository;
  private final List<ArtifactValidator> validators;

  public TakariLocalRepositoryManager(File basedir, RepositorySystemSession session, List<ArtifactValidator> validators) {
    if (basedir == null) {
      throw new IllegalArgumentException("base directory has not been specified");
    }
    this.validators = validators;
    localRepository = new LocalRepository(basedir.getAbsoluteFile(), "enhanced");
    String filename = getString(session, "", "aether.enhancedLocalRepository.trackingFilename");
    if (filename.length() <= 0 || filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
      filename = "_remote.repositories";
    }
    trackingFilename = filename;
    trackingFileManager = new TrackingFileManager();
  }

  public LocalArtifactResult find(RepositorySystemSession session, LocalArtifactRequest request) {
    String path = getPathForArtifact(request.getArtifact(), false);
    File file = new File(getRepository().getBasedir(), path);
    LocalArtifactResult result = new LocalArtifactResult(request);
    if (file.isFile()) {
      result.setFile(file);
      Properties props = readRepos(file);
      if (props.get(getKey(file, LOCAL_REPO_ID)) != null) {
        //
        // artifact installed into the local repo is always accepted
        //
        result.setAvailable(true);
      } else {
        RemoteRepository remoteRepositoryForArtifact = null;
        String context = request.getContext();
        for (RemoteRepository remoteRepository : request.getRepositories()) {
          if (props.get(getKey(file, getRepositoryKey(remoteRepository, context))) != null) {
            //
            // This is the remote repository that the artifact was resolved from initially. If the artifact is now available from
            // a different remote repository
            remoteRepositoryForArtifact = remoteRepository;
            result.setRepository(remoteRepositoryForArtifact);            
            break;
          }
        }
        try {
          for (ArtifactValidator validator : validators) {
            validator.validateOnFind(request.getArtifact(), localRepository,
                remoteRepositoryForArtifact);
          }
          result.setFile(file);
          result.setAvailable(true);
        } catch (ArtifactUnavailableException e) {
          // sadly, there is no way to communicate the exception/message to the caller
          result.setFile(null);
          result.setAvailable(false);
        }

        /*
        
        This is the check to make sure what is found locally comes from the same remote repository. We actually don't care where it comes
        from really provided the SHA1 is the same.
        
        String context = request.getContext();
        for (RemoteRepository remoteRepository : request.getRepositories()) {
          if (props.get(getKey(file, getRepositoryKey(remoteRepository, context))) != null) {
            // artifact downloaded from remote repository is accepted 
            for (ArtifactValidator validator : validators) {
              validator.validateOnFind(request.getArtifact(), localRepository, remoteRepository);
            }
            result.setAvailable(true);
            result.setRepository(remoteRepository);
            break;
          }
        }
        if (!result.isAvailable() && !isTracked(props, file)) {
           // NOTE: The artifact is present but not tracked at all, for inter-op with simple local repo, assume the artifact was locally installed.
          result.setAvailable(true);
        }
        */
        
      }
    }

    return result;
  }

  public void add(RepositorySystemSession session, LocalArtifactRegistration request) {
    Collection<String> repositories;
    if (request.getRepository() == null) {
      repositories = Collections.singleton(LOCAL_REPO_ID);
    } else {
      repositories = getRepositoryKeys(request.getRepository(), request.getContexts());
    }

    Artifact artifact = request.getArtifact();
    boolean local = request.getRepository() == null;

    if (artifact == null) {
      throw new IllegalArgumentException("artifact to register not specified");
    }
    String path = getPathForArtifact(artifact, local);
    File file = new File(getRepository().getBasedir(), path);

    Map<String, String> updates = new HashMap<String, String>();
    for (String repository : repositories) {
      updates.put(getKey(file, repository), "");
    }

    File trackingFile = getTrackingFile(file);
    trackingFileManager.update(trackingFile, updates);
    
    // The files are now present in the local repository
    for (ArtifactValidator validator : validators) {
      validator.validateOnAdd(request.getArtifact(), localRepository, request.getRepository());
    }
    
  }

  private Collection<String> getRepositoryKeys(RemoteRepository repository, Collection<String> contexts) {
    Collection<String> keys = new HashSet<String>();

    if (contexts != null) {
      for (String context : contexts) {
        keys.add(getRepositoryKey(repository, context));
      }
    }

    return keys;
  }

  private Properties readRepos(File artifactFile) {
    File trackingFile = getTrackingFile(artifactFile);
    Properties props = trackingFileManager.read(trackingFile);
    return (props != null) ? props : new Properties();
  }

  private File getTrackingFile(File artifactFile) {
    return new File(artifactFile.getParentFile(), trackingFilename);
  }

  private String getKey(File file, String repository) {
    return file.getName() + '>' + repository;
  }

  public static String getString(Map<?, ?> properties, String defaultValue, String... keys) {
    for (String key : keys) {
      Object value = properties.get(key);

      if (value instanceof String) {
        return (String) value;
      }
    }

    return defaultValue;
  }

  public static String getString(RepositorySystemSession session, String defaultValue, String... keys) {
    return getString(session.getConfigProperties(), defaultValue, keys);
  }

  ///////

  public LocalRepository getRepository() {
    return localRepository;
  }

  String getPathForArtifact(Artifact artifact, boolean local) {
    StringBuilder path = new StringBuilder(128);
    path.append(artifact.getGroupId().replace('.', '/')).append('/');
    path.append(artifact.getArtifactId()).append('/');
    path.append(artifact.getBaseVersion()).append('/');
    path.append(artifact.getArtifactId()).append('-');
    if (local) {
      path.append(artifact.getBaseVersion());
    } else {
      path.append(artifact.getVersion());
    }
    if (artifact.getClassifier().length() > 0) {
      path.append('-').append(artifact.getClassifier());
    }
    if (artifact.getExtension().length() > 0) {
      path.append('.').append(artifact.getExtension());
    }
    return path.toString();
  }

  public String getPathForLocalArtifact(Artifact artifact) {
    return getPathForArtifact(artifact, true);
  }

  public String getPathForRemoteArtifact(Artifact artifact, RemoteRepository repository, String context) {
    return getPathForArtifact(artifact, false);
  }

  public String getPathForLocalMetadata(Metadata metadata) {
    return getPath(metadata, "local");
  }

  public String getPathForRemoteMetadata(Metadata metadata, RemoteRepository repository, String context) {
    return getPath(metadata, getRepositoryKey(repository, context));
  }

  String getRepositoryKey(RemoteRepository repository, String context) {
    String key;
    if (repository.isRepositoryManager()) {
      // repository serves dynamic contents, take request parameters into account for key
      StringBuilder buffer = new StringBuilder(128);
      buffer.append(repository.getId());
      buffer.append('-');

      SortedSet<String> subKeys = new TreeSet<String>();
      for (RemoteRepository mirroredRepo : repository.getMirroredRepositories()) {
        subKeys.add(mirroredRepo.getId());
      }

      SimpleDigest digest = new SimpleDigest();
      digest.update(context);
      for (String subKey : subKeys) {
        digest.update(subKey);
      }
      buffer.append(digest.digest());

      key = buffer.toString();
    } else {
      // repository serves static contents, its id is sufficient as key
      key = repository.getId();
    }

    return key;
  }

  private String getPath(Metadata metadata, String repositoryKey) {
    StringBuilder path = new StringBuilder(128);

    if (metadata.getGroupId().length() > 0) {
      path.append(metadata.getGroupId().replace('.', '/')).append('/');

      if (metadata.getArtifactId().length() > 0) {
        path.append(metadata.getArtifactId()).append('/');

        if (metadata.getVersion().length() > 0) {
          path.append(metadata.getVersion()).append('/');
        }
      }
    }

    path.append(insertRepositoryKey(metadata.getType(), repositoryKey));

    return path.toString();
  }

  private String insertRepositoryKey(String filename, String repositoryKey) {
    String result;
    int idx = filename.indexOf('.');
    if (idx < 0) {
      result = filename + '-' + repositoryKey;
    } else {
      result = filename.substring(0, idx) + '-' + repositoryKey + filename.substring(idx);
    }
    return result;
  }

  public LocalArtifactResult Xfind(RepositorySystemSession session, LocalArtifactRequest request) {
    String path = getPathForArtifact(request.getArtifact(), false);
    File file = new File(getRepository().getBasedir(), path);

    LocalArtifactResult result = new LocalArtifactResult(request);
    if (file.isFile()) {
      result.setFile(file);
      result.setAvailable(true);
    }

    return result;
  }

  public void Xadd(RepositorySystemSession session, LocalArtifactRegistration request) {
    // noop
  }

  @Override
  public String toString() {
    return String.valueOf(getRepository());
  }

  public LocalMetadataResult find(RepositorySystemSession session, LocalMetadataRequest request) {
    LocalMetadataResult result = new LocalMetadataResult(request);

    String path;

    Metadata metadata = request.getMetadata();
    String context = request.getContext();
    RemoteRepository remote = request.getRepository();

    if (remote != null) {
      path = getPathForRemoteMetadata(metadata, remote, context);
    } else {
      path = getPathForLocalMetadata(metadata);
    }

    File file = new File(getRepository().getBasedir(), path);
    if (file.isFile()) {
      result.setFile(file);
    }

    return result;
  }

  public void add(RepositorySystemSession session, LocalMetadataRegistration request) {
    // noop
  }

  //
  // Just use Guava
  //
  class SimpleDigest {

    private MessageDigest digest;

    private long hash;

    public SimpleDigest() {
      try {
        digest = MessageDigest.getInstance("SHA-1");
      } catch (NoSuchAlgorithmException e) {
        try {
          digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException ne) {
          digest = null;
          hash = 13;
        }
      }
    }

    public void update(String data) {
      if (data == null || data.length() <= 0) {
        return;
      }
      if (digest != null) {
        try {
          digest.update(data.getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
          // broken JVM
        }
      } else {
        hash = hash * 31 + data.hashCode();
      }
    }

    public String digest() {
      if (digest != null) {
        StringBuilder buffer = new StringBuilder(64);

        byte[] bytes = digest.digest();
        for (int i = 0; i < bytes.length; i++) {
          int b = bytes[i] & 0xFF;

          if (b < 0x10) {
            buffer.append('0');
          }

          buffer.append(Integer.toHexString(b));
        }

        return buffer.toString();
      } else {
        return Long.toHexString(hash);
      }
    }
  }
}

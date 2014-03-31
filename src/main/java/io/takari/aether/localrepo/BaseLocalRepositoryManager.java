package io.takari.aether.localrepo;

/*******************************************************************************
 * Copyright (c) 2010, 2011 Sonatype, Inc.
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
 * A local repository manager that realizes the classical Maven 2.0 local repository.
 */
class BaseLocalRepositoryManager implements LocalRepositoryManager {

  private final LocalRepository repository;

  public BaseLocalRepositoryManager(File basedir) {
    this(basedir, "simple");
  }

  public BaseLocalRepositoryManager(String basedir) {
    this((basedir != null) ? new File(basedir) : null, "simple");
  }

  BaseLocalRepositoryManager(File basedir, String type) {
    if (basedir == null) {
      throw new IllegalArgumentException("base directory has not been specified");
    }
    repository = new LocalRepository(basedir.getAbsoluteFile(), type);
  }

  public LocalRepository getRepository() {
    return repository;
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

  public LocalArtifactResult find(RepositorySystemSession session, LocalArtifactRequest request) {
    String path = getPathForArtifact(request.getArtifact(), false);
    File file = new File(getRepository().getBasedir(), path);

    LocalArtifactResult result = new LocalArtifactResult(request);
    if (file.isFile()) {
      result.setFile(file);
      result.setAvailable(true);
    }

    return result;
  }

  public void add(RepositorySystemSession session, LocalArtifactRegistration request) {
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

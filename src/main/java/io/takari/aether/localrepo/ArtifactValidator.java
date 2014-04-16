package io.takari.aether.localrepo;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;

public interface ArtifactValidator {

  void validateOnAdd(Artifact artifact, LocalRepository localRepository, RemoteRepository remoteRepository) throws ArtifactValidationException;
  
  void validateOnFind(Artifact artifact, LocalRepository localRepository, RemoteRepository remoteRepository) throws ArtifactValidationException;
  
}

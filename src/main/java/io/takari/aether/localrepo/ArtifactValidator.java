package io.takari.aether.localrepo;

import java.util.Properties;

import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalRepository;

public interface ArtifactValidator {

  void validate(LocalRepository localRepository, LocalArtifactRequest localArtifactRequest, Properties artifactProperties) throws ArtifactValidationException;
}

package io.takari.aether.localrepo;

/**
 * Artifact validation exception that blocks artifact resolution but does not trigger immediate
 * build failure.
 */
public class ArtifactUnavailableException extends ArtifactValidationException {
  private static final long serialVersionUID = 9197584795220384513L;

  public ArtifactUnavailableException(String message) {
    super(message);
  }
}

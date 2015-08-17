package io.takari.aether.localrepo.its;

import static org.junit.Assert.assertFalse;
import io.takari.maven.testing.TestProperties;
import io.takari.maven.testing.TestResources;
import io.takari.maven.testing.executor.MavenExecution;
import io.takari.maven.testing.executor.MavenExecutionResult;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenJUnitTestRunner;

import java.io.File;

import org.codehaus.plexus.util.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(MavenJUnitTestRunner.class)
@MavenVersions({"3.3.1", "3.3.3"})
public class TakariLocalRepositoryTest {

  @Rule
  public final TestResources resources = new TestResources();
  public final TestProperties proprties = new TestProperties();
  public final MavenRuntime verifier;
  private String basedir;
  
  public TakariLocalRepositoryTest(MavenRuntimeBuilder runtimeBuilder) throws Exception {
    this.verifier = runtimeBuilder.withExtension(new File("target/classes").getCanonicalFile()) //
        .build();
  }

  @Test
  public void validateRetryOnDowloadErrorFlagIsFunctional() throws Exception {
    File localRepository = new File(getBasedir(), "target/local-repo");
    FileUtils.deleteDirectory(localRepository);
    File basedir = resources.getBasedir("basic-it");
    MavenExecution execution = verifier.forProject(basedir) //
        .withCliOptions(String.format("-Dmaven.repo.local=%s", localRepository.getAbsolutePath())) //
        .withCliOptions(String.format("-Dmaven.retryOnDownloadError=true"));
    MavenExecutionResult result = execution.execute("compile");

    result.assertLogText("Could not resolve dependencies for project io.takari.aether.localrepo.its:update-check:jar:0.1.0");

    File updateCheckFile = new File(localRepository, "io/takari/aether/localrepo/its/non-existent/1.0/non-existent-1.0.jar.lastUpdated");
    assertFalse(updateCheckFile.exists());
  }

  public final String getBasedir() {
    if (null == basedir) {
      basedir = System.getProperty("basedir", new File("").getAbsolutePath());
    }
    return basedir;
  }

}

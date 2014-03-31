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

import javax.inject.Named;

import org.codehaus.plexus.component.annotations.Component;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.LocalRepositoryManager;
import org.eclipse.aether.repository.NoLocalRepositoryManagerException;
import org.eclipse.aether.spi.localrepo.LocalRepositoryManagerFactory;

/**
 * Creates enhanced local repository managers for repository types {@code "default"} or {@code "" (automatic)}.
 * Enhanced local repository manager is built upon the classical Maven 2.0 local repository structure but additionally keeps
 * track of from what repositories a cached artifact was resolved.
 * Resolution of locally cached artifacts will be rejected in case the current resolution request does not match the
 * known source repositories of an artifact, thereby emulating physically separated artifact caches per remote repository.
 */
@Named("takari")
@Component(role = LocalRepositoryManagerFactory.class, hint = "takari")
public class TakariLocalRepositoryManagerFactory implements LocalRepositoryManagerFactory {

  public LocalRepositoryManager newInstance(RepositorySystemSession session, LocalRepository repository) throws NoLocalRepositoryManagerException {
    if ("".equals(repository.getContentType()) || "default".equals(repository.getContentType())) {
      return new TakariLocalRepositoryManager(repository.getBasedir(), session);
    } else {
      throw new NoLocalRepositoryManagerException(repository);
    }
  }

  public float getPriority() {
    return 20;
  }

}

package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.nio.channels.FileLock;

import org.sonatype.aether.spi.locator.ServiceLocator;

/**
 * Marker interface to use for {@link ServiceLocator} initialization. This marks a LockManager using {@link FileLock}.
 * 
 * @author Benjamin Hanzelmann
 */
public interface FileLockManager
    extends LockManager
{

}

package org.sonatype.aether.extension.concurrency;

import java.io.File;
import java.nio.channels.FileChannel;

import org.sonatype.aether.extension.concurrency.LockManager.Lock;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

/**
 * A LockManager holding external locks, locking files between OS processes (e.g. via {@link AetherFileLock}.
 * 
 * @author Benjamin Hanzelmann
 */
public interface FileLockManager
{

    public interface AetherFileLock
        extends Lock
    {
        FileChannel channel();
    }

    AetherFileLock readLock( File target );

    AetherFileLock writeLock( File target );

}

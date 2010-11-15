package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.File;
import java.nio.channels.FileChannel;

import org.sonatype.aether.extension.concurrency.LockManager.Lock;

/**
 * A LockManager holding external locks, locking files between OS processes (e.g. via {@link ExternalFileLock}.
 * 
 * @author Benjamin Hanzelmann
 */
public interface FileLockManager
{

    public interface ExternalFileLock
        extends Lock
    {
        FileChannel channel();
    }

    ExternalFileLock readLock( File target );

    ExternalFileLock writeLock( File target );

}

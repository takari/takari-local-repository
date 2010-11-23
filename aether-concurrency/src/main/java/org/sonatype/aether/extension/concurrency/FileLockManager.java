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

    /**
     * This lock object adds the ability to directly access the {@link FileChannel} of the locked file.
     * 
     * @author Benjamin Hanzelmann
     */
    public interface ExternalFileLock
        extends Lock
    {
        /**
         * Returns the channel for the locked file.
         * 
         * @return the channel for the locked file.
         */
        FileChannel channel();
    }

    /**
     * Obtain a lock object that may be used to lock the target file for reading. This method must not lock that file
     * right immediately (see {@link Lock#lock()}).
     * 
     * @param target the file to lock, never {@code null}.
     * @return a lock object, never {@code null}.
     */
    ExternalFileLock readLock( File target );

    /**
     * Obtain a lock object that may be used to lock the target file for writing. This method must not lock that file
     * right immediately (see {@link Lock#lock()}).
     * 
     * @param target the file to lock, never {@code null}.
     * @return a lock object, never {@code null}.
     */
    ExternalFileLock writeLock( File target );

}

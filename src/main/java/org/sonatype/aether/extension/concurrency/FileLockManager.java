package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.File;
import java.io.RandomAccessFile;

import org.sonatype.aether.extension.concurrency.LockManager.Lock;

/**
 * A LockManager holding external locks, locking files between OS processes (e.g. via {@link ExternalFileLock}.
 * 
 * @author Benjamin Hanzelmann
 */
public interface FileLockManager
{

    /**
     * This lock object adds the ability to directly access the contents of the locked file.
     * 
     * @author Benjamin Hanzelmann
     */
    public interface ExternalFileLock
        extends Lock
    {

        /**
         * Gets the random access file used to read/write the contents of the locked file.
         * 
         * @return The random access file used to read/write or {@code null} if the lock isn't acquired.
         */
        RandomAccessFile getRandomAccessFile();

        /**
         * Tells whether the lock is shared or exclusive.
         * 
         * @return {@code true} if the lock is shared, {@code false} if the lock is exclusive.
         */
        boolean isShared();

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

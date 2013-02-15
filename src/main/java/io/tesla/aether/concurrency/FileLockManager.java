package io.tesla.aether.concurrency;

/*******************************************************************************
 * Copyright (c) 2010-2011 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * A LockManager holding external locks, locking files between OS processes (e.g. via {@link Lock}.
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
    public interface Lock
    {

        /**
         * Gets the random access file used to read/write the contents of the locked file.
         * 
         * @return The random access file used to read/write or {@code null} if the lock isn't acquired.
         * @throws IOException
         */
        RandomAccessFile getRandomAccessFile()
            throws IOException;

        /**
         * Tells whether the lock is shared or exclusive.
         * 
         * @return {@code true} if the lock is shared, {@code false} if the lock is exclusive.
         */
        boolean isShared();

        /**
         * Lock the file this Lock was obtained for.
         * <p>
         * Multiple {@link #lock()} invocations on the same or other lock objects using the same (canonical) file as
         * target are possible and non-blocking from the same caller thread.
         * 
         * @throws IOException if an error occurs while locking the file.
         */
        void lock()
            throws IOException;

        /**
         * Unlock the file this Lock was obtained for.
         * 
         * @throws IOException if an error occurs while locking the file.
         */
        void unlock()
            throws IOException;

        /**
         * Get the file this Lock was obtained for.
         * 
         * @return The file this lock was obtained for, never {@code null}.
         */
        File getFile();
    }

    /**
     * Obtain a lock object that may be used to lock the target file for reading. This method must not lock that file
     * right immediately (see {@link Lock#lock()}).
     * 
     * @param target the file to lock, never {@code null}.
     * @return a lock object, never {@code null}.
     */
    Lock readLock( File target );

    /**
     * Obtain a lock object that may be used to lock the target file for writing. This method must not lock that file
     * right immediately (see {@link Lock#lock()}).
     * 
     * @param target the file to lock, never {@code null}.
     * @return a lock object, never {@code null}.
     */
    Lock writeLock( File target );

}

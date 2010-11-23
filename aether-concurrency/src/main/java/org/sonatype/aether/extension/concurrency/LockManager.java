package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A LockManager holding locks internal to the running VM (e.g. via
 * {@link java.util.concurrent.locks.ReentrantReadWriteLock}.
 * <p>
 * The locks must behave similar to {@link ReentrantReadWriteLock} e.g. with regard to multiple locks from the same
 * thread (recursion).
 * 
 * @author Benjamin Hanzelmann
 */
public interface LockManager
{

    /**
     * Obtain a lock object that may be used to lock the target file for writing. This method must not lock that file
     * right immediately (see {@link Lock#lock()}).
     * 
     * @param target the file to lock, never {@code null}.
     * @return a lock object, never {@code null}.
     */
    Lock writeLock( File target );

    /**
     * Obtain a lock object that may be used to lock the target file for reading. This method must not lock that file
     * right immediately (see {@link Lock#lock()}).
     * 
     * @param target the file to lock, never {@code null}.
     * @return a lock object, never {@code null}.
     */
    Lock readLock( File target );

    /**
     * These lock objects have to be used to actually lock files.
     */
    public interface Lock
    {
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
    }

}

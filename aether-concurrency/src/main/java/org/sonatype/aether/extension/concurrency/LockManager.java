package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.File;

/**
 * @author Benjamin Hanzelmann
 */
public interface LockManager
{

    Lock writeLock( File target );

    Lock readLock( File target );

    public interface Lock
    {
        void lock()
            throws LockingException;

        void unlock()
            throws LockingException;
    }
}

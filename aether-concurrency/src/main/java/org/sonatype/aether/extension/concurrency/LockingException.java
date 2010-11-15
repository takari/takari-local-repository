package org.sonatype.aether.extension.concurrency;

/*******************************************************************************
 * Copyright (c) 2010 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import java.io.IOException;

/**
 * Signals that a locking operation has gone wrong.
 * 
 * @author Benjamin Hanzelmann
 */
public class LockingException
    extends IOException
{

    public LockingException( String msg )
    {
        super( msg );
    }

    public LockingException( String msg, Throwable cause )
    {
        super( msg );
        super.initCause( cause );
    }

}

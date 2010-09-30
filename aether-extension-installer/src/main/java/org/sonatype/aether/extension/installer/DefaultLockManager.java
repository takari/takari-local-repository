package org.sonatype.aether.extension.installer;

/*
 * Copyright (c) 2010 Sonatype, Inc. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0, 
 * and you may not use this file except in compliance with the Apache License Version 2.0. 
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the Apache License Version 2.0 is distributed on an 
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;

import org.codehaus.plexus.component.annotations.Component;

@Component( role = LockManager.class )
public class DefaultLockManager
    implements LockManager
{
    private Map<File, ReentrantReadWriteLock> locks = new WeakHashMap<File, ReentrantReadWriteLock>();

    public ReadLock readLock( File file )
    {
        ReentrantReadWriteLock lock = lookup( file );

        return lock.readLock();
    }

    public WriteLock writeLock( File file )
    {
        ReentrantReadWriteLock lock = lookup( file );

        return lock.writeLock();
    }

    private ReentrantReadWriteLock lookup( File file )
    {
        ReentrantReadWriteLock lock = null;

        try
        {
            file = file.getCanonicalFile();
        }
        catch ( IOException e )
        {
            // best effort - use given file
        }

        synchronized ( locks )
        {
            if ( ( lock = locks.get( file ) ) == null )
            {
                lock = new ReentrantReadWriteLock( true );
                locks.put( file, lock );
            }
        }
        return lock;
    }
}

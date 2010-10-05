package org.sonatype.aether.extension.concurrency;

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
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;


/**
 * @author Benjamin Hanzelmann
 *
 */
public class ExternalFileLock
    extends ForkJvm
{

    public static void main( String[] args )
        throws IOException, InterruptedException
    {
        String path = args[0];
        String time = args[1];

        File file = new File( path );
        if ( !file.exists() )
        {
            file.getParentFile().mkdirs();
            file.createNewFile();
        }

        int millis = Integer.valueOf( time );

        RandomAccessFile raf = new RandomAccessFile( file, "rw" );
        FileLock lock = raf.getChannel().lock();

        Thread.sleep( millis );

        lock.release();
        raf.close();
    }

    public Process lockFile( String path, int wait )
        throws InterruptedException, IOException
    {
        addClassPathEntry( this.getClass() );
        setParameters( path, String.valueOf( wait ) );
        Process p = run( this.getClass().getName() );
        p.getOutputStream().close();
        return p;
    }

}

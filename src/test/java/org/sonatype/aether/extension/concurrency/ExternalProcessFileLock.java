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
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * @author Benjamin Hanzelmann
 */
public class ExternalProcessFileLock
    extends ForkJvm
{

    public static void main( String[] args )
        throws IOException, InterruptedException
    {
        String path = args[0] + ".aetherlock";
        String time = args[1];

        File file = new File( path );

        file.getParentFile().mkdirs();

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

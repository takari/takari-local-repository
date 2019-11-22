package io.takari.filemanager.internal;

/*******************************************************************************
 * Copyright (c) 2010-2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import io.takari.filemanager.FileManager;
import io.takari.filemanager.Lock;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Offers advisory file locking independently of the platform. With regard to concurrent readers that don't use any file
 * locking (i.e. 3rd party code accessing files), mandatory locking (as seen on Windows) must be avoided as this would
 * immediately kill the unaware readers. To emulate advisory locking, this implementation uses a dedicated lock file
 * (*.aetherlock) next to the actual file. The inter-process file locking is performed on this lock file, thereby
 * keeping the data file free from locking.
 * 
 * @author Benjamin Bentmann
 */
@Named
@Singleton
public class DefaultFileManager implements FileManager {

  private static Boolean IS_SET_LAST_MODIFIED_SAFE;

  private Logger logger = LoggerFactory.getLogger(DefaultFileManager.class);
  private static final ConcurrentMap<File, LockFile> lockFiles = new ConcurrentHashMap<File, LockFile>(64);

  public Lock readLock(File target) {
    return new IndirectFileLock(normalize(target), false);
  }

  public Lock writeLock(File target) {
    return new IndirectFileLock(normalize(target), true);
  }

  private File normalize(File file) {
    try {
      return file.getCanonicalFile();
    } catch (IOException e) {
      logger.warn("Failed to normalize pathname for lock on " + file + ": " + e);
      return file.getAbsoluteFile();
    }
  }

  /**
   * Null-safe variant of {@link File#mkdirs()}.
   * 
   * @param directory The directory to create, may be {@code null}.
   * @return {@code true} if and only if the directory was created, along with all necessary parent
   *         directories; {@code false} otherwise
   */
  public boolean mkdirs(File directory) {
    if (directory == null) {
      return false;
    }

    return directory.mkdirs();
  }

  private RandomAccessFile open(File file, String mode) throws IOException {
    boolean interrupted = false;

    try {
      mkdirs(file.getParentFile());

      return new RandomAccessFile(file, mode);
    } catch (IOException e) {
      /*
       * NOTE: I've seen failures (on Windows) when opening the file which I can't really explain ("access denied", "locked"). Assuming those are bad interactions with OS-level processes (e.g.
       * indexing, anti-virus), let's just retry before giving up due to a potentially spurious problem.
       */
      for (int i = 3; i >= 0; i--) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException e1) {
          interrupted = true;
        }
        try {
          return new RandomAccessFile(file, mode);
        } catch (IOException ie) {
          // ignored, we eventually rethrow the original error
        }
      }

      throw e;
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void close(Closeable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (IOException e) {
        if (logger != null) {
          logger.warn("Failed to close file: " + e);
        }
      }
    }
  }

  class IndirectFileLock implements Lock {

    private final File file;
    private final boolean write;
    private final Throwable stackTrace;
    private RandomAccessFile raFile;
    private LockFile lockFile;
    private int nesting;

    public IndirectFileLock(File file, boolean write) {
      this.file = file;
      this.write = write;
      this.stackTrace = new IllegalStateException();
    }

    public synchronized void lock() throws IOException {
      if (lockFile == null) {
        open();
        nesting = 1;
      } else {
        nesting++;
      }
    }

    public synchronized void unlock() throws IOException {
      nesting--;
      if (nesting <= 0) {
        close();
      }
    }

    public RandomAccessFile getRandomAccessFile() throws IOException {
      if (raFile == null && lockFile != null && lockFile.getFileLock().isValid()) {
        raFile = DefaultFileManager.this.open(file, write ? "rw" : "r");
      }
      return raFile;
    }

    public boolean isShared() {
      if (lockFile == null) {
        throw new IllegalStateException("lock not acquired");
      }
      return lockFile.isShared();
    }

    public FileLock getLock() {
      if (lockFile == null) {
        return null;
      }
      return lockFile.getFileLock();
    }

    public File getFile() {
      return file;
    }

    @Override
    protected void finalize() throws Throwable {
      try {
        if (lockFile != null) {
          logger.warn("Lock on file " + file + " has not been properly released", stackTrace);
        }
        close();
      } finally {
        super.finalize();
      }
    }

    private void open() throws IOException {
      lockFile = lock(file, write);
    }

    private void close() throws IOException {
      try {
        if (raFile != null) {
          try {
            raFile.close();
          } finally {
            raFile = null;
          }
        }
      } finally {
        if (lockFile != null) {
          try {
            unlock(lockFile);
          } catch (IOException e) {
            logger.warn("Failed to release lock for " + file + ": " + e);
          } finally {
            lockFile = null;
          }
        }
      }
    }    
    
    private LockFile lock(File file, boolean write) throws IOException {
      boolean interrupted = false;

      try {
        while (true) {
          LockFile lockFile = lockFiles.get(file);

          if (lockFile == null) {
            lockFile = new LockFile(file);

            LockFile existing = lockFiles.putIfAbsent(file, lockFile);
            if (existing != null) {
              lockFile = existing;
            }
          }

          synchronized (lockFile) {
            if (lockFile.isInvalid()) {
              continue;
            } else if (lockFile.lock(write)) {
              return lockFile;
            }

            try {
              lockFile.wait();
            } catch (InterruptedException e) {
              interrupted = true;
            }
          }
        }
      } finally {
        /*
         * NOTE: We want to ignore the interrupt but other code might want/need to react to it, so restore the interrupt flag.
         */
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }

    private void unlock(LockFile lockFile) throws IOException {
      synchronized (lockFile) {
        try {
          lockFile.unlock();
        } finally {
          if (lockFile.isInvalid()) {
            lockFiles.remove(lockFile.getDataFile(), lockFile);
            lockFile.notifyAll();
          }
        }
      }
    }
  }

  // LockFile

  /**
   * Manages an {@code *.aetherlock} file. <strong>Note:</strong> This class is not thread-safe and requires external
   * synchronization.
   */
  class LockFile {

    private final File dataFile;
    private final File lockFile;
    private FileLock fileLock;
    private RandomAccessFile raFile;
    private int refCount;
    private Thread owner;
    private final Map<Thread, AtomicInteger> clients = new HashMap<Thread, AtomicInteger>();

    public LockFile(File dataFile) {
      this.dataFile = dataFile;
      if (dataFile.isDirectory()) {
        lockFile = new File(dataFile, ".aetherlock");
      } else {
        lockFile = new File(dataFile.getPath() + ".aetherlock");
      }
    }

    public File getDataFile() {
      return dataFile;
    }

    public boolean lock(boolean write) throws IOException {
      if (isInvalid()) {
        throw new IllegalStateException("lock for " + dataFile + " has been invalidated");
      }

      if (isClosed()) {
        open(write);

        return true;
      } else if (isReentrant(write)) {
        incRefCount();

        return true;
      } else if (isAlreadyHoldByCurrentThread()) {
        throw new IllegalStateException("Cannot acquire " + (write ? "write" : "read") + " lock on " + dataFile + " for thread " + Thread.currentThread() + " which already holds incompatible lock");
      }

      return false;
    }

    public void unlock() throws IOException {
      if (decRefCount() <= 0) {
        close();
      }
    }

    FileLock getFileLock() {
      return fileLock;
    }

    public boolean isInvalid() {
      return refCount < 0;
    }

    public boolean isShared() {
      if (fileLock == null) {
        throw new IllegalStateException("lock not acquired");
      }
      return fileLock.isShared();
    }

    private boolean isClosed() {
      return fileLock == null;
    }

    private boolean isReentrant(boolean write) {
      if (isShared()) {
        return !write;
      } else {
        return Thread.currentThread() == owner;
      }
    }

    private boolean isAlreadyHoldByCurrentThread() {
      return clients.get(Thread.currentThread()) != null;
    }

    private void open(boolean write) throws IOException {
      refCount = 1;

      owner = write ? Thread.currentThread() : null;

      clients.put(Thread.currentThread(), new AtomicInteger(1));

      RandomAccessFile raf = null;
      FileLock lock = null;
      boolean interrupted = false;

      try {
        while (true) {
          raf = DefaultFileManager.this.open(lockFile, "rw");

          try {
            lock = raf.getChannel().lock(0, 1, !write);

            if (lock == null) {
              /*
               * Probably related to http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=6979009, lock() erroneously returns null when the thread got interrupted and the channel silently closed.
               */
              throw new FileLockInterruptionException();
            }

            break;
          } catch (FileLockInterruptionException e) {
            /*
             * NOTE: We want to lock that file and this isn't negotiable, so whatever felt like interrupting our thread, try again later, we have work to get done. And since the interrupt closed the
             * channel, we need to start with a fresh file handle.
             */

            interrupted |= Thread.interrupted();

            DefaultFileManager.this.close(raf);
          } catch (IOException e) {
            DefaultFileManager.this.close(raf);

            // EVIL: parse message of IOException to find out if it's a (probably erroneous) 'deadlock
            // detection' (linux kernel does not account for different threads holding the locks for the
            // same process)
            if (isPseudoDeadlock(e)) {
              logger.debug("OS detected pseudo deadlock for " + lockFile + ", retrying locking");
              try {
                Thread.sleep(100);
              } catch (InterruptedException e1) {
                interrupted = true;
              }
            } else {
              delete();
              throw e;
            }
          }
        }
      } finally {
        /*
         * NOTE: We want to ignore the interrupt but other code might want/need to react to it, so restore the interrupt flag.
         */
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }

      raFile = raf;
      fileLock = lock;
    }

    private boolean isPseudoDeadlock(IOException e) {
      String msg = e.getMessage();
      return msg != null && msg.toLowerCase(Locale.ENGLISH).contains("deadlock");
    }

    private void close() throws IOException {
      refCount = -1;

      if (fileLock != null) {
        try {
          if (fileLock.isValid()) {
            fileLock.release();
          }
        } catch (IOException e) {
          logger.warn("Failed to release lock on " + lockFile + ": " + e);
        } finally {
          fileLock = null;
        }
      }

      if (raFile != null) {
        try {
          raFile.close();
        } finally {
          raFile = null;
          delete();
        }
      }
    }

    private void delete() {
      if (lockFile != null) {
        if (!lockFile.delete() && lockFile.exists()) {
          // NOTE: This happens naturally when some other thread locked it in the meantime
          lockFile.deleteOnExit();
        }
      }
    }

    private int incRefCount() {
      AtomicInteger clientRefCount = clients.get(Thread.currentThread());
      if (clientRefCount == null) {
        clients.put(Thread.currentThread(), new AtomicInteger(1));
      } else {
        clientRefCount.incrementAndGet();
      }

      return ++refCount;
    }

    private int decRefCount() {
      AtomicInteger clientRefCount = clients.get(Thread.currentThread());
      if (clientRefCount != null && clientRefCount.decrementAndGet() <= 0) {
        clients.remove(Thread.currentThread());
      }

      return --refCount;
    }

    @Override
    protected void finalize() throws Throwable {
      try {
        close();
      } finally {
        super.finalize();
      }
    }
  }
  
  // FileManager
  
  private void unlock(Lock lock) {
    if (lock != null) {
      try {
        lock.unlock();
      } catch (IOException e) {
        logger.warn("Failed to unlock file " + lock.getFile() + ": " + e);
      }
    }
  }

  public void copy(File source, File target) throws IOException {
    copy(source, target, null);
  }

  /**
   * Copy src- to target-file. Creates the necessary directories for the target file. In case of an error, the created
   * directories will be left on the file system.
   * <p>
   * This method performs R/W-locking on the given files to provide concurrent access to files without data
   * corruption, and will honor {@link FileLock}s from an external process.
   * 
   * @param source the file to copy from, must not be {@code null}.
   * @param target the file to copy to, must not be {@code null}.
   * @param listener the listener to notify about the copy progress, may be {@code null}.
   * @return the number of copied bytes.
   * @throws IOException if an I/O error occurs.
   */
  public long copy(File source, File target, ProgressListener listener) throws IOException {
    Lock sourceLock = readLock(source);
    Lock targetLock = writeLock(target);

    try {
      mkdirs(target.getParentFile());

      sourceLock.lock();
      targetLock.lock();

      return copy(sourceLock.getRandomAccessFile(), targetLock.getRandomAccessFile(), listener);
    } finally {
      target.setLastModified(source.lastModified());
      unlock(sourceLock);
      unlock(targetLock);
    }
  }

  private long copy(RandomAccessFile rafIn, RandomAccessFile rafOut, ProgressListener listener) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(1024 * 32);
    byte[] array = buffer.array();
    long total = 0;
    for (;;) {
      int bytes = rafIn.read(array);
      if (bytes < 0) {
        rafOut.setLength(rafOut.getFilePointer());
        break;
      }
      rafOut.write(array, 0, bytes);
      total += bytes;
      if (listener != null && bytes > 0) {
        try {
          buffer.rewind();
          buffer.limit(bytes);
          listener.progressed(buffer);
        } catch (Exception e) {
          logger.debug("Failed to invoke copy progress listener", e);
        }
      }
    }
    return total;
  }

  public void write(File file, InputStream source) throws IOException {
        
    Lock lock = writeLock(file);

    try {
      mkdirs(file.getParentFile());

      lock.lock();

      RandomAccessFile raf = lock.getRandomAccessFile();

      raf.seek(0);
      if (source != null) {
        byte[] buffer = new byte[1024];
        int len;
        while ((len = source.read(buffer)) != -1) {
          raf.write(buffer, 0, len);
        }
      }

      raf.setLength(raf.getFilePointer());
    } finally {
      unlock(lock);
    }
  }

  /**
   * Write the given data to a file. UTF-8 is assumed as encoding for the data.
   * 
   * @param file The file to write to, must not be {@code null}. This file will be truncated.
   * @param data The data to write, may be {@code null}.
   * @throws IOException if an I/O error occurs.
   */
  public void write(File file, String data) throws IOException {
    Lock lock = writeLock(file);

    try {
      mkdirs(file.getParentFile());

      lock.lock();

      RandomAccessFile raf = lock.getRandomAccessFile();

      raf.seek(0);
      if (data != null) {
        raf.write(data.getBytes("UTF-8"));
      }

      raf.setLength(raf.getFilePointer());
    } finally {
      unlock(lock);
    }
  }

  public void move(File source, File target) throws IOException {
    /*
     * NOTE: For graceful collaboration with concurrent readers don't attempt to delete the target file, if it already exists, it's safer to just overwrite it, especially when the contents doesn't
     * actually change.
     */

    /*
     * NOTE: We're about to remove/delete the source file so be sure to acquire an exclusive lock for the source.
     */

    Lock sourceLock = writeLock(source);
    Lock targetLock = writeLock(target);

    try {
      mkdirs(target.getParentFile());

      sourceLock.lock();
      targetLock.lock();

      if (!source.renameTo(target)) {
        copy(sourceLock.getRandomAccessFile(), targetLock.getRandomAccessFile(), null);

        /*
         * NOTE: On Windows and before JRE 1.7, File.setLastModified() opens the file without any sharing enabled (cf. evaluation of Sun bug 6357599). This means while setLastModified() is executing,
         * no other thread/process can open the file "because it is being used by another process". The read accesses to files can't always be guarded by locks, take for instance class loaders reading
         * JARs, so we must avoid calling setLastModified() completely on the affected platforms to enable safe concurrent IO. The setLastModified() call below while the file is still open is
         * generally ineffective as the OS will update the timestamp after closing the file (at least Windows does so). But its failure allows us to detect the problematic platforms. The destination
         * file not having the same timestamp as the source file isn't overly beauty but shouldn't actually matter in real life either.
         */
        if (IS_SET_LAST_MODIFIED_SAFE == null) {
          IS_SET_LAST_MODIFIED_SAFE = Boolean.valueOf(target.setLastModified(source.lastModified()));
          logger.debug("Updates of file modification timestamp are safe: " + IS_SET_LAST_MODIFIED_SAFE);
        }

        close(targetLock.getRandomAccessFile());

        if (IS_SET_LAST_MODIFIED_SAFE.booleanValue()) {
          target.setLastModified(source.lastModified());
        }

        // NOTE: Close the file handle to enable its deletion but don't release the lock yet.
        close(sourceLock.getRandomAccessFile());

        source.delete();
      }
    } finally {
      unlock(sourceLock);
      unlock(targetLock);
    }
  }  
}

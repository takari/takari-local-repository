package io.takari.filemanager;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * This lock object adds the ability to directly access the contents of the locked file.
 * 
 * @author Benjamin Hanzelmann
 */
public interface Lock {

  /**
   * Gets the random access file used to read/write the contents of the locked file.
   * 
   * @return The random access file used to read/write or {@code null} if the lock isn't acquired.
   * @throws IOException
   */
  RandomAccessFile getRandomAccessFile() throws IOException;

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
  void lock() throws IOException;

  /**
   * Unlock the file this Lock was obtained for.
   * 
   * @throws IOException if an error occurs while locking the file.
   */
  void unlock() throws IOException;

  /**
   * Get the file this Lock was obtained for.
   * 
   * @return The file this lock was obtained for, never {@code null}.
   */
  File getFile();
  
  // I added this to remove having to reference the concrete implementation in the test case
  FileLock getLock();
}
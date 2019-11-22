package io.takari.filemanager;

/*******************************************************************************
 * Copyright (c) 2010-2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import static org.junit.Assert.*;
import io.takari.filemanager.Lock;
import io.takari.filemanager.internal.DefaultFileManager;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Before;
import org.junit.Test;

import edu.umd.cs.mtc.MultithreadedTestCase;
import edu.umd.cs.mtc.TestFramework;

@SuppressWarnings("unused")
public class DefaultFileManagerTest {

  private DefaultFileManager manager;

  private Process process;

  private DefaultFileManager newManager() {
    return new DefaultFileManager();
  }

  @Before
  public void setup() throws IOException {
    manager = newManager();
  }

  @Test
  public void testExternalLockTryReadLock() throws InterruptedException, IOException {
    int wait = 1500;

    File file = TestFileUtils.createTempFile("");

    Lock lock = manager.readLock(file);

    ExternalProcessFileLock ext = new ExternalProcessFileLock(file);
    process = ext.lockFile(wait);

    long start = ext.awaitLock();

    lock.lock();

    long end = System.currentTimeMillis();

    lock.unlock();

    String message = "expected " + wait + "ms wait, real delta: " + (end - start);

    assertTrue(message, end - start > wait);
  }

  @Test
  public void testExternalLockTryWriteLock() throws InterruptedException, IOException {
    int wait = 1500;

    File file = TestFileUtils.createTempFile("");

    ExternalProcessFileLock ext = new ExternalProcessFileLock(file);
    process = ext.lockFile(wait);

    Lock lock = manager.writeLock(file);

    long start = ext.awaitLock();

    lock.lock();

    long end = System.currentTimeMillis();

    lock.unlock();

    String message = "expected " + wait + "ms wait, real delta: " + (end - start);
    assertTrue(message, end - start > wait);
  }

  @Test
  public void testUpgradeSharedToExclusiveLock() throws Throwable {
    final File file = TestFileUtils.createTempFile("");

    TestFramework.runOnce(new MultithreadedTestCase() {
      public void thread1() throws IOException {
        Lock lock = manager.readLock(file);
        lock.lock();
        assertTrue("read lock is not shared", lock.isShared());
        waitForTick(2);
        lock.unlock();
      }

      public void thread2() throws IOException {
        waitForTick(1);
        Lock lock = manager.writeLock(file);
        lock.lock();
        assertTick(2);
        assertTrue("read lock did not upgrade to exclusive", !lock.isShared());
        lock.unlock();
      }
    });
  }

  @Test
  public void testCanonicalFileLock() throws Exception {
    File file1 = TestFileUtils.createTempFile("testCanonicalFileLock");
    File file2 = new File(file1.getParent() + File.separator + ".", file1.getName());

    Lock lock1 = manager.readLock(file1);
    Lock lock2 = manager.readLock(file2);

    lock1.lock();
    lock2.lock();

    FileChannel channel1 = lock1.getRandomAccessFile().getChannel();
    FileChannel channel2 = lock2.getRandomAccessFile().getChannel();

    assertNotSame(channel1, channel2);
    assertSame(lock1.getLock(), lock2.getLock());

    lock1.unlock();
    assertNull(lock1.getRandomAccessFile());
    assertFalse(channel1.isOpen());

    assertTrue(lock2.getLock().isValid());
    assertNotNull(lock2.getRandomAccessFile());
    assertTrue(channel2.isOpen());

    lock2.unlock();
    assertNull(lock2.getRandomAccessFile());
    assertFalse(channel2.isOpen());
  }

  @Test
  public void testSafeUnlockOfNonAcquiredLock() throws IOException {
    File file = TestFileUtils.createTempFile("");

    Lock lock = manager.readLock(file);
    lock.unlock();
  }

  @Test
  public void testMultipleLocksSameThread() throws Throwable {
    final File a = TestFileUtils.createTempFile("a");
    final File b = TestFileUtils.createTempFile("b");

    TestFramework.runOnce(new MultithreadedTestCase() {
      private Lock r1;

      private Lock r2;

      private Lock w1;

      private Lock w2;

      public void thread1() throws IOException {
        r1 = manager.readLock(a);
        r2 = manager.readLock(a);
        w1 = manager.writeLock(b);
        w2 = manager.writeLock(b);
        try {

          r1.lock();
          r2.lock();
          w1.lock();
          w2.lock();

          assertSame(r1.getLock(), r2.getLock());
          assertEquals(true, r1.getLock().isValid());
          assertEquals(true, r2.getLock().isValid());

          assertSame(w1.getLock(), w2.getLock());
          assertEquals(true, w1.getLock().isValid());
          assertEquals(true, w2.getLock().isValid());

          r1.unlock();
          assertEquals(true, r2.getLock().isValid());
          r2.unlock();
          w1.unlock();
          assertEquals(true, w2.getLock().isValid());
          w2.unlock();
        } finally {
          if (w1 != null) {
            w1.unlock();
          }
          if (w2 != null) {
            w2.unlock();
          }
          if (r1 != null) {
            r1.unlock();
          }
          if (r2 != null) {
            r2.unlock();
          }
        }
      }

    });
  }

  @Test
  public void testSameThreadMultipleLocksReadRead() throws Exception {
    File file = TestFileUtils.createTempFile("");

    Lock lock1 = manager.readLock(file);
    Lock lock2 = manager.readLock(file);

    lock1.lock();
    try {
      lock2.lock();
      lock2.unlock();
    } finally {
      lock1.unlock();
    }
  }

  @Test
  public void testSameThreadMultipleLocksWriteWrite() throws Exception {
    File file = TestFileUtils.createTempFile("");

    Lock lock1 = manager.writeLock(file);
    Lock lock2 = manager.writeLock(file);

    lock1.lock();
    try {
      lock2.lock();
      lock2.unlock();
    } finally {
      lock1.unlock();
    }
  }

  @Test
  public void testSameThreadMultipleLocksWriteRead() throws Exception {
    File file = TestFileUtils.createTempFile("");

    Lock lock1 = manager.writeLock(file);
    Lock lock2 = manager.readLock(file);

    lock1.lock();
    try {
      lock2.lock();
      lock2.unlock();
    } finally {
      lock1.unlock();
    }
  }

  @Test
  public void testSameThreadMultipleLocksReadWrite() throws Exception {
    File file = TestFileUtils.createTempFile("");

    Lock lock1 = manager.readLock(file);
    Lock lock2 = manager.writeLock(file);

    lock1.lock();
    try {
      try {
        lock2.lock();
        try {
          lock2.unlock();
        } catch (IOException e) {
          // ignored
        }
      } catch (IllegalStateException e) {
        assertTrue(true);
      }
    } finally {
      lock1.unlock();
    }
  }

  @Test
  public void testReentrantLock() throws Exception {
    File file = TestFileUtils.createTempFile("");

    Lock lock = manager.readLock(file);
    lock.lock();
    assertTrue(lock.isShared());
    lock.lock();
    assertTrue(lock.isShared());
    lock.unlock();
    assertTrue(lock.isShared());
    assertNotNull(lock.getRandomAccessFile());
    lock.unlock();
    assertNull(lock.getRandomAccessFile());
  }

  @Test
  public void testAcquiredLockDoesNotPreventLockedFileToBeDeleted() throws Exception {
    File file = TestFileUtils.createTempFile("");

    Lock lock = manager.writeLock(file);
    lock.lock();
    try {
      assertTrue(file.exists());
      assertTrue(file.delete());
      assertFalse(file.exists());
    } finally {
      lock.unlock();
    }
  }

  @Test
  public void testWaitingForAlreadyLockedFileToBeReleasedMustOnlyBlockCurrentThread() throws Exception {
    final File file1 = TestFileUtils.createTempFile("file1");
    final File file2 = TestFileUtils.createTempFile("file2");

    // external process locks files in opposite order than our process, i.e. file2 first
    ExternalProcessFileLocks external = new ExternalProcessFileLocks(file2, file1);

    final AtomicReference<Exception> exception = new AtomicReference<Exception>();

    // this thread will block when attempting to lock file2 which will already be locked by the external process
    Thread thread = new Thread() {
      @Override
      public void run() {
        Lock lock2 = manager.writeLock(file2);
        try {
          lock2.lock();
          lock2.unlock();
        } catch (IOException e) {
          e.printStackTrace();
          exception.set(e);
        }
      }
    };

    Lock lock1 = manager.writeLock(file1);
    lock1.lock();
    try {
      external.lockFiles();
      external.awaitLock1();

      thread.start();

      // wait a little to allow the thread to block
      long start = System.currentTimeMillis();
      while (System.currentTimeMillis() - start < 1000) {
        try {
          Thread.sleep(200);
        } catch (Exception e) {
          // irrelevant
        }
      }

      // this must not block or the inter-process deadlock is perfect
      lock1.unlock();
    } finally {
      lock1.unlock();
    }
    assertNull("inner thread got IOException: " + String.valueOf(exception.get()), exception.get());
  }

  @Test
  public void testMultipleManagerInstancesShareTheSameLockTable() throws Exception {
    File file = TestFileUtils.createTempFile("");

    DefaultFileManager manager2 = newManager();

    Lock lock1 = manager.readLock(file);
    Lock lock2 = manager2.readLock(file);

    lock1.lock();
    try {
      lock2.lock();
      try {
        assertSame(lock1.getLock(), lock2.getLock());
      } finally {
        lock2.unlock();
      }
    } finally {
      lock1.unlock();
    }
  }

  @Test
  public void testCopiedFilesHaveTheSameLastModifiedTime() throws Exception {
    File source = TestFileUtils.createTempFile("bytes");
    source.setLastModified(source.lastModified() - 86400000); // -1 day
    File target = new File(TestFileUtils.createTempDir(), "target.txt");
    DefaultFileManager manager = newManager();
    manager.copy(source, target);
    assertEquals("We expect the length of the source and target to be equal.", source.length(), target.length());
    assertEquals("We expect the last modified time of the source and target to be equal.", source.lastModified(), target.lastModified());
  }
}

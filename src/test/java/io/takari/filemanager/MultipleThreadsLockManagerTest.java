package io.takari.filemanager;

/*******************************************************************************
 * Copyright (c) 2010-2013 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

import io.takari.filemanager.Lock;
import io.takari.filemanager.internal.DefaultFileManager;

import java.io.File;
import java.io.IOException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import edu.umd.cs.mtc.MultithreadedTestCase;
import edu.umd.cs.mtc.TestFramework;

@SuppressWarnings("unused")
public class MultipleThreadsLockManagerTest {
  private DefaultFileManager manager;

  private File dir;

  @Before
  public void setup() throws IOException {
    manager = new DefaultFileManager();
    dir = TestFileUtils.createTempDir(getClass().getSimpleName());
  }

  @After
  public void tearDown() throws Exception {
    if (dir != null) {
      TestFileUtils.delete(dir);
    }
    manager = null;
  }

  @Test
  public void testLockCanonicalFile() throws Throwable {
    final File a = new File(dir, "a/b");
    final File b = new File(dir, "a/./b");

    TestFramework.runOnce(new MultithreadedTestCase() {
      public void thread1() throws IOException {
        Lock lock = manager.writeLock(a);
        lock.lock();
        waitForTick(3);
        lock.unlock();
      }

      public void thread2() throws IOException {
        waitForTick(1);
        Lock lock = manager.writeLock(b);
        lock.lock();
        assertTick(3);
        lock.unlock();
      }
    });
  }

  @Test
  public void testWriteBlocksRead() throws Throwable {
    final File a = new File(dir, "a/b");
    final File b = new File(dir, "a/b");

    TestFramework.runOnce(new MultithreadedTestCase() {
      public void thread1() throws IOException {
        Lock lock = manager.writeLock(a);
        lock.lock();
        waitForTick(2);
        lock.unlock();
      }

      public void thread2() throws IOException {
        waitForTick(1);
        Lock lock = manager.readLock(b);
        lock.lock();
        assertTick(2);
        lock.unlock();
      }
    });
  }

  @Test
  public void testReadDoesNotBlockRead() throws Throwable {
    final File a = new File(dir, "a/b");
    final File b = new File(dir, "a/b");

    TestFramework.runOnce(new MultithreadedTestCase() {
      public void thread1() throws IOException {
        Lock lock = manager.readLock(a);
        lock.lock();
        waitForTick(2);
        lock.unlock();
      }

      public void thread2() throws IOException {
        waitForTick(1);
        Lock lock = manager.readLock(b);
        lock.lock();
        assertTick(1);
        lock.unlock();
      }
    });
  }

  @Test
  public void testReadBlocksWrite() throws Throwable {
    final File a = new File(dir, "a/b");
    final File b = new File(dir, "a/b");

    TestFramework.runOnce(new MultithreadedTestCase() {
      public void thread1() throws IOException {
        Lock lock = manager.readLock(a);
        lock.lock();
        waitForTick(2);
        lock.unlock();
      }

      public void thread2() throws IOException {
        waitForTick(1);
        Lock lock = manager.writeLock(b);
        lock.lock();
        assertTick(2);
        lock.unlock();
      }
    });
  }

  @Test
  public void testWriteBlocksWrite() throws Throwable {
    final File a = new File(dir, "a/b");
    final File b = new File(dir, "a/b");

    TestFramework.runOnce(new MultithreadedTestCase() {
      public void thread1() throws IOException {
        Lock lock = manager.writeLock(a);
        lock.lock();
        waitForTick(2);
        lock.unlock();
      }

      public void thread2() throws IOException {
        waitForTick(1);
        Lock lock = manager.writeLock(b);
        lock.lock();
        assertTick(2);
        lock.unlock();
      }
    });
  }

  @Test
  public void testNoPrematureLocking() throws Throwable {
    final File a = new File(dir, "a/b");

    TestFramework.runOnce(new MultithreadedTestCase() {
      public void thread1() throws IOException {
        Lock lock = manager.readLock(a);
        waitForTick(2);
      }

      public void thread2() throws IOException {
        waitForTick(1);
        Lock lock = manager.writeLock(a);
        lock.lock();
        assertTick(1);
        lock.unlock();
      }
    });
  }

}

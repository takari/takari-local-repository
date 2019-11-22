package io.takari.filemanager;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;

/**
 * @author Benjamin Hanzelmann
 */
public class ExternalProcessFileLock {

  private final File file;

  public static void main(String[] args) throws Exception {
    String path = args[0];
    String time = args[1];

    File file = new File(path + ".aetherlock");

    file.getParentFile().mkdirs();

    int millis = Integer.valueOf(time);

    RandomAccessFile raf = new RandomAccessFile(file, "rw");
    FileLock lock = raf.getChannel().lock();

    File touchFile = getTouchFile(path);
    touchFile.createNewFile();

    for (long start = System.currentTimeMillis(); System.currentTimeMillis() - start < 5 * 1000 && touchFile.exists();) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        // ignored
      }
    }

    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < millis) {
      Thread.sleep(millis / 10 + 1);
    }

    lock.release();
    raf.close();
  }

  public ExternalProcessFileLock(File file) {
    this.file = file;
  }

  private static File getTouchFile(String path) {
    return new File(path + ".touch");
  }

  public Process lockFile(int wait) throws InterruptedException, IOException {
    ForkJvm jvm = new ForkJvm();
    jvm.addClassPathEntry(getClass());
    jvm.setParameters(file.getAbsolutePath(), String.valueOf(wait));
    Process p = jvm.run(getClass().getName());
    p.getOutputStream().close();
    return p;
  }

  public long awaitLock() {
    File touchFile = getTouchFile(file.getAbsolutePath());

    for (long start = System.currentTimeMillis(); System.currentTimeMillis() - start < 10 * 1000;) {
      if (touchFile.exists()) {
        long now = System.currentTimeMillis();
        touchFile.delete();
        return now;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        // ignored
      }
    }

    throw new IllegalStateException("External lock on " + file + " wasn't aquired in time");
  }

}

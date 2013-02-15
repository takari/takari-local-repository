package io.tesla.aether.concurrency;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.aether.spi.io.FileProcessor.ProgressListener;

public class NullProgressListener implements ProgressListener {

  public void progressed(ByteBuffer buffer) throws IOException {
  }
}

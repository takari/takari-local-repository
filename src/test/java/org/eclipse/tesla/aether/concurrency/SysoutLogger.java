package org.eclipse.tesla.aether.concurrency;

import org.eclipse.aether.spi.log.Logger;

public class SysoutLogger implements Logger {

  public boolean isDebugEnabled() {
    return false;
  }

  public void debug(String msg) {
    System.out.println(msg);
  }

  public void debug(String msg, Throwable error) {
    System.out.println(msg);
    error.printStackTrace();
  }

  public boolean isWarnEnabled() {
    return false;
  }

  public void warn(String msg) {
    System.out.println(msg);
  }

  public void warn(String msg, Throwable error) {
    System.out.println(msg);
    error.printStackTrace();
  }
}

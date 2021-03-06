/*
 * topNET
 * Fast HTTP Server Solution.
 * Copyright 2016, Qubit Group <www.qubit.com>
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *  This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  
 * If not, see <https://www.gnu.org/licenses/lgpl-3.0.en.html>
 * 
 * Author: Peter Fronc <peter.fronc@qubitdigital.com>
 */
package com.qubit.topnet.waitonly;

import com.qubit.topnet.AbstractHandlingThread;
import com.qubit.topnet.DataHandler;
import static com.qubit.topnet.DataHandler.bodyReadyHandler;
import com.qubit.topnet.ServerBase;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Peter Fronc <peter.fronc@qubitdigital.com>
 */
public abstract class HandlingThread extends AbstractHandlingThread {

  public HandlingThread(ServerBase server) {
    super(server);
  }
  
  private static final Logger log
      = Logger.getLogger(HandlingThread.class.getName());
  private static int spinBeforeCount = 1000;
  
  public static void setSpinCountBeforeSleep(int value) {
    spinBeforeCount = value;
  }

  private long defaultMaxMessageSize;
  private int delayForNoIOReadsInSuite = 100 * 1000;
  private boolean running;
  protected volatile long jobsAdded = 0;
  protected volatile long jobsRemoved = 0;
  private final Object sleepingLocker = new Object();
  
  static volatile long handlingClosedIdleCounter = 0; // less more counter...
  
  abstract public boolean addJob(SocketChannel channel, Long acceptTime);
  abstract public boolean canAddJob();
  
  @Override
  public void run() {
    try {
      this.setRunning(true);
      while (this.isRunning()) {
        this.trySomeWork();
      }
      
      this.trySomeWork();
    } finally {
      this.server.removeThread(this);
    }
  }
  
  private void trySomeWork() {
    long count = spinBeforeCount;
    while (this.hasJobs()) {
      if (this.runSinglePass()) {
        if (count-- < 1) {
          this.waitForSomethingToIO();
        }
      } else {
        count = spinBeforeCount;
      }
    }
      this.sleepNow();
    }
  
  /**
   * Returns false if not finished writing or
 "this.finishedOrWaitForMoreRequests(...)" when finished. It tells if job can be
   * released.
   *
   * @param key
   * @param dataHandler
   * @return
   * @throws IOException
   */
  private int writeResponse(DataHandler dataHandler)
      throws IOException {
    int written = dataHandler.write();
    if (written < 0) {
      if (written == -1) {
        dataHandler.requestFinishedHandler();
        if (dataHandler.finishedOrWaitForMoreRequests(true)) {
          // finished
          return -1;
        } else {
          return 0; /// REGISTER KEY RATHER THAN THIS
        }
      }
    }
    return written;
  }

  /**
   *
   * @param dataHandler
   * @return true only if key should be released
   * @throws IOException
   */
  protected int processJob(DataHandler dataHandler)
      throws IOException {
    SocketChannel channel = dataHandler.getChannel();
    if (channel.isConnected()) {
      if (dataHandler.writingResponse) { // in progress of writing
        return this.writeResponse(dataHandler);
      } else {
        int many = dataHandler.read();
        if (many < 0) { // reading is over
          if (many == -2) {// finished reading correctly, otherwise many is -1
            // writingResponse will be unchecked by writeResponse(...)
            bodyReadyHandler(dataHandler);
            return this.writeResponse(dataHandler);
          } else {
            //if (many == -1)
            return -1;
          }
        }

        return many;
      }
    }
    
    return -1;
  }

  /**
   *
   * @param channel
   * @param dataHandler
   * @param finishedWriting
   * @return true if closed connection, false otherwise and will reset handler
   */
  /**
   * @return the defaultMaxMessageSize
   */
  public long getDefaultMaxMessageSize() {
    return defaultMaxMessageSize;
  }

  /**
   * @param defaultMaxMessageSize the defaultMaxMessageSize to set
   */
  public void setDefaultMaxMessageSize(long defaultMaxMessageSize) {
    this.defaultMaxMessageSize = defaultMaxMessageSize;
  }

  public abstract boolean hasJobs();

  public static volatile long totalWaitedIO = 0;

  protected boolean waitForSomethingToIO() {
    if (this.delayForNoIOReadsInSuite > 0) {// code is 0 if no IO occured
      totalWaitedIO++;
      takeSomeBreak(this.delayForNoIOReadsInSuite);
      return true;
    }
    return false;
  }

  protected void wakeup() {
    if (this.getState() == State.TIMED_WAITING ||
        this.getState() == State.WAITING) {
      synchronized (sleepingLocker) {
        sleepingLocker.notify();
      }
    }
  }

  private void sleepNow() {
    try {
      synchronized (sleepingLocker) {
        sleepingLocker.wait();
      }
    } catch (InterruptedException e) {}
  }

  private void takeSomeBreak(int delay) {
    try {
      if (delay < 1000000) {
        synchronized (sleepingLocker) {
          sleepingLocker.wait(0, delay);
        }
      } else {
        synchronized (sleepingLocker) {
          sleepingLocker.wait(delay/1000000);
        }
      }
    } catch (InterruptedException e) {}
  }
  
  protected boolean handleMaxIdle(DataHandler dataHandler, long maxIdle) {
    if (dataHandler.owningThread == null) return false;
    
    //check if connection is not open too long! Prevent DDoS
    long idle = dataHandler.getMaxIdle(maxIdle);
    if (idle != 0 && (System.currentTimeMillis() - dataHandler.getTouch()) > idle) {
      if (this.server.getLimitsHandler() != null) {
        return this.server.getLimitsHandler().handleTimeout(null, idle, dataHandler);
      } else {
        handlingClosedIdleCounter++;
        return true;
      }
    }

    // check if not too large
    long maxSize = dataHandler
        .getMaxMessageSize(getDefaultMaxMessageSize());

    if (maxSize != -1 && dataHandler.getSize() >= maxSize) {
      if (this.server.getLimitsHandler() != null) {
        return this.server.getLimitsHandler()
            .handleSizeLimit(null, idle, dataHandler);
      } else {
        log.log(Level.INFO, "Max size reached - closing: {0}",
            dataHandler.getSize());
        return true;
      }
    }

    return false;
  }
  
  /**
   * Tells if there was should wait for IO.
   *
   * @return
   */
  protected abstract boolean runSinglePass();

  /**
   * @return the delayForNoIOReadsInSuite
   */
  public int getDelayForNoIO() {
    return delayForNoIOReadsInSuite;
  }

  /**
   * @param delayForNoIO the delayForNoIOReadsInSuite to set
   */
  public void setDelayForNoIO(int delayForNoIO) {
    this.delayForNoIOReadsInSuite = delayForNoIO;
  }

  /**
   * @return the running
   */
  public boolean isRunning() {
    return running;
  }

  /**
   * @param started the running to set
   */
  public synchronized void setRunning(boolean started) {
    this.running = started;
  }
  
  /**
   * @return the jobsAdded
   */
  @Override
  public long getJobsAdded() {
    return jobsAdded;
  }

  /**
   * @return the jobsRemoved
   */
  @Override
  public long getJobsRemoved() {
    return jobsRemoved;
  }
  
  public long jobsLeft() {
    if (jobsAdded < 0) {
      if (jobsRemoved > 0) {
        return (Long.MAX_VALUE - jobsRemoved) + (jobsAdded - Long.MIN_VALUE);
      } else {
        return jobsAdded - jobsRemoved;
      }
    } else {
      return jobsAdded - jobsRemoved;
    }
  }
  
  public abstract int getLimit();
}

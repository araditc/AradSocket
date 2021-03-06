package com.araditc.aradsocket.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.araditc.aradsocket.*;
import com.araditc.aradsocket.util.Util;

public class AradSocketImpl implements ByteChannel {

  private static final Logger logger = LoggerFactory.getLogger(AradSocketImpl.class);

  public static final int buffersInitialSize = 4096;

  /** Official TLS max data size is 2^14 = 16k. Use 1024 more to account for the overhead */
  public static final int maxTlsPacketSize = 17 * 1024;

  /** Used to signal EOF conditions from the underlying channel */
  public static class EofException extends Exception {
    private static final long serialVersionUID = -3859156713994602991L;

    /** For efficiency, override this method to do nothing. */
    @Override
    public Throwable fillInStackTrace() {
      return this;
    }
  }

  private final ReadableByteChannel readChannel;
  private final WritableByteChannel writeChannel;
  private final SSLEngine engine;
  private BufferHolder inEncrypted;
  private final Consumer<SSLSession> initSessionCallback;

  private final boolean runTasks;
  private final TrackingAllocator encryptedBufAllocator;
  private final TrackingAllocator plainBufAllocator;
  private final boolean waitForCloseConfirmation;

  // @formatter:off
  public AradSocketImpl(
      ReadableByteChannel readChannel,
      WritableByteChannel writeChannel,
      SSLEngine engine,
      Optional<BufferHolder> inEncrypted,
      Consumer<SSLSession> initSessionCallback,
      boolean runTasks,
      TrackingAllocator plainBufAllocator,
      TrackingAllocator encryptedBufAllocator,
      boolean releaseBuffers,
      boolean waitForCloseConfirmation) {
    // @formatter:on
    this.readChannel = readChannel;
    this.writeChannel = writeChannel;
    this.engine = engine;
    this.inEncrypted =
        inEncrypted.orElseGet(
            () ->
                new BufferHolder(
                    "inEncrypted",
                    Optional.empty(),
                    encryptedBufAllocator,
                    buffersInitialSize,
                    maxTlsPacketSize,
                    false /* plainData */,
                    releaseBuffers));
    this.initSessionCallback = initSessionCallback;
    this.runTasks = runTasks;
    this.plainBufAllocator = plainBufAllocator;
    this.encryptedBufAllocator = encryptedBufAllocator;
    this.waitForCloseConfirmation = waitForCloseConfirmation;
    inPlain =
        new BufferHolder(
            "inPlain",
            Optional.empty(),
            plainBufAllocator,
            buffersInitialSize,
            maxTlsPacketSize,
            true /* plainData */,
            releaseBuffers);
    outEncrypted =
        new BufferHolder(
            "outEncrypted",
            Optional.empty(),
            encryptedBufAllocator,
            buffersInitialSize,
            maxTlsPacketSize,
            false /* plainData */,
            releaseBuffers);
  }

  private final Lock initLock = new ReentrantLock();
  private final Lock readLock = new ReentrantLock();
  private final Lock writeLock = new ReentrantLock();

  private volatile boolean negotiated = false;

  /**
   * Whether a IOException was received from the underlying channel or from the {@link SSLEngine}.
   */
  private volatile boolean invalid = false;

  /** Whether a close_notify was already sent. */
  private volatile boolean shutdownSent = false;

  /** Whether a close_notify was already received. */
  private volatile boolean shutdownReceived = false;

  /** Decrypted data from inEncrypted */
  private BufferHolder inPlain;

  /** Contains data encrypted to send to the underlying channel */
  private BufferHolder outEncrypted;

  /**
   * Reference to the current read buffer supplied by the client this field is only valid during a
   * read operation. This field is used instead of {@link #inPlain} in order to avoid copying
   * returned bytes when possible.
   */
  private ByteBufferSet suppliedInPlain;

  /** Bytes produced by the current read operation */
  private int bytesToReturn;

  /**
   * Handshake wrap() method calls need a buffer to read from, even when they actually do not read
   * anything.
   *
   * <p>Note: standard SSLEngine is happy with no buffers, the empty buffer is here to make this
   * work with Netty's OpenSSL's wrapper.
   */
  private final ByteBufferSet dummyOut =
      new ByteBufferSet(new ByteBuffer[] {ByteBuffer.allocate(0)});

  public Consumer<SSLSession> getSessionInitCallback() {
    return initSessionCallback;
  }

  public TrackingAllocator getPlainBufferAllocator() {
    return plainBufAllocator;
  }

  public TrackingAllocator getEncryptedBufferAllocator() {
    return encryptedBufAllocator;
  }

  // read

  public long read(ByteBufferSet dest) throws IOException, NeedsTaskException {
    checkReadBuffer(dest);
    if (!dest.hasRemaining()) return 0;
    handshake();
    readLock.lock();
    try {
      if (invalid || shutdownSent) {
        throw new ClosedChannelException();
      }

      long originalDestPosition = dest.position();
      suppliedInPlain = dest;
      bytesToReturn = inPlain.nullOrEmpty() ? 0 : inPlain.buffer.position();

      while (true) {

        // return bytes are soon as we have them
        if (bytesToReturn > 0) {
          if (inPlain.nullOrEmpty()) {
            // if there is not in internal buffer, that means that the bytes must be in the supplied
            // buffer
            Util.assertTrue(dest.position() == originalDestPosition + bytesToReturn);
            return bytesToReturn;
          } else {
            Util.assertTrue(inPlain.buffer.position() == bytesToReturn);
            return transferPendingPlain(dest);
          }
        }

        if (shutdownReceived) {
          return -1;
        }
        Util.assertTrue(inPlain.nullOrEmpty());
        switch (engine.getHandshakeStatus()) {
          case NEED_UNWRAP:
          case NEED_WRAP:
            writeAndHandshake();
            break;
          case NOT_HANDSHAKING:
          case FINISHED:
            readAndUnwrap();
            if (shutdownReceived) {
              return -1;
            }
            break;
          case NEED_TASK:
            handleTask();
            break;
          default:
            // Unsupported stage eg: NEED_UNWRAP_AGAIN
            return -1;
        }
      }
    } catch (EofException e) {
      return -1;
    } finally {
      bytesToReturn = 0;
      suppliedInPlain = null;
      readLock.unlock();
    }
  }

  private void handleTask() throws NeedsTaskException {
    if (runTasks) {
      engine.getDelegatedTask().run();
    } else {
      throw new NeedsTaskException(engine.getDelegatedTask());
    }
  }

  /** Copies bytes from the internal input plain buffer to the supplied buffer. */
  private int transferPendingPlain(ByteBufferSet dstBuffers) {
    inPlain.buffer.flip(); // will read
    int bytes = dstBuffers.putRemaining(inPlain.buffer);
    inPlain.buffer.compact(); // will write
    boolean disposed = inPlain.release();
    if (!disposed) {
      inPlain.zeroRemaining();
    }
    return bytes;
  }

  private void unwrapLoop(HandshakeStatus originalStatus) throws SSLException {

    ByteBufferSet effDest;
    if (suppliedInPlain != null) {
      effDest = suppliedInPlain;
    } else {
      inPlain.prepare();
      effDest = new ByteBufferSet(inPlain.buffer);
    }

    while (true) {
      Util.assertTrue(inPlain.nullOrEmpty());
      SSLEngineResult result = callEngineUnwrap(effDest);
      /*
       * Note that data can be returned even in case of overflow, in that
       * case, just return the data.
       */
      if (result.bytesProduced() > 0
          || result.getStatus() == Status.BUFFER_UNDERFLOW
          || result.getStatus() == Status.CLOSED
          || result.getHandshakeStatus() != originalStatus) {
        bytesToReturn = result.bytesProduced();
        if (result.getStatus() == Status.CLOSED) {
          shutdownReceived = true;
        }
        return;
      }
      if (result.getStatus() == Status.BUFFER_OVERFLOW) {
        if (suppliedInPlain != null && effDest == suppliedInPlain) {
          /*
           * The client-supplier buffer is not big enough. Use the
           * internal inPlain buffer, also ensure that it is bigger
           * than the too-small supplied one.
           */
          inPlain.prepare();
          ensureInPlainCapacity(
              Math.min(((int) suppliedInPlain.remaining()) * 2, maxTlsPacketSize));
        } else {
          inPlain.enlarge();
        }
        // inPlain changed, re-create the wrapper
        effDest = new ByteBufferSet(inPlain.buffer);
      }
    }
  }

  private SSLEngineResult callEngineUnwrap(ByteBufferSet dest) throws SSLException {
    inEncrypted.buffer.flip();
    try {
      SSLEngineResult result =
          engine.unwrap(inEncrypted.buffer, dest.array, dest.offset, dest.length);
      if (logger.isTraceEnabled()) {
        logger.trace(
            "engine.unwrap() result [{}]. Engine status: {}; inEncrypted {}; inPlain: {}",
            Util.resultToString(result),
            //result.getHandshakeStatus(),
            //inEncrypted,
            dest);
      }
      return result;
    } catch (SSLException e) {
      // something bad was received from the underlying channel, we cannot
      // continue
      invalid = true;
      throw e;
    } finally {
      inEncrypted.buffer.compact();
    }
  }

  private int readFromChannel() throws IOException, EofException {
    try {
      return readFromChannel(readChannel, inEncrypted.buffer);
    } catch (WouldBlockException e) {
      throw e;
    } catch (IOException e) {
      invalid = true;
      throw e;
    }
  }

  public static int readFromChannel(ReadableByteChannel readChannel, ByteBuffer buffer)
      throws IOException, EofException {
    Util.assertTrue(buffer.hasRemaining());
    logger.trace("Reading from channel");
    int c = readChannel.read(buffer); // IO block
    logger.trace("Read from channel; response: {}, buffer: {}", c, buffer);
    if (c == -1) {
      throw new EofException();
    }
    if (c == 0) {
      throw new NeedsReadException();
    }
    return c;
  }

  // write

  public long write(ByteBufferSet source) throws IOException {
    /*
     * Note that we should enter the write loop even in the case that the source buffer has no remaining bytes,
     * as it could be the case, in non-blocking usage, that the user is forced to call write again after the
     * underlying channel is available for writing, just to write pending encrypted bytes.
     */
    handshake();
    writeLock.lock();
    try {
      if (invalid || shutdownSent) {
        throw new ClosedChannelException();
      }
      return wrapAndWrite(source);
    } finally {
      writeLock.unlock();
    }
  }

  private long wrapAndWrite(ByteBufferSet source) throws IOException {
    long bytesToConsume = source.remaining();
    outEncrypted.prepare();
    try {
      while (true) {
        writeToChannel();
        if (source.remaining() == 0) {
          return bytesToConsume;
        }
        wrapLoop(source);
      }
    } finally {
      outEncrypted.release();
    }
  }

  /** Returns last {@link HandshakeStatus} of the loop */
  private void wrapLoop(ByteBufferSet source) throws SSLException {
    while (true) {
      SSLEngineResult result = callEngineWrap(source);
      switch (result.getStatus()) {
        case OK:
        case CLOSED:
          return;
        case BUFFER_OVERFLOW:
          Util.assertTrue(result.bytesConsumed() == 0);
          outEncrypted.enlarge();
          break;
        case BUFFER_UNDERFLOW:
          throw new IllegalStateException();
      }
    }
  }

  private SSLEngineResult callEngineWrap(ByteBufferSet source) throws SSLException {
    try {
      SSLEngineResult result =
          engine.wrap(source.array, source.offset, source.length, outEncrypted.buffer);
      if (logger.isTraceEnabled()) {
        logger.trace(
            "engine.wrap() result: [{}]; engine status: {}; srcBuffer: {}, outEncrypted: {}",
            //Util.resultToString(result),
            //result.getHandshakeStatus(),
            source,
            outEncrypted);
      }
      return result;
    } catch (SSLException e) {
      invalid = true;
      throw e;
    }
  }

  private void ensureInPlainCapacity(int newCapacity) {
    if (inPlain.buffer.capacity() < newCapacity) {
      logger.trace(
          "inPlain buffer too small, increasing from {} to {}",
          inPlain.buffer.capacity(),
          newCapacity);
      inPlain.resize(newCapacity);
    }
  }

  private void writeToChannel() throws IOException {
    if (outEncrypted.buffer.position() == 0) {
      return;
    }
    outEncrypted.buffer.flip();
    try {
      try {
        writeToChannel(writeChannel, outEncrypted.buffer);
      } catch (WouldBlockException e) {
        throw e;
      } catch (IOException e) {
        invalid = true;
        throw e;
      }
    } finally {
      outEncrypted.buffer.compact();
    }
  }

  private static void writeToChannel(WritableByteChannel channel, ByteBuffer src)
      throws IOException {
    while (src.hasRemaining()) {
      logger.trace("Writing to channel: {}", src);
      int c = channel.write(src);
      if (c == 0) {
        /*
         * If no bytesProduced were written, it means that the socket is
         * non-blocking and needs more buffer space, so stop the loop
         */
        throw new NeedsWriteException();
      }
      // blocking SocketChannels can write less than all the bytesProduced
      // just before an error the loop forces the exception
    }
  }

  // handshake and close

  /**
   * Force a new negotiation.
   *
   * @throws IOException if the underlying channel throws an IOException
   */
  public void renegotiate() throws IOException {
    /*
     * Renegotiation was removed in TLS 1.3. We have to do the check at this level because SSLEngine will not
     * check that, and just enter into undefined behavior.
     */
    // relying in hopefully-robust lexicographic ordering of protocol names
    if (engine.getSession().getProtocol().compareTo("TLSv1.3") >= 0) {
      throw new SSLException("renegotiation not supported in TLS 1.3 or latter");
    }
    try {
      doHandshake(true /* force */);
    } catch (EofException e) {
      throw new ClosedChannelException();
    }
  }

  /**
   * Do a negotiation if this connection is new and it hasn't been done already.
   *
   * @throws IOException if the underlying channel throws an IOException
   */
  public void handshake() throws IOException {
    try {
      doHandshake(false /* force */);
    } catch (EofException e) {
      throw new ClosedChannelException();
    }
  }

  private void doHandshake(boolean force) throws IOException, EofException {
    if (!force && negotiated) return;
    initLock.lock();
    try {
      if (invalid || shutdownSent) throw new ClosedChannelException();
      if (force || !negotiated) {
        engine.beginHandshake();
        logger.trace("Called engine.beginHandshake()");
        writeAndHandshake();

        if (engine.getSession().getProtocol().startsWith("DTLS")) {
          throw new IllegalArgumentException("DTLS not supported");
        }

        // call client code
        try {
          initSessionCallback.accept(engine.getSession());
        } catch (Exception e) {
          logger.trace("client code threw exception in session initialization callback", e);
          throw new AradSocketCallbackException("session initialization callback failed", e);
        }
        negotiated = true;
      }
    } finally {
      initLock.unlock();
    }
  }

  private int writeAndHandshake() throws IOException, EofException {
    readLock.lock();
    try {
      writeLock.lock();
      try {
        Util.assertTrue(inPlain.nullOrEmpty());
        outEncrypted.prepare();
        try {
          writeToChannel(); // IO block
          return handshakeLoop();
        } finally {
          outEncrypted.release();
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      readLock.unlock();
    }
  }

  private int handshakeLoop() throws IOException, EofException {
    Util.assertTrue(inPlain.nullOrEmpty());
    while (true) {
      switch (engine.getHandshakeStatus()) {
        case NEED_WRAP:
          Util.assertTrue(outEncrypted.nullOrEmpty());
          wrapLoop(dummyOut);
          writeToChannel(); // IO block
          break;
        case NEED_UNWRAP:
          readAndUnwrap();
          if (bytesToReturn > 0) {
            return bytesToReturn;
          }
          break;
        case NOT_HANDSHAKING:
          /*
           * This should not really happen using SSLEngine, because
           * handshaking ends with a FINISHED status. However, we accept
           * this value to permit the use of a pass-through stub engine
           * with no encryption.
           */
          return 0;
        case NEED_TASK:
          handleTask();
          break;
        case FINISHED:
          return 0;
        default:
          // Unsupported stage eg: NEED_UNWRAP_AGAIN
          return 0;
      }
    }
  }

  private void readAndUnwrap() throws IOException, EofException {
    // Save status before operation: use it to stop when status changes
    HandshakeStatus orig = engine.getHandshakeStatus();
    inEncrypted.prepare();
    try {
      while (true) {
        Util.assertTrue(inPlain.nullOrEmpty());
        unwrapLoop(orig);
        if (bytesToReturn > 0 || engine.getHandshakeStatus() != orig || shutdownReceived) {
          return;
        }
        if (!inEncrypted.buffer.hasRemaining()) {
          inEncrypted.enlarge();
        }
        readFromChannel(); // IO block
      }
    } finally {
      inEncrypted.release();
    }
  }

  public void close() throws IOException {
    tryShutdown();
    writeChannel.close();
    readChannel.close();
    /*
     * After closing the underlying channels, locks should be taken fast.
     */
    readLock.lock();
    try {
      writeLock.lock();
      try {
        freeBuffers();
      } finally {
        writeLock.unlock();
      }
    } finally {
      readLock.unlock();
    }
  }

  private void tryShutdown() {
    if (!readLock.tryLock()) return;
    try {
      if (!writeLock.tryLock()) return;
      try {
        if (!shutdownSent) {
          try {
            boolean closed = shutdown();
            if (!closed && waitForCloseConfirmation) {
              shutdown();
            }
          } catch (Throwable e) {
            logger.debug("error doing TLS shutdown on close(), continuing: {}", e.getMessage());
          }
        }
      } finally {
        writeLock.unlock();
      }
    } finally {
      readLock.unlock();
    }
  }

  public boolean shutdown() throws IOException {
    readLock.lock();
    try {
      writeLock.lock();
      try {
        if (invalid) {
          throw new ClosedChannelException();
        }
        if (!shutdownSent) {
          shutdownSent = true;
          outEncrypted.prepare();
          try {
            writeToChannel(); // IO block
            engine.closeOutbound();
            wrapLoop(dummyOut);
            writeToChannel(); // IO block
          } finally {
            outEncrypted.release();
          }
          /*
           * If this side is the first to send close_notify, then,
           * inbound is not done and false should be returned (so the
           * client waits for the response). If this side is the
           * second, then inbound was already done, and we can return
           * true.
           */
          if (shutdownReceived) {
            freeBuffers();
          }
          return shutdownReceived;
        }
        /*
         * If we reach this point, then we just have to read the close
         * notification from the client. Only try to do it if necessary,
         * to make this method idempotent.
         */
        if (!shutdownReceived) {
          try {
            // IO block
            readAndUnwrap();
            Util.assertTrue(shutdownReceived);
          } catch (EofException e) {
            throw new ClosedChannelException();
          }
        }
        freeBuffers();
        return true;
      } finally {
        writeLock.unlock();
      }
    } finally {
      readLock.unlock();
    }
  }

  private void freeBuffers() {
    if (inEncrypted != null) {
      inEncrypted.dispose();
      inEncrypted = null;
    }
    if (inPlain != null) {
      inPlain.dispose();
      inPlain = null;
    }
    if (outEncrypted != null) {
      outEncrypted.dispose();
      outEncrypted = null;
    }
  }

  public boolean isOpen() {
    return !invalid && writeChannel.isOpen() && readChannel.isOpen();
  }

  public static void checkReadBuffer(ByteBufferSet dest) {
    if (dest.isReadOnly()) throw new IllegalArgumentException();
  }

  public SSLEngine engine() {
    return engine;
  }

  public boolean getRunTasks() {
    return runTasks;
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    return (int) read(new ByteBufferSet(dst));
  }

  @Override
  public int write(ByteBuffer src) throws IOException {
    return (int) write(new ByteBufferSet(src));
  }

  public boolean shutdownReceived() {
    return shutdownReceived;
  }

  public boolean shutdownSent() {
    return shutdownSent;
  }

  public ReadableByteChannel plainReadableChannel() {
    return readChannel;
  }

  public WritableByteChannel plainWritableChannel() {
    return writeChannel;
  }
}

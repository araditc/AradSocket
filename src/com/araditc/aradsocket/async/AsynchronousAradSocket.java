package com.araditc.aradsocket.async;

import static com.araditc.aradsocket.async.AsynchronousAradSocketGroup.ReadOperation;
import static com.araditc.aradsocket.async.AsynchronousAradSocketGroup.WriteOperation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.araditc.aradsocket.AradSocket;
import com.araditc.aradsocket.async.AsynchronousAradSocketGroup.RegisteredSocket;
import com.araditc.aradsocket.impl.ByteBufferSet;

/** An {@link AsynchronousByteChannel} that works using {@link AradSocket}s. */
public class AsynchronousAradSocket implements ExtendedAsynchronousByteChannel {

  private class FutureReadResult extends CompletableFuture<Integer> {
    ReadOperation op;

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      super.cancel(mayInterruptIfRunning);
      return group.doCancelRead(registeredSocket, op);
    }
  }

  private class FutureWriteResult extends CompletableFuture<Integer> {
    WriteOperation op;

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      super.cancel(mayInterruptIfRunning);
      return group.doCancelWrite(registeredSocket, op);
    }
  }

  private final AsynchronousAradSocketGroup group;
  private final AradSocket aradSocket;
  private final RegisteredSocket registeredSocket;

  /**
   * Initializes a new instance of this class.
   *
   * @param channelGroup group to associate new new channel to
   * @param aradSocket existing Arad Socket to be used asynchronously
   * @param socketChannel underlying socket
   * @throws ClosedChannelException if any of the underlying channels are closed.
   * @throws IllegalArgumentException is the socket is in blocking mode
   */
  public AsynchronousAradSocket(
      AsynchronousAradSocketGroup channelGroup, AradSocket aradSocket, SocketChannel socketChannel)
      throws ClosedChannelException, IllegalArgumentException {
    if (!aradSocket.isOpen() || !socketChannel.isOpen()) {
      throw new ClosedChannelException();
    }
    if (socketChannel.isBlocking()) {
      throw new IllegalArgumentException("socket channel must be in non-blocking mode");
    }
    this.group = channelGroup;
    this.aradSocket = aradSocket;
    this.registeredSocket = channelGroup.registerSocket(aradSocket, socketChannel);
  }

  @Override
  public <A> void read(ByteBuffer dst, A attach, CompletionHandler<Integer, ? super A> handler) {
    checkReadOnly(dst);
    if (!dst.hasRemaining()) {
      completeWithZeroInt(attach, handler);
      return;
    }
    group.startRead(
        registeredSocket,
        new ByteBufferSet(dst),
        0,
        TimeUnit.MILLISECONDS,
        c -> group.executor.submit(() -> handler.completed((int) c, attach)),
        e -> group.executor.submit(() -> handler.failed(e, attach)));
  }

  @Override
  public <A> void read(
      ByteBuffer dst,
      long timeout,
      TimeUnit unit,
      A attach,
      CompletionHandler<Integer, ? super A> handler) {
    checkReadOnly(dst);
    if (!dst.hasRemaining()) {
      completeWithZeroInt(attach, handler);
      return;
    }
    group.startRead(
        registeredSocket,
        new ByteBufferSet(dst),
        timeout,
        unit,
        c -> group.executor.submit(() -> handler.completed((int) c, attach)),
        e -> group.executor.submit(() -> handler.failed(e, attach)));
  }

  @Override
  public <A> void read(
      ByteBuffer[] dsts,
      int offset,
      int length,
      long timeout,
      TimeUnit unit,
      A attach,
      CompletionHandler<Long, ? super A> handler) {
    ByteBufferSet bufferSet = new ByteBufferSet(dsts, offset, length);
    if (bufferSet.isReadOnly()) {
      throw new IllegalArgumentException("buffer is read-only");
    }
    if (!bufferSet.hasRemaining()) {
      completeWithZeroLong(attach, handler);
      return;
    }
    group.startRead(
        registeredSocket,
        bufferSet,
        timeout,
        unit,
        c -> group.executor.submit(() -> handler.completed(c, attach)),
        e -> group.executor.submit(() -> handler.failed(e, attach)));
  }

  @Override
  public Future<Integer> read(ByteBuffer dst) {
    checkReadOnly(dst);
    if (!dst.hasRemaining()) {
      return CompletableFuture.completedFuture(0);
    }
    FutureReadResult future = new FutureReadResult();
    ReadOperation op =
        group.startRead(
            registeredSocket,
            new ByteBufferSet(dst),
            0,
            TimeUnit.MILLISECONDS,
            c -> future.complete((int) c),
            future::completeExceptionally);
    future.op = op;
    return future;
  }

  private void checkReadOnly(ByteBuffer dst) {
    if (dst.isReadOnly()) {
      throw new IllegalArgumentException("buffer is read-only");
    }
  }

  @Override
  public <A> void write(ByteBuffer src, A attach, CompletionHandler<Integer, ? super A> handler) {
    if (!src.hasRemaining()) {
      completeWithZeroInt(attach, handler);
      return;
    }
    group.startWrite(
        registeredSocket,
        new ByteBufferSet(src),
        0,
        TimeUnit.MILLISECONDS,
        c -> group.executor.submit(() -> handler.completed((int) c, attach)),
        e -> group.executor.submit(() -> handler.failed(e, attach)));
  }

  @Override
  public <A> void write(
      ByteBuffer src,
      long timeout,
      TimeUnit unit,
      A attach,
      CompletionHandler<Integer, ? super A> handler) {
    if (!src.hasRemaining()) {
      completeWithZeroInt(attach, handler);
      return;
    }
    group.startWrite(
        registeredSocket,
        new ByteBufferSet(src),
        timeout,
        unit,
        c -> group.executor.submit(() -> handler.completed((int) c, attach)),
        e -> group.executor.submit(() -> handler.failed(e, attach)));
  }

  @Override
  public <A> void write(
      ByteBuffer[] srcs,
      int offset,
      int length,
      long timeout,
      TimeUnit unit,
      A attach,
      CompletionHandler<Long, ? super A> handler) {
    ByteBufferSet bufferSet = new ByteBufferSet(srcs, offset, length);
    if (!bufferSet.hasRemaining()) {
      completeWithZeroLong(attach, handler);
      return;
    }
    group.startWrite(
        registeredSocket,
        bufferSet,
        timeout,
        unit,
        c -> group.executor.submit(() -> handler.completed(c, attach)),
        e -> group.executor.submit(() -> handler.failed(e, attach)));
  }

  @Override
  public Future<Integer> write(ByteBuffer src) {
    if (!src.hasRemaining()) {
      return CompletableFuture.completedFuture(0);
    }
    FutureWriteResult future = new FutureWriteResult();
    WriteOperation op =
        group.startWrite(
            registeredSocket,
            new ByteBufferSet(src),
            0,
            TimeUnit.MILLISECONDS,
            c -> future.complete((int) c),
            future::completeExceptionally);
    future.op = op;
    return future;
  }

  private <A> void completeWithZeroInt(A attach, CompletionHandler<Integer, ? super A> handler) {
    group.executor.submit(() -> handler.completed(0, attach));
  }

  private <A> void completeWithZeroLong(A attach, CompletionHandler<Long, ? super A> handler) {
    group.executor.submit(() -> handler.completed(0L, attach));
  }

  /**
   * Tells whether or not this channel is open.
   *
   * @return <code>true</code> if, and only if, this channel is open
   */
  @Override
  public boolean isOpen() {
    return aradSocket.isOpen();
  }

  /**
   * Closes this channel.
   *
   * <p>This method will close the underlying {@link AradSocket} and also deregister it from its
   * group.
   *
   * @throws IOException If an I/O error occurs
   */
  @Override
  public void close() throws IOException {
    aradSocket.close();
    registeredSocket.close();
  }
}

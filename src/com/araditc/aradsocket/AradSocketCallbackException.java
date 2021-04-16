package com.araditc.aradsocket;

import javax.net.ssl.SSLException;

/**
 * Thrown during {@link AradSocket} handshake to indicate that a user-supplied function threw an
 * exception.
 */
public class AradSocketCallbackException extends SSLException {
  private static final long serialVersionUID = 8491908031320425318L;

  public AradSocketCallbackException(String message, Throwable throwable) {
    super(message, throwable);
  }
}

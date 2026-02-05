package com.android.adblib

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException

/**
 * Abstraction over a communication channel that can send and receive data over distinct steams, i.e. a
 * [java.nio.channels.AsynchronousSocketChannel] or [java.nio.channels.SocketChannel]
 */
interface AdbChannel : AdbInputChannel, AdbOutputChannel, AutoCloseable {

  /**
   * Shutdown the channel for reading, so that the peer receives EOF when writing.
   *
   * See [java.nio.channels.AsynchronousSocketChannel.shutdownInput]
   */
  suspend fun shutdownInput()

  /**
   * Shutdown the channel for writing, so that the peer receives EOF when reading.
   *
   * See [java.nio.channels.AsynchronousSocketChannel.shutdownOutput]
   */
  suspend fun shutdownOutput()
}

/** An [AdbChannel] that wraps a connected client socket */
interface AdbSocketChannel : AdbChannel {

  /**
   * Returns the socket address that this channel's socket is bound to. This property will throw after channel is closed.
   *
   * @throws ClosedChannelException – If the channel is closed
   * @throws IOException – If an I/ O error occurs
   */
  val localAddress: InetSocketAddress

  /**
   * Returns the remote address to which this channel's socket is connected. This property will throw after channel is closed.
   *
   * @throws ClosedChannelException – If the channel is closed
   * @throws IOException – If an I/ O error occurs
   */
  val remoteAddress: InetSocketAddress
}

/*
 * Copyright 2015-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rsocket.kotlin.transport.netty.tcp

import io.netty.buffer.*
import io.netty.channel.*
import io.netty.channel.socket.*
import io.rsocket.kotlin.internal.io.*
import io.rsocket.kotlin.transport.*
import io.rsocket.kotlin.transport.internal.*
import io.rsocket.kotlin.transport.netty.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.channels.Channel
import kotlinx.io.*
import io.netty.channel.socket.DuplexChannel as NettyDuplexChannel

@RSocketTransportApi
internal class NettyTcpConnectionHandler(
    private val channel: NettyDuplexChannel,
    private val handler: RSocketConnectionHandler,
    scope: CoroutineScope,
) : ChannelInboundHandlerAdapter() {
    private val inbound = bufferChannel(Channel.UNLIMITED)

    private val handlerJob = scope.launch(start = CoroutineStart.LAZY) {
        val outboundQueue = PrioritizationFrameQueue(Channel.BUFFERED)

        val writerJob = launch {
            try {
                while (true) {
                    // we write all available frames here, and only after it flush
                    // in this case, if there are several buffered frames we can send them in one go
                    // avoiding unnecessary flushes
                    // TODO: could be optimized to avoid allocation of not-needed promises
                    var lastWriteFuture = channel.write(outboundQueue.dequeueFrame()?.toByteBuf(channel.alloc()) ?: break)
                    while (true) lastWriteFuture = channel.write(outboundQueue.tryDequeueFrame()?.toByteBuf(channel.alloc()) ?: break)
                    channel.flush()
                    // await writing to respect transport backpressure
                    lastWriteFuture.awaitFuture()
                }
            } finally {
                withContext(NonCancellable) {
                    channel.shutdownOutput().awaitFuture()
                }
            }
        }.onCompletion { outboundQueue.cancel() }

        try {
            handler.handleConnection(NettyTcpConnection(outboundQueue, inbound))
        } finally {
            outboundQueue.close() // will cause `writerJob` completion
            // no more reading
            inbound.cancel()
            withContext(NonCancellable) {
                writerJob.join()
                channel.close().awaitFuture()
            }
        }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        handlerJob.start()
        ctx.pipeline().addLast("rsocket-inbound", NettyTcpConnectionInboundHandler(inbound))

        ctx.fireChannelActive()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        handlerJob.cancel("Channel is not active")

        ctx.fireChannelInactive()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable?) {
        handlerJob.cancel("exceptionCaught", cause)
    }
}

// TODO: implement support for isAutoRead=false to support `inbound` backpressure
private class NettyTcpConnectionInboundHandler(
    private val inbound: SendChannel<Buffer>,
) : ChannelInboundHandlerAdapter() {
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        msg as ByteBuf
        try {
            val frame = msg.toBuffer()
            if (inbound.trySend(frame).isFailure) {
                frame.clear()
                error("inbound is closed")
            }
        } finally {
            msg.release()
        }
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext?, evt: Any?) {
        if (evt is ChannelInputShutdownEvent) {
            inbound.close()
        }
        super.userEventTriggered(ctx, evt)
    }
}

@RSocketTransportApi
private class NettyTcpConnection(
    private val outboundQueue: PrioritizationFrameQueue,
    private val inbound: ReceiveChannel<Buffer>,
) : RSocketSequentialConnection {
    override val isClosedForSend: Boolean get() = outboundQueue.isClosedForSend
    override suspend fun sendFrame(streamId: Int, frame: Buffer) {
        return outboundQueue.enqueueFrame(streamId, frame)
    }

    override suspend fun receiveFrame(): Buffer? {
        return inbound.receiveCatching().getOrNull()
    }
}

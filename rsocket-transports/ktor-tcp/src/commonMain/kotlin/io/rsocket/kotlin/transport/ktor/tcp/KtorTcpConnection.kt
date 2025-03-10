/*
 * Copyright 2015-2025 the original author or authors.
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

package io.rsocket.kotlin.transport.ktor.tcp

import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.rsocket.kotlin.internal.io.*
import io.rsocket.kotlin.transport.*
import io.rsocket.kotlin.transport.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.io.*
import kotlin.coroutines.*

@RSocketTransportApi
internal class KtorTcpConnection(
    parentContext: CoroutineContext,
    private val socket: Socket,
) : RSocketSequentialConnection {
    private val outboundQueue = PrioritizationFrameQueue()
    private val inbound = bufferChannel(Channel.BUFFERED)

    override val coroutineContext: CoroutineContext = parentContext.childContext()

    init {
        @OptIn(DelicateCoroutinesApi::class)
        launch(start = CoroutineStart.ATOMIC) {
            val outboundJob = launch {
                nonCancellable {
                    val output = socket.openWriteChannel()
                    try {
                        while (true) {
                            // we write all available frames here, and only after it flush
                            // in this case, if there are several buffered frames we can send them in one go
                            // avoiding unnecessary flushes
                            output.writeFrame(outboundQueue.dequeueFrame() ?: break)
                            while (true) output.writeFrame(outboundQueue.tryDequeueFrame() ?: break)
                            output.flush()
                        }
                        output.flushAndClose()
                    } catch (cause: Throwable) {
                        output.cancel(cause)
                        throw cause
                    }
                }
            }.onCompletion {
                outboundQueue.cancel()
            }

            val inboundJob = launch {
                val input = socket.openReadChannel()
                try {
                    while (true) {
                        inbound.send(input.readFrame() ?: break)
                    }
                    input.cancel(null)
                } catch (cause: Throwable) {
                    input.cancel(cause)
                    throw cause
                }
            }.onCompletion {
                inbound.cancel()
            }

            try {
                awaitCancellation()
            } finally {
                nonCancellable {
                    outboundQueue.close()
                    outboundJob.join()
                    inboundJob.join()
                    // await socket completion
                    socket.close()
                    socket.socketContext.join()
                }
            }
        }
    }

    override suspend fun sendFrame(streamId: Int, frame: Buffer) {
        return outboundQueue.enqueueFrame(streamId, frame)
    }

    override suspend fun receiveFrame(): Buffer? {
        return inbound.receiveCatching().getOrNull()
    }

    @OptIn(InternalAPI::class)
    private fun ByteWriteChannel.writeFrame(frame: Buffer) {
        writeBuffer.writeInt24(frame.size.toInt())
        writeBuffer.transferFrom(frame)
    }

    @OptIn(InternalAPI::class)
    private suspend fun ByteReadChannel.readFrame(): Buffer? {
        while (availableForRead < 3 && awaitContent(3)) yield()
        if (availableForRead == 0) return null

        val length = readBuffer.readInt24()
        return readBuffer(length).also {
            it.require(length.toLong())
        }
    }
}

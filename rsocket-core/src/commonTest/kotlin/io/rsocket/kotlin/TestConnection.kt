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

package io.rsocket.kotlin

import app.cash.turbine.*
import io.ktor.utils.io.core.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.io.*
import io.rsocket.kotlin.test.*
import io.rsocket.kotlin.transport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.*
import kotlin.test.*
import kotlin.time.*
import kotlin.time.Duration.Companion.seconds

class TestConnection : RSocketSequentialConnection, RSocketClientTarget {
    private val job = Job()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Unconfined + TestExceptionHandler

    private val sendChannel = channelForCloseable<ByteReadPacket>(Channel.UNLIMITED)
    private val receiveChannel = channelForCloseable<ByteReadPacket>(Channel.UNLIMITED)

    init {
        coroutineContext.job.invokeOnCompletion {
            sendChannel.cancelWithCause(it)
            receiveChannel.cancelWithCause(it)
        }
    }

    override fun connectClient(handler: RSocketConnectionHandler): Job = launch {
        handler.handleConnection(this@TestConnection)
    }.onCompletion {
        if (it != null) job.completeExceptionally(it)
    }

    override val isClosedForSend: Boolean get() = sendChannel.isClosedForSend

    override suspend fun sendFrame(streamId: Int, frame: ByteReadPacket) {
        sendChannel.send(frame)
    }

    override suspend fun receiveFrame(): ByteReadPacket {
        return receiveChannel.receive()
    }

    suspend fun ignoreSetupFrame() {
        assertEquals(FrameType.Setup, sendChannel.receive().readFrame(InUseTrackingPool).type)
    }

    internal suspend fun sendToReceiver(vararg frames: Frame) {
        frames.forEach {
            val packet = it.toPacket(InUseTrackingPool)
            receiveChannel.send(packet)
        }
    }

    internal suspend fun test(validate: suspend ReceiveTurbine<Frame>.() -> Unit) {
        sendChannel.consumeAsFlow().map {
            it.readFrame(InUseTrackingPool)
        }.test(5.seconds, validate = validate)
    }
}

suspend fun ReceiveTurbine<*>.expectNoEventsIn(duration: Duration) {
    delay(duration)
    expectNoEvents()
}

suspend fun ReceiveTurbine<*>.expectNoEventsIn(timeMillis: Long) {
    delay(timeMillis)
    expectNoEvents()
}

internal suspend inline fun ReceiveTurbine<Frame>.awaitFrame(block: (frame: Frame) -> Unit) {
    block(awaitItem())
}

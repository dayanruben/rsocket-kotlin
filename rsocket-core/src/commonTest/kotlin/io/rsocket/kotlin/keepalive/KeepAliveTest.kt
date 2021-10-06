/*
 * Copyright 2015-2020 the original author or authors.
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

package io.rsocket.kotlin.keepalive

import io.ktor.utils.io.core.*
import io.rsocket.kotlin.*
import io.rsocket.kotlin.core.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import io.rsocket.kotlin.test.*
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.time.*

class KeepAliveTest : TestWithConnection(), TestWithLeakCheck {

    private suspend fun requester(
        keepAlive: KeepAlive = KeepAlive(Duration.milliseconds(100), Duration.seconds(1))
    ): RSocket = connect(
        connection = connection,
        isServer = false,
        maxFragmentSize = 0,
        interceptors = InterceptorsBuilder().build(),
        connectionConfig = ConnectionConfig(keepAlive, DefaultPayloadMimeType, Payload.Empty)
    ) { RSocketRequestHandler { } }

    @Test
    fun requesterSendKeepAlive() = test {
        requester()
        connection.test {
            repeat(5) {
                awaitFrame { frame ->
                    assertTrue(frame is KeepAliveFrame)
                    assertTrue(frame.respond)
                }
            }
        }
    }

    @Test
    fun rSocketNotCanceledOnPresentKeepAliveTicks() = test {
        val rSocket = requester(KeepAlive(Duration.seconds(100), Duration.seconds(100)))
        connection.launch {
            repeat(50) {
                delay(Duration.milliseconds(100))
                connection.sendToReceiver(KeepAliveFrame(true, 0, ByteReadPacket.Empty))
            }
        }
        delay(Duration.seconds(1.5))
        assertTrue(rSocket.isActive)
        connection.test {
            repeat(50) {
                awaitItem()
            }
        }
    }

    @Test
    fun requesterRespondsToKeepAlive() = test {
        requester(KeepAlive(Duration.seconds(100), Duration.seconds(100)))
        connection.launch {
            while (isActive) {
                delay(Duration.milliseconds(100))
                connection.sendToReceiver(KeepAliveFrame(true, 0, ByteReadPacket.Empty))
            }
        }

        connection.test {
            repeat(5) {
                awaitFrame { frame ->
                    assertTrue(frame is KeepAliveFrame)
                    assertFalse(frame.respond)
                }
            }
        }
    }

    @Test
    fun noKeepAliveSentAfterRSocketCanceled() = test {
        requester().cancel()
        connection.test {
            expectNoEventsIn(500)
        }
    }

    @Test
    fun rSocketCanceledOnMissingKeepAliveTicks() = test {
        val rSocket = requester()
        connection.test {
            while (rSocket.isActive) kotlin.runCatching { awaitItem() }
        }
        assertTrue(rSocket.coroutineContext.job.getCancellationException().cause is RSocketError.ConnectionError)
    }

}

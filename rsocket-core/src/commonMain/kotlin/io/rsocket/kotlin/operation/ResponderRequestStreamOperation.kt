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

package io.rsocket.kotlin.operation

import io.rsocket.kotlin.*
import io.rsocket.kotlin.frame.*
import io.rsocket.kotlin.internal.*
import io.rsocket.kotlin.payload.*
import kotlinx.coroutines.*

internal class ResponderRequestStreamOperation(
    private val requestJob: Job,
    private val responder: RSocket,
) : ResponderOperation {
    private val limiter = PayloadLimiter(0)

    override suspend fun execute(outbound: OperationOutbound, requestPayload: Payload) {
        try {
            responder.requestStream(requestPayload).collectLimiting(limiter) { responsePayload ->
                outbound.sendNext(responsePayload, complete = false)
            }
            outbound.sendComplete()
        } catch (cause: Throwable) {
            if (currentCoroutineContext().isActive) outbound.sendError(cause)
            throw cause
        }
    }

    override fun shouldReceiveFrame(frameType: FrameType): Boolean =
        frameType === FrameType.RequestN || frameType === FrameType.Cancel

    override fun receiveRequestNFrame(requestN: Int) {
        limiter.updateRequests(requestN)
    }

    override fun receiveCancelFrame() {
        requestJob.cancel("Request was cancelled by remote party")
    }
}
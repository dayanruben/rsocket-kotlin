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

package io.rsocket.kotlin.frame

import io.rsocket.kotlin.test.*
import kotlinx.io.*
import kotlin.test.*

class RequestNFrameTest {

    private val dump = "00000a00000001200000000005"

    @Test
    fun testEncoding() {
        val frame = RequestNFrame(1, 5)
        val bytes = frame.toBufferWithLength().readByteArray()

        assertEquals(dump, bytes.toHexString())
    }

    @Test
    fun testDecoding() {
        val packet = packet(dump.hexToByteArray())
        val frame = packet.toFrameWithLength()

        assertTrue(frame is RequestNFrame)
        assertEquals(1, frame.streamId)
        assertEquals(5, frame.requestN)
    }

}

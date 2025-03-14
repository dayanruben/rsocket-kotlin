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

package io.rsocket.kotlin.transport.nodejs.tcp

import io.rsocket.kotlin.transport.tests.*
import kotlin.test.*

@Suppress("DEPRECATION_ERROR")
@Ignore
class TcpTransportTest : TransportTest() {
    private lateinit var server: TcpServer

    override suspend fun before() {
        val port = PortProvider.next()
        server = startServer(TcpServerTransport(port, "127.0.0.1"))
        client = connectClient(TcpClientTransport(port, "127.0.0.1", testContext))
    }

    override suspend fun after() {
        super.after()
        server.close()
    }
}

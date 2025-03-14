# rsocket-kotlin

RSocket Kotlin multi-platform implementation based on
[kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) and [kotlinx-io](https://github.com/Kotlin/kotlinx-io).

RSocket is a binary application protocol providing Reactive Streams semantics for use on byte stream transports such as
TCP, WebSockets, QUIC and Aeron.

It enables the following symmetric interaction models via async message passing over a single connection:

* [Fire-and-Forget](https://rsocket.io/about/motivations#fire-and-forget)
* [Request-Response](https://rsocket.io/about/motivations#requestresponse-single-response)
* [Request-Stream](https://rsocket.io/about/motivations#requeststream-multi-response-finite)
* [Request-Channel](https://rsocket.io/about/motivations#channel)

Learn more at http://rsocket.io

## Supported platforms and transports:

Local (in memory) transport is supported for all targets.
Starting from [Ktor 3.1 release](https://blog.jetbrains.com/kotlin/2025/02/ktor-3-1-0-release/),
all ktor-client, ktor-server and ktor-network modules are supported for all targets.
So all Ktor related transports (TCP and WebSocket) are supported by rsocket-kotlin for all targets.
Additionally, there is experimental JVM-only support for Netty TCP and QUIC transpots.

## Using in your projects

rsocket-kotlin is available on [Maven Central](https://mvnrepository.com/artifact/io.rsocket.kotlin):

```kotlin
repositories {
    mavenCentral()
}
```

### Ktor plugins

rsocket-kotlin provides [client](https://ktor.io/docs/client-plugins.html)
and [server](https://ktor.io/docs/server-plugins.html) plugins for [ktor](https://ktor.io)

Dependencies:

```kotlin
dependencies {
    // for client
    implementation("io.rsocket.kotlin:ktor-client-rsocket:0.20.0")

    // for server
    implementation("io.rsocket.kotlin:ktor-server-rsocket:0.20.0")
}
```

Example of client plugin usage:

```kotlin
//create ktor client
val client = HttpClient {
    install(WebSockets) // rsocket requires websockets plugin installed
    install(RSocketSupport) {
        // configure rSocket connector (all values have defaults)
        connector {
            connectionConfig {
                // payload for setup frame
                setupPayload {
                    buildPayload {
                        data("""{ "data": "setup" }""")
                    }
                }

                // mime types
                payloadMimeType = PayloadMimeType(
                    data = WellKnownMimeType.ApplicationJson,
                    metadata = WellKnownMimeType.MessageRSocketCompositeMetadata
                )
            }
        }
    }
}

//connect to some url
val rSocket: RSocket = client.rSocket("wss://demo.rsocket.io/rsocket")

//request stream
val stream: Flow<Payload> = rSocket.requestStream(
    buildPayload {
        data("""{ "data": "hello world" }""")
    }
)

//take 5 values and print response
stream.take(5).collect { payload: Payload ->
    println(payload.data.readString())
}
```

Example of server plugin usage:

```kotlin
//create ktor server
embeddedServer(CIO) {
    install(WebSockets) // rsocket requires websockets plugin installed
    install(RSocketSupport) {
        // optionally configure rSocket server
    }
    routing {
        rSocket("rsocket") {
            println(config.setupPayload.data.readString()) //print setup payload data

            RSocketRequestHandler {
                // handler for request/response
                requestResponse { request: Payload ->
                    println(request.data.readString()) //print request payload data
                    delay(500) // work emulation
                    buildPayload {
                        data("""{ "data": "Server response" }""")
                    }
                }
                // handler for request/stream      
                requestStream { request: Payload ->
                    println(request.data.readString()) // print request payload data
                    flow {
                        repeat(10) { i ->
                            emit(
                                buildPayload {
                                    data("""{ "data": "Server stream response: $i" }""")
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}.start(true)
```

### Standalone transports

rsocket-kotlin also provides standalone transports which can be used to establish RSocket connection:

Dependencies:

```kotlin
dependencies {
    implementation("io.rsocket.kotlin:rsocket-core:0.20.0")

    // TCP ktor client/server transport
    implementation("io.rsocket.kotlin:rsocket-transport-ktor-tcp:0.20.0")

    // WS ktor client transport
    implementation("io.rsocket.kotlin:rsocket-transport-ktor-websocket-client:0.20.0")

    // WS ktor server transport
    implementation("io.rsocket.kotlin:rsocket-transport-ktor-websocket-server:0.20.0")

    // Netty TCP client/server transport
    implementation("io.rsocket.kotlin:rsocket-transport-netty-tcp:0.20.0")
}
```

Example of usage standalone TCP ktor client transport:

```kotlin
val parentContext = Job()
val target = KtorTcpClientTransport(parentContext) {
    // optional configuration
}.target("127.0.0.1", 8080)
val connector = RSocketConnector {
    //configuration goes here
}
val rsocket: RSocket = connector.connect(target)
//use rsocket to do request
val response = rsocket.requestResponse(buildPayload { data("""{ "data": "hello world" }""") })
println(response.data.readString())
```

Example of usage standalone TCP ktor server transport:

```kotlin
val parentContext = Job()
val target = KtorTcpServerTransport(parentContext) {
    // optional configuration
}.target("127.0.0.1", 8080)
val server = RSocketServer {
    //configuration goes here
}
val serverInstance = server.startServer(target) {
    RSocketRequestHandler {
        //handler for request/response
        requestResponse { request: Payload ->
            println(request.data.readString()) //print request payload data
            delay(500) // work emulation
            buildPayload {
                data("""{ "data": "Server response" }""")
            }
        }
    }
}
serverInstance.coroutineContext.job.join() // wait for server to finish
```

### More samples:

- [multiplatform-chat](samples/chat) - chat implementation with JVM/JS/Native server and JVM/JS/Native client with
  shared classes and shared data serialization
  using [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)

## Reactive Streams Semantics

From [RSocket protocol](https://github.com/rsocket/rsocket/blob/master/Protocol.md#reactive-streams-semantics):

    Reactive Streams semantics are used for flow control of Streams, Subscriptions, and Channels. 
    This is a credit-based model where the Requester grants the Responder credit for the number of PAYLOADs it can send. 
    It is sometimes referred to as "request-n" or "request(n)".

`kotlinx.coroutines` doesn't truly support `request(n)` semantic,
but it has flexible `CoroutineContext` which can be used to achieve something similar.
`rsocket-kotlin` contains `RequestStrategy` coroutine context element, which defines,
strategy for sending of `requestN`frames.

Example:

```kotlin
//assume we have client
val client: RSocket = TODO()

//and stream
val stream: Flow<Payload> = client.requestStream(Payload("data"))

//now we can use `flowOn` to add request strategy to context of flow
//here we use prefetch strategy which will send requestN for 10 elements, when, there is 5 elements left to collect
//so on call `collect`, requestStream frame with requestN will be sent, and then, after 5 elements will be collected
//new requestN with 5 will be sent, so collect will be smooth 
stream.flowOn(PrefetchStrategy(requestSize = 10, requestOn = 5)).collect { payload: Payload ->
    println(payload.data.readString())
}
```

## Bugs and Feedback

For bugs, questions and discussions please use the [Github Issues](https://github.com/rsocket/rsocket-kotlin/issues).

## LICENSE

Copyright 2015-2024 the original author or authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

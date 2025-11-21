package cosmex.ws

import cosmex.{ClientRequest, ClientResponse}
import cosmex.JsonCodecs.given
import upickle.default.*

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.{CompletableFuture, CompletionStage, CountDownLatch, TimeUnit}
import scala.util.{Failure, Success, Try}

/** Simple synchronous WebSocket client for demos */
class SimpleWebSocketClient(serverUrl: String) {

    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private var webSocket: WebSocket = _
    private var responseBuffer: StringBuilder = new StringBuilder()
    private var responseLatch: CountDownLatch = _

    /** Connect to the WebSocket server */
    def connect(): Unit = {
        val listener = new WebSocket.Listener {
            override def onOpen(webSocket: WebSocket): Unit = {
                println(s"[Client] Connected to $serverUrl")
                webSocket.request(1) // Request one message
            }

            override def onText(
                webSocket: WebSocket,
                data: CharSequence,
                last: Boolean
            ): CompletionStage[?] = {
                responseBuffer.append(data)
                if last then {
                    // Complete message received
                    responseLatch.countDown()
                }
                webSocket.request(1)
                null
            }

            override def onError(webSocket: WebSocket, error: Throwable): Unit = {
                println(s"[Client] WebSocket error: ${error.getMessage}")
                error.printStackTrace()
            }

            override def onClose(
                webSocket: WebSocket,
                statusCode: Int,
                reason: String
            ): CompletionStage[?] = {
                println(s"[Client] Connection closed: $reason")
                null
            }
        }

        webSocket = httpClient
            .newWebSocketBuilder()
            .buildAsync(URI.create(serverUrl), listener)
            .get(5, TimeUnit.SECONDS)
    }

    /** Send a request and wait for response (synchronous) */
    def sendRequest(request: ClientRequest, timeoutSeconds: Int = 10): Try[ClientResponse] = {
        if webSocket == null || !webSocket.isOutputClosed then {
            // Prepare for response
            responseBuffer.clear()
            responseLatch = new CountDownLatch(1)

            // Serialize and send request
            val requestJson = write(request)
            webSocket.sendText(requestJson, true).get(timeoutSeconds, TimeUnit.SECONDS)

            // Wait for response
            val received = responseLatch.await(timeoutSeconds, TimeUnit.SECONDS)
            if !received then {
                return Failure(
                  new Exception(s"Timeout waiting for response after $timeoutSeconds seconds")
                )
            }

            // Parse response
            Try {
                val responseJson = responseBuffer.toString
                read[ClientResponse](responseJson)
            }
        } else {
            Failure(new Exception("WebSocket is not connected or closed"))
        }
    }

    /** Close the WebSocket connection */
    def close(): Unit = {
        if webSocket != null then {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing")
            println("[Client] Disconnected")
        }
    }
}

object SimpleWebSocketClient {
    def apply(serverUrl: String): SimpleWebSocketClient = {
        val client = new SimpleWebSocketClient(serverUrl)
        client.connect()
        client
    }
}

package cosmex.ws

import cosmex.{ClientRequest, ClientResponse}
import upickle.default.*
import sttp.client4.*
import sttp.client4.ws.{sync, SyncWebSocket}
import sttp.ws.WebSocketFrame

import java.util.concurrent.{CountDownLatch, LinkedBlockingQueue, TimeUnit}
import scala.util.{Failure, Try}

/** Simple synchronous WebSocket client for demos using sttp
  *
  * Uses a blocking queue to handle request/response synchronization with a background receiver
  * thread
  */
class SimpleWebSocketClient(serverUrl: String) {

    private val backend = DefaultSyncBackend()
    private val responseQueue = new LinkedBlockingQueue[String]()
    private val receiverReady = new CountDownLatch(1)
    @volatile private var webSocket: SyncWebSocket = _
    @volatile private var receiverThread: Thread = _

    /** Connect to the WebSocket server */
    def connect(): Unit = {
        // Create WebSocket connection that stays open
        val request = basicRequest
            .get(uri"$serverUrl")
            .response(sync.asWebSocket { ws =>
                webSocket = ws

                // Signal that connection is ready immediately
                receiverReady.countDown()

                // Process messages in this thread (blocking call)
                try {
                    while true do {
                        val frame = ws.receive()
                        frame match {
                            case WebSocketFrame.Text(payload, _, _) =>
                                responseQueue.put(payload)
                            case WebSocketFrame.Close(_, _) =>
                                return ()
                            case _ => // Ignore other frame types
                        }
                    }
                } catch {
                    case e: Exception =>
                        val msg = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
                        if !msg.contains("closed") then {
                            System.err.println(s"[Client] Error receiving: $msg")
                        }
                }
                ()
            })

        // Send the request in a background thread so it doesn't block
        receiverThread = new Thread {
            override def run(): Unit = {
                backend.send(request)
            }
        }
        receiverThread.setDaemon(true)
        receiverThread.start()

        // Wait for connection to be ready
        if !receiverReady.await(5, TimeUnit.SECONDS) then {
            throw new Exception("WebSocket connection failed to establish")
        }
        println(s"[Client] Connected to $serverUrl")
    }

    /** Send a request without waiting for a response */
    def sendMessage(request: ClientRequest): Try[Unit] = {
        if webSocket != null then {
            Try {
                val requestJson = write(request)
                println(s"[Client] Sending message: ${requestJson.take(100)}...")
                webSocket.sendText(requestJson)
                println(s"[Client] Message sent successfully")
            }.recoverWith { case e =>
                println(s"[Client] ERROR sending message: ${e.getMessage}")
                e.printStackTrace()
                Failure(e)
            }
        } else {
            Failure(new Exception("WebSocket is not connected or closed"))
        }
    }

    /** Wait for a message from the WebSocket */
    def receiveMessage(timeoutSeconds: Int = 10): Try[String] = {
        Try {
            val message = responseQueue.poll(timeoutSeconds.toLong, TimeUnit.SECONDS)
            if message != null then {
                message
            } else {
                throw new Exception("Timeout waiting for response")
            }
        }
    }

    /** Send a request and wait for a response (synchronous) */
    def sendRequest(request: ClientRequest, timeoutSeconds: Int = 10): Try[ClientResponse] = {
        for {
            _ <- sendMessage(request)
            responseJson <- receiveMessage(timeoutSeconds)
            response <- Try(read[ClientResponse](responseJson))
        } yield response
    }

    /** Close the WebSocket connection */
    def close(): Unit = {
        if receiverThread != null then {
            receiverThread.interrupt()
        }
        if webSocket != null then {
            Try(webSocket.close())
            println("[Client] Disconnected")
        }
        if backend != null then {
            backend.close()
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

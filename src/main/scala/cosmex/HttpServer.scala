package cosmex

import ox.*
import sttp.tapir.*
import sttp.tapir.server.netty.sync.{NettySyncServer, NettySyncServerBinding}
import sttp.model.StatusCode
import scalus.builtin.ByteString
import scalus.cardano.ledger.{CardanoInfo, TransactionInput}
import scalus.cardano.node.Provider

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import JsonCodecs.*

class HttpServer(
    port: Int,
    server: Server
) {

  private val friendlyIdToClientId = new ConcurrentHashMap[String, ClientId]()

  // Register a client mapping (used by tests to map friendly names to ClientIds)
  def registerClient(friendlyId: String, clientId: ClientId): Unit = {
    friendlyIdToClientId.put(friendlyId, clientId)
  }

  // Define HTTP endpoints
  val requestEndpoint = endpoint.post
    .in("api" / path[String]("clientId") / "request")
    .in(stringBody)
    .out(stringBody)
    .errorOut(stringBody)

  // Handle client requests
  private def handleClientRequest(friendlyId: String, requestJson: String): Either[String, String] = {
    val maybeClientId = Option(friendlyIdToClientId.get(friendlyId))

    maybeClientId match {
      case None =>
        Left(encodeResponse(ClientResponse.Error(s"Client not registered: $friendlyId")))

      case Some(clientId) =>
        decodeRequest(requestJson) match {
          case Right(request) =>
            val response = processRequest(clientId, request)
            Right(encodeResponse(response))
          case Left(error) =>
            Left(encodeResponse(ClientResponse.Error(s"Invalid request: $error")))
        }
    }
  }

  private def processRequest(clientId: ClientId, request: ClientRequest): ClientResponse = {
    request match {
      case ClientRequest.CreateOrder(order) =>
        server.handleCreateOrder(clientId, order) match {
          case Right((snapshot, trades)) =>
            // Get order ID from snapshot version for simplicity
            val orderId = snapshot.signedSnapshot.snapshotVersion

            // For trades, we would notify both parties in a real WebSocket implementation
            // For now, just return success
            ClientResponse.OrderCreated(orderId.toInt)

          case Left(error) =>
            ClientResponse.Error(error)
        }

      case ClientRequest.CancelOrder(orderId) =>
        server.handleCancelOrder(clientId, BigInt(orderId)) match {
          case Right(_) =>
            ClientResponse.OrderCancelled(orderId)
          case Left(error) =>
            ClientResponse.Error(error)
        }

      case _ =>
        ClientResponse.Error("Unsupported request type")
    }
  }

  // Start the HTTP server
  private var serverBinding: Option[NettySyncServerBinding] = None

  def start(): Unit = {
    val route = requestEndpoint.handle { case (friendlyId, requestBody) =>
      handleClientRequest(friendlyId, requestBody)
    }

    // Use a thread to avoid blocking
    val serverThread = new Thread {
      override def run(): Unit = supervised {
        val binding = NettySyncServer()
          .port(port)
          .addEndpoint(route)
          .start()

        serverBinding = Some(binding)
        println(s"HTTP server started on port $port")
      }
    }
    serverThread.setDaemon(false) // Not a daemon so it keeps running
    serverThread.setName("HttpServer-Thread")
    serverThread.start()

    // Wait for server to be ready
    Thread.sleep(1500)
  }

  def stop(): Unit = {
    serverBinding.foreach(_.stop())
    friendlyIdToClientId.clear()
    println("HTTP server stopped")
  }

  def getServer: Server = server
}

object HttpServer {
  def apply(port: Int, server: Server): HttpServer = {
    new HttpServer(port, server)
  }
}

package cosmex

import sttp.client3.*
import sttp.model.Uri
import JsonCodecs.*

import scala.concurrent.duration.*

/**
 * HTTP test client for integration testing.
 * Connects to the COSMEX HTTP server and provides methods to send orders and receive responses.
 */
class HttpTestClient(clientId: String, serverHost: String, serverPort: Int) {

  private val backend = HttpURLConnectionBackend()
  private val baseUri = uri"http://$serverHost:$serverPort/api/$clientId/request"

  /**
   * Send a client request to the server and get response
   */
  def sendRequest(request: ClientRequest): Either[String, ClientResponse] = {
    val json = encodeRequest(request)

    val httpRequest = basicRequest
      .post(baseUri)
      .body(json)
      .response(asString)

    try {
      val response = httpRequest.send(backend)

      println(s"HTTP Response status: ${response.code}")
      response.body match {
        case Right(responseJson) =>
          println(s"Response body: $responseJson")
          decodeResponse(responseJson)
        case Left(error) =>
          Left(s"HTTP error (${response.code}): $error")
      }
    } catch {
      case e: Exception =>
        Left(s"Exception during HTTP request: ${e.getMessage}")
    }
  }

  /**
   * Create and send a limit order
   */
  def createOrder(order: LimitOrder): Either[String, ClientResponse] = {
    sendRequest(ClientRequest.CreateOrder(order))
  }

  /**
   * Cancel an order
   */
  def cancelOrder(orderId: Int): Either[String, ClientResponse] = {
    sendRequest(ClientRequest.CancelOrder(orderId))
  }

  /**
   * Close the client (cleanup)
   */
  def close(): Unit = {
    backend.close()
  }
}

object HttpTestClient {
  def apply(clientId: String, serverHost: String = "localhost", serverPort: Int = 8080): HttpTestClient = {
    new HttpTestClient(clientId, serverHost, serverPort)
  }
}

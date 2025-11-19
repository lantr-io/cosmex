package cosmex

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import scalus.builtin.ByteString

object JsonCodecs {

  // JSON representation for simplified serialization
  case class OrderJson(
      pair: (String, String), // (base asset hex, quote asset hex) as hex strings
      amount: String, // BigInt as string
      price: String // BigInt as string
  )

  case class TradeJson(
      orderId: String,
      amount: String,
      price: String
  )

  case class CreateOrderRequest(order: OrderJson)
  case class CancelOrderRequest(orderId: Int)

  case class OrderCreatedResponse(orderId: Int)
  case class OrderCancelledResponse(orderId: Int)
  case class ErrorResponse(message: String)
  case class TradeNotificationResponse(
      orderId: String,
      amount: String,
      price: String,
      snapshotVersion: String
  )

  // Discriminated union for requests
  case class ClientRequestJson(`type`: String, order: Option[OrderJson], orderId: Option[Int])

  // Discriminated union for responses
  case class ClientResponseJson(
      `type`: String,
      orderId: Option[Int],
      message: Option[String],
      trade: Option[TradeJson],
      snapshotVersion: Option[String]
  )

  // Codecs for JSON types
  given JsonValueCodec[OrderJson] = JsonCodecMaker.make
  given JsonValueCodec[TradeJson] = JsonCodecMaker.make
  given JsonValueCodec[ClientRequestJson] = JsonCodecMaker.make
  given JsonValueCodec[ClientResponseJson] = JsonCodecMaker.make

  // Convert domain types to/from JSON types
  def orderToJson(order: LimitOrder): OrderJson = {
    val (basePolicyId, baseTokenName) = order.orderPair._1
    val (quotePolicyId, quoteTokenName) = order.orderPair._2
    OrderJson(
      pair = (
        s"${basePolicyId.toHex}:${baseTokenName.toHex}",
        s"${quotePolicyId.toHex}:${quoteTokenName.toHex}"
      ),
      amount = order.orderAmount.toString,
      price = order.orderPrice.toString
    )
  }

  def orderFromJson(json: OrderJson): LimitOrder = {
    val Array(basePolicyHex, baseTokenHex) = json.pair._1.split(":")
    val Array(quotePolicyHex, quoteTokenHex) = json.pair._2.split(":")

    val baseAsset: AssetClass = (ByteString.fromHex(basePolicyHex), ByteString.fromHex(baseTokenHex))
    val quoteAsset: AssetClass = (ByteString.fromHex(quotePolicyHex), ByteString.fromHex(quoteTokenHex))

    LimitOrder(
      orderPair = (baseAsset, quoteAsset),
      orderAmount = BigInt(json.amount),
      orderPrice = BigInt(json.price)
    )
  }

  def tradeToJson(trade: Trade): TradeJson = {
    TradeJson(
      orderId = trade.orderId.toString,
      amount = trade.tradeAmount.toString,
      price = trade.tradePrice.toString
    )
  }

  // Convert ClientRequest to JSON
  def encodeRequest(request: ClientRequest): String = {
    val json = request match {
      case ClientRequest.CreateOrder(order) =>
        ClientRequestJson("createOrder", Some(orderToJson(order)), None)
      case ClientRequest.CancelOrder(orderId) =>
        ClientRequestJson("cancelOrder", None, Some(orderId))
      case _ =>
        ClientRequestJson("unsupported", None, None)
    }
    writeToString(json)
  }

  // Parse JSON to ClientRequest
  def decodeRequest(jsonString: String): Either[String, ClientRequest] = {
    try {
      val json = readFromString[ClientRequestJson](jsonString)
      json.`type` match {
        case "createOrder" =>
          json.order match {
            case Some(orderJson) =>
              Right(ClientRequest.CreateOrder(orderFromJson(orderJson)))
            case None =>
              Left("Missing order field for createOrder")
          }
        case "cancelOrder" =>
          json.orderId match {
            case Some(id) =>
              Right(ClientRequest.CancelOrder(id))
            case None =>
              Left("Missing orderId field for cancelOrder")
          }
        case other =>
          Left(s"Unknown request type: $other")
      }
    } catch {
      case e: JsonReaderException => Left(s"JSON parsing error: ${e.getMessage}")
      case e: Exception => Left(s"Error: ${e.getMessage}")
    }
  }

  // Convert ClientResponse to JSON
  def encodeResponse(response: ClientResponse): String = {
    val json = response match {
      case ClientResponse.OrderCreated(orderId) =>
        ClientResponseJson("orderCreated", Some(orderId), None, None, None)
      case ClientResponse.OrderCancelled(orderId) =>
        ClientResponseJson("orderCancelled", Some(orderId), None, None, None)
      case ClientResponse.Error(message) =>
        ClientResponseJson("error", None, Some(message), None, None)
      case ClientResponse.TradeNotification(trade, newBalance, snapshotVersion) =>
        ClientResponseJson(
          "tradeNotification",
          None,
          None,
          Some(tradeToJson(trade)),
          Some(snapshotVersion.toString)
        )
      case _ =>
        ClientResponseJson("unsupported", None, None, None, None)
    }
    writeToString(json)
  }

  // Parse JSON to ClientResponse
  def decodeResponse(jsonString: String): Either[String, ClientResponse] = {
    try {
      val json = readFromString[ClientResponseJson](jsonString)
      json.`type` match {
        case "orderCreated" =>
          json.orderId match {
            case Some(id) => Right(ClientResponse.OrderCreated(id))
            case None => Left("Missing orderId for orderCreated")
          }
        case "orderCancelled" =>
          json.orderId match {
            case Some(id) => Right(ClientResponse.OrderCancelled(id))
            case None => Left("Missing orderId for orderCancelled")
          }
        case "error" =>
          json.message match {
            case Some(msg) => Right(ClientResponse.Error(msg))
            case None => Left("Missing message for error")
          }
        case "tradeNotification" =>
          (json.trade, json.snapshotVersion) match {
            case (Some(tradeJson), Some(versionStr)) =>
              val trade = Trade(
                orderId = BigInt(tradeJson.orderId),
                tradeAmount = BigInt(tradeJson.amount),
                tradePrice = BigInt(tradeJson.price)
              )
              // For now, we'll use a dummy value for newBalance since it's complex to serialize
              import scalus.ledger.api.v3.Value
              val dummyBalance = Value.zero
              Right(ClientResponse.TradeNotification(trade, dummyBalance, BigInt(versionStr)))
            case _ => Left("Missing trade or snapshotVersion for tradeNotification")
          }
        case other =>
          Left(s"Unknown response type: $other")
      }
    } catch {
      case e: JsonReaderException => Left(s"JSON parsing error: ${e.getMessage}")
      case e: Exception => Left(s"Error: ${e.getMessage}")
    }
  }
}

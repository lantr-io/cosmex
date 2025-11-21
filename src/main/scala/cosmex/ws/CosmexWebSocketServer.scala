package cosmex.ws

import scala.util.control.NonFatal

import cosmex.*
import ox.*
import sttp.tapir.*
import sttp.tapir.server.netty.sync.{NettySyncServer, OxStreams}
import upickle.default.*

/** WebSocket server for COSMEX */
object CosmexWebSocketServer {

    // WebSocket endpoint - text-based messages
    val wsEndpoint =
        endpoint.get
            .in("ws")
            .out(
              webSocketBody[String, CodecFormat.TextPlain, String, CodecFormat.TextPlain](OxStreams)
            )

    /** Run the WebSocket server with the given Server instance */
    def run(server: Server, port: Int = 8080)(using Ox): Unit = {
        println("=" * 60)
        println("COSMEX WebSocket Server")
        println("=" * 60)
        println(s"Listening on: ws://localhost:$port/ws")
        println("=" * 60)

        // Define WebSocket logic
        val wsServerEndpoint = wsEndpoint.handleSuccess { _ =>
            import ox.flow.Flow
            (in: Flow[String]) =>
                in.map { msg =>
                    try {
                        // Parse incoming JSON as ClientRequest
                        val request = read[ClientRequest](msg)
                        println(s"[Server] Received request: ${request.getClass.getSimpleName}")

                        // Handle the request
                        val response = handleRequest(server, request)

                        // Send response back
                        val responseJson = write(response)
                        println(s"[Server] Sent response: ${response.getClass.getSimpleName}")
                        responseJson
                    } catch {
                        case NonFatal(e) =>
                            println(s"[Server] Error handling message: ${e.getMessage}")
                            e.printStackTrace()
                            val errorResponse = ClientResponse.Error(e.getMessage)
                            write(errorResponse)
                    }
                }
        }

        // Start server
        val binding = NettySyncServer()
            .port(port)
            .addEndpoint(wsServerEndpoint)
            .start()

        println("\n[Server] Ready to accept connections. Press ENTER to stop.")
        scala.io.StdIn.readLine()

        println("\n[Server] Shutting down...")
        binding.stop()
    }

    /** Handle a client request and return a response */
    def handleRequest(server: Server, request: ClientRequest): ClientResponse = {
        request match {
            case ClientRequest.OpenChannel(tx, snapshot) =>
                // Validate the opening request
                val validation = server.validateOpenChannelRequest(tx, snapshot)
                validation match {
                    case Left(error) =>
                        ClientResponse.Error(error)
                    case Right(_) =>
                        // Extract client TxOutRef from first input
                        val firstInput = tx.body.value.inputs.toSeq.head
                        val clientTxOutRef = scalus.ledger.api.v3.TxOutRef(
                          scalus.ledger.api.v3.TxId(firstInput.transactionId),
                          firstInput.index
                        )

                        // Sign the snapshot
                        val signedSnapshot = server.signSnapshot(clientTxOutRef, snapshot)

                        // Store client state
                        val channelRef = scalus.cardano.ledger.TransactionInput(tx.id, 0)
                        val clientId = ClientId(channelRef)

                        val actualDeposit = tx.body.value.outputs.view
                            .find(_.value.address == server.CosmexScriptAddress)
                            .get
                            .value
                            .value // TransactionOutput.value.value = Value

                        val clientState = ClientState(
                          latestSnapshot = signedSnapshot,
                          channelRef = channelRef,
                          lockedValue = actualDeposit,
                          status = ChannelStatus.Open
                        )
                        server.clients.put(clientId, clientState)

                        println(s"[Server] Channel opened for client: ${clientId}")
                        ClientResponse.ChannelOpened(signedSnapshot)
                }

            case ClientRequest.CreateOrder(clientId, order) =>
                server.handleCreateOrder(clientId, order) match {
                    case Left(error) =>
                        ClientResponse.Error(error)
                    case Right((orderId, snapshot, trades)) =>
                        // The orderId is the one that was assigned (nextOrderId was incremented)
                        println(s"[Server] Order created: $orderId, trades: ${trades.size}")
                        ClientResponse.OrderCreated(BigInt(orderId))
                }

            case ClientRequest.CancelOrder(clientId, orderId) =>
                // TODO: Implement cancel order
                ClientResponse.Error("CancelOrder not implemented yet")

            case ClientRequest.Deposit(clientId, amount) =>
                // TODO: Implement deposit
                ClientResponse.Error("Deposit not implemented yet")

            case ClientRequest.Withdraw(clientId, amount) =>
                // TODO: Implement withdraw
                ClientResponse.Error("Withdraw not implemented yet")

            case ClientRequest.GetBalance(clientId) =>
                server.clients.get(clientId) match {
                    case Some(state) =>
                        val balance =
                            state.latestSnapshot.signedSnapshot.snapshotTradingState.tsClientBalance
                        ClientResponse.Balance(balance)
                    case None =>
                        ClientResponse.Error("Client not found")
                }

            case ClientRequest.GetOrders(clientId) =>
                server.clients.get(clientId) match {
                    case Some(state) =>
                        val orders =
                            state.latestSnapshot.signedSnapshot.snapshotTradingState.tsOrders
                        ClientResponse.Orders(orders)
                    case None =>
                        ClientResponse.Error("Client not found")
                }
        }
    }
}

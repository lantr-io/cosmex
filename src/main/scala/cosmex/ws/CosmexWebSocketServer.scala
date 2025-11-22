package cosmex.ws

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal
import cosmex.*
import ox.*
import ox.channels.{Channel, Source}
import sttp.tapir.*
import sttp.tapir.server.netty.sync.{NettySyncServer, NettySyncServerBinding, OxStreams}
import upickle.default.*
import cats.Id // Import Id

import cats.Id // Import Id

/** WebSocket server for COSMEX */
object CosmexWebSocketServer {

    // WebSocket endpoint - text-based messages
    val wsEndpoint =
        endpoint.get
            .in("ws" / path[String] / path[Int])
            .out(
              webSocketBody[String, CodecFormat.TextPlain, String, CodecFormat.TextPlain](OxStreams)
            )

    private def wsLogic(server: Server) = {
        wsEndpoint.handleSuccess { (clientTrId, clientTrIdx) =>
            import ox.flow.Flow
            val clientTrInput = scalus.cardano.ledger.TransactionInput(
              scalus.cardano.ledger.TransactionHash.fromHex(clientTrId),
              clientTrIdx
            )
            val clientId = ClientId(clientTrInput)
            // in future: creater some policy for overflow
            val channel = server.clientChannels.getOrElseUpdate(clientId, Channel.unlimited[Trade])
            (in: Flow[String]) =>
                println(s"[Server] WebSocket handler started for client: ${clientId}")
                val handleRequestFlow = in.mapConcat { msg =>
                    println(s"[Server] Raw message received: ${msg.take(100)}...")
                    try {
                        // Parse incoming JSON as ClientRequest
                        val request = read[ClientRequest](msg)
                        println(s"[Server] Received request: ${request.getClass.getSimpleName}")

                        // Handle the request - returns List[ClientResponse]
                        val responses = handleRequest(server, request)

                        // Convert each response to JSON and return as List
                        responses.map { response =>
                            val responseJson = write(response)
                            println(s"[Server] Sent response: ${response.getClass.getSimpleName}")
                            responseJson
                        }
                    } catch {
                        case NonFatal(e) =>
                            println(s"[Server] Error handling message: ${e.getMessage}")
                            e.printStackTrace()
                            val errorResponse = ClientResponse.Error(e.getMessage)
                            List(write(errorResponse))
                    }
                }
                val orderExecutionFlow = Flow.fromSource(channel).map { trade =>
                   val response = ClientResponse.OrderExecuted(trade)
                   val responseJson = write(response)
                   println(s"[Server] Sent order execution: Order ID ${trade.orderId}")
                   responseJson
                }
                val retval = handleRequestFlow.merge(orderExecutionFlow)
                in.onComplete(
                  // actially we should have something like ref-counting here, because
                  // multiple connections from same clientId may exist
                  println(s"[Server] Connection closed for client: ${clientId}"),
                  server.clientChannels.remove(clientId)
                )
                retval
        }
    }

    /** Run the WebSocket server with the given Server instance (for main application - runs forever) */
    def run(server: Server, port: Int = 8080)(using Ox): Unit = {
        val binding = runBinding(server, port)
        println("\n[Server] Ready to accept connections. Press ENTER to stop.")
        scala.io.StdIn.readLine()
        println("\n[Server] Shutting down...")
        binding.stop()
    }

    /** Run the WebSocket server and return the binding (for testing) */
    def runBinding(server: Server, port: Int = 8080)(using Ox): NettySyncServerBinding = {
        println("=" * 60)
        println("COSMEX WebSocket Server")
        println("=" * 60)
        println(s"Listening on: ws://localhost:$port/ws")
        println("=" * 60)

        val wsServerEndpoint = wsLogic(server)

        // Start server
        NettySyncServer()
            .port(port)
            .addEndpoint(wsServerEndpoint)
            .start()
    }
    
    /** Handle a client request and return a response */
    /** Handle a single client request and return a sequence of responses
      * 
      * Most requests return a single response, but CreateOrder returns OrderCreated
      * followed by OrderExecuted for each trade (ensures correct ordering)
      */
    def handleRequest(server: Server, request: ClientRequest): List[ClientResponse] = {
        val responses = request match {
            case ClientRequest.OpenChannel(tx, snapshot) =>
                // Validate the opening request
                val validation = server.validateOpenChannelRequest(tx, snapshot)
                val response = validation match {
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

                        // Submit transaction to blockchain
                        server.sendTx(tx)
                        
                        // Check if transaction is confirmed (for MockLedgerApi it's immediate)
                        val txConfirmed = server.isUtxoConfirmed(channelRef)
                        val channelStatus = if (txConfirmed) ChannelStatus.Open else ChannelStatus.PendingOpen
                        
                        val clientState = ClientState(
                          latestSnapshot = signedSnapshot,
                          channelRef = channelRef,
                          lockedValue = actualDeposit,
                          status = channelStatus
                        )
                        server.clientStates.put(clientId, clientState)

                        println(s"[Server] Channel opened for client: ${clientId}, status: ${channelStatus}")
                        ClientResponse.ChannelOpened(signedSnapshot)
                }
                List(response)

            case ClientRequest.CreateOrder(clientId, order) =>
                server.handleCreateOrder(clientId, order) match {
                    case Left(error) =>
                        List(ClientResponse.Error(error))
                    case Right((orderId, snapshot, trades)) =>
                        // The orderId is the one that was assigned (nextOrderId was incremented)
                        println(s"[Server] Order created: $orderId, trades: ${trades.size}")
                        
                        // Split trades into:
                        // 1. Trades for THIS client (incoming order) - return immediately
                        // 2. Trades for OTHER clients (matched orders) - send via ox channel
                        val incomingOrderId = BigInt(orderId)
                        println(s"[Server] Processing ${trades.size} trades for order $incomingOrderId")
                        trades.foreach(t => println(s"[Server]   Trade: orderId=${t.orderId}, amount=${t.tradeAmount}"))
                        
                        val (myTrades, otherTrades) = trades.partition(_.orderId == incomingOrderId)
                        println(s"[Server] Split: ${myTrades.size} for this client, ${otherTrades.size} for others")
                        
                        // Send notifications to OTHER clients via ox channel (async)
                        otherTrades.foreach { trade =>
                            server.orderOwners.get(trade.orderId).foreach { ownerClientId =>
                                server.clientChannels.get(ownerClientId).foreach { channel =>
                                    println(s"[Server] Queuing trade notification to client: $ownerClientId, trade: ${trade.orderId}")
                                    channel.send(trade)
                                }
                            }
                        }
                        
                        // Return OrderCreated FIRST, then OrderExecuted for THIS client's trades
                        val orderCreated = ClientResponse.OrderCreated(BigInt(orderId))
                        val myOrderExecuted = myTrades.map(trade => ClientResponse.OrderExecuted(trade))
                        
                        println(s"[Server] Returning ${1 + myOrderExecuted.size} responses (OrderCreated + ${myOrderExecuted.size} OrderExecuted)")
                        orderCreated :: myOrderExecuted
                }

            case ClientRequest.CancelOrder(clientId, orderId) =>
                // TODO: Implement cancel order
                List(ClientResponse.Error("CancelOrder not implemented yet"))

            case ClientRequest.Deposit(clientId, amount) =>
                // TODO: Implement deposit
                List(ClientResponse.Error("Deposit not implemented yet"))

            case ClientRequest.Withdraw(clientId, amount) =>
                // TODO: Implement withdraw
                List(ClientResponse.Error("Withdraw not implemented yet"))

            case ClientRequest.GetBalance(clientId) =>
                val response = server.clientStates.get(clientId) match {
                    case Some(state) =>
                        val balance =
                            state.latestSnapshot.signedSnapshot.snapshotTradingState.tsClientBalance
                        ClientResponse.Balance(balance)
                    case None =>
                        ClientResponse.Error("Client not found")
                }
                List(response)

            case ClientRequest.GetOrders(clientId) =>
                val response = server.clientStates.get(clientId) match {
                    case Some(state) =>
                        val orders =
                            state.latestSnapshot.signedSnapshot.snapshotTradingState.tsOrders
                        ClientResponse.Orders(orders)
                    case None =>
                        ClientResponse.Error("Client not found")
                }
                List(response)
        }
        responses
    }
}

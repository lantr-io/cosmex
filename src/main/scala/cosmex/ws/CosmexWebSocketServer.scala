package cosmex.ws

import scala.util.control.NonFatal
import cosmex.*
import ox.*
import ox.channels.{Channel, ChannelClosedUnion}
import sttp.tapir.*
import sttp.tapir.server.netty.sync.{NettySyncServer, NettySyncServerBinding, OxStreams}
import upickle.default.*

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
            // in future: create some policy for overflow
            val channel =
                server.clientChannels.getOrElseUpdate(clientId, Channel.unlimited[ClientResponse])
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
                            val errorResponse =
                                ClientResponse.Error(ErrorCode.InternalError, e.getMessage)
                            List(write(errorResponse))
                    }
                }
                val asyncResponseFlow = Flow.fromSource(channel).map { response =>
                    val responseJson = write(response)
                    response match {
                        case ClientResponse.OrderExecuted(trade) =>
                            println(
                              s"[Server] Sent async order execution: Order ID ${trade.orderId}"
                            )
                        case ClientResponse.ChannelOpened(_) =>
                            println(s"[Server] Sent async ChannelOpened")
                        case other =>
                            println(
                              s"[Server] Sent async response: ${other.getClass.getSimpleName}"
                            )
                    }
                    responseJson
                }

                // Register cleanup handler BEFORE merging
                in.onComplete(
                  println(s"[Server] Connection closing for client: ${clientId}"),
                  // Close the channel so Flow.fromSource completes
                  channel.done(),
                  // Drain any remaining items from channel to complete the flow
                  supervised {
                      fork {
                          try {
                              import ChannelClosedUnion.isValue
                              while channel.receiveOrClosed().isValue do {
                                  // Drain the channel
                              }
                              println(s"[Server] Channel drained for client: ${clientId}")
                          } catch {
                              case e: Exception =>
                                  println(
                                    s"[Server] Error draining channel for client $clientId: ${e.getMessage}"
                                  )
                                  e.printStackTrace()
                          }
                      }
                  },
                  server.clientChannels.remove(clientId)
                )

                handleRequestFlow.merge(asyncResponseFlow)
        }
    }

    /** Run the WebSocket server with the given Server instance (for main application - runs
      * forever)
      */
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

    /** Print current order book state for debugging */
    private def printOrderBook()(using server: Server): Unit = {
        val book = server.orderBookRef.get()
        println("\n" + "=" * 80)
        println("[Server] Order Book State")
        println("=" * 80)

        if book.buyOrders.isEmpty && book.sellOrders.isEmpty then println("  (empty)")
        else
            if book.sellOrders.nonEmpty then
                println("  SELL Orders (asks):")
                book.sellOrders.reverse.foreach { entry =>
                    val amount = -entry.order.orderAmount // Negative for sell orders
                    println(
                      f"    ${amount}%10d @ ${entry.order.orderPrice}%10d  (orderId: ${entry.orderId})"
                    )
                }

            println("  " + "-" * 76)

            if book.buyOrders.nonEmpty then
                println("  BUY Orders (bids):")
                book.buyOrders.foreach { entry =>
                    val amount = entry.order.orderAmount // Positive for buy orders
                    println(
                      f"    ${amount}%10d @ ${entry.order.orderPrice}%10d  (orderId: ${entry.orderId})"
                    )
                }

        println("=" * 80 + "\n")
    }

    /** Handle a client request and return a response */
    /** Handle a single client request and return a sequence of responses
      *
      * Most requests return a single response, but CreateOrder returns OrderCreated followed by
      * OrderExecuted for each trade (ensures correct ordering)
      */
    def handleRequest(server: Server, request: ClientRequest): List[ClientResponse] = {
        given Server = server
        val responses = request match {
            case ClientRequest.OpenChannel(tx, snapshot) =>
                // Validate the opening request
                server.validateOpenChannelRequest(tx, snapshot) match {
                    case Left(error) =>
                        List(ClientResponse.Error(ErrorCode.InvalidTransaction, error))
                    case Right(openChannelInfo) =>
                        // Extract client TxOutRef from first input
                        val firstInput = tx.body.value.inputs.toSeq.head
                        val clientTxOutRef = scalus.ledger.api.v3.TxOutRef(
                          scalus.ledger.api.v3.TxId(firstInput.transactionId),
                          firstInput.index
                        )

                        // Sign the snapshot
                        val signedSnapshot = server.signSnapshot(clientTxOutRef, snapshot)

                        // Store client state - use the actual channel ref from validation
                        val channelRef = openChannelInfo.channelRef
                        val clientId = ClientId(channelRef)

                        // Use the deposit amount from validation result
                        val actualDeposit = openChannelInfo.amount

                        // Create client state with PendingOpen status
                        val clientState = ClientState(
                          latestSnapshot = signedSnapshot,
                          channelRef = channelRef,
                          lockedValue = actualDeposit,
                          status = ChannelStatus.PendingOpen,
                          clientPubKey = openChannelInfo.clientPubKey
                        )
                        server.clientStates.put(clientId, clientState)

                        // Submit transaction to blockchain (non-blocking)
                        server.provider.submit(tx) match {
                            case Left(submitError) =>
                                println(s"[Server] Transaction submission failed: $submitError")
                                return List(
                                  ClientResponse.Error(
                                    ErrorCode.TransactionFailed,
                                    s"Transaction submission failed: ${submitError.getMessage}"
                                  )
                                )
                            case Right(_) =>
                                println(
                                  s"[Server] Transaction submitted: ${tx.id.toHex.take(16)}..."
                                )
                        }

                        // Get the client's async response channel
                        server.clientChannels.get(clientId) match {
                            case None =>
                                val errorMsg =
                                    s"Internal error: No async channel for client ${clientId}"
                                println(s"[Server] ERROR: $errorMsg")
                                List(ClientResponse.Error(ErrorCode.InternalError, errorMsg))

                            case Some(channel) =>
                                // Launch background thread to wait for confirmation using submitAndWait
                                val pollingThread = new Thread(() => {
                                    println(
                                      s"[Server] Starting background confirmation wait for: ${clientId}"
                                    )

                                    // Use submitAndWait extension (handles both transaction status and UTxO polling)
                                    // Transaction already submitted, so we just poll for first output
                                    var attempts = 0
                                    val maxAttempts =
                                        180 // 3 minutes - preprod blocks are ~20s, plus Blockfrost indexing delay
                                    val delayMs = 1000

                                    var confirmed = false
                                    while attempts < maxAttempts && !confirmed do {
                                        server.provider.findUtxo(channelRef) match {
                                            case Right(_) =>
                                                println(
                                                  s"[Server] Channel confirmed after ${attempts + 1} attempt(s): ${clientId}"
                                                )
                                                server.updateChannelStatus(
                                                  clientId,
                                                  ChannelStatus.Open
                                                )
                                                channel.send(
                                                  ClientResponse.ChannelOpened(signedSnapshot)
                                                )
                                                confirmed = true
                                            case Left(_) =>
                                                attempts += 1
                                                if attempts < maxAttempts then {
                                                    if attempts % 5 == 0 then {
                                                        println(
                                                          s"[Server] Still waiting for confirmation... (attempt ${attempts}/${maxAttempts})"
                                                        )
                                                    }
                                                    Thread.sleep(delayMs)
                                                }
                                        }
                                    }

                                    if !confirmed then {
                                        println(
                                          s"[Server] Channel confirmation timeout for: ${clientId}"
                                        )
                                        channel.send(
                                          ClientResponse.Error(
                                            ErrorCode.TransactionFailed,
                                            "Transaction confirmation timeout"
                                          )
                                        )
                                    }
                                })
                                pollingThread.setDaemon(true)
                                pollingThread.start()

                                println(s"[Server] Channel pending for client: ${clientId}")
                                List(ClientResponse.ChannelPending(tx.id.toHex))
                        }
                }

            case ClientRequest.CloseChannel(clientId, tx, snapshot) =>
                server.handleCloseChannel(clientId, tx, snapshot)
                List(ClientResponse.ChannelClosed(snapshot))

            case ClientRequest.CreateOrder(clientId, order) =>
                server.handleCreateOrder(clientId, order) match {
                    case Left((code, error)) =>
                        List(ClientResponse.Error(code, error))
                    case Right((orderId, _, trades)) =>
                        // The orderId is the one that was assigned (nextOrderId was incremented)
                        println(s"[Server] Order created: $orderId, trades: ${trades.size}")

                        // Print current order book state
                        printOrderBook()

                        // Split trades into:
                        // 1. Trades for THIS client (incoming order) - return immediately
                        // 2. Trades for OTHER clients (matched orders) - send via ox channel
                        val incomingOrderId = BigInt(orderId)
                        println(
                          s"[Server] Processing ${trades.size} trades for order $incomingOrderId"
                        )
                        trades.foreach(t =>
                            println(
                              s"[Server]   Trade: orderId=${t.orderId}, amount=${t.tradeAmount}"
                            )
                        )

                        val (myTrades, otherTrades) = trades.partition(_.orderId == incomingOrderId)
                        println(
                          s"[Server] Split: ${myTrades.size} for this client, ${otherTrades.size} for others"
                        )

                        // Send notifications to OTHER clients via ox channel (async)
                        otherTrades.foreach { trade =>
                            server.orderOwners.get(trade.orderId).foreach { ownerClientId =>
                                server.clientChannels.get(ownerClientId).foreach { channel =>
                                    println(
                                      s"[Server] Queuing trade notification to client: $ownerClientId, trade: ${trade.orderId}"
                                    )
                                    channel.send(ClientResponse.OrderExecuted(trade))
                                }
                            }
                        }

                        // Return OrderCreated FIRST, then OrderExecuted for THIS client's trades
                        val orderCreated = ClientResponse.OrderCreated(BigInt(orderId))
                        val myOrderExecuted =
                            myTrades.map(trade => ClientResponse.OrderExecuted(trade))

                        println(
                          s"[Server] Returning ${1 + myOrderExecuted.size} responses (OrderCreated + ${myOrderExecuted.size} OrderExecuted)"
                        )
                        orderCreated :: myOrderExecuted
                }

            case ClientRequest.CancelOrder(clientId, orderId) =>
                // TODO: Implement cancel order
                List(
                  ClientResponse.Error(ErrorCode.InternalError, "CancelOrder not implemented yet")
                )

            case ClientRequest.Deposit(clientId, amount) =>
                // TODO: Implement deposit
                List(ClientResponse.Error(ErrorCode.InternalError, "Deposit not implemented yet"))

            case ClientRequest.Withdraw(clientId, amount) =>
                // TODO: Implement withdraw
                List(ClientResponse.Error(ErrorCode.InternalError, "Withdraw not implemented yet"))

            case ClientRequest.GetBalance(clientId) =>
                val response = server.clientStates.get(clientId) match {
                    case Some(state) =>
                        val balance =
                            state.latestSnapshot.signedSnapshot.snapshotTradingState.tsClientBalance
                        ClientResponse.Balance(balance)
                    case None =>
                        ClientResponse.Error(ErrorCode.ClientNotFound, "Client not found")
                }
                List(response)

            case ClientRequest.GetOrders(clientId) =>
                val response = server.clientStates.get(clientId) match {
                    case Some(state) =>
                        val orders =
                            state.latestSnapshot.signedSnapshot.snapshotTradingState.tsOrders
                        ClientResponse.Orders(orders)
                    case None =>
                        ClientResponse.Error(ErrorCode.ClientNotFound, "Client not found")
                }
                List(response)

            case ClientRequest.GetState(clientId) =>
                server.handleGetState(clientId) match {
                    case Right(state) => List(state)
                    case Left((code, error)) =>
                        List(ClientResponse.Error(code, error))
                }

            case ClientRequest.FixBalance(clientId) =>
                server.handleFixBalance(clientId) match {
                    case Right(_) =>
                        println(s"[Server] Rebalancing started by client: $clientId")
                        List(ClientResponse.RebalanceStarted)
                    case Left((code, error)) =>
                        List(ClientResponse.Error(code, error))
                }

            case ClientRequest.SignRebalance(clientId, signedTx) =>
                server.handleSignRebalance(clientId, signedTx) match {
                    case Right(_) =>
                        // Response will be sent asynchronously when all signatures collected
                        List.empty
                    case Left((code, error)) =>
                        List(ClientResponse.Error(code, error))
                }
        }
        responses
    }
}

package cosmex

import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration
import cosmex.util.JsonCodecs.given
import cosmex.util.submitAndWait
import scalus.builtin.ToData.tupleToData
import scalus.builtin.{platform, Builtins, ByteString}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.node.Provider
import scalus.ledger.api.v3.{TxId, TxOutRef}
import scalus.prelude.Eq
import scalus.utils.await
import upickle.default.*

import java.time.Instant
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.annotation.unused
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext.Implicits.global

enum ClientRequest derives ReadWriter:
    case OpenChannel(tx: Transaction, snapshot: SignedSnapshot)
    case CloseChannel(clientId: ClientId, tx: Transaction, snapshot: SignedSnapshot)
    case CreateOrder(clientId: ClientId, order: LimitOrder)
    case CancelOrder(clientId: ClientId, orderId: Int)
    case Deposit(clientId: ClientId, amount: Value)
    case Withdraw(clientId: ClientId, amount: Value)
    case GetBalance(clientId: ClientId)
    case GetOrders(clientId: ClientId)
    case GetState(clientId: ClientId)
    case FixBalance(clientId: ClientId)
    case SignRebalance(clientId: ClientId, signedTx: Transaction)

end ClientRequest

type ChainSlot = Long

case class ChainPoint(slotNo: ChainSlot, headerHash: String) derives ReadWriter

enum OnChainTx derives ReadWriter:
    case OnCommitTx
    case OnAbortTx
    case OnCloseTx

enum BlockchainEvent derives ReadWriter:
    case Observation(tx: OnChainTx, newChainState: OnChainChannelState)
    case Rollback(chainState: OnChainChannelState)
    case Tick(chainTime: Instant, chainSlot: ChainSlot)

/** Error codes for ClientResponse.Error */
object ErrorCode:
    val ClientNotFound = "CLIENT_NOT_FOUND"
    val ChannelNotOpen = "CHANNEL_NOT_OPEN"
    val InsufficientBalance = "INSUFFICIENT_BALANCE"
    val InvalidOrder = "INVALID_ORDER"
    val OrderNotFound = "ORDER_NOT_FOUND"
    val InvalidSnapshot = "INVALID_SNAPSHOT"
    val InvalidTransaction = "INVALID_TRANSACTION"
    val TransactionFailed = "TRANSACTION_FAILED"
    val InternalError = "INTERNAL_ERROR"
    val RebalancingInProgress = "REBALANCING_IN_PROGRESS"
    val RebalancingFailed = "REBALANCING_FAILED"
    val NoRebalancingNeeded = "NO_REBALANCING_NEEDED"
    val RebalancingRequired = "REBALANCING_REQUIRED"

enum ClientResponse derives ReadWriter:
    case ChannelPending(txId: String) // Transaction submitted, waiting for confirmation
    case ChannelOpened(snapshot: SignedSnapshot)
    case ClosePending(txId: String) // Close transaction submitted, waiting for confirmation
    case ChannelClosed(snapshot: SignedSnapshot)
    case Error(code: String, message: String)
    case OrderCreated(orderId: OrderId)
    case OrderCancelled(orderId: OrderId)
    case OrderExecuted(trade: Trade)
    case Balance(balance: scalus.ledger.api.v3.Value)
    case Orders(orders: scalus.prelude.AssocMap[OrderId, LimitOrder])
    case State(
        balance: scalus.ledger.api.v3.Value,
        orders: scalus.prelude.AssocMap[OrderId, LimitOrder],
        channelStatus: ChannelStatus,
        snapshotVersion: BigInt
    )
    case RebalanceStarted
    case RebalanceRequired(tx: Transaction)
    case RebalanceComplete(snapshot: SignedSnapshot)
    case RebalanceAborted(reason: String)

enum ServerEvent derives ReadWriter:
    case ClientEvent(clientId: Int, action: ClientRequest)
    case OnChainEvent(event: BlockchainEvent)

enum Command derives ReadWriter:
    case ClientCommand(clientId: Int, action: ClientRequest)

enum Effect derives ReadWriter:
    case DoNothing

case class ClientId(txOutRef: TransactionInput) derives ReadWriter

enum ChannelStatus derives ReadWriter:
    case PendingOpen, Open, Closing, Closed

case class ClientState(
    latestSnapshot: SignedSnapshot,
    channelRef: TransactionInput,
    lockedValue: Value,
    status: ChannelStatus,
    clientPubKey: ByteString // Client's public key for signature verification and rebalancing
) derives ReadWriter

case class ClientRecord(
    state: ClientState,
    oxChannel: ox.channels.Channel[ClientResponse]
)

case class OpenChannelInfo(
    channelRef: TransactionInput,
    amount: Value,
    tx: Transaction,
    snapshot: SignedSnapshot,
    clientPubKey: ByteString
) derives ReadWriter

/** Context for tracking an in-progress rebalancing operation */
case class RebalancingContext(
    startTime: Instant,
    affectedClients: Set[ClientId],
    rebalanceTx: Transaction,
    collectedSignatures: Map[ClientId, Transaction]
)

class Server(
    env: CardanoInfo,
    exchangeParams: ExchangeParams,
    val provider: Provider, // Made public for WebSocket server
    exchangePrivKey: ByteString
) {
    val program = CosmexContract.mkCosmexProgram(exchangeParams, generateErrorTraces = true)
    val script = Script.PlutusV3(program.cborByteString)
    val CosmexScriptAddress = Address(env.network, Credential.ScriptHash(script.scriptHash))
    val CosmexSignKey = exchangePrivKey
    val CosmexPubKey = exchangeParams.exchangePubKey

    val clientStates = TrieMap.empty[ClientId, ClientState]
    val clientChannels = TrieMap.empty[ClientId, ox.channels.Channel[ClientResponse]]
    var orderBookRef: AtomicReference[OrderBook] = new AtomicReference(OrderBook.empty)
    var nextOrderId: AtomicLong = new AtomicLong(0L)
    val orderOwners = TrieMap.empty[OrderId, ClientId] // Maps order ID to client

    // Rebalancing state: None = normal operation, Some = rebalancing in progress
    val rebalancingState: AtomicReference[Option[RebalancingContext]] = new AtomicReference(None)

    def handleCommand(command: Command): Unit = command match
        case Command.ClientCommand(clientId, action) => handleClientRequest(action)

    private def handleClientRequest(request: ClientRequest): Unit = {
        request match
            case ClientRequest.OpenChannel(tx, snapshot) =>
                validateOpenChannelRequest(tx, snapshot) match
                    case Right(openChannelInfo) =>
                        // Extract client TxOutRef from the first input (channel identifier)
                        val firstInput = tx.body.value.inputs.toSeq.head
                        val clientTxOutRef = TxOutRef(
                          TxId(firstInput.transactionId),
                          firstInput.index
                        )

                        // Sign the snapshot with exchange key
                        val bothSignedSnapshot = signSnapshot(clientTxOutRef, snapshot)

                        // Create and store client state
                        val clientId = ClientId(openChannelInfo.channelRef)
                        val clientState = ClientState(
                          latestSnapshot = bothSignedSnapshot,
                          channelRef = openChannelInfo.channelRef,
                          lockedValue = openChannelInfo.amount,
                          status = ChannelStatus.PendingOpen,
                          clientPubKey = openChannelInfo.clientPubKey
                        )
                        clientStates.put(clientId, clientState)

                        // Send the transaction to the blockchain
                        sendTx(tx)

                        // Reply with the both-signed snapshot
                        reply(ClientResponse.ChannelOpened(bothSignedSnapshot))
                    case Left(error) =>
                        reply(ClientResponse.Error(ErrorCode.InvalidTransaction, error))
            case _ => List.empty

    }

    def handleEvent(event: ServerEvent) = {}

    def handleCloseChannel(
        clientId: ClientId,
        tx: Transaction,
        snapshot: SignedSnapshot
    ): Either[(String, String), (Transaction, SignedSnapshot)] = {
        clientStates.get(clientId) match
            case None => Left((ErrorCode.ClientNotFound, "Client not found"))
            case Some(clientState) =>
                if clientState.status != ChannelStatus.Open then
                    return Left(
                      (
                        ErrorCode.ChannelNotOpen,
                        s"Channel is not open, status: ${clientState.status}"
                      )
                    )

                // Validate snapshot matches server's view
                val serverSnapshot = clientState.latestSnapshot
                if snapshot.signedSnapshot.snapshotVersion != serverSnapshot.signedSnapshot.snapshotVersion
                then
                    return Left(
                      (
                        ErrorCode.InvalidSnapshot,
                        s"Snapshot version mismatch: client=${snapshot.signedSnapshot.snapshotVersion}, server=${serverSnapshot.signedSnapshot.snapshotVersion}"
                      )
                    )

                // Validate graceful close prerequisites:
                // 1. exchangeBalance == 0
                // 2. No open orders
                // 3. clientBalance == lockedValue
                import scalus.ledger.api.v3.Value as V3Value
                import scalus.prelude.AssocMap
                val tradingState = serverSnapshot.signedSnapshot.snapshotTradingState
                val exchangeBalance = tradingState.tsExchangeBalance
                val clientBalance = tradingState.tsClientBalance
                val orders = tradingState.tsOrders

                if exchangeBalance != V3Value.zero then
                    return Left(
                      (
                        ErrorCode.RebalancingRequired,
                        s"Graceful close requires exchangeBalance == 0. Current exchange balance: $exchangeBalance. Rebalancing needed."
                      )
                    )

                if orders != AssocMap.empty then
                    return Left(
                      (
                        ErrorCode.RebalancingRequired,
                        s"Graceful close requires no open orders. Cancel orders first."
                      )
                    )

                val lockedValueV3 = LedgerToPlutusTranslation.getValue(clientState.lockedValue)
                if clientBalance != lockedValueV3 then
                    return Left(
                      (
                        ErrorCode.RebalancingRequired,
                        s"Graceful close requires clientBalance == lockedValue. Client balance: $clientBalance, locked: $lockedValueV3. Rebalancing needed."
                      )
                    )

                // Sign the transaction with exchange key
                val signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider
                val signature = ByteString.fromArray(
                  signingProvider.signExtended(tx.id.bytes, CosmexSignKey.bytes)
                )
                val exchangeWitness = VKeyWitness(CosmexPubKey, signature)

                // Add exchange witness to transaction
                val existingWitnesses = tx.witnessSet.vkeyWitnesses.toSeq
                val allWitnesses = existingWitnesses :+ exchangeWitness
                val bothSignedTx = tx.copy(
                  witnessSet = tx.witnessSet.copy(
                    vkeyWitnesses = TaggedSortedSet.from(allWitnesses)
                  )
                )

                // Submit the transaction (non-blocking)
                provider.submit(bothSignedTx).await() match
                    case Left(error) =>
                        Left(
                          (
                            ErrorCode.TransactionFailed,
                            s"Failed to submit close transaction: $error"
                          )
                        )
                    case Right(_) =>
                        // Update channel status to Closing
                        clientStates.update(
                          clientId,
                          clientState.copy(status = ChannelStatus.Closing)
                        )
                        Right((bothSignedTx, serverSnapshot))
    }

    def validateOpenChannelRequest(
        tx: Transaction,
        snapshot: SignedSnapshot
    ): Either[String, OpenChannelInfo] = {
        // Find the unique output to Cosmex script address
        println(s"[Server] Expected Cosmex script address: $CosmexScriptAddress")
        println(s"[Server] Transaction outputs:")
        tx.body.value.outputs.view.map(_.value).zipWithIndex.foreach { case (out, idx) =>
            println(s"[Server]   Output $idx: ${out.address}")
        }
        val cosmexOutput = tx.body.value.outputs.view
            .map(_.value)
            .zipWithIndex
            .filter(_._1.address == CosmexScriptAddress)
            .toVector match
            case Vector((output, idx)) => (output, idx)
            case Vector() =>
                return Left(s"No output to Cosmex script address. Expected: $CosmexScriptAddress")
            case _ => return Left("More than one output to Cosmex script address")

        val (output, outputIdx) = cosmexOutput
        val depositAmount = output.value

        // Validate snapshot version is 0 (initial snapshot)
        if snapshot.signedSnapshot.snapshotVersion != 0 then
            return Left(
              s"Invalid snapshot version: ${snapshot.signedSnapshot.snapshotVersion}, expected 0"
            )

        // Validate snapshot has no pending transactions
        if snapshot.signedSnapshot.snapshotPendingTx.isDefined then
            return Left("Initial snapshot must not have pending transactions")

        // Validate empty TradingState
        val tradingState = snapshot.signedSnapshot.snapshotTradingState

        // Check that client balance matches deposit amount (convert types for comparison)
        import scalus.ledger.api.v3.Value as V3Value
        val depositAmountV3 = LedgerToPlutusTranslation.getValue(depositAmount)
        if tradingState.tsClientBalance != depositAmountV3 then
            return Left(
              s"Client balance ${tradingState.tsClientBalance} doesn't match deposit ${depositAmountV3}"
            )

        // Check that exchange balance is zero
        if tradingState.tsExchangeBalance != V3Value.zero then
            return Left(
              s"Exchange balance must be zero in initial snapshot, got ${tradingState.tsExchangeBalance}"
            )

        // Check that there are no orders
        if !tradingState.tsOrders.isEmpty then return Left("Initial snapshot must have no orders")

        // Extract client public key and TxOutRef from the inline datum (OnChainState)
        // Note: We must use the clientTxOutRef from the on-chain state, not from the transaction inputs,
        // because transaction inputs are sorted and may differ from the original order used when signing.
        val (clientPubKey, clientTxOutRef) = output.datumOption match
            case Some(DatumOption.Inline(data)) =>
                val onChainState = data.to[OnChainState]
                (onChainState.clientPubKey, onChainState.clientTxOutRef)
            case _ =>
                return Left("Output must have inline datum with OnChainState")
        println(s"[Server] Verifying client signature:")
        println(
          s"[Server]   Public key length: ${clientPubKey.length}, hex: ${clientPubKey.toHex.take(32)}..."
        )
        println(
          s"[Server]   TxOutRef full: ${clientTxOutRef.id.hash.toHex} index ${clientTxOutRef.idx}"
        )
        println(s"[Server]   tx.id: ${tx.id.toHex}")
        println(s"[Server]   Signature length: ${snapshot.snapshotClientSignature.length}")
        if !verifyClientSignature(clientPubKey, clientTxOutRef, snapshot) then
            return Left("Invalid client signature on snapshot")

        Right(
          OpenChannelInfo(
            TransactionInput(tx.id, outputIdx),
            depositAmount,
            tx,
            snapshot,
            clientPubKey
          )
        )
    }

    def verifyClientSignature(
        clientPubKey: ByteString,
        clientTxOutRef: TxOutRef,
        snapshot: SignedSnapshot
    ): Boolean = {
        val signedInfo = (clientTxOutRef, snapshot.signedSnapshot)
        import scalus.builtin.Data.toData
        val msg = Builtins.serialiseData(signedInfo.toData)
        platform.verifyEd25519Signature(
          clientPubKey,
          msg,
          snapshot.snapshotClientSignature
        )
    }

    def signSnapshot(clientTxOutRef: TxOutRef, snapshot: SignedSnapshot): SignedSnapshot = {
        val signedInfo = (clientTxOutRef, snapshot.signedSnapshot)
        import scalus.builtin.Data.toData
        val msg = Builtins.serialiseData(signedInfo.toData)
        // Use Bloxbean's signExtended for Cardano's BIP32-Ed25519 extended keys
        val signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider
        val cosmexSignature = ByteString.fromArray(
          signingProvider.signExtended(msg.bytes, CosmexSignKey.bytes)
        )
        snapshot.copy(snapshotExchangeSignature = cosmexSignature)
    }

    /** Validate that the client has sufficient balance for the order.
      *
      * For SELL orders (orderAmount < 0): need base asset (|orderAmount|)
      * For BUY orders (orderAmount > 0): need quote asset (orderAmount * orderPrice / PRICE_SCALE)
      *
      * Note: Uses tsClientBalance directly because existing orders have already
      * had their locked amounts deducted from tsClientBalance (pre-deduction model).
      */
    def validateOrderBalance(
        tradingState: TradingState,
        order: LimitOrder
    ): Either[(String, String), Unit] = {
        val (baseAsset, quoteAsset) = order.orderPair
        val orderAmount = order.orderAmount
        val orderPrice = order.orderPrice
        val clientBalance = tradingState.tsClientBalance

        if orderAmount < 0 then {
            // SELL order: need base asset
            val requiredAmount = -orderAmount // Make positive
            val availableAmount = clientBalance.quantityOf(baseAsset._1, baseAsset._2)
            if availableAmount < requiredAmount then
                Left(
                  (
                    ErrorCode.InsufficientBalance,
                    s"Insufficient base asset balance: have $availableAmount, need $requiredAmount"
                  )
                )
            else Right(())
        } else if orderAmount > 0 then {
            // BUY order: need quote asset (amount * price / PRICE_SCALE)
            val requiredAmount = orderAmount * orderPrice / CosmexValidator.PRICE_SCALE
            val availableAmount = clientBalance.quantityOf(quoteAsset._1, quoteAsset._2)
            if availableAmount < requiredAmount then
                Left(
                  (
                    ErrorCode.InsufficientBalance,
                    s"Insufficient quote asset balance: have $availableAmount, need $requiredAmount"
                  )
                )
            else Right(())
        } else {
            // Zero amount order is invalid
            Left((ErrorCode.InvalidOrder, "Order amount cannot be zero"))
        }
    }

    def handleCreateOrder(
        clientId: ClientId,
        order: LimitOrder
    ): Either[(String, String), (Long, SignedSnapshot, List[Trade])] = {
        clientStates.get(clientId) match
            case None => Left((ErrorCode.ClientNotFound, "Client not found"))
            case Some(clientState) =>
                if clientState.status != ChannelStatus.Open then
                    return Left(
                      (
                        ErrorCode.ChannelNotOpen,
                        s"Channel is not open, status: ${clientState.status}"
                      )
                    )

                // Get current trading state
                val currentSnapshot = clientState.latestSnapshot.signedSnapshot
                val currentTradingState = currentSnapshot.snapshotTradingState

                // Validate that client has sufficient balance for the order
                validateOrderBalance(currentTradingState, order) match
                    case Left(error) => return Left(error)
                    case Right(())   => () // Validation passed

                // Assign order ID
                val longOrderId = nextOrderId.getAndIncrement()
                val orderId = BigInt(longOrderId)

                import scalus.prelude.AssocMap

                // Calculate the value locked by this order
                val LimitOrder((base, quote), orderAmount, orderPrice) = order
                val lockedValue =
                    if orderAmount < 0 then
                        // Sell order: lock base asset (absolute value)
                        CosmexValidator.assetClassValue(base, -orderAmount)
                    else
                        // Buy order: lock quote asset (amount * price / PRICE_SCALE)
                        CosmexValidator.assetClassValue(
                          quote,
                          orderAmount * orderPrice / CosmexValidator.PRICE_SCALE
                        )

                // Reduce client's free balance by the locked amount
                // This maintains the invariant: tsClientBalance + tsExchangeBalance + lockedInOrders = locked
                val newClientBalance = currentTradingState.tsClientBalance - lockedValue

                val newOrders = AssocMap.insert(currentTradingState.tsOrders)(orderId, order)
                val tradingStateWithOrder = currentTradingState.copy(
                  tsClientBalance = newClientBalance,
                  tsOrders = newOrders
                )

                // Register order owner BEFORE matching so notifications can be sent
                orderOwners.put(orderId, clientId)

                // Check if rebalancing is in progress - if so, pause matching
                val isRebalancing = rebalancingState.get().isDefined

                var orderBookUpdated = false
                var matchResult: OrderBook.MatchResult = null
                while !orderBookUpdated do
                    val orderBook = orderBookRef.get()

                    if isRebalancing then
                        // During rebalancing: accept order but don't match, just add to book
                        matchResult = OrderBook.MatchResult(
                          trades = List.empty,
                          updatedBook = orderBook,
                          remainingAmount = order.orderAmount
                        )
                        val newOrderBook = OrderBook.addOrder(orderBook, orderId, order)
                        if orderBookRef.compareAndSet(orderBook, newOrderBook) then
                            orderBookUpdated = true
                    else
                        // Normal operation: match against order book
                        matchResult = OrderBook.matchOrder(orderBook, orderId, order)

                        // Update order book with remaining order (if any)
                        var newOrderBook = matchResult.updatedBook
                        if matchResult.remainingAmount != 0 then
                            newOrderBook = OrderBook.addOrder(
                              newOrderBook,
                              orderId,
                              order.copy(orderAmount = matchResult.remainingAmount)
                            )
                        if orderBookRef.compareAndSet(orderBook, newOrderBook) then
                            orderBookUpdated = true

                // Note: Trade notifications are now sent from CosmexWebSocketServer.handleRequest
                // This ensures OrderCreated is sent before OrderExecuted for the initiating client

                // Apply trades to trading state
                // Only apply trades for THIS client's orders (filter by orderId)
                import CosmexValidator.applyTrade
                val myTrades = matchResult.trades.filter(_.orderId == orderId)
                val tradingStateAfterTrades = myTrades.foldLeft(tradingStateWithOrder) {
                    (ts, trade) => applyTrade(ts, trade)
                }

                // Create new snapshot
                val newSnapshot = Snapshot(
                  snapshotTradingState = tradingStateAfterTrades,
                  snapshotPendingTx = currentSnapshot.snapshotPendingTx,
                  snapshotVersion = currentSnapshot.snapshotVersion + 1
                )

                // Extract client TxOutRef
                val clientTxOutRef = TxOutRef(
                  TxId(clientState.channelRef.transactionId),
                  clientState.channelRef.index
                )

                // Sign snapshot (need both client and exchange signatures)
                // For now, we'll create a SignedSnapshot with empty client signature
                // In a real scenario, client would sign first
                val signedSnapshot = SignedSnapshot(
                  signedSnapshot = newSnapshot,
                  snapshotClientSignature = ByteString.empty, // Client should sign
                  snapshotExchangeSignature = ByteString.empty
                )

                val bothSignedSnapshot = signSnapshot(clientTxOutRef, signedSnapshot)

                // Update stored state
                val updatedState = clientState.copy(latestSnapshot = bothSignedSnapshot)
                clientStates.put(clientId, updatedState)

                // Update counterparty states for matched trades
                updateCounterpartyStates(clientId, matchResult.trades)

                Right((longOrderId, bothSignedSnapshot, matchResult.trades))
    }

    /** Update counterparty states after trades are executed.
      * For each trade, finds the counterparty (owner of the matched order) and
      * updates their trading state with the trade result.
      */
    def updateCounterpartyStates(initiatingClientId: ClientId, trades: List[Trade]): Unit = {
        import CosmexValidator.applyTrade

        // Group trades by orderId to find counterparty orders
        val tradesByOrderId = trades.groupBy(_.orderId)

        tradesByOrderId.foreach { case (tradeOrderId, tradesForOrder) =>
            orderOwners.get(tradeOrderId).foreach { counterpartyClientId =>
                // Skip if this is the initiating client (already updated)
                if counterpartyClientId != initiatingClientId then {
                    clientStates.get(counterpartyClientId).foreach { counterpartyState =>
                        val currentSnapshot = counterpartyState.latestSnapshot.signedSnapshot
                        val currentTradingState = currentSnapshot.snapshotTradingState

                        // Apply counterparty's trades to their trading state
                        val tradingStateAfterTrades = tradesForOrder.foldLeft(currentTradingState) {
                            (ts, trade) => applyTrade(ts, trade)
                        }

                        // Create new snapshot
                        val newSnapshot = Snapshot(
                          snapshotTradingState = tradingStateAfterTrades,
                          snapshotPendingTx = currentSnapshot.snapshotPendingTx,
                          snapshotVersion = currentSnapshot.snapshotVersion + 1
                        )

                        val counterpartyTxOutRef = TxOutRef(
                          TxId(counterpartyState.channelRef.transactionId),
                          counterpartyState.channelRef.index
                        )

                        val signedSnapshot = SignedSnapshot(
                          signedSnapshot = newSnapshot,
                          snapshotClientSignature = ByteString.empty,
                          snapshotExchangeSignature = ByteString.empty
                        )

                        val bothSignedSnapshot = signSnapshot(counterpartyTxOutRef, signedSnapshot)
                        val updatedCounterpartyState =
                            counterpartyState.copy(latestSnapshot = bothSignedSnapshot)
                        clientStates.put(counterpartyClientId, updatedCounterpartyState)
                    }
                }
            }
        }
    }

    def handleCancelOrder(
        clientId: ClientId,
        orderId: OrderId
    ): Either[(String, String), SignedSnapshot] = {
        clientStates.get(clientId) match
            case None => Left((ErrorCode.ClientNotFound, "Client not found"))
            case Some(clientState) =>
                if clientState.status != ChannelStatus.Open then
                    return Left(
                      (
                        ErrorCode.ChannelNotOpen,
                        s"Channel is not open, status: ${clientState.status}"
                      )
                    )

                // Remove order from client's trading state
                val currentSnapshot = clientState.latestSnapshot.signedSnapshot
                val currentTradingState = currentSnapshot.snapshotTradingState

                import scalus.prelude.AssocMap

                // Find the order to calculate the locked value to restore
                val orderOpt = currentTradingState.tsOrders.get(orderId)
                if orderOpt.isEmpty then
                    return Left((ErrorCode.OrderNotFound, s"Order $orderId not found"))

                val order = orderOpt.get
                val LimitOrder((base, quote), orderAmount, orderPrice) = order

                // Calculate the value that was locked by this order (to restore it)
                val lockedValue =
                    if orderAmount < 0 then
                        // Sell order: base asset was locked (absolute value)
                        CosmexValidator.assetClassValue(base, -orderAmount)
                    else
                        // Buy order: quote asset was locked
                        CosmexValidator.assetClassValue(
                          quote,
                          orderAmount * orderPrice / CosmexValidator.PRICE_SCALE
                        )

                // Restore client's free balance
                val newClientBalance = currentTradingState.tsClientBalance + lockedValue

                val newOrders = AssocMap.delete(currentTradingState.tsOrders)(orderId)
                val newTradingState = currentTradingState.copy(
                  tsClientBalance = newClientBalance,
                  tsOrders = newOrders
                )

                var orderBookUpdated = false
                while !orderBookUpdated do {
                    // Remove from order book if present
                    val orderBook: OrderBook = orderBookRef.get()
                    val newOrderBook = OrderBook.removeOrder(orderBook, orderId)
                    orderBookUpdated = orderBookRef.compareAndSet(orderBook, newOrderBook)
                }

                orderOwners.remove(orderId)

                // Create new snapshot
                val newSnapshot = Snapshot(
                  snapshotTradingState = newTradingState,
                  snapshotPendingTx = currentSnapshot.snapshotPendingTx,
                  snapshotVersion = currentSnapshot.snapshotVersion + 1
                )

                // Extract client TxOutRef
                val clientTxOutRef = TxOutRef(
                  TxId(clientState.channelRef.transactionId),
                  clientState.channelRef.index
                )

                // Sign snapshot
                val signedSnapshot = SignedSnapshot(
                  signedSnapshot = newSnapshot,
                  snapshotClientSignature = ByteString.empty,
                  snapshotExchangeSignature = ByteString.empty
                )

                val bothSignedSnapshot = signSnapshot(clientTxOutRef, signedSnapshot)

                // Update stored state
                val updatedState = clientState.copy(latestSnapshot = bothSignedSnapshot)
                clientStates.put(clientId, updatedState)

                Right(bothSignedSnapshot)
    }

    def getLatestSnapshot(clientId: ClientId): Option[SignedSnapshot] = {
        clientStates.get(clientId).map(_.latestSnapshot)
    }

    def handleGetState(clientId: ClientId): Either[(String, String), ClientResponse.State] = {
        clientStates.get(clientId) match
            case None => Left((ErrorCode.ClientNotFound, "Client not found"))
            case Some(clientState) =>
                val tradingState = clientState.latestSnapshot.signedSnapshot.snapshotTradingState
                Right(
                  ClientResponse.State(
                    balance = tradingState.tsClientBalance,
                    orders = tradingState.tsOrders,
                    channelStatus = clientState.status,
                    snapshotVersion = clientState.latestSnapshot.signedSnapshot.snapshotVersion
                  )
                )
    }

    def getChannelStatus(clientId: ClientId): Option[ChannelStatus] = {
        clientStates.get(clientId).map(_.status)
    }

    def updateChannelStatus(clientId: ClientId, newStatus: ChannelStatus): Unit = {
        clientStates.get(clientId).foreach { state =>
            clientStates.put(clientId, state.copy(status = newStatus))
        }
    }

    def storeSnapshot(snapshot: SignedSnapshot): Unit = {
        println(snapshot)
    }

    def sendTx(tx: Transaction): Unit = {
        // Use longer timeout for real blockchains (30s)
        provider.submitAndWait(tx, maxAttempts = 30, delayMs = 1000) match {
            case Left(error) => println(s"[Server] Transaction submission failed: $error")
            case Right(_) => println(s"[Server] Transaction confirmed: ${tx.id.toHex.take(16)}...")
        }
    }

    /** Check if a transaction output exists on-chain (i.e., transaction is confirmed) */
    def isUtxoConfirmed(txOutRef: TransactionInput): Boolean = {
        provider.findUtxo(txOutRef).await().isRight
    }

    /** Check if a client needs rebalancing (on-chain locked value != client's entitled balance) */
    def needsRebalancing(clientId: ClientId): Boolean = {
        clientStates.get(clientId) match
            case None => false
            case Some(clientState) =>
                val tradingState = clientState.latestSnapshot.signedSnapshot.snapshotTradingState
                // Client's entitled balance = client balance + locked in orders
                // (exchange balance is what the exchange owes/is owed, not part of client's entitlement)
                val clientEntitledBalance = tradingState.tsClientBalance +
                    CosmexValidator.lockedInOrders(tradingState.tsOrders)
                // Compare with on-chain locked value
                val lockedValue = LedgerToPlutusTranslation.getValue(clientState.lockedValue)
                clientEntitledBalance != lockedValue
    }

    /** Find all clients that need rebalancing */
    def findClientsNeedingRebalancing(): Set[ClientId] = {
        clientStates.keys.filter(needsRebalancing).toSet
    }

    /** Handle fix-balance request - initiates global rebalancing */
    def handleFixBalance(
        requestingClientId: ClientId
    ): Either[(String, String), Unit] = {
        // Check if already rebalancing
        if rebalancingState.get().isDefined then
            return Left((ErrorCode.RebalancingInProgress, "Rebalancing already in progress"))

        // Find all clients needing rebalancing
        val affectedClients = findClientsNeedingRebalancing()

        if affectedClients.isEmpty then
            println(s"[Server] No clients need rebalancing - on-chain state matches snapshot state")
            return Left(
              (
                ErrorCode.NoRebalancingNeeded,
                "All client balances are already synchronized with on-chain state"
              )
            )

        println(s"[Server] Starting rebalancing for ${affectedClients.size} clients")

        // Build the rebalance transaction
        // We need to fetch current UTxOs for all affected channels
        val channelUtxos = affectedClients.flatMap { clientId =>
            clientStates.get(clientId).flatMap { clientState =>
                provider.findUtxo(clientState.channelRef).await() match
                    case Right(utxo) => Some((clientId, utxo, clientState))
                    case Left(_) =>
                        println(s"[Server] Warning: Could not find UTxO for client $clientId")
                        None
            }
        }.toSeq

        if channelUtxos.isEmpty then
            return Left((ErrorCode.RebalancingFailed, "Could not find any channel UTxOs"))

        // Build rebalance transaction using CosmexTransactions
        val txBuilder = CosmexTransactions(exchangeParams, env)

        // Reconstruct OnChainState from ClientState (we have all the info we need)
        val channelDataWithOnChainState = channelUtxos.map { case (clientId, utxo, state) =>
            // Compute clientPkh from clientPubKey
            val clientPkh = scalus.ledger.api.v2.PubKeyHash(
              platform.blake2b_224(state.clientPubKey)
            )
            val clientTxOutRef = scalus.ledger.api.v3.TxOutRef(
              scalus.ledger.api.v3.TxId(state.channelRef.transactionId),
              state.channelRef.index
            )
            val onChainState = OnChainState(
              clientPkh = clientPkh,
              clientPubKey = state.clientPubKey,
              clientTxOutRef = clientTxOutRef,
              channelState = OnChainChannelState.OpenState
            )
            (utxo, onChainState, state.latestSnapshot.signedSnapshot.snapshotTradingState)
        }

        val rebalanceTx = txBuilder.rebalance(
          channelDataWithOnChainState,
          exchangeParams.exchangePkh
        )

        // Set rebalancing state
        val context = RebalancingContext(
          startTime = Instant.now(),
          affectedClients = affectedClients,
          rebalanceTx = rebalanceTx,
          collectedSignatures = Map.empty
        )

        if !rebalancingState.compareAndSet(None, Some(context)) then
            return Left((ErrorCode.RebalancingInProgress, "Rebalancing started by another request"))

        // Send RebalanceRequired to all affected clients
        affectedClients.foreach { clientId =>
            clientChannels.get(clientId).foreach { channel =>
                println(s"[Server] Sending RebalanceRequired to client $clientId")
                channel.send(ClientResponse.RebalanceRequired(rebalanceTx))
            }
        }

        Right(())
    }

    /** Handle signed rebalance transaction from a client */
    def handleSignRebalance(
        clientId: ClientId,
        signedTx: Transaction
    ): Either[(String, String), Unit] = {
        rebalancingState.get() match
            case None =>
                Left((ErrorCode.InternalError, "No rebalancing in progress"))
            case Some(context) =>
                if !context.affectedClients.contains(clientId) then
                    return Left((ErrorCode.InternalError, "Client not part of rebalancing"))

                // Store the signed transaction
                val newContext = context.copy(
                  collectedSignatures = context.collectedSignatures + (clientId -> signedTx)
                )
                rebalancingState.set(Some(newContext))

                println(
                  s"[Server] Received signature from client $clientId (${newContext.collectedSignatures.size}/${context.affectedClients.size})"
                )

                // Check if we have all signatures
                if newContext.collectedSignatures.size == context.affectedClients.size then
                    completeRebalancing(newContext)
                else Right(())
    }

    /** Complete rebalancing by assembling and submitting the final transaction */
    private def completeRebalancing(context: RebalancingContext): Either[(String, String), Unit] = {
        println(s"[Server] All signatures collected, assembling final transaction")

        // Combine all signatures from collected transactions
        // The final transaction needs witness set from all clients + exchange
        import scalus.cardano.ledger.{TaggedSortedSet, VKeyWitness}
        val allWitnesses: Seq[VKeyWitness] = context.collectedSignatures.values.flatMap { tx =>
            tx.witnessSet.vkeyWitnesses.toSeq
        }.toSeq

        // Create final transaction with all witnesses
        val finalWitnessSet = context.rebalanceTx.witnessSet.copy(
          vkeyWitnesses = TaggedSortedSet.from(allWitnesses)
        )
        val finalTx = context.rebalanceTx.copy(
          witnessSet = finalWitnessSet
        )

        // Sign with exchange key and submit
        // TODO: Add exchange signature to finalTx

        println(s"[Server] Submitting rebalance transaction: ${finalTx.id.toHex.take(16)}...")

        provider.submitAndWait(finalTx, maxAttempts = 30, delayMs = 1000) match {
            case Left(error) =>
                println(s"[Server] Rebalance transaction failed: $error")
                // Notify clients of failure
                context.affectedClients.foreach { clientId =>
                    clientChannels.get(clientId).foreach { channel =>
                        channel.send(ClientResponse.RebalanceAborted(s"Transaction failed: $error"))
                    }
                }
                // Release lock
                rebalancingState.set(None)
                Left((ErrorCode.RebalancingFailed, s"Transaction failed: $error"))

            case Right(_) =>
                println(s"[Server] Rebalance transaction confirmed!")

                // Update client states with new locked values
                context.affectedClients.foreach { clientId =>
                    clientStates.get(clientId).foreach { clientState =>
                        // Find the new output for this client in the transaction
                        // For now, we assume locked value matches snapshot total after rebalancing
                        val tradingState =
                            clientState.latestSnapshot.signedSnapshot.snapshotTradingState
                        val newLockedValue = tradingState.tsClientBalance +
                            tradingState.tsExchangeBalance +
                            CosmexValidator.lockedInOrders(tradingState.tsOrders)

                        val updatedState = clientState.copy(
                          lockedValue = newLockedValue.toLedgerValue
                        )
                        clientStates.put(clientId, updatedState)

                        // Send completion notification
                        clientChannels.get(clientId).foreach { channel =>
                            channel.send(
                              ClientResponse.RebalanceComplete(clientState.latestSnapshot)
                            )
                        }
                    }
                }

                // Release lock
                rebalancingState.set(None)
                Right(())
        }
    }

    def reply(@unused response: ClientResponse): List[ServerEvent] = {
        List.empty
    }
}

object Server {
    def main(args: Array[String]): Unit = {
        println("Hello, world!")
    }

}

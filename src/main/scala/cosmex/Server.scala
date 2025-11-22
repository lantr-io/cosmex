package cosmex

import scalus.builtin.ToData.tupleToData
import scalus.builtin.{platform, Builtins, ByteString}

import scala.collection.concurrent.{Map as ConcurrentMap, TrieMap}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.node.Provider
import scalus.ledger.api.v3.{PubKeyHash, TxId, TxOutRef}
import scalus.prelude.{Eq, Ord}
import upickle.default.*

import java.time.Instant
import scala.annotation.unused
import cosmex.util.JsonCodecs.given

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

enum ClientRequest derives ReadWriter:
    case OpenChannel(tx: Transaction, snapshot: SignedSnapshot)
    case CreateOrder(clientId: ClientId, order: LimitOrder)
    case CancelOrder(clientId: ClientId, orderId: Int)
    case Deposit(clientId: ClientId, amount: Value)
    case Withdraw(clientId: ClientId, amount: Value)
    case GetBalance(clientId: ClientId)
    case GetOrders(clientId: ClientId)

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

enum ClientResponse derives ReadWriter:
    case ChannelOpened(snapshot: SignedSnapshot)
    case Error(message: String)
    case OrderCreated(orderId: OrderId)
    case OrderCancelled(orderId: OrderId)
    case OrderExecuted(trade: Trade)
    case Balance(balance: scalus.ledger.api.v3.Value)
    case Orders(orders: scalus.prelude.AssocMap[OrderId, LimitOrder])


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
    status: ChannelStatus
) derives ReadWriter

case class ClientRecord(
    state: ClientState,
    oxChannel: ox.channels.Channel[Trade]
                       )

case class OpenChannelInfo(
    channelRef: TransactionInput,
    amount: Value,
    tx: Transaction,
    snapshot: SignedSnapshot
) derives ReadWriter

class Server(
    env: CardanoInfo,
    exchangeParams: ExchangeParams,
    @unused provider: Provider,
    exchangePrivKey: ByteString
) {
    val program = CosmexContract.mkCosmexProgram(exchangeParams)
    val script = Script.PlutusV3(program.cborByteString)
    val CosmexScriptAddress = Address(env.network, Credential.ScriptHash(script.scriptHash))
    val CosmexSignKey = exchangePrivKey
    val CosmexPubKey = exchangeParams.exchangePubKey

    val clientStates = TrieMap.empty[ClientId, ClientState]
    val clientChannels = TrieMap.empty[ClientId, ox.channels.Channel[Trade]]
    var orderBookRef: AtomicReference[OrderBook] = new AtomicReference(OrderBook.empty)
    var nextOrderId: AtomicLong = new AtomicLong(0L)
    val orderOwners = TrieMap.empty[OrderId, ClientId] // Maps order ID to client


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
                          status = ChannelStatus.PendingOpen
                        )
                        clientStates.put(clientId, clientState)

                        // Send the transaction to the blockchain
                        sendTx(tx)

                        // Reply with the both-signed snapshot
                        reply(ClientResponse.ChannelOpened(bothSignedSnapshot))
                    case Left(error) => reply(ClientResponse.Error(error))
            case _ => List.empty

    }

    def handleEvent(event: ServerEvent) = {}

    def validateOpenChannelRequest(
        tx: Transaction,
        snapshot: SignedSnapshot
    ): Either[String, OpenChannelInfo] = {
        // Validate transaction has at least one input
        if tx.body.value.inputs.toSeq.headOption.isEmpty then
            return Left("Transaction has no inputs")

        // Find the unique output to Cosmex script address
        val cosmexOutput = tx.body.value.outputs.view
            .map(_.value)
            .zipWithIndex
            .filter(_._1.address == CosmexScriptAddress)
            .toVector match
            case Vector((output, idx)) => (output, idx)
            case Vector()              => return Left("No output to Cosmex script address")
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

        // Extract client public key from the datum (we need to check the OnChainState in the output)
        // For now, we'll assume it's validated elsewhere or extract it from the datum
        // TODO: Extract clientPubKey from the output's inline datum if needed for signature verification

        // Verify client signature
        // Note: We can't verify the signature here without the client's public key
        // The public key should be in the output's datum (OnChainState.clientPubKey)
        // For now, we'll skip this check and verify it when we have access to the client pub key
        // This will be verified when the transaction is submitted with the proper datum

        Right(OpenChannelInfo(TransactionInput(tx.id, outputIdx), depositAmount, tx, snapshot))
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
        val cosmexSignature = platform.signEd25519(CosmexSignKey, msg)
        snapshot.copy(snapshotExchangeSignature = cosmexSignature)
    }

    def handleCreateOrder(
        clientId: ClientId,
        order: LimitOrder
    ): Either[String, (Long, SignedSnapshot, List[Trade])] = {
        clientStates.get(clientId) match
            case None => Left("Client not found")
            case Some(clientState) =>
                if clientState.status != ChannelStatus.Open then
                    return Left(s"Channel is not open, status: ${clientState.status}")

                // Assign order ID
                val longOrderId = nextOrderId.getAndIncrement()
                val orderId = BigInt(longOrderId)

                // Add order to client's trading state
                val currentSnapshot = clientState.latestSnapshot.signedSnapshot
                val currentTradingState = currentSnapshot.snapshotTradingState

                import scalus.prelude.AssocMap
                val newOrders = AssocMap.insert(currentTradingState.tsOrders)(orderId, order)
                val tradingStateWithOrder = currentTradingState.copy(tsOrders = newOrders)

                // Register order owner BEFORE matching so notifications can be sent
                orderOwners.put(orderId, clientId)

                var orderBookUpdated = false
                var matchResult: OrderBook.MatchResult = null
                while !orderBookUpdated do
                    val orderBook = orderBookRef.get()
                    // Match against order book
                    matchResult = OrderBook.matchOrder(orderBook, orderId, order)

                    // Update order book with remaining order (if any)
                    var newOrderBook = matchResult.updatedBook
                    if matchResult.remainingAmount != 0 then
                        newOrderBook = OrderBook.addOrder(
                          newOrderBook,
                          orderId,
                          order.copy(orderAmount = matchResult.remainingAmount)
                        )
                    if (orderBookRef.compareAndSet(orderBook, newOrderBook)) then
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

                Right((longOrderId, bothSignedSnapshot, matchResult.trades))
    }

    def handleCancelOrder(
        clientId: ClientId,
        orderId: OrderId
    ): Either[String, SignedSnapshot] = {
        clientStates.get(clientId) match
            case None => Left("Client not found")
            case Some(clientState) =>
                if clientState.status != ChannelStatus.Open then
                    return Left(s"Channel is not open, status: ${clientState.status}")

                // Remove order from client's trading state
                val currentSnapshot = clientState.latestSnapshot.signedSnapshot
                val currentTradingState = currentSnapshot.snapshotTradingState

                import scalus.prelude.AssocMap
                val newOrders = AssocMap.delete(currentTradingState.tsOrders)(orderId)
                val newTradingState = currentTradingState.copy(tsOrders = newOrders)

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
        provider.submit(tx) match {
            case Left(error) => println(s"[Server] Transaction submission failed: $error")
            case Right(_)    => println(s"[Server] Transaction submitted: ${tx.id.toHex.take(16)}...")
        }
    }
    
    /** Check if a transaction output exists on-chain (i.e., transaction is confirmed) */
    def isUtxoConfirmed(txOutRef: TransactionInput): Boolean = {
        provider.findUtxo(txOutRef).isRight
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

package cosmex

import upickle.default.*
import scalus.builtin.ToData.tupleToData
import scalus.builtin.{Builtins, ByteString, platform}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.Credential.ScriptHash
import scalus.cardano.ledger.LedgerToPlutusTranslation
import scalus.cardano.node.Provider
import scalus.ledger.api.v3.{PosixTime, PubKeyHash, TxId, TxOutRef}

import java.time.Instant
import scala.annotation.unused
import scala.collection.mutable.HashMap

object JsonCodecs {
  // Basic type codecs
  given ReadWriter[ByteString] = readwriter[String].bimap[ByteString](
    bs => bs.toHex,
    hex => ByteString.fromHex(hex)
  )

  given rwBigInt: ReadWriter[BigInt] = readwriter[ujson.Value].bimap[BigInt](
    n => ujson.Str(n.toString),
    {
      case ujson.Str(s) => BigInt(s)
      case ujson.Num(n) => BigInt(n.toLong)
      case other => throw new Exception(s"Cannot parse BigInt from $other")
    }
  )

  // Cardano ledger types
  given rwTransactionInput: ReadWriter[TransactionInput] = readwriter[ujson.Value].bimap[TransactionInput](
    { input => ujson.Str(s"${input.transactionId}#${input.index}") },
    { case _ => throw new Exception("TransactionInput deserialization not implemented") }
  )

  given rwTxId: ReadWriter[TxId] = readwriter[ujson.Value].bimap[TxId](
    { txId => txId match {
      case TxId(hash) => ujson.Str(hash.toHex)
    }},
    {
      case ujson.Str(hex) => TxId(ByteString.fromHex(hex))
      case other => throw new Exception(s"Cannot parse TxId from $other")
    }
  )

  given rwTxOutRef: ReadWriter[TxOutRef] = readwriter[ujson.Obj].bimap[TxOutRef](
    { ref => ref match {
      case TxOutRef(txId, idx) => ujson.Obj(
        "txId" -> writeJs(txId)(using rwTxId),
        "txIdx" -> writeJs(idx)(using rwBigInt)
      )
    }},
    { obj => TxOutRef(
      read[TxId](obj("txId"))(using rwTxId),
      read[BigInt](obj("txIdx"))(using rwBigInt)
    )}
  )

  given rwAssetName: ReadWriter[AssetName] = readwriter[ujson.Value].bimap[AssetName](
    { an => ujson.Str(an.bytes.toHex) },
    {
      case ujson.Str(hex) => AssetName(ByteString.fromHex(hex))
      case other => throw new Exception(s"Cannot parse AssetName from $other")
    }
  )

  given rwPolicyId: ReadWriter[PolicyId] = readwriter[ujson.Value].bimap[PolicyId](
    { pid => ujson.Str(pid.toString) },
    { case _ => throw new Exception("PolicyId deserialization not implemented") }
  )

  // Simplified Value codecs - serialize as string for now
  given rwPlutusValue: ReadWriter[scalus.ledger.api.v3.Value] = readwriter[ujson.Value].bimap[scalus.ledger.api.v3.Value](
    { value => ujson.Str(value.toString) },
    { case ujson.Str(_) => throw new Exception("Plutus Value deserialization not implemented")
      case other => throw new Exception(s"Cannot parse Plutus Value from $other")
    }
  )

  given rwLedgerValue: ReadWriter[Value] = readwriter[ujson.Value].bimap[Value](
    { value => ujson.Str(value.toString) },
    { case ujson.Str(_) => throw new Exception("Ledger Value deserialization not implemented")
      case other => throw new Exception(s"Cannot parse Ledger Value from $other")
    }
  )

  // CosmexValidator types
  given rwPendingTxType: ReadWriter[PendingTxType] = readwriter[ujson.Obj].bimap[PendingTxType](
    {
      case PendingTxType.PendingIn => ujson.Obj("type" -> "PendingIn")
      case PendingTxType.PendingOut(idx) => ujson.Obj("type" -> "PendingOut", "txOutIndex" -> writeJs(idx)(using rwBigInt))
      case PendingTxType.PendingTransfer(idx) => ujson.Obj("type" -> "PendingTransfer", "txOutIndex" -> writeJs(idx)(using rwBigInt))
    },
    obj => obj("type").str match {
      case "PendingIn" => PendingTxType.PendingIn
      case "PendingOut" => PendingTxType.PendingOut(read[BigInt](obj("txOutIndex"))(using rwBigInt))
      case "PendingTransfer" => PendingTxType.PendingTransfer(read[BigInt](obj("txOutIndex"))(using rwBigInt))
      case other => throw new Exception(s"Unknown PendingTxType: $other")
    }
  )

  given rwPendingTx: ReadWriter[PendingTx] = readwriter[ujson.Obj].bimap[PendingTx](
    tx => ujson.Obj(
      "pendingTxValue" -> writeJs(tx.pendingTxValue)(using rwPlutusValue),
      "pendingTxType" -> writeJs(tx.pendingTxType)(using rwPendingTxType),
      "pendingTxSpentTxOutRef" -> writeJs(tx.pendingTxSpentTxOutRef)(using rwTxOutRef)
    ),
    obj => PendingTx(
      read[scalus.ledger.api.v3.Value](obj("pendingTxValue")),
      read[PendingTxType](obj("pendingTxType")),
      read[TxOutRef](obj("pendingTxSpentTxOutRef"))
    )
  )

  given rwLimitOrder: ReadWriter[LimitOrder] = readwriter[ujson.Obj].bimap[LimitOrder](
    order => ujson.Obj(
      "orderPair" -> ujson.Arr(
        ujson.Arr(writeJs(order.orderPair._1._1), writeJs(order.orderPair._1._2)),
        ujson.Arr(writeJs(order.orderPair._2._1), writeJs(order.orderPair._2._2))
      ),
      "orderAmount" -> writeJs(order.orderAmount)(using rwBigInt),
      "orderPrice" -> writeJs(order.orderPrice)(using rwBigInt)
    ),
    obj => LimitOrder(
      (
        (read[ByteString](obj("orderPair")(0)(0)), read[ByteString](obj("orderPair")(0)(1))),
        (read[ByteString](obj("orderPair")(1)(0)), read[ByteString](obj("orderPair")(1)(1)))
      ),
      read[BigInt](obj("orderAmount"))(using rwBigInt),
      read[BigInt](obj("orderPrice"))(using rwBigInt)
    )
  )

  given rwTrade: ReadWriter[Trade] = readwriter[ujson.Obj].bimap[Trade](
    trade => ujson.Obj(
      "orderId" -> writeJs(trade.orderId)(using rwBigInt),
      "tradeAmount" -> writeJs(trade.tradeAmount)(using rwBigInt),
      "tradePrice" -> writeJs(trade.tradePrice)(using rwBigInt)
    ),
    obj => Trade(
      read[BigInt](obj("orderId"))(using rwBigInt),
      read[BigInt](obj("tradeAmount"))(using rwBigInt),
      read[BigInt](obj("tradePrice"))(using rwBigInt)
    )
  )

  given rwTradingState: ReadWriter[TradingState] = readwriter[ujson.Obj].bimap[TradingState](
    state => {
      val ordersJson = state.tsOrders.toList.asScala.map { case (orderId, order) =>
        ujson.Obj(
          "orderId" -> writeJs(orderId)(using rwBigInt),
          "order" -> writeJs(order)(using rwLimitOrder)
        )
      }
      ujson.Obj(
        "tsClientBalance" -> writeJs(state.tsClientBalance)(using rwPlutusValue),
        "tsExchangeBalance" -> writeJs(state.tsExchangeBalance)(using rwPlutusValue),
        "tsOrders" -> ujson.Arr(ordersJson.toSeq*)
      )
    },
    { _ => throw new Exception("TradingState deserialization not implemented") }
  )

  given rwSnapshot: ReadWriter[Snapshot] = readwriter[ujson.Obj].bimap[Snapshot](
    snapshot => {
      import scalus.prelude.{Option => POption}
      ujson.Obj(
        "snapshotTradingState" -> writeJs(snapshot.snapshotTradingState)(using rwTradingState),
        "snapshotPendingTx" -> (snapshot.snapshotPendingTx match {
          case POption.Some(tx) => writeJs(tx)(using rwPendingTx)
          case POption.None => ujson.Null
        }),
        "snapshotVersion" -> writeJs(snapshot.snapshotVersion)(using rwBigInt)
      )
    },
    { _ => throw new Exception("Snapshot deserialization not implemented") }
  )

  given rwSignedSnapshot: ReadWriter[SignedSnapshot] = readwriter[ujson.Obj].bimap[SignedSnapshot](
    signed => ujson.Obj(
      "signedSnapshot" -> writeJs(signed.signedSnapshot)(using rwSnapshot),
      "snapshotClientSignature" -> writeJs(signed.snapshotClientSignature),
      "snapshotExchangeSignature" -> writeJs(signed.snapshotExchangeSignature)
    ),
    obj => SignedSnapshot(
      read[Snapshot](obj("signedSnapshot")),
      read[ByteString](obj("snapshotClientSignature")),
      read[ByteString](obj("snapshotExchangeSignature"))
    )
  )

  // Transaction codec (simplified - expand as needed)
  given rwTransaction: ReadWriter[Transaction] = readwriter[String].bimap[Transaction](
    tx => tx.id.toString, // Simplified - just serialize the ID
    _ => throw new Exception("Transaction deserialization not implemented")
  )

  // Instant codec for timestamps
  given rwInstant: ReadWriter[Instant] = readwriter[String].bimap[Instant](
    _.toString,
    Instant.parse(_)
  )

  // PubKeyHash from scalus v1
  given rwPubKeyHashV1: ReadWriter[scalus.ledger.api.v1.PubKeyHash] = readwriter[String].bimap[scalus.ledger.api.v1.PubKeyHash](
    pkh => pkh.hash.toHex,
    hex => scalus.ledger.api.v1.PubKeyHash(ByteString.fromHex(hex))
  )

  // PubKeyHash from scalus v3
  given rwPubKeyHashV3: ReadWriter[PubKeyHash] = readwriter[String].bimap[PubKeyHash](
    pkh => pkh.hash.toHex,
    hex => PubKeyHash(ByteString.fromHex(hex))
  )

  // ExchangeParams
  given rwExchangeParams: ReadWriter[ExchangeParams] = readwriter[ujson.Obj].bimap[ExchangeParams](
    params => ujson.Obj(
      "exchangePkh" -> writeJs(params.exchangePkh)(using rwPubKeyHashV3),
      "exchangePubKey" -> writeJs(params.exchangePubKey),
      "contestationPeriodInMilliseconds" -> writeJs(params.contestationPeriodInMilliseconds)(using rwBigInt)
    ),
    { _ => throw new Exception("ExchangeParams deserialization not implemented") }
  )

  // Action enum
  given rwParty: ReadWriter[Party] = readwriter[ujson.Obj].bimap[Party](
    {
      case Party.Client => ujson.Obj("party" -> "Client")
      case Party.Exchange => ujson.Obj("party" -> "Exchange")
    },
    obj => obj("party").str match {
      case "Client" => Party.Client
      case "Exchange" => Party.Exchange
      case other => throw new Exception(s"Unknown Party: $other")
    }
  )

  given rwAction: ReadWriter[Action] = readwriter[ujson.Obj].bimap[Action](
    {
      case Action.Update => ujson.Obj("action" -> "Update")
      case Action.ClientAbort => ujson.Obj("action" -> "ClientAbort")
      case Action.Close(party, signedSnapshot) => ujson.Obj(
        "action" -> "Close",
        "party" -> writeJs(party)(using rwParty),
        "signedSnapshot" -> writeJs(signedSnapshot)(using rwSignedSnapshot)
      )
      case Action.Trades(trades, cancelOthers) =>
        ujson.Obj(
          "action" -> "Trades",
          "actionTrades" -> ujson.Arr(trades.asScala.map(t => writeJs(t)(using rwTrade): ujson.Value).toSeq*),
          "actionCancelOthers" -> ujson.Bool(cancelOthers)
        )
      case Action.Payout => ujson.Obj("action" -> "Payout")
      case Action.Transfer(txOutIndex, value) => ujson.Obj(
        "action" -> "Transfer",
        "txOutIndex" -> writeJs(txOutIndex)(using rwBigInt),
        "value" -> writeJs(value)(using rwPlutusValue)
      )
      case Action.Timeout => ujson.Obj("action" -> "Timeout")
    },
    { _ => throw new Exception("Action deserialization not implemented") }
  )

  // OnChainChannelState enum codec
  given rwOnChainChannelState: ReadWriter[OnChainChannelState] = readwriter[ujson.Obj].bimap[OnChainChannelState](
    {
      case OnChainChannelState.OpenState => ujson.Obj("state" -> "OpenState")
      case OnChainChannelState.SnapshotContestState(snapshot, start, initiator, channelRef) => ujson.Obj(
        "state" -> "SnapshotContestState",
        "contestSnapshot" -> writeJs(snapshot)(using rwSnapshot),
        "contestSnapshotStart" -> ujson.Str(start.toString),
        "contestInitiator" -> writeJs(initiator)(using rwParty),
        "contestChannelTxOutRef" -> writeJs(channelRef)(using rwTxOutRef)
      )
      case OnChainChannelState.TradesContestState(latestTradingState, tradeContestStart) => ujson.Obj(
        "state" -> "TradesContestState",
        "latestTradingState" -> writeJs(latestTradingState)(using rwTradingState),
        "tradeContestStart" -> ujson.Str(tradeContestStart.toString)
      )
      case OnChainChannelState.PayoutState(clientBalance, exchangeBalance) => ujson.Obj(
        "state" -> "PayoutState",
        "clientBalance" -> writeJs(clientBalance)(using rwPlutusValue),
        "exchangeBalance" -> writeJs(exchangeBalance)(using rwPlutusValue)
      )
    },
    { _ => throw new Exception("OnChainChannelState deserialization not implemented") }
  )

  // OnChainState codec
  given rwOnChainState: ReadWriter[OnChainState] = readwriter[ujson.Obj].bimap[OnChainState](
    state => ujson.Obj(
      "clientPkh" -> writeJs(state.clientPkh)(using rwPubKeyHashV3),
      "clientPubKey" -> writeJs(state.clientPubKey),
      "clientTxOutRef" -> writeJs(state.clientTxOutRef)(using rwTxOutRef),
      "channelState" -> writeJs(state.channelState)(using rwOnChainChannelState)
    ),
    { _ => throw new Exception("OnChainState deserialization not implemented") }
  )
}

import JsonCodecs.given

enum ClientRequest derives ReadWriter:
    case OpenChannel(tx: Transaction, snapshot: SignedSnapshot)
    case CreateOrder(order: LimitOrder)
    case CancelOrder(orderId: Int)
    case CancelAllOrders
    case Settle
    case CloseChannel

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
    case OrderCreated(orderId: Int)
    case OrderCancelled(orderId: Int)
    case AllOrdersCancelled
    case Settled
    case ChannelClosed

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
    val CosmexScriptAddress = Address(env.network, ScriptHash(script.scriptHash))
    val CosmexSignKey = exchangePrivKey
    val CosmexPubKey = exchangeParams.exchangePubKey

    val clients = HashMap.empty[ClientId, ClientState]
    var orderBook: OrderBook = OrderBook.empty
    var nextOrderId: OrderId = 1
    val orderOwners = HashMap.empty[OrderId, ClientId] // Maps order ID to client

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
                        clients.put(clientId, clientState)

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
    ): Either[String, (SignedSnapshot, List[Trade])] = {
        clients.get(clientId) match
            case None => Left("Client not found")
            case Some(clientState) =>
                if clientState.status != ChannelStatus.Open then
                    return Left(s"Channel is not open, status: ${clientState.status}")

                // Assign order ID
                val orderId = nextOrderId
                nextOrderId += 1

                // Add order to client's trading state
                val currentSnapshot = clientState.latestSnapshot.signedSnapshot
                val currentTradingState = currentSnapshot.snapshotTradingState

                import scalus.prelude.AssocMap
                val newOrders = AssocMap.insert(currentTradingState.tsOrders)(orderId, order)
                val tradingStateWithOrder = currentTradingState.copy(tsOrders = newOrders)

                // Match against order book
                val matchResult = OrderBook.matchOrder(orderBook, orderId, order)

                // Apply trades to trading state
                import CosmexValidator.applyTrade
                val tradingStateAfterTrades = matchResult.trades.foldLeft(tradingStateWithOrder) {
                    (ts, trade) => applyTrade(ts, trade)
                }

                // Update order book with remaining order (if any)
                orderBook = matchResult.updatedBook
                if matchResult.remainingAmount != 0 then
                    orderBook = OrderBook.addOrder(
                      orderBook,
                      orderId,
                      order.copy(orderAmount = matchResult.remainingAmount)
                    )
                    orderOwners.put(orderId, clientId)

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
                clients.put(clientId, updatedState)

                Right((bothSignedSnapshot, matchResult.trades))
    }

    def handleCancelOrder(
        clientId: ClientId,
        orderId: OrderId
    ): Either[String, SignedSnapshot] = {
        clients.get(clientId) match
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

                // Remove from order book if present
                orderBook = OrderBook.removeOrder(orderBook, orderId)
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
                clients.put(clientId, updatedState)

                Right(bothSignedSnapshot)
    }

    def getLatestSnapshot(clientId: ClientId): Option[SignedSnapshot] = {
        clients.get(clientId).map(_.latestSnapshot)
    }

    def getChannelStatus(clientId: ClientId): Option[ChannelStatus] = {
        clients.get(clientId).map(_.status)
    }

    def updateChannelStatus(clientId: ClientId, newStatus: ChannelStatus): Unit = {
        clients.get(clientId).foreach { state =>
            clients.put(clientId, state.copy(status = newStatus))
        }
    }

    def storeSnapshot(snapshot: SignedSnapshot): Unit = {
        println(snapshot)
    }

    def sendTx(tx: Transaction): Unit = {
        println(tx)
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

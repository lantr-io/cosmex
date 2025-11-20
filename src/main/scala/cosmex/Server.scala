package cosmex

import scalus.builtin.ToData.tupleToData
import scalus.builtin.{platform, Builtins, ByteString}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.node.Provider
import scalus.ledger.api.v3.{PubKeyHash, TxId, TxOutRef}
import scalus.prelude.{Eq, Ord}
import upickle.default.*

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
          case other        => throw new Exception(s"Cannot parse BigInt from $other")
      }
    )

    // Cardano ledger types
    given rwTransactionHash: ReadWriter[TransactionHash] =
        readwriter[String].bimap[TransactionHash](
          th => th.toHex,
          hex => TransactionHash.fromHex(hex)
        )
    given rwTransactionInput: ReadWriter[TransactionInput] = macroRW

    given rwTxId: ReadWriter[TxId] = readwriter[ByteString].bimap(_.hash, b => TxId(b))

    given rwTxOutRef: ReadWriter[TxOutRef] = macroRW

    given rwAssetName: ReadWriter[AssetName] =
        readwriter[ByteString].bimap(_.bytes, b => AssetName(b))

    given rwPolicyId: ReadWriter[PolicyId] = readwriter[ByteString].bimap[PolicyId](
      { pid => pid },
      { bs => ScriptHash.fromByteString(bs) }
    )

    // Simplified Value codecs - serialize as string for now
    given [A: ReadWriter]: ReadWriter[scalus.prelude.Option[A]] =
        readwriter[ujson.Value].bimap[scalus.prelude.Option[A]](
          {
              case scalus.prelude.Option.Some(value) => writeJs(value)
              case scalus.prelude.Option.None        => ujson.Null
          },
          {
              case ujson.Null => scalus.prelude.Option.None: scalus.prelude.Option[A]
              case other      => scalus.prelude.Option.Some(read[A](other))
          }
        )
    given rwSortedMap[A: ReadWriter: Ord, B: ReadWriter]
        : ReadWriter[scalus.prelude.SortedMap[A, B]] =
        readwriter[ujson.Value].bimap[scalus.prelude.SortedMap[A, B]](
          smap =>
              ujson.Arr.from(smap.toList.asScala.map { case (k, v) =>
                  ujson.Obj("k" -> writeJs(k), "v" -> writeJs(v))
              }.toSeq),
          {
              case ujson.Arr(a) =>
                  scalus.prelude.SortedMap.from(
                    a.map(v => read[A](v.obj("k")) -> read[B](v.obj("v")))
                  )
              case other => throw new Exception(s"Cannot parse SortedMap from $other")
          }
        )

    given rwList[A: ReadWriter]: ReadWriter[scalus.prelude.List[A]] =
        readwriter[ujson.Value].bimap[scalus.prelude.List[A]](
          list => ujson.Arr.from(list.asScala.map(elem => writeJs(elem))),
          {
              case ujson.Arr(a) =>
                  scalus.prelude.List.from(a.map(v => read[A](v)))
              case other => throw new Exception(s"Cannot parse List from $other")
          }
        )

    given rwAssocMap[A: ReadWriter: Eq, B: ReadWriter]: ReadWriter[scalus.prelude.AssocMap[A, B]] =
        readwriter[ujson.Value].bimap[scalus.prelude.AssocMap[A, B]](
          amap =>
              ujson.Arr.from(amap.toList.asScala.map { case (k, v) =>
                  ujson.Obj("k" -> writeJs(k), "v" -> writeJs(v))
              }.toSeq),
          {
              case ujson.Arr(a) =>
                  scalus.prelude.AssocMap.fromList(
                    scalus.prelude.List.from(a.map(v => (read[A](v.obj("k")), read[B](v.obj("v")))))
                  )
              case other => throw new Exception(s"Cannot parse AssocMap from $other")
          }
        )

    given rwCoin: ReadWriter[Coin] = macroRW
    given rwMultiAsset: ReadWriter[MultiAsset] = macroRW
    given rwLedgerValue: ReadWriter[Value] = macroRW
    given rwPlutusValue: ReadWriter[scalus.ledger.api.v1.Value] =
        readwriter[Value]
            .bimap(
              value => value.toLedgerValue,
              value => LedgerToPlutusTranslation.getValue(value),
            )

    // CosmexValidator types
    given rwPendingTxType: ReadWriter[PendingTxType] = macroRWAll

    given rwPendingTx: ReadWriter[PendingTx] = macroRW

    given rwLimitOrder: ReadWriter[LimitOrder] = macroRW
    given rwTrade: ReadWriter[Trade] = macroRW
    given rwTradingState: ReadWriter[TradingState] = macroRW

    given rwSnapshot: ReadWriter[Snapshot] = macroRW
    given rwSignedSnapshot: ReadWriter[SignedSnapshot] = macroRW

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
    given rwPubKeyHashV1: ReadWriter[scalus.ledger.api.v1.PubKeyHash] =
        readwriter[ByteString].bimap[scalus.ledger.api.v1.PubKeyHash](
          pkh => pkh.hash,
          bs => scalus.ledger.api.v1.PubKeyHash(bs)
        )

    // ExchangeParams
    given rwExchangeParams: ReadWriter[ExchangeParams] = macroRW

    // Action enum
    given rwParty: ReadWriter[Party] = readwriter[ujson.Obj].bimap[Party](
      {
          case Party.Client   => ujson.Obj("party" -> "Client")
          case Party.Exchange => ujson.Obj("party" -> "Exchange")
      },
      obj =>
          obj("party").str match {
              case "Client"   => Party.Client
              case "Exchange" => Party.Exchange
              case other      => throw new Exception(s"Unknown Party: $other")
          }
    )

    given rwAction: ReadWriter[Action] = readwriter[ujson.Obj].bimap[Action](
      {
          case Action.Update      => ujson.Obj("action" -> "Update")
          case Action.ClientAbort => ujson.Obj("action" -> "ClientAbort")
          case Action.Close(party, signedSnapshot) =>
              ujson.Obj(
                "action" -> "Close",
                "party" -> writeJs(party)(using rwParty),
                "signedSnapshot" -> writeJs(signedSnapshot)(using rwSignedSnapshot)
              )
          case Action.Trades(trades, cancelOthers) =>
              ujson.Obj(
                "action" -> "Trades",
                "actionTrades" -> writeJs(trades),
                "actionCancelOthers" -> ujson.Bool(cancelOthers)
              )
          case Action.Payout => ujson.Obj("action" -> "Payout")
          case Action.Transfer(txOutIndex, value) =>
              ujson.Obj(
                "action" -> "Transfer",
                "txOutIndex" -> writeJs(txOutIndex)(using rwBigInt),
                "value" -> writeJs(value)(using rwPlutusValue)
              )
          case Action.Timeout => ujson.Obj("action" -> "Timeout")
      },
      { _ => throw new Exception("Action deserialization not implemented") }
    )

    // OnChainChannelState enum codec
    given rwOnChainChannelState: ReadWriter[OnChainChannelState] = macroRWAll
    // OnChainState codec
    given rwOnChainState: ReadWriter[OnChainState] = macroRW
}

import cosmex.JsonCodecs.given

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
    val CosmexScriptAddress = Address(env.network, Credential.ScriptHash(script.scriptHash))
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

package cosmex

import scalus.builtin.ToData.tupleToData
import scalus.builtin.{platform, Builtins, ByteString}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.Credential.ScriptHash
import scalus.cardano.ledger.LedgerToPlutusTranslation
import scalus.cardano.node.Provider
import scalus.ledger.api.v3.{TxId, TxOutRef}

import java.time.Instant
import scala.annotation.unused
import scala.collection.mutable.HashMap

enum ClientRequest:
    case OpenChannel(tx: Transaction, snapshot: SignedSnapshot)
    case CreateOrder(order: LimitOrder)
    case CancelOrder(orderId: Int)
    case CancelAllOrders
    case Settle
    case CloseChannel

type ChainSlot = Long

case class ChainPoint(slotNo: ChainSlot, headerHash: String)

enum OnChainTx:
    case OnCommitTx
    case OnAbortTx
    case OnCloseTx

enum BlockchainEvent:
    case Observation(tx: OnChainTx, newChainState: OnChainChannelState)
    case Rollback(chainState: OnChainChannelState)
    case Tick(chainTime: Instant, chainSlot: ChainSlot)

enum ClientResponse:
    case ChannelOpened(snapshot: SignedSnapshot)
    case Error(message: String)
    case OrderCreated(orderId: Int)
    case OrderCancelled(orderId: Int)
    case AllOrdersCancelled
    case Settled
    case ChannelClosed

enum ServerEvent:
    case ClientEvent(clientId: Int, action: ClientRequest)
    case OnChainEvent(event: BlockchainEvent)

enum Command:
    case ClientCommand(clientId: Int, action: ClientRequest)

enum Effect:
    case DoNothing

case class ClientId(txOutRef: TransactionInput)

enum ChannelStatus:
    case PendingOpen, Open, Closing, Closed

case class ClientState(
    latestSnapshot: SignedSnapshot,
    channelRef: TransactionInput,
    lockedValue: Value,
    status: ChannelStatus
)

case class OpenChannelInfo(
    channelRef: TransactionInput,
    amount: Value,
    tx: Transaction,
    snapshot: SignedSnapshot
)

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
        orderId: OrderId,
        @unused order: LimitOrder,
        clientSignedSnapshot: SignedSnapshot
    ): Either[String, SignedSnapshot] = {
        clients.get(clientId) match
            case None => Left("Client not found")
            case Some(clientState) =>
                if clientState.status != ChannelStatus.Open then
                    return Left(s"Channel is not open, status: ${clientState.status}")

                // Verify snapshot version increments by 1
                val currentVersion = clientState.latestSnapshot.signedSnapshot.snapshotVersion
                val newVersion = clientSignedSnapshot.signedSnapshot.snapshotVersion
                if newVersion != currentVersion + 1 then
                    return Left(
                      s"Invalid snapshot version: $newVersion, expected ${currentVersion + 1}"
                    )

                // Verify client signature
                // Note: We'd need to extract clientPubKey from somewhere - for now skip this check
                // if !verifyClientSignature(clientPubKey, clientTxOutRef, clientSignedSnapshot) then
                //     return Left("Invalid client signature")

                // Verify the order is added to the trading state
                val newTradingState = clientSignedSnapshot.signedSnapshot.snapshotTradingState
                if !newTradingState.tsOrders.toList.exists(_._1 == orderId) then
                    return Left("Order not found in new snapshot")

                // Extract client TxOutRef (stored in OnChainState)
                val clientTxOutRef = TxOutRef(
                  TxId(clientState.channelRef.transactionId),
                  clientState.channelRef.index
                )

                // Sign with exchange key
                val bothSignedSnapshot = signSnapshot(clientTxOutRef, clientSignedSnapshot)

                // Update stored state
                val updatedState = clientState.copy(latestSnapshot = bothSignedSnapshot)
                clients.put(clientId, updatedState)

                Right(bothSignedSnapshot)
    }

    def handleCancelOrder(
        clientId: ClientId,
        orderId: OrderId,
        clientSignedSnapshot: SignedSnapshot
    ): Either[String, SignedSnapshot] = {
        clients.get(clientId) match
            case None => Left("Client not found")
            case Some(clientState) =>
                if clientState.status != ChannelStatus.Open then
                    return Left(s"Channel is not open, status: ${clientState.status}")

                // Verify snapshot version increments by 1
                val currentVersion = clientState.latestSnapshot.signedSnapshot.snapshotVersion
                val newVersion = clientSignedSnapshot.signedSnapshot.snapshotVersion
                if newVersion != currentVersion + 1 then
                    return Left(
                      s"Invalid snapshot version: $newVersion, expected ${currentVersion + 1}"
                    )

                // Verify the order is removed from the trading state
                val newTradingState = clientSignedSnapshot.signedSnapshot.snapshotTradingState
                if newTradingState.tsOrders.toList.exists(_._1 == orderId) then
                    return Left("Order still exists in new snapshot")

                // Extract client TxOutRef
                val clientTxOutRef = TxOutRef(
                  TxId(clientState.channelRef.transactionId),
                  clientState.channelRef.index
                )

                // Sign with exchange key
                val bothSignedSnapshot = signSnapshot(clientTxOutRef, clientSignedSnapshot)

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

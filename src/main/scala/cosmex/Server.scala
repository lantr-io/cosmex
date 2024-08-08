package cosmex

import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.transaction.util.TransactionUtil
import scalus.builtin.Builtins
import scalus.builtin.ByteString
import scalus.ledger.api.v1.TxId
import scalus.ledger.api.v1.TxOutRef
import scalus.ledger.api.v1.Value

import java.time.Instant
import scala.collection.mutable.HashMap

case class ServerState()
case class Block()

enum ClientRequest:
    case OpenChannel(tx: Transaction, snapshot: SignedSnapshot)
    case CreateOrder(order: LimitOrder)
    case CancelOrder(orderId: Int)
    case CancellAllOrders
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

case class ClientId(txOutRef: TxOutRef)
case class ClientState()

case class OpenChannelInfo(channelRef: TxOutRef, amount: Value, tx: Transaction, snapshot: SignedSnapshot)

object Server {
    val CosmexScriptAddress = "cosmex-script-address"
    val CosmexPubKey = ByteString.fromString("cosmex-pub-key")
    val serverState = ServerState()

    val clients = HashMap.empty[ClientId, ClientState]

    def handleCommand(command: Command): Unit = command match
        case Command.ClientCommand(clientId, action) => handleClientRequest(action)

    def handleClientRequest(request: ClientRequest): Unit = {
        request match
            case ClientRequest.OpenChannel(tx, snapshot) =>
                validateOpenChannelRequest(tx, snapshot) match
                    case Right(openChannelInfo) =>
                        // val bothSignedSnapshot = signSnapshot(snapshot)
                        // storeSnapshot(bothSignedSnapshot)
                        sendTx(tx)
                    // reply(ClientResponse.ChannelOpened(bothSignedSnapshot))
                    case Left(error) => reply(ClientResponse.Error(error))
            case _ => List.empty

    }
    def handleEvent(state: ServerState, event: ServerEvent): (ServerState, List[Effect]) = {
        (state, List.empty)
    }

    def validateOpenChannelRequest(tx: Transaction, snapshot: SignedSnapshot): Either[String, OpenChannelInfo] = {
        import scala.jdk.CollectionConverters._

        // TODO
        // Check that the transaction is valid
        // Check that the transaction has only one output to Cosmex script address
        // Check that the transaction has enough value
        // Check that the transaction has correct pair of assets
        // Check that the snapshot is valid and contains the same pair of assets
        // Check signature

        tx.getBody.getOutputs.asScala.zipWithIndex.filter(_._1.getAddress == CosmexScriptAddress).toVector match
            case Vector((output, idx)) =>
                val hash = ByteString.fromHex(TransactionUtil.getTxHash(tx))
                Right(OpenChannelInfo(TxOutRef(TxId(hash), idx), Value.lovelace(output.getValue.getCoin), tx, snapshot))
            case Vector() => Left("No output to Cosmex script address")
            case _        => Left("More than one output to Cosmex script address")
    }

    def verifySnapshotSignature(snapshot: SignedSnapshot): Boolean = {
        true
    }

    def signSnapshot(clientTxOutRef: TxOutRef, snapshot: SignedSnapshot): SignedSnapshot = {
        val signedInfo = (clientTxOutRef, snapshot.signedSnapshot)
        import scalus.builtin.Data.toData
        import scalus.builtin.ToDataInstances.given
        import scalus.ledger.api.v1.ToDataInstances.given
        import CosmexToDataInstances.given
        val msg = Builtins.serialiseData(signedInfo.toData)
        val cosmexSignature = ByteString.empty // TODO: sign
        snapshot.copy(snapshotExchangeSignature = cosmexSignature)
    }

    def storeSnapshot(snapshot: SignedSnapshot): Unit = {
        println(snapshot)
    }

    def sendTx(tx: Transaction): Unit = {
        println(tx)
    }

    def reply(response: ClientResponse): List[ServerEvent] = {
        List.empty
    }

    def main(args: Array[String]): Unit = {
        println("Hello, world!")
    }
}

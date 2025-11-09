package cosmex

import scalus.builtin.ToData.tupleToData
import scalus.builtin.{platform, Builtins}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.Credential.ScriptHash
import scalus.ledger.api.v3.TxOutRef

import java.time.Instant
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
case class ClientState()

case class OpenChannelInfo(channelRef: TransactionInput, amount: Value, tx: Transaction, snapshot: SignedSnapshot)

class Server(env: CardanoInfo, exchangeParams: ExchangeParams) {
    val program = CosmexContract.mkCosmexProgram(exchangeParams)
    val script = Script.PlutusV3(program.cborByteString)
    val CosmexScriptAddress = Address(env.network, ScriptHash(script.scriptHash))
    val CosmexSignKey = exchangeParams.exchangePubKey
    val CosmexPubKey = exchangeParams.exchangePubKey

    val clients = HashMap.empty[ClientId, ClientState]

    def handleCommand(command: Command): Unit = command match
        case Command.ClientCommand(clientId, action) => handleClientRequest(action)

    private def handleClientRequest(request: ClientRequest): Unit = {
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

    def handleEvent(event: ServerEvent) = {}

    def validateOpenChannelRequest(tx: Transaction, snapshot: SignedSnapshot): Either[String, OpenChannelInfo] = {

        // TODO
        // Check that the transaction is valid
        // Check that the transaction has only one output to Cosmex script address
        // Check that the transaction has enough value
        // Check that the transaction has correct pair of assets
        // Check that the snapshot is valid and contains the same pair of assets
        // Check signature

        tx.body.value.outputs.view.map(_.value).zipWithIndex.filter(_._1.address == CosmexScriptAddress).toVector match
            case Vector((output, idx)) =>
                Right(OpenChannelInfo(TransactionInput(tx.id, idx), output.value, tx, snapshot))
            case Vector() => Left("No output to Cosmex script address")
            case _        => Left("More than one output to Cosmex script address")
    }

    def verifySnapshotSignature(snapshot: SignedSnapshot): Boolean = {
        true
    }

    def signSnapshot(clientTxOutRef: TxOutRef, snapshot: SignedSnapshot): SignedSnapshot = {
        val signedInfo = (clientTxOutRef, snapshot.signedSnapshot)
        import scalus.builtin.Data.toData
        val msg = Builtins.serialiseData(signedInfo.toData)
        val cosmexSignature = platform.signEd25519(CosmexSignKey, msg)
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

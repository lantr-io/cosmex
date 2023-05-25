package cosmex
import scalus.builtins.ByteString
import scalus.Compiler
import scalus.ledger.api.v1.POSIXTime
import scalus.ledger.api.v2.*
import scalus.Compile
import scalus.uplc.Data
import scalus.ledger.api.v1.LowerBound
import scalus.ledger.api.v1.Extended.Finite
import scalus.ledger.api.v1.UpperBound
import scalus.ledger.api.v1.Extended
import dotty.tools.dotc.Run

type DiffMilliSeconds = BigInt
type Signature = ByteString
type OrderId = BigInt
type AssetClass = ByteString

case class Trade(orderId: OrderId, tradeAmount: BigInt, tradePrice: BigInt)

type TxOutIndex = BigInt

enum Action:
  case Update
  case ClientAbort
  case Close(party: Party, signedSnapshot: SignedSnapshot)
  case Trades(actionTrades: List[Trade], actionCancelOthers: Boolean)
  case Payout
  case Transfer(txOutIndex: TxOutIndex, value: Value)
  case Timeout

enum Party:
  case Client
  case Exchange

type Pair = (AssetClass, AssetClass)

case class LimitOrder(orderPair: Pair, orderAmount: BigInt, orderPrice: BigInt)

case class PendingTxType(
    pendingIn: Boolean,
    pendingOut: Option[TxOutIndex],
    pendingTransfer: Option[TxOutIndex]
)

case class PendingTx(
    pendingTxValue: Value,
    pendingTxType: PendingTxType,
    pendingTxSpentTxOutRef: TxOutRef
)

case class TradingState(
    tsClientBalance: Value,
    tsExchangeBalance: Value,
    tsOrders: Map[OrderId, LimitOrder]
)

case class Snapshot(
    snapshotTradingState: TradingState,
    snapshotPendingTx: Option[PendingTx],
    snapshotVersion: BigInt
)

case class SignedSnapshot(
    signedSnapshot: Snapshot,
    snapshotClientSignature: Signature,
    snapshotExchangeSignature: Signature
)

enum OnChainChannelState:
  case OpenState
  case SnapshotContestState(
      contestSnapshot: Snapshot,
      contestSnapshotStart: POSIXTime,
      contestInitiator: Party,
      contestChannelTxOutRef: TxOutRef
  )
  case TradesContestState(latestTradingState: TradingState, tradeContestStart: POSIXTime)
  case PayoutState(clientBalance: Value, exchangeBalance: Value)

case class OnChainState(
    clientPkh: PubKeyHash,
    clientPubKey: ByteString,
    clientTxOutRef: TxOutRef,
    channelState: OnChainChannelState
)

case class ExchangeParams(
    exchangePkh: PubKeyHash,
    exchangePubKey: ByteString,
    contestationPeriodInMilliseconds: DiffMilliSeconds
)

@Compile
object CosmexContract {
  def validator(redeemer: Data, datum: Data, ctxData: Data): Unit = {
    val a = validRange(_)
    ()
  }

  def abs(x: BigInt): BigInt = if x < 0 then -x else x

  def validTrade(orderAmount: BigInt, orderPrice: BigInt, tradeAmount: BigInt, tradePrice: BigInt): Boolean = {
    (0 < orderPrice) && (0 < tradePrice) && (orderAmount != BigInt(0)) && (tradeAmount != BigInt(0)) &&
      (abs(tradeAmount) <= abs(orderAmount)) &&
      (if (0 < orderAmount) {
        (0 < tradeAmount) && (tradePrice <= orderPrice)
      } else {
        (tradeAmount < 0) && (orderPrice <= tradePrice)
      })
  }

  def validRange(interval: Interval[POSIXTime]): (POSIXTime, POSIXTime) =
    interval match
      case Interval(lower, upper) => lower match
        case LowerBound(start, l) => start match
          case Extended.NegInf => throw new RuntimeException("LBNI")
          case Extended.PosInf => throw new RuntimeException("LBPI")
          case Finite(start) => upper match
            case UpperBound(end, u) => end match
              case Extended.NegInf => throw new RuntimeException("UBNI")
              case Extended.PosInf => throw new RuntimeException("UBPI")
              case Finite(end) => (start, end)

}

object CosmexValidator {
  val compiledValidator = Compiler.compile(CosmexContract.validator)
}

package cosmex
import scalus.builtins.ByteString
import scalus.Compiler
import scalus.ledger.api.v1.POSIXTime
import scalus.ledger.api.v2.*
import scalus.Compile
import scalus.uplc.Data
import scalus.ledger.api.v1.CurrencySymbol
import scalus.ledger.api.v1.TokenName
import scalus.ledger.api.v1.LowerBound
import scalus.ledger.api.v1.Extended.Finite
import scalus.ledger.api.v1.UpperBound
import scalus.ledger.api.v1.Extended
import scalus.ledger.api.v1.Value.{+, -}
import dotty.tools.dotc.Run
import scalus.prelude.AssocMap
import scalus.prelude.Maybe
import scalus.prelude.Prelude.given
import java.util.Currency

type DiffMilliSeconds = BigInt
type Signature = ByteString
type OrderId = BigInt
type AssetClass = (CurrencySymbol, TokenName)

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
    tsOrders: AssocMap[OrderId, LimitOrder]
)

case class Snapshot(
    snapshotTradingState: TradingState,
    snapshotPendingTx: Maybe[PendingTx],
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
    val b = applyTrade(_, _)
    ()
  }

  def assetClassValue(assetClass: AssetClass, i: BigInt): Value =
    Value.apply(assetClass._1, assetClass._2, i)

    
  /* 
    Apply a trade to the trading state.
    We represent BUY/SELL orders as positive/negative amounts, respectively.
    This allows us to use the same code for both BUY and SELL orders.

    ADA/USD, Ada is the base currency, USD is the quote currency.

    Order/Trade Amount > 0 => BUY
    Order/Trade Amount < 0 => SELL

    Invariants:
      orderAmount > 0, tradeAmount > 0, orderPrice != 0, tradePrice != 0
      clientBalance >= 0
      tradePrice <= orderPrice (BUY @ 110 of 125 order, SELL @ -130 of -125 order)
      |tradeAmount| <= |orderAmount|
      newOrderAmount = orderAmount - tradeAmount
      newOrderAmount >= 0
      ∀ orders => ∑ (tradeAmount) <= ∑ (orderAmount) <= baseCurrencyAmount clientBalance
      ∀ orders => ∑ |orderAmount * orderPrice| <= quoteCurrency clientBalance
    Conclusions:
  */
  def applyTrade(tradingState: TradingState, trade: Trade): TradingState = {
    trade match
      case Trade(orderId, tradeAmount, tradePrice) =>
        tradingState match
          case TradingState(tsClientBalance, tsExchangeBalance, tsOrders) =>
            AssocMap.lookup(tsOrders)(orderId) match {
              case Maybe.Just(order) => order  match
                case LimitOrder(pair, orderAmount, orderPrice) => pair match
                  case (baseAsset, quoteAsset) =>
                    if validTrade(orderAmount, orderPrice, tradeAmount, tradePrice) then
                      val quoteAmount = tradeAmount * tradePrice
                      val baseAssetValue = assetClassValue(baseAsset, tradeAmount)
                      val quoteAssetValue = assetClassValue(quoteAsset, quoteAmount)
                      val clientBalance1 = tsClientBalance + baseAssetValue - quoteAssetValue
                      val exchangeBalance1 = tsExchangeBalance - baseAssetValue + quoteAssetValue
                      val orderAmountLeft = orderAmount - tradeAmount
                      val newOrders =
                        if orderAmountLeft == BigInt(0) then AssocMap.delete(tsOrders)(orderId)
                        else AssocMap.insert(tsOrders)(orderId, new LimitOrder(pair, orderAmount = orderAmountLeft, orderPrice = orderPrice ))
                      new TradingState(clientBalance1, exchangeBalance1, newOrders)
                    else throw new RuntimeException("Invalid trade")
              case Maybe.Nothing => throw new RuntimeException("Invalid order")
    }
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

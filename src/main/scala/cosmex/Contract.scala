package cosmex
import dotty.tools.dotc.Run
import io.bullet.borer.Cbor
import scalus.Compile
import scalus.Compiler
import scalus.builtins
import scalus.builtins.Builtins
import scalus.builtins.ByteString
import scalus.uplc.FromDataInstances.given
import scalus.ledger.api.v1.CurrencySymbol
import scalus.ledger.api.v1.Extended
import scalus.ledger.api.v1.Extended.Finite
import scalus.ledger.api.v1.LowerBound
import scalus.ledger.api.v1.POSIXTime
import scalus.ledger.api.v1.TokenName
import scalus.ledger.api.v1.UpperBound
import scalus.ledger.api.v1.Value.{*, given}
import scalus.ledger.api.v1.FromDataInstances.given
import scalus.ledger.api.v2.FromDataInstances.given
import scalus.ledger.api.v2.*
import scalus.prelude.AssocMap
import scalus.prelude.List
import scalus.prelude.Maybe
import scalus.prelude.Maybe.*
import scalus.prelude.Prelude.===
import scalus.prelude.Prelude.Eq
import scalus.prelude.Prelude.given
import scalus.sir.SimpleSirToUplcLowering
import scalus.uplc.Data
import scalus.uplc.Data.fromData
import scalus.uplc.Program
import scalus.uplc.ProgramFlatCodec
import scalus.utils.Hex

import java.util.Currency
import scalus.uplc.FromData
import scalus.uplc.ToData

type DiffMilliSeconds = BigInt
type Signature = ByteString
type OrderId = BigInt
type AssetClass = (CurrencySymbol, TokenName)
type PubKey = ByteString

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

enum PendingTxType:
  case PendingIn
  case PendingOut(txOutIndex: TxOutIndex)
  case PendingTransfer(txOutIndex: TxOutIndex)

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
object CosmexToDataInstances {
  import scalus.uplc.Data.toData
  import scalus.uplc.ToDataInstances.given
  import scalus.ledger.api.v1.ToDataInstances.given
  given Data.ToData[Party] = (p: Party) =>
    Builtins.mkConstr(
      (p match
        case Party.Client   => BigInt(0)
        case Party.Exchange => BigInt(1)
      ),
      Builtins.mkNilData()
    )

  given Data.ToData[LimitOrder] = ToData.deriveCaseClass[LimitOrder](0)
  given Data.ToData[TradingState] = ToData.deriveCaseClass[TradingState](0)

  given Data.ToData[PendingTxType] = (o: PendingTxType) =>
    o match
      case PendingTxType.PendingIn => Builtins.mkConstr(BigInt(0), Builtins.mkNilData())
      case PendingTxType.PendingOut(txOutIndex) =>
        Builtins.mkConstr(BigInt(1), Builtins.mkCons(txOutIndex.toData, Builtins.mkNilData()))
      case PendingTxType.PendingTransfer(txOutIndex) =>
        Builtins.mkConstr(BigInt(2), Builtins.mkCons(txOutIndex.toData, Builtins.mkNilData()))

  given Data.ToData[PendingTx] = ToData.deriveCaseClass[PendingTx](0)
  given Data.ToData[Snapshot] = ToData.deriveCaseClass[Snapshot](0)

  given Data.ToData[OnChainChannelState] = (o: OnChainChannelState) =>
    o match
      case OnChainChannelState.OpenState =>
        Builtins.mkConstr(BigInt(0), Builtins.mkNilData())
      case OnChainChannelState.SnapshotContestState(
            contestSnapshot,
            contestSnapshotStart,
            contestInitiator,
            contestChannelTxOutRef
          ) =>
        Builtins.mkConstr(
          BigInt(1),
          Builtins.mkCons(
            contestSnapshot.toData,
            Builtins.mkCons(
              contestSnapshotStart.toData,
              Builtins.mkCons(
                contestInitiator.toData,
                Builtins.mkCons(
                  contestChannelTxOutRef.toData,
                  Builtins.mkNilData()
                )
              )
            )
          )
        )
      case OnChainChannelState.TradesContestState(
            latestTradingState,
            tradeContestStart
          ) =>
        Builtins.mkConstr(
          BigInt(2),
          Builtins.mkCons(
            latestTradingState.toData,
            Builtins.mkCons(tradeContestStart.toData, Builtins.mkNilData())
          )
        )
      case OnChainChannelState.PayoutState(clientBalance, exchangeBalance) =>
        Builtins.mkConstr(
          BigInt(3),
          Builtins.mkCons(
            clientBalance.toData,
            Builtins.mkCons(exchangeBalance.toData, Builtins.mkNilData())
          )
        )

  given Data.ToData[OnChainState] = ToData.deriveCaseClass[OnChainState](0)
}
@Compile
object CosmexContract {

  given Data.FromData[Party] = FromData.deriveEnum[Party] {
    case 0 => _ => Party.Client
    case 1 => _ => Party.Exchange
  }

  given Data.FromData[LimitOrder] = FromData.deriveCaseClass

  given Data.FromData[TradingState] = FromData.deriveCaseClass

  given Data.FromData[PendingTxType] = FromData.deriveEnum[PendingTxType] {
    case 0 => _ => PendingTxType.PendingIn
    case 1 => FromData.deriveConstructor[PendingTxType.PendingOut]
    case 2 => FromData.deriveConstructor[PendingTxType.PendingTransfer]
  }

  given Data.FromData[PendingTx] = FromData.deriveCaseClass

  given Data.FromData[Snapshot] = FromData.deriveCaseClass

  given Data.FromData[SignedSnapshot] = FromData.deriveCaseClass

  given Data.FromData[OnChainChannelState] = FromData.deriveEnum[OnChainChannelState] {
    case 0 => _ => OnChainChannelState.OpenState
    case 1 => FromData.deriveConstructor[OnChainChannelState.SnapshotContestState]
    case 2 => FromData.deriveConstructor[OnChainChannelState.TradesContestState]
    case 3 => FromData.deriveConstructor[OnChainChannelState.PayoutState]
  }

  given Data.FromData[Trade] = FromData.deriveCaseClass

  given Data.FromData[Action] = (d: Data) =>
    val pair = Builtins.unsafeDataAsConstr(d)
    val tag = pair.fst
    val args = pair.snd
    if tag === BigInt(0) then Action.Update
    else if tag === BigInt(1) then Action.ClientAbort
    else if tag === BigInt(2) then
      new Action.Close(fromData[Party](args.head), fromData[SignedSnapshot](args.tail.head))
    else if tag === BigInt(3) then
      new Action.Trades(fromData[List[Trade]](args.head), fromData[Boolean](args.tail.head))
    else if tag === BigInt(4) then Action.Payout
    else if tag === BigInt(5) then new Action.Transfer(fromData[TxOutIndex](args.head), fromData[Value](args.tail.head))
    else if tag === BigInt(6) then Action.Timeout
    else throw new Exception(s"Unknown Action tag: $tag")

  def validator(
      cosmexValidator: (ExchangeParams, Action, OnChainState, ScriptContext) => Boolean,
      redeemer: Data,
      datum: Data,
      ctxData: Data
  ): Unit = {
    val ctx = fromData[ScriptContext](ctxData)
    val d = cosmexValidator(_, _, _, ctx)
    ()
  }

  def findOwnInputAndIndex(inputs: List[TxInInfo], spendingTxOutRef: TxOutRef): (TxInInfo, BigInt) = {
    def go(i: BigInt, txIns: List[TxInInfo]): (TxInInfo, BigInt) = txIns match
      case List.Nil => throw new Exception("Own input not found")
      case List.Cons(txInInfo, tail) =>
        if txInInfo.outRef === spendingTxOutRef then (txInInfo, i)
        else go(i + 1, tail)

    go(0, inputs)
  }

  def expectNewState(ownOutput: TxOut, ownInputAddress: Address, newState: OnChainState, newValue: Value): Boolean = {
    import scalus.uplc.Data.toData
    import CosmexToDataInstances.given
    ownOutput match
      case TxOut(address, value, datum, referenceScript) =>
        val newStateData = newState.toData
        datum === new OutputDatum.OutputDatum(newStateData) &&
        address === ownInputAddress &&
        value === newValue
  }

  def txSignedBy(signatories: List[PubKeyHash], k: PubKeyHash, msg: String): Boolean =
    List.find(signatories)(k.hash === _.hash) match
      case Just(a) => true
      case Nothing => throw new Exception(msg)

  def handleUpdate(
      ownInputAddress: Address,
      ownOutput: TxOut,
      state: OnChainState,
      signatories: List[PubKeyHash],
      clientPkh: PubKeyHash,
      exchangePkh: PubKeyHash
  ) = {
    val newValue = ownOutput.value
    // both parties must sign the transaction,
    // thus it's validated by them, so no need to check anything else
    // NOTE: this allows parties to change the channel funds by mutual agreement
    txSignedBy(signatories, clientPkh, "no client sig") && txSignedBy(signatories, exchangePkh, "no exchange sig")
    && expectNewState(ownOutput, ownInputAddress, state, newValue)
  }

  def handleClientAbort(
      ownInputAddress: Address,
      ownInputValue: Value,
      txInfo: TxInfo,
      state: OnChainState,
      spendingTxOutRef: TxOutRef,
      ownOutput: TxOut
  ) = {
    val contestSnapshotStart = validRange(txInfo.validRange)._2
    state match
      case OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState) =>
        val tradingState =
          new TradingState(tsClientBalance = Value.zero, tsExchangeBalance = Value.zero, tsOrders = AssocMap.empty)
        val snapshot =
          new Snapshot(snapshotTradingState = tradingState, snapshotPendingTx = Nothing, snapshotVersion = 0)
        val contestSnapshotState = new OnChainChannelState.SnapshotContestState(
          contestSnapshot = snapshot,
          contestSnapshotStart = contestSnapshotStart,
          contestInitiator = Party.Client,
          contestChannelTxOutRef = spendingTxOutRef
        )
        val snapshotContestState = new OnChainState(clientPkh, clientPubKey, clientTxOutRef, contestSnapshotState)
        txSignedBy(txInfo.signatories, clientPkh, "no client sig")
        && expectNewState(ownOutput, ownInputAddress, snapshotContestState, ownInputValue)
  }

  def lockedInOrders(orders: AssocMap[BigInt, LimitOrder]): Value = {
    List.foldLeft(orders.inner, Value.zero) { (acc, pair) =>
      pair match
        case (orderId, order) =>
          order match
            case LimitOrder(pair, orderAmount, orderPrice) =>
              pair match
                case (base, quote) =>
                  val orderValue = if (orderAmount < 0) {
                    assetClassValue(base, orderAmount) // Sell base asset
                  } else {
                    assetClassValue(quote, orderAmount * orderPrice) // Buy quote asset
                  }
                  acc + orderValue
    }
  }

  def validSignedSnapshot(
      signedSnapshot: SignedSnapshot,
      clientTxOutRef: TxOutRef,
      clientPubKey: PubKey,
      exchangePubKey: PubKey
  ): Boolean = {
    import scalus.uplc.Data.toData
    import scalus.uplc.ToDataInstances.given
    import scalus.ledger.api.v1.ToDataInstances.given
    import CosmexToDataInstances.given
    signedSnapshot match
      case SignedSnapshot(signedSnapshot, snapshotClientSignature, snapshotExchangeSignature) =>
        val signedInfo = (clientTxOutRef, signedSnapshot)
        val msg = Builtins.serialiseData(
          signedInfo.toData
        )
        val validExchangeSig = Builtins.verifyEd25519Signature(exchangePubKey, msg, snapshotExchangeSignature)
        val validClientSig = Builtins.verifyEd25519Signature(clientPubKey, msg, snapshotClientSignature)
        validClientSig && validExchangeSig
  }

  def balancedSnapshot(ts: TradingState, locked: Value): Boolean = {
    ts match
      case TradingState(tsClientBalance, tsExchangeBalance, tsOrders) =>
        val allFunds = tsClientBalance + tsExchangeBalance + lockedInOrders(tsOrders)
        allFunds === locked
  }

  def handleClose(
      initiator: Party,
      ownInputAddress: Address,
      ownInputValue: Value,
      txInfo: TxInfo,
      params: ExchangeParams,
      state: OnChainState,
      newSignedSnapshot: SignedSnapshot,
      spendingTxOutRef: TxOutRef,
      ownOutput: TxOut
  ) = {
    val contestSnapshotStart = validRange(txInfo.validRange)._2
    state match
      case OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState) =>
        val validInitiator = initiator match
          case Party.Client   => txSignedBy(txInfo.signatories, clientPkh, "no client sig")
          case Party.Exchange => txSignedBy(txInfo.signatories, params.exchangePkh, "no exchange sig")
        newSignedSnapshot match
          case SignedSnapshot(signedSnapshot, snapshotClientSignature, snapshotExchangeSignature) =>
            val newChannelState =
              new OnChainChannelState.SnapshotContestState(
                contestSnapshot = signedSnapshot,
                contestSnapshotStart = contestSnapshotStart,
                contestInitiator = initiator,
                // save the channel tx out ref so that we can check it in the contest
                contestChannelTxOutRef = spendingTxOutRef
              )

            val newState = new OnChainState(clientPkh, clientPubKey, clientTxOutRef, newChannelState)
            validInitiator
            && balancedSnapshot(signedSnapshot.snapshotTradingState, ownInputValue)
            && validSignedSnapshot(newSignedSnapshot, clientTxOutRef, clientPubKey, params.exchangePubKey)
            && expectNewState(ownOutput, ownInputAddress, newState, ownInputValue)
  }

  // handleContestClose(contestSnapshot, contestSnapshotStart, contestInitiator, contestChannelTxOutRef, party, newSignedSnapshot)
  def handleContestClose(
      params: ExchangeParams,
      txInfo: TxInfo,
      state: OnChainState,
      contestSnapshot: Snapshot,
      contestSnapshotStart: POSIXTime,
      contestInitiator: Party,
      contestChannelTxOutRef: TxOutRef,
      party: Party,
      newSignedSnapshot: SignedSnapshot,
      ownInputAddress: Address,
      ownInputValue: Value,
      ownOutput: TxOut
  ): Boolean = contestSnapshot match {
    case Snapshot(snapshotTradingState, snapshotPendingTx, oldVersion) =>
      state match
        case OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState) =>
          val validParty = contestInitiator match
            case Party.Client =>
              party match
                case Party.Client   => throw new Exception("Invalid party")
                case Party.Exchange => txSignedBy(txInfo.signatories, params.exchangePkh, "no exchange sig")
            case Party.Exchange =>
              party match
                case Party.Client   => txSignedBy(txInfo.signatories, clientPkh, "no client sig")
                case Party.Exchange => throw new Exception("Invalid party")

          val (_, tradeContestStart) = validRange(txInfo.validRange)
          val latestTradingState = handlePendingTx(contestChannelTxOutRef, snapshotPendingTx, snapshotTradingState)
          latestTradingState match
            case TradingState(tsClientBalance, tsExchangeBalance, tsOrders) =>
              val newChannelState =
                if List.isEmpty(tsOrders.inner) then
                  new OnChainChannelState.PayoutState(tsClientBalance, tsExchangeBalance)
                else
                  new OnChainChannelState.TradesContestState(
                    latestTradingState = latestTradingState,
                    tradeContestStart = tradeContestStart
                  )

              val newState = new OnChainState(clientPkh, clientPubKey, clientTxOutRef, newChannelState)
              val isNewerSnapshot =
                if oldVersion <= newSignedSnapshot.signedSnapshot.snapshotVersion then true
                else throw new Exception("Older snapshot")
              validParty && isNewerSnapshot && balancedSnapshot(latestTradingState, ownInputValue) &&
              validSignedSnapshot(newSignedSnapshot, clientTxOutRef, clientPubKey, params.exchangePubKey) &&
              expectNewState(ownOutput, ownInputAddress, newState, ownInputValue)
  }

  def handleContestTimeout(
      params: ExchangeParams,
      contestSnapshotStart: POSIXTime,
      contestationPeriodInMilliseconds: POSIXTime,
      txInfoValidRange: (POSIXTime, POSIXTime),
      contestChannelTxOutRef: TxOutRef,
      contestSnapshot: Snapshot,
      state: OnChainState,
      ownInputAddress: Address,
      ownInputValue: Value,
      ownOutput: TxOut
  ): Boolean = {
    val (start, tradeContestStart) = txInfoValidRange
    val timeoutPassed = {
      val timeoutTime = contestSnapshotStart + params.contestationPeriodInMilliseconds
      timeoutTime < start
    }

    val latestTradingState =
      handlePendingTx(contestChannelTxOutRef, contestSnapshot.snapshotPendingTx, contestSnapshot.snapshotTradingState)

    latestTradingState match
      case TradingState(tsClientBalance, tsExchangeBalance, tsOrders) =>
        val newChannelState =
          if List.isEmpty(tsOrders.inner) then new OnChainChannelState.PayoutState(tsClientBalance, tsExchangeBalance)
          else new OnChainChannelState.TradesContestState(latestTradingState, tradeContestStart)

        state match
          case OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState) =>
            val newState = new OnChainState(clientPkh, clientPubKey, clientTxOutRef, newChannelState)
            timeoutPassed && expectNewState(ownOutput, ownInputAddress, newState, ownInputValue)
  }

  def handleTradesContestTimeout(
      params: ExchangeParams,
      txInfo: TxInfo,
      tradeContestStart: POSIXTime,
      state: OnChainState,
      latestTradingState: TradingState,
      ownInputAddress: Address,
      ownInputValue: Value,
      ownOutput: TxOut
  ) = {
    val (start, _) = validRange(txInfo.validRange)
    val timeoutPassed =
      val timeoutTime = tradeContestStart + params.contestationPeriodInMilliseconds
      timeoutTime < start
    val newChannelState = latestTradingState match
      case TradingState(tsClientBalance, tsExchangeBalance, _) =>
        new OnChainChannelState.PayoutState(clientBalance = tsClientBalance, exchangeBalance = tsExchangeBalance)
    state match
      case OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState) =>
        val newState = new OnChainState(clientPkh, clientPubKey, clientTxOutRef, newChannelState)
        timeoutPassed && expectNewState(ownOutput, ownInputAddress, newState, ownInputValue)
  }

  def handleContestTrades(
      params: ExchangeParams,
      txInfo: TxInfo,
      actionTrades: List[Trade],
      tradeContestStart: POSIXTime,
      actionCancelOthers: Boolean,
      latestTradingState: TradingState,
      state: OnChainState,
      ownInputAddress: Address,
      ownInputValue: Value,
      ownOutput: TxOut
  ) = {
    val newTradeingState = List.foldLeft(actionTrades, latestTradingState)(applyTrade)
    val newChannelState = newTradeingState match
      case TradingState(tsClientBalance, tsExchangeBalance, tsOrders) =>
        if actionCancelOthers || List.isEmpty(tsOrders.inner) then
          new OnChainChannelState.PayoutState(tsClientBalance, tsExchangeBalance)
        else new OnChainChannelState.TradesContestState(newTradeingState, tradeContestStart)

    state match
      case OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState) =>
        val newState = new OnChainState(clientPkh, clientPubKey, clientTxOutRef, newChannelState)
        txSignedBy(txInfo.signatories, params.exchangePkh, "no exchange sig") && expectNewState(
          ownOutput,
          ownInputAddress,
          newState,
          ownInputValue
        )
  }

  /*  The clientBalance and exchangeBalance can be fulfilled partially, meaning that
      the exchange may owe the client some money.
      This state allows a client to withdraw the money that the exchange owes him
      from other TxOuts where exchangeBalance is available.

      client1: {A: 1000, $: 0} exchange: {A: 0, $: 0} locked: {A: 1000, $: 0}
      client2: {A: 0, $: 1000} exchange: {A: 0, $: 0} locked: {A: 0, $: 1000}
      ==> Trade ADA/DJED SELL A 400 @ 0.3
      client1: {A: 600, $: 120} exchange: {A: 400, $: 0} locked: {A: 1000, $: 0}
      client2: {A: 400, $: 880} exchange: {A: 0, $: 120} locked: {A: 0, $: 1000}
      ==> Payout if the exchange is offline
      client1 can withdraw $120 from client2's locked funds
      client2 can withdraw A400 from client1's locked funds
      even in a single transaction
      client1--|-----|--client1
      client2--| Tx  |--client2
              |-----|
   */
  def handlePayoutTransfer(
      params: ExchangeParams,
      state: OnChainState,
      txInfo: TxInfo,
      ownIdx: BigInt,
      transferValue: Value,
      clientBalance: Value,
      exchangeBalance: Value,
      ownInputAddress: Address,
      ownInputValue: Value,
      ownOutput: TxOut
  ): Boolean = {
    import ScriptPurpose.*
    import Action.*
    import OnChainChannelState.*

    val (locked, cosmexScriptHash) = ownOutput match
      case TxOut(address, txOutValue, _, _) =>
        address match
          case Address(cred, _) =>
            cred match
              case Credential.ScriptCredential(sh) => (txOutValue, sh)
              case Credential.PubKeyCredential(_)  => throw new Exception("Invalid output")

    val transferValueIsPositive = transferValue > Value.zero

    def cosmexInputTransferAmountToTxOutIdx(txInInfo: TxInInfo): Value = txInInfo match
      case TxInInfo(txOutRef, resolved) =>
        resolved match
          case TxOut(address, txOutValue, _, _) =>
            address match
              case Address(cred, _) =>
                cred match
                  case Credential.ScriptCredential(sh) =>
                    if sh === cosmexScriptHash then
                      val action = {
                        AssocMap.lookup(txInfo.redeemers)(new Spending(txOutRef)) match
                          case Nothing     => throw new Exception("No redeemer")
                          case Just(value) => fromData[Action](value)
                      }
                      action match
                        case Transfer(targetIdx, amount) =>
                          if targetIdx === ownIdx then amount
                          else Value.zero
                        case _ => throw new Exception("Invalid action")
                    else Value.zero
                  case Credential.PubKeyCredential(_) => Value.zero

    val transferedToMe = List.foldLeft(txInfo.inputs, Value.zero) { (acc, input) =>
      acc + cosmexInputTransferAmountToTxOutIdx(input)
    }
    val diff = transferedToMe - transferValue
    val newOutputValue = locked - diff
    val newExchangeBalance = exchangeBalance - diff
    state match
      case OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState) =>
        val newChannelState = new PayoutState(clientBalance, newExchangeBalance)
        val newState = new OnChainState(clientPkh, clientPubKey, clientTxOutRef, newChannelState)
        transferValueIsPositive && expectNewState(ownOutput, ownInputAddress, newState, newOutputValue)
  }

  def handlePayoutPayout(
      params: ExchangeParams,
      state: OnChainState,
      clientBalance: Value,
      exchangeBalance: Value,
      ownInputAddress: Address,
      ownInputValue: Value,
      ownOutput: TxOut
  ): Boolean = {
    import ScriptPurpose.*
    import OnChainChannelState.*

    val isFilled = clientBalance === ownInputValue && exchangeBalance === Value.zero
    if isFilled then
      ownOutput match
        case TxOut(address, txOutValue, _, _) =>
          address.credential match
            case Credential.PubKeyCredential(hash) =>
              if hash === params.exchangePkh && txOutValue === ownInputValue then true
              else throw new Exception("Invalid payout")
            case Credential.ScriptCredential(hash) =>
              throw new Exception("Invalid payout")
    else
      val min = (a: BigInt, b: BigInt) => if a < b then a else b
      val availableForPayment = Value.unionWith(min)(clientBalance, ownInputValue)
      val newOutputValue = ownInputValue - availableForPayment
      val newClientBalance = clientBalance - availableForPayment
      state match
        case OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState) =>
          val newState = new OnChainState(
            clientPkh,
            clientPubKey,
            clientTxOutRef,
            new PayoutState(newClientBalance, exchangeBalance)
          )
          expectNewState(ownOutput, ownInputAddress, newState, newOutputValue)
  }

  def cosmexValidator(
      handlePayoutTransfer: (
          params: ExchangeParams,
          state: OnChainState,
          txInfo: TxInfo,
          ownIdx: BigInt,
          transferValue: Value,
          clientBalance: Value,
          exchangeBalance: Value,
          ownInputAddress: Address,
          ownInputValue: Value,
          ownOutput: TxOut
      ) => Boolean,
      params: ExchangeParams,
      state: OnChainState,
      action: Action,
      ctx: ScriptContext
  ): Boolean = {
    import ScriptPurpose.*

    def cosmexSpending(txInfo: TxInfo, spendingTxOutRef: TxOutRef): Boolean = {
      import Action.*
      import OnChainChannelState.*

      def findOwnInputAndIndex(i: BigInt, txIns: List[TxInInfo]): (TxOut, BigInt) = txIns match
        case List.Nil => throw new Exception("Own input not found")
        case List.Cons(txInInfo, tail) =>
          txInInfo match
            case TxInInfo(txOutRef, resolved) =>
              if txInInfo.outRef === spendingTxOutRef then (resolved, i)
              else findOwnInputAndIndex(i + 1, tail)

      findOwnInputAndIndex(0, txInfo.inputs) match
        case (ownTxInResolvedTxOut, ownIndex) =>
          state match
            case OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState) =>
              val signedByClient = () => txSignedBy(txInfo.signatories, clientPkh, "no client sig")
              val signedByExchange = () => txSignedBy(txInfo.signatories, params.exchangePkh, "no exchange sig")

              channelState match
                case OpenState =>
                  action match
                    case Update =>
                      val ownOutput = txInfo.outputs !! ownIndex
                      handleUpdate(
                        ownTxInResolvedTxOut.address,
                        ownOutput,
                        state,
                        txInfo.signatories,
                        clientPkh,
                        params.exchangePkh
                      )
                    case ClientAbort =>
                      /*  This should only be called by the client on channel open
                          in case the exchange doesn't respond to the initial snapshot
                          hence, the currentSnapshot must be version 0, with only the client's balance
                          Note: this allows the client to claim all locked funds in the channel,
                          hence the exchange MUST contest with a valid snapshot if needed.
                          Consider penalizing the client for this. */
                      val ownOutput = txInfo.outputs !! ownIndex
                      handleClientAbort(
                        ownTxInResolvedTxOut.address,
                        ownTxInResolvedTxOut.value,
                        txInfo,
                        state,
                        spendingTxOutRef,
                        ownOutput
                      )
                    case Close(party, signedSnapshot) =>
                      val ownOutput = txInfo.outputs !! ownIndex
                      handleClose(
                        party,
                        ownTxInResolvedTxOut.address,
                        ownTxInResolvedTxOut.value,
                        txInfo,
                        params,
                        state,
                        signedSnapshot,
                        spendingTxOutRef,
                        ownOutput
                      )
                    case _ => throw new Exception("Invalid action")

                case SnapshotContestState(
                      contestSnapshot,
                      contestSnapshotStart,
                      contestInitiator,
                      contestChannelTxOutRef
                    ) =>
                  action match
                    case Close(party, newSignedSnapshot) =>
                      val ownOutput = txInfo.outputs !! ownIndex
                      handleContestClose(
                        params,
                        txInfo,
                        state,
                        contestSnapshot,
                        contestSnapshotStart,
                        contestInitiator,
                        contestChannelTxOutRef,
                        party,
                        newSignedSnapshot,
                        ownTxInResolvedTxOut.address,
                        ownTxInResolvedTxOut.value,
                        ownOutput
                      )
                    case Timeout =>
                      val ownOutput = txInfo.outputs !! ownIndex
                      handleContestTimeout(
                        params,
                        contestSnapshotStart,
                        params.contestationPeriodInMilliseconds,
                        validRange(txInfo.validRange),
                        contestChannelTxOutRef,
                        contestSnapshot,
                        state,
                        ownTxInResolvedTxOut.address,
                        ownTxInResolvedTxOut.value,
                        ownOutput
                      )
                    case _ => throw new Exception("Invalid action")
                case TradesContestState(latestTradingState, tradeContestStart) =>
                  action match
                    case Timeout =>
                      val ownOutput = txInfo.outputs !! ownIndex
                      handleTradesContestTimeout(
                        params,
                        txInfo,
                        tradeContestStart,
                        state,
                        latestTradingState,
                        ownTxInResolvedTxOut.address,
                        ownTxInResolvedTxOut.value,
                        ownOutput
                      )
                    case Trades(actionTrades, actionCancelOthers) =>
                      val ownOutput = txInfo.outputs !! ownIndex
                      handleContestTrades(
                        params,
                        txInfo,
                        actionTrades,
                        tradeContestStart,
                        actionCancelOthers,
                        latestTradingState,
                        state,
                        ownTxInResolvedTxOut.address,
                        ownTxInResolvedTxOut.value,
                        ownOutput
                      )
                    case _ => throw new Exception("Invalid action")

                case PayoutState(clientBalance, exchangeBalance) =>
                  action match
                    case Transfer(txOutIndex, value) =>
                      val ownOutput = txInfo.outputs !! ownIndex
                      handlePayoutTransfer(
                        params,
                        state,
                        txInfo,
                        txOutIndex,
                        value,
                        clientBalance,
                        exchangeBalance,
                        ownTxInResolvedTxOut.address,
                        ownTxInResolvedTxOut.value,
                        ownOutput
                      )
                    case Payout =>
                      val ownOutput = txInfo.outputs !! ownIndex
                      handlePayoutPayout(
                        params,
                        state,
                        clientBalance,
                        exchangeBalance,
                        ownTxInResolvedTxOut.address,
                        ownTxInResolvedTxOut.value,
                        ownOutput
                      )
                    case _ => throw new Exception("Invalid action")
    }

    ctx match
      case ScriptContext(txInfo, purpose) =>
        purpose match
          case Spending(spendingTxOutRef) => cosmexSpending(txInfo, spendingTxOutRef)
          case _                          => throw new Exception("Spending expected")

    false
  }

  def assetClassValue(assetClass: AssetClass, i: BigInt): Value =
    Value.apply(assetClass._1, assetClass._2, i)

  def handlePendingTx(
      contestChannelTxOutRef: TxOutRef,
      snapshotPendingTx: Maybe[PendingTx],
      snapshotTradingState: TradingState
  ): TradingState = {
    snapshotPendingTx match
      case Nothing => snapshotTradingState
      case Just(pendingTx) =>
        pendingTx match
          case PendingTx(pendingTxValue, pendingTxType, pendingTxSpentTxOutRef) =>
            snapshotTradingState match
              case TradingState(tsClientBalance, tsExchangeBalance, tsOrders) =>
                if pendingTxSpentTxOutRef === contestChannelTxOutRef then
                  pendingTxType match
                    case PendingTxType.PendingIn =>
                      new TradingState(tsClientBalance + pendingTxValue, tsExchangeBalance, tsOrders)
                    case PendingTxType.PendingOut(a) =>
                      new TradingState(tsClientBalance - pendingTxValue, tsExchangeBalance, tsOrders)
                    case PendingTxType.PendingTransfer(a) =>
                      new TradingState(tsClientBalance, tsExchangeBalance - pendingTxValue, tsOrders)
                else snapshotTradingState
  }

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
              case Maybe.Just(order) =>
                order match
                  case LimitOrder(pair, orderAmount, orderPrice) =>
                    pair match
                      case (baseAsset, quoteAsset) =>
                        if validTrade(orderAmount, orderPrice, tradeAmount, tradePrice) then
                          val quoteAmount = tradeAmount * tradePrice
                          val baseAssetValue = assetClassValue(baseAsset, tradeAmount)
                          val quoteAssetValue = assetClassValue(quoteAsset, quoteAmount)
                          val clientBalance1 = tsClientBalance + baseAssetValue - quoteAssetValue
                          val exchangeBalance1 = tsExchangeBalance - baseAssetValue + quoteAssetValue
                          val orderAmountLeft = orderAmount - tradeAmount
                          val newOrders =
                            if orderAmountLeft === BigInt(0) then AssocMap.delete(tsOrders)(orderId)
                            else
                              AssocMap.insert(tsOrders)(
                                orderId,
                                new LimitOrder(pair, orderAmount = orderAmountLeft, orderPrice = orderPrice)
                              )
                          new TradingState(clientBalance1, exchangeBalance1, newOrders)
                        else throw new Exception("Invalid trade")
              case Maybe.Nothing => throw new Exception("Invalid order")
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

  def validRange(interval: Interval[POSIXTime]): (POSIXTime, POSIXTime) = {
    interval match
      case Interval(lower, upper) =>
        lower match
          case LowerBound(start, closure) =>
            start match
              case Extended.NegInf => throw new Exception("LBNI")
              case Extended.PosInf => throw new Exception("LBPI")
              case Finite(start) =>
                upper match
                  case UpperBound(upper, closure) =>
                    upper match
                      case Extended.NegInf => throw new Exception("UBNI")
                      case Extended.PosInf => throw new Exception("UBPI")
                      case Finite(e)       => (start, e)
  }

}

object CosmexValidator {
  // Split the validator into two parts to avoid
  // Generated bytecode for method 'cosmex.CosmexValidator$.<clinit>' is too large. Limit is 64KB
  import scalus.sir.SirDSL.{*, given}
  def compiledhandlePayoutTransfer = Compiler.compile(CosmexContract.handlePayoutTransfer)
  def compiledWrapperValidator = Compiler.compile(CosmexContract.validator)
  def compiledTypedValidator = Compiler.compile(CosmexContract.cosmexValidator)
  val compiledValidator = compiledWrapperValidator $ (compiledTypedValidator $ compiledhandlePayoutTransfer)
  val validator = new SimpleSirToUplcLowering(generateErrorTraces = false).lower(compiledValidator)
  val flatEncodedValidator = ProgramFlatCodec.encodeFlat(Program((2, 0, 0), validator))
  val cborEncodedValidator = Cbor.encode(flatEncodedValidator).toByteArray
  val doubleCborEncodedValidator = Cbor.encode(cborEncodedValidator).toByteArray
  val doubleCborEncodedValidatorHex = Hex.bytesToHex(doubleCborEncodedValidator)
}

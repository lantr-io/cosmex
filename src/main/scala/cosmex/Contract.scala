package cosmex
import dotty.tools.dotc.Run
import io.bullet.borer.Cbor
import scalus.Compile
import scalus.Compiler
import scalus.builtins.Builtins
import scalus.builtins.ByteString
import scalus.ledger.api.v1.CurrencySymbol
import scalus.ledger.api.v1.Extended
import scalus.ledger.api.v1.Extended.Finite
import scalus.ledger.api.v1.LowerBound
import scalus.ledger.api.v1.POSIXTime
import scalus.ledger.api.v1.TokenName
import scalus.ledger.api.v1.UpperBound
import scalus.ledger.api.v1.Value.+
import scalus.ledger.api.v1.Value.-
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
object CosmexContract {

  given Eq[Value] = (a: Value, b: Value) => false // FIXME

  given Eq[PubKeyHash] = (a: PubKeyHash, b: PubKeyHash) => Builtins.equalsByteString(a.hash, b.hash)

  given Eq[Credential] = (a: Credential, b: Credential) =>
    a match
      case Credential.PubKeyCredential(hash) =>
        b match
          case Credential.PubKeyCredential(hash2) => hash === hash2
          case Credential.ScriptCredential(hash)  => false
      case Credential.ScriptCredential(hash) =>
        b match
          case Credential.PubKeyCredential(hash2) => false
          case Credential.ScriptCredential(hash2) => hash === hash2

  given Eq[StakingCredential] = (lhs: StakingCredential, rhs: StakingCredential) =>
    lhs match
      case StakingCredential.StakingHash(cred) =>
        rhs match
          case StakingCredential.StakingHash(cred2)  => cred === cred2
          case StakingCredential.StakingPtr(a, b, c) => false
      case StakingCredential.StakingPtr(a, b, c) =>
        rhs match
          case StakingCredential.StakingHash(cred2)     => false
          case StakingCredential.StakingPtr(a2, b2, c2) => a === a2 && b === b2 && c === c2

  given Eq[Address] = (a: Address, b: Address) =>
    a match
      case Address(credentials, stakingCredential) =>
        b match
          case Address(credentials2, stakingCredential2) =>
            credentials === credentials2 && stakingCredential === stakingCredential2

  given Eq[OutputDatum] = (a: OutputDatum, b: OutputDatum) =>
    a match
      case OutputDatum.NoOutputDatum =>
        b match
          case OutputDatum.NoOutputDatum              => true
          case OutputDatum.OutputDatumHash(datumHash) => false
          case OutputDatum.OutputDatum(datum)         => false
      case OutputDatum.OutputDatumHash(datumHash) =>
        b match
          case OutputDatum.NoOutputDatum               => false
          case OutputDatum.OutputDatumHash(datumHash2) => datumHash === datumHash2
          case OutputDatum.OutputDatum(datum)          => false
      case OutputDatum.OutputDatum(datum) =>
        b match
          case OutputDatum.NoOutputDatum              => false
          case OutputDatum.OutputDatumHash(datumHash) => false
          case OutputDatum.OutputDatum(datum2)        => false // FIXME: datum === datum2

  given Eq[TxOutRef] = (a: TxOutRef, b: TxOutRef) =>
    a match
      case TxOutRef(aTxId, aTxOutIndex) =>
        b match
          case TxOutRef(bTxId, bTxOutIndex) =>
            aTxOutIndex === bTxOutIndex && Builtins.equalsByteString(aTxId.hash, bTxId.hash)

  def isEmpty[A](lst: List[A]): Boolean = lst match
    case List.Nil        => true
    case List.Cons(_, _) => false

  def validator(cosmexValidator: (ExchangeParams, Action, OnChainState, ScriptContext) => Boolean, redeemer: Data, datum: Data, ctxData: Data): Unit = {
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
    ownOutput match
      case TxOut(address, value, datum, referenceScript) =>
        val newStateData = Builtins.mkI(1) // FIXME: use newState.toData
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
    signedSnapshot match
      case SignedSnapshot(signedSnapshot, snapshotClientSignature, snapshotExchangeSignature) =>
        val signedInfo = (clientTxOutRef, signedSnapshot)
        val msg = Builtins.serialiseData(
          Builtins.mkI(42)
        ) // FIXME: Builtins.mkPairData(clientTxOutRef, signedSnapshot.signedSnapshot)
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
                if isEmpty(tsOrders.inner) then new OnChainChannelState.PayoutState(tsClientBalance, tsExchangeBalance)
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
          if isEmpty(tsOrders.inner) then new OnChainChannelState.PayoutState(tsClientBalance, tsExchangeBalance)
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
        if actionCancelOthers || isEmpty(tsOrders.inner) then
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

  def cosmexValidator(params: ExchangeParams, state: OnChainState, action: Action, ctx: ScriptContext): Boolean = {
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
                    case Trades(actionTrades, actionCancelOthers) =>
                      throw new Exception("Trades not supported in OpenState")
                    case Payout                      => throw new Exception("Payout not supported in OpenState")
                    case Transfer(txOutIndex, value) => throw new Exception("Transfer not supported in OpenState")
                    case Timeout                     => throw new Exception("Timeout not supported in OpenState")

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
                    case Update      => throw new Exception("Update not supported in SnapshotContestState")
                    case ClientAbort => throw new Exception("ClientAbort not supported in SnapshotContestState")
                    case Trades(actionTrades, actionCancelOthers) =>
                      throw new Exception("Trades not supported in SnapshotContestState")
                    case Payout => throw new Exception("Payout not supported in SnapshotContestState")
                    case Transfer(txOutIndex, value) =>
                      throw new Exception("Transfer not supported in SnapshotContestState")
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
                    case Update      => throw new Exception("Update not supported in TradesContestState")
                    case ClientAbort => throw new Exception("ClientAbort not supported in TradesContestState")
                    case Close(party, signedSnapshot) =>
                      throw new Exception("Close not supported in TradesContestState")
                    case Payout => throw new Exception("Payout not supported in TradesContestState")
                    case Transfer(txOutIndex, value) =>
                      throw new Exception("Transfer not supported in TradesContestState")

                case PayoutState(clientBalance, exchangeBalance) =>
                  action match
                    case Transfer(txOutIndex, value)  =>
                    case Payout                       =>
                    case Update                       => throw new Exception("Update not supported in PayoutState")
                    case ClientAbort                  => throw new Exception("ClientAbort not supported in PayoutState")
                    case Close(party, signedSnapshot) => throw new Exception("Close not supported in PayoutState")
                    case Trades(actionTrades, actionCancelOthers) =>
                      throw new Exception("Trades not supported in PayoutState")
                    case Timeout => throw new Exception("Timeout not supported in PayoutState")

      false
    }

    ctx match
      case ScriptContext(txInfo, purpose) =>
        purpose match
          case Minting(curSymbol)         => throw new Exception("Minting not supported")
          case Rewarding(stakingCred)     => throw new Exception("Rewarding not supported")
          case Certifying(cert)           => throw new Exception("Certifying not supported")
          case Spending(spendingTxOutRef) => cosmexSpending(txInfo, spendingTxOutRef)

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
                            if orderAmountLeft == BigInt(0) then AssocMap.delete(tsOrders)(orderId)
                            else
                              AssocMap.insert(tsOrders)(
                                orderId,
                                new LimitOrder(pair, orderAmount = orderAmountLeft, orderPrice = orderPrice)
                              )
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

  def validRange(interval: Interval[POSIXTime]): (POSIXTime, POSIXTime) = {
    interval match
      case Interval(lower, upper) =>
        lower match
          case LowerBound(start, closure) =>
            start match
              case Extended.NegInf => throw new RuntimeException("LBNI")
              case Extended.PosInf => throw new RuntimeException("LBPI")
              case Finite(start) =>
                upper match
                  case UpperBound(upper, closure) =>
                    upper match
                      case Extended.NegInf => throw new RuntimeException("UBNI")
                      case Extended.PosInf => throw new RuntimeException("UBPI")
                      case Finite(e)       => (start, e)
  }

}

object CosmexValidator {
  // Split the validator into two parts to avoid 
  // Generated bytecode for method 'cosmex.CosmexValidator$.<clinit>' is too large. Limit is 64KB
  import scalus.sir.SirDSL.{*, given}
  def compiledWrapperValidator = Compiler.compile(CosmexContract.validator)
  def compiledTypedValidator = Compiler.compile(CosmexContract.cosmexValidator)
  val compiledValidator = compiledWrapperValidator $ compiledTypedValidator
  val validator = new SimpleSirToUplcLowering(generateErrorTraces = false).lower(compiledValidator)
  val flatEncodedValidator = ProgramFlatCodec.encodeFlat(Program((2, 0, 0), validator))
  val cborEncodedValidator = Cbor.encode(flatEncodedValidator).toByteArray
  val doubleCborEncodedValidator = Cbor.encode(cborEncodedValidator).toByteArray
  val doubleCborEncodedValidatorHex = Hex.bytesToHex(doubleCborEncodedValidator)
}

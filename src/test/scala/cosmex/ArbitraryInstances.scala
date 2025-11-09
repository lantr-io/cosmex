package cosmex
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import scalus.builtin.ByteString
import scalus.builtin.ByteString.hex
import scalus.ledger.api.v1.PosixTime
import scalus.ledger.api.v1.TokenName
import scalus.ledger.api.v3.*
import scalus.prelude.*

trait ArbitraryInstances extends scalus.testing.kit.ArbitraryInstances {
    given Arbitrary[Party] = Arbitrary { Gen.oneOf(Party.Client, Party.Exchange) }

    given Arbitrary[LimitOrder] = Arbitrary {
        for
            pair <- Gen.const((hex"aa", hex"bb"), (hex"bb", hex"aa")) // FIXME: use real generator
            amount <- Arbitrary.arbitrary[BigInt]
            price <- Arbitrary.arbitrary[BigInt]
        yield LimitOrder(pair, amount, price)
    }

    given Arbitrary[PendingTxType] = Arbitrary {
        for
            txOutIndex <- Gen.choose[BigInt](0, 1000)
            t <- Gen.oneOf(
              PendingTxType.PendingIn,
              PendingTxType.PendingOut(txOutIndex),
              PendingTxType.PendingTransfer(txOutIndex)
            )
        yield t
    }

    val genNonAdaValue: Gen[Value] =
        for
            currency <- Arbitrary.arbitrary[PolicyId]
            token <- Arbitrary.arbitrary[TokenName]
            value <- Gen.choose[BigInt](0, 1000)
        yield Value(currency, token, value)

    given Arbitrary[PendingTx] = Arbitrary {
        for
            pendingTxValue <- Arbitrary.arbitrary[Value]
            pendingTxType <- Arbitrary.arbitrary[PendingTxType]
            pendingTxSpentTxOutRef <- Arbitrary.arbitrary[TxOutRef]
        yield PendingTx(pendingTxValue, pendingTxType, pendingTxSpentTxOutRef)
    }

    given arbAssocMap[A: Arbitrary, B: Arbitrary]: Arbitrary[scalus.prelude.AssocMap[A, B]] =
        Arbitrary {
            for map <- Arbitrary.arbitrary[Map[A, B]]
            yield scalus.prelude.AssocMap.unsafeFromList(scalus.prelude.List(map.toSeq*))
        }

    given Arbitrary[TradingState] = Arbitrary {
        for
            tsClientBalance <- Arbitrary.arbitrary[Value]
            tsExchangeBalance <- Arbitrary.arbitrary[Value]
            tsOrders <- Arbitrary.arbitrary[AssocMap[OrderId, LimitOrder]]
        yield TradingState(tsClientBalance, tsExchangeBalance, tsOrders)
    }

    given Arbitrary[Snapshot] = Arbitrary {
        for
            snapshotTradingState <- Arbitrary.arbitrary[TradingState]
            snapshotPendingTx <- Arbitrary.arbitrary[Option[PendingTx]]
            snapshotVersion <- Arbitrary.arbitrary[BigInt]
        yield Snapshot(snapshotTradingState, snapshotPendingTx, snapshotVersion)
    }

    val genSignature: Gen[Signature] =
        Gen.listOfN(64 * 2, Gen.hexChar).map(h => ByteString.fromHex(h.mkString))

    given Arbitrary[SignedSnapshot] = Arbitrary {
        for
            signedSnapshot <- Arbitrary.arbitrary[Snapshot]
            snapshotClientSignature <- genSignature
            snapshotExchangeSignature <- genSignature
        yield SignedSnapshot(signedSnapshot, snapshotClientSignature, snapshotExchangeSignature)
    }

    given Arbitrary[OnChainChannelState] = Arbitrary {
        for o <- Gen.oneOf(
              Gen.const(OnChainChannelState.OpenState),
              for
                  contestSnapshot <- Arbitrary.arbitrary[Snapshot]
                  contestSnapshotStart <- Arbitrary.arbitrary[PosixTime]
                  contestInitiator <- Arbitrary.arbitrary[Party]
                  contestChannelTxOutRef <- Arbitrary.arbitrary[TxOutRef]
              yield OnChainChannelState.SnapshotContestState(
                contestSnapshot,
                contestSnapshotStart,
                contestInitiator,
                contestChannelTxOutRef
              ),
              for
                  latestTradingState <- Arbitrary.arbitrary[TradingState]
                  tradeContestStart <- Arbitrary.arbitrary[PosixTime]
              yield OnChainChannelState.TradesContestState(latestTradingState, tradeContestStart),
              for
                  clientBalance <- Arbitrary.arbitrary[Value]
                  exchangeBalance <- Arbitrary.arbitrary[Value]
              yield OnChainChannelState.PayoutState(clientBalance, exchangeBalance)
            )
        yield o
    }

    given Arbitrary[OnChainState] = Arbitrary {
        for
            clientPkh <- Arbitrary.arbitrary[PubKeyHash]
            clientPubKey <- Gen
                .listOfN(32 * 2, Gen.hexChar)
                .map(h => ByteString.fromHex(h.mkString))
            clientTxOutRef <- Arbitrary.arbitrary[TxOutRef]
            channelState <- Arbitrary.arbitrary[OnChainChannelState]
        yield OnChainState(clientPkh, clientPubKey, clientTxOutRef, channelState)
    }

    given Arbitrary[Action] = Arbitrary {
        Gen.const(Action.Update)
    }
}

package cosmex.util


import scalus.builtin.ToData.tupleToData
import scalus.builtin.{platform, Builtins, ByteString}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.node.Provider
import scalus.ledger.api.v3.{PubKeyHash, TxId, TxOutRef}
import scalus.prelude.{Eq, Ord}
import scalus.serialization.cbor.Cbor
import upickle.default.*

import java.time.Instant
import scala.annotation.unused
import scala.collection.mutable.HashMap

import cosmex.*
import cosmex.given

import scalus.utils.Hex.*

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

    given rwCoin: ReadWriter[Coin] = readwriter[Long].bimap(_.value, Coin.apply)
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
        tx => tx.toCbor.toHex, // Simplified - just serialize the ID
        hex => Cbor.decode(hex.hexToBytes)
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


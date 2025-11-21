package cosmex.util

import cosmex.*
import scalus.builtin.ByteString
import scalus.cardano.ledger.*
import scalus.ledger.api.v1
import scalus.ledger.api.v3.{PubKeyHash, TxId, TxOutRef}
import scalus.prelude
import scalus.prelude.{Eq, Ord}
import scalus.serialization.cbor.Cbor
import scalus.utils.Hex.*
import upickle.default.*

import java.time.Instant

object JsonCodecs {
    // Basic type codecs
    given ReadWriter[ByteString] = readwriter[String].bimap[ByteString](
      bs => bs.toHex,
      hex => ByteString.fromHex(hex)
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
    given [A: ReadWriter]: ReadWriter[prelude.Option[A]] =
        readwriter[Option[A]].bimap[prelude.Option[A]](
          opt => opt.asScala,
          {
              case None    => prelude.Option.None: prelude.Option[A]
              case Some(a) => prelude.Option.Some(a)
          }
        )
    given rwSortedMap[A: ReadWriter: Ord, B: ReadWriter]: ReadWriter[prelude.SortedMap[A, B]] =
        readwriter[ujson.Value].bimap[prelude.SortedMap[A, B]](
          smap =>
              ujson.Arr.from(smap.toList.asScala.map { case (k, v) =>
                  ujson.Obj("k" -> writeJs(k), "v" -> writeJs(v))
              }.toSeq),
          {
              case ujson.Arr(a) =>
                  prelude.SortedMap.from(
                    a.map(v => read[A](v.obj("k")) -> read[B](v.obj("v")))
                  )
              case other => throw new Exception(s"Cannot parse SortedMap from $other")
          }
        )

    given rwList[A: ReadWriter]: ReadWriter[prelude.List[A]] =
        readwriter[ujson.Value].bimap[prelude.List[A]](
          list => ujson.Arr.from(list.asScala.map(elem => writeJs(elem))),
          {
              case ujson.Arr(a) =>
                  prelude.List.from(a.map(v => read[A](v)))
              case other => throw new Exception(s"Cannot parse List from $other")
          }
        )

    given rwAssocMap[A: ReadWriter: Eq, B: ReadWriter]: ReadWriter[prelude.AssocMap[A, B]] =
        readwriter[ujson.Value].bimap[prelude.AssocMap[A, B]](
          amap =>
              ujson.Arr.from(amap.toList.asScala.map { case (k, v) =>
                  ujson.Obj("k" -> writeJs(k), "v" -> writeJs(v))
              }.toSeq),
          {
              case ujson.Arr(a) =>
                  prelude.AssocMap.fromList(
                    prelude.List.from(a.map(v => (read[A](v.obj("k")), read[B](v.obj("v")))))
                  )
              case other => throw new Exception(s"Cannot parse AssocMap from $other")
          }
        )

    given rwCoin: ReadWriter[Coin] = readwriter[Long].bimap(_.value, Coin.apply)
    given rwMultiAsset: ReadWriter[MultiAsset] = macroRW
    given rwLedgerValue: ReadWriter[Value] = macroRW
    given rwPlutusValue: ReadWriter[v1.Value] =
        readwriter[Value].bimap(_.toLedgerValue, LedgerToPlutusTranslation.getValue)

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
      _.toCbor.toHex, // Simplified - just serialize the ID
      hex => Cbor.decode(hex.hexToBytes)
    )

    // Instant codec for timestamps
    given rwInstant: ReadWriter[Instant] = readwriter[String].bimap[Instant](
      _.toString,
      Instant.parse(_)
    )

    // PubKeyHash from scalus v1
    given rwPubKeyHashV1: ReadWriter[v1.PubKeyHash] =
        readwriter[ByteString].bimap[v1.PubKeyHash](
          _.hash,
          v1.PubKeyHash.apply
        )

    // ExchangeParams
    given rwExchangeParams: ReadWriter[ExchangeParams] = macroRW

    // Action enum
    given rwParty: ReadWriter[Party] = readwriter[String].bimap[Party](_.toString, Party.valueOf)

    given rwAction: ReadWriter[Action] = macroRWAll
    // OnChainChannelState enum codec
    given rwOnChainChannelState: ReadWriter[OnChainChannelState] = macroRWAll
    // OnChainState codec
    given rwOnChainState: ReadWriter[OnChainState] = macroRW
}

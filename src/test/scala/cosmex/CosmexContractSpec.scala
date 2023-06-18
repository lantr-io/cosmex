package cosmex

import cosmex.CosmexContract.given
import cosmex.CosmexToDataInstances.given
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.Compiler.compile
import scalus.*
import scalus.builtins.ByteString
import scalus.builtins.ByteString.given
import scalus.ledger.api.v1.CurrencySymbol
import scalus.ledger.api.v1.TokenName
import scalus.ledger.api.v2.*
import scalus.prelude.AssocMap
import scalus.prelude.Maybe
import scalus.pretty
import scalus.uplc.Data.FromData
import scalus.uplc.Data.ToData
import scalus.uplc.Data.fromData
import scalus.uplc.Data.toData
import scalus.uplc.TermDSL.{_, given}
import scalus.uplc.*

import scala.reflect.ClassTag

enum Expected {
    case Success(value: Term)
    case Failure(reason: String)
}

class CosmexContractSpec extends AnyFunSuite with ScalaCheckPropertyChecks {
    import Expected.*

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

    val genCurrencySymbol: Gen[CurrencySymbol] =
        Gen.listOfN(28 * 2, Gen.hexChar).map(_.mkString).map(ByteString.fromHex)

    val genTokenName: Gen[TokenName] = for
        len <- Gen.choose(1, 32)
        name <- Gen.stringOfN(len, Gen.alphaNumChar)
    yield ByteString.fromString(name)

    val genNonAdaValue: Gen[Value] =
        for
            currency <- genCurrencySymbol
            token <- genTokenName
            value <- Gen.choose[BigInt](0, 1000)
        yield Value(currency, token, value)

    // TODO: improve generator
    given Arbitrary[Value] = Arbitrary {
        Gen.oneOf(genNonAdaValue, Gen.choose[BigInt](0, 1000).map(Value.lovelace))
    }

    given Arbitrary[PubKeyHash] = Arbitrary {
        Gen.listOfN(28 * 2, Gen.hexChar).map(h => PubKeyHash(ByteString.fromHex(h.mkString)))
    }
    given Arbitrary[TxId] = Arbitrary {
        Gen.listOfN(32 * 2, Gen.hexChar).map(h => TxId(ByteString.fromHex(h.mkString)))
    }
    given Arbitrary[TxOutRef] = Arbitrary {
        // case class TxOutRef(id: TxId, idx: BigInt)
        for
            txId <- Arbitrary.arbitrary[TxId]
            idx <- Gen.choose[BigInt](0, 1000)
        yield TxOutRef(txId, idx)
    }

    given Arbitrary[PendingTx] = Arbitrary {
        for
            pendingTxValue <- Arbitrary.arbitrary[Value]
            pendingTxType <- Arbitrary.arbitrary[PendingTxType]
            pendingTxSpentTxOutRef <- Arbitrary.arbitrary[TxOutRef]
        yield PendingTx(pendingTxValue, pendingTxType, pendingTxSpentTxOutRef)
    }

    /* case class TradingState(
    tsClientBalance: Value,
    tsExchangeBalance: Value,
    tsOrders: AssocMap[OrderId, LimitOrder]
) */
    given arbAssocMap[A: Arbitrary, B: Arbitrary]: Arbitrary[scalus.prelude.AssocMap[A, B]] =
        Arbitrary {
            for map <- Arbitrary.arbitrary[Map[A, B]]
            yield scalus.prelude.AssocMap.fromList(scalus.prelude.List(map.toSeq: _*))
        }
    given Arbitrary[TradingState] = Arbitrary {
        for
            tsClientBalance <- Arbitrary.arbitrary[Value]
            tsExchangeBalance <- Arbitrary.arbitrary[Value]
            tsOrders <- Arbitrary.arbitrary[AssocMap[OrderId, LimitOrder]]
        yield TradingState(tsClientBalance, tsExchangeBalance, tsOrders)
    }

    /* case class Snapshot(
    snapshotTradingState: TradingState,
    snapshotPendingTx: Maybe[PendingTx],
    snapshotVersion: BigInt
) */
    given arbMaybe[A: Arbitrary]: Arbitrary[scalus.prelude.Maybe[A]] = Arbitrary {
        for o <- Arbitrary.arbitrary[Option[A]]
        yield o match
            case None        => scalus.prelude.Maybe.Nothing
            case Some(value) => scalus.prelude.Maybe.Just(value)
    }
    given Arbitrary[Snapshot] = Arbitrary {
        for
            snapshotTradingState <- Arbitrary.arbitrary[TradingState]
            snapshotPendingTx <- Arbitrary.arbitrary[Maybe[PendingTx]]
            snapshotVersion <- Arbitrary.arbitrary[BigInt]
        yield Snapshot(snapshotTradingState, snapshotPendingTx, snapshotVersion)
    }

    test("Pretty print CosmexContract") {
        val program = CosmexValidator.mkCosmexValidator(ExchangeParams(PubKeyHash(hex"1234"), hex"5678", 5000))
        println(program.term.pretty.render(100))
        val uplcProgram = Program(program.version, program.term.toUplc())
        println(s"Size: ${uplcProgram.cborEncoded.length}")
        // println(s"CBOR: ${uplcProgram.doubleCborHex}")

    }

    inline def testSerialization[A: FromData: ToData: ClassTag: Arbitrary] = {
        val sir = compile { (d: Data) => fromData[A](d).toData }
        // println(sir.pretty.render(100))
        val term = sir.toUplc()
        test(s"Serialization of ${summon[ClassTag[A]].runtimeClass.getSimpleName}") {
            forAll { (a: A) =>
                assertEval(Program((2, 0, 0), term $ a.toData), Success(a.toData))
            }
        }
    }

    testSerialization[Party]
    testSerialization[LimitOrder]
    testSerialization[PendingTxType]
    testSerialization[PendingTx]
    testSerialization[TradingState]
    testSerialization[Snapshot]

    def assertEval(p: Program, expected: Expected) = {
        val result = PlutusUplcEval.evalFlat(p)
        (result, expected) match
            case (UplcEvalResult.Success(result), Expected.Success(expected)) =>
                assert(result == expected)
            case (UplcEvalResult.UplcFailure(code, error), Expected.Failure(expected)) =>
            case _ => fail(s"Unexpected result: $result, expected: $expected")
    }
}

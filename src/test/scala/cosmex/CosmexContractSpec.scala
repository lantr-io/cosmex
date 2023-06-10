package cosmex

import cosmex.CosmexContract.given
import cosmex.CosmexToDataInstances.given
import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.Compiler.compile
import scalus.builtins.ByteString
import scalus.builtins.ByteString.given
import scalus.ledger.api.v2.*
import scalus.prelude.*
import scalus.pretty
import scalus.sir.SIR
import scalus.sir.SimpleSirToUplcLowering
import scalus.uplc.Data.FromData
import scalus.uplc.Data.ToData
import scalus.uplc.Data.fromData
import scalus.uplc.Data.toData
import scalus.uplc.TermDSL.{_, given}
import scalus.uplc.*
import scalus.*

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

  test("Pretty print CosmexContract") {
    println(CosmexValidator.compiledValidator.pretty.render(100))
    println(s"Size: ${CosmexValidator.programV2.flatEncoded.length}")
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

  def assertEval(p: Program, expected: Expected) = {
    val result = PlutusUplcEval.evalFlat(p)
    (result, expected) match
      case (UplcEvalResult.Success(result), Expected.Success(expected)) =>
        assert(result == expected)
      case (UplcEvalResult.UplcFailure(code, error), Expected.Failure(expected)) =>
      case _ => fail(s"Unexpected result: $result, expected: $expected")
  }
}

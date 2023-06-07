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
import scalus.sir.SimpleSirToUplcLowering
import scalus.uplc.Data.FromData
import scalus.uplc.Data.ToData
import scalus.uplc.Data.fromData
import scalus.uplc.Data.toData
import scalus.uplc.TermDSL.{_, given}
import scalus.uplc.*
import scala.reflect.ClassTag
import scalus.sir.SIR

enum Expected {
  case Success(value: Term)
  case Failure(reason: String)
}

class CosmexContractSpec extends AnyFunSuite with ScalaCheckPropertyChecks {
  import Expected.*

  given Arbitrary[Party] = Arbitrary { Gen.oneOf(Party.Client, Party.Exchange) }

  test("Pretty print CosmexContract") {
    // println(CosmexValidator.compiledValidator.pretty.render(100))
    // println(s"Size: ${CosmexValidator.flatEncodedValidator.size}")
  }

  def testSerialization[A: FromData: ToData: ClassTag: Arbitrary](sir: SIR) = {
    val term = new SimpleSirToUplcLowering().lower(sir)
    test(s"Serialization of ${summon[ClassTag[A]].runtimeClass.getSimpleName}") {
      forAll { (a: A) =>
        assertEval(Program((2, 0, 0), term $ a.toData), Success(a.toData))
      }
    }
  }

  testSerialization[Party](compile { (d: Data) => fromData[Party](d).toData })

  def assertEval(p: Program, expected: Expected) = {
    val result = PlutusUplcEval.evalFlat(p)
    (result, expected) match
      case (UplcEvalResult.Success(result), Expected.Success(expected)) =>
        assert(result == expected)
      case (UplcEvalResult.UplcFailure(code, error), Expected.Failure(expected)) =>
      case _ => fail(s"Unexpected result: $result, expected: $expected")
  }
}

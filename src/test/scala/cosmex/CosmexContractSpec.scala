package cosmex

import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.pretty
import scalus.Compiler.compile
import scalus.builtins.ByteString
import scalus.builtins.ByteString.given
import scalus.ledger.api.v2.*
import scalus.prelude.*
import scalus.sir.SimpleSirToUplcLowering
import scalus.uplc.TermDSL.{_, given}
import scalus.uplc.*

enum Expected {
  case Success(value: Term)
  case Failure(reason: String)
}

class CosmexContractSpec extends AnyFunSuite with ScalaCheckPropertyChecks {
  import Expected.*

  test("Pretty print CosmexContract") {
    // println(CosmexValidator.compiledValidator.pretty.render(100))
    println(s"Size: ${CosmexValidator.flatEncodedValidator.size}")
  }

  def assertEval(p: Program, expected: Expected) = {
    val result = PlutusUplcEval.evalFlat(p)
    (result, expected) match
      case (UplcEvalResult.Success(result), Expected.Success(expected)) =>
        assert(result == expected)
      case (UplcEvalResult.UplcFailure(code, error), Expected.Failure(expected)) =>
      case _ => fail(s"Unexpected result: $result, expected: $expected")
  }
}

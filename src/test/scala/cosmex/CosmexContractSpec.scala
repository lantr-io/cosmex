package cosmex

import com.bloxbean.cardano.client.common.ADAConversionUtil
import com.bloxbean.cardano.client.plutus.spec.ExUnits
import com.bloxbean.cardano.client.plutus.spec.PlutusV2Script
import com.bloxbean.cardano.client.plutus.spec.Redeemer as PlutusRedeemer
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag
import com.bloxbean.cardano.client.transaction.spec
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.transaction.spec.TransactionBody
import com.bloxbean.cardano.client.transaction.spec.TransactionInput
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet
import cosmex.CosmexFromDataInstances.given
import cosmex.CosmexToDataInstances.given
import io.bullet.borer.Cbor
import org.scalacheck.Arbitrary
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.*
import scalus.Compiler.compile
import scalus.bloxbean.Interop
import scalus.bloxbean.SlotConfig
import scalus.builtin.ByteString
import scalus.builtin.ByteString.given
import scalus.builtin.Data
import scalus.builtin.Data.FromData
import scalus.builtin.Data.ToData
import scalus.builtin.Data.toData
import scalus.builtin.PlatformSpecific
import scalus.builtin.given
import scalus.ledger.api.v2.*
import scalus.sir.EtaReduce
import scalus.sir.RemoveRecursivity
import scalus.sir.SIR
import scalus.uplc.*
import scalus.uplc.TermDSL.{*, given}
import scalus.uplc.eval.Result
import scalus.uplc.eval.VM

import java.math.BigInteger
import java.util
import scala.language.implicitConversions
import scala.reflect.ClassTag

enum Expected {
    case Success(value: Term)
    case Failure(reason: String)
}

class CosmexContractSpec extends AnyFunSuite with ScalaCheckPropertyChecks with ArbitraryInstances {
    import Expected.*

    test("Pretty print CosmexContract") {
        val program = CosmexValidator.mkCosmexValidator(ExchangeParams(PubKeyHash(hex"1234"), hex"5678", 5000))
        // println(program.term.prettyXTerm.render(100))
        // println(s"Size: ${program.cborEncoded.length}")
        // println(s"CBOR: ${uplcProgram.doubleCborHex}")

    }

    testSerialization[Action](compile((d: Data) => d.to[Action].toData))
    testSerialization[Party](compile((d: Data) => d.to[Party].toData))
    testSerialization[LimitOrder](compile((d: Data) => d.to[LimitOrder].toData))
    testSerialization[PendingTxType](compile((d: Data) => d.to[PendingTxType].toData))
    testSerialization[PendingTx](compile((d: Data) => d.to[PendingTx].toData))
    testSerialization[TradingState](compile((d: Data) => d.to[TradingState].toData))
    testSerialization[Snapshot](compile((d: Data) => d.to[Snapshot].toData))
    testSerialization[SignedSnapshot](compile((d: Data) => d.to[SignedSnapshot].toData))
    testSerialization[OnChainChannelState](compile((d: Data) => d.to[OnChainChannelState].toData))
    testSerialization[OnChainState](compile((d: Data) => d.to[OnChainState].toData))

    test("validRange") {
        val sir = compile { (i: Interval) =>
            CosmexContract.validRange(i)._2
        } |> RemoveRecursivity.apply
        // println(sir.prettyXTerm.render(100))
        val uplc = sir.toUplc(generateErrorTraces = true) |> EtaReduce.apply
        // println(uplc.prettyXTerm.render(100))
        val i = VM.evaluateTerm(compile(Interval.between(1, 10)).toUplc())
        assertEval(Program((1, 0, 0), uplc $ i), Success(10))
    }

    test("validator should return false") {
        CosmexValidator.mkCosmexValidator(ExchangeParams(PubKeyHash(hex"1234"), hex"5678", 5000))

    }

    def assertEval(p: Program, expected: Expected) = {
        val result = VM.evaluateDebug(p.term)
        (result, expected) match
            case (Result.Success(result, budget, _, _), Expected.Success(expected)) =>
                assert(result == expected)
            case (Result.Failure(ex, _, _, _), Expected.Failure(expected)) =>
            case (Result.Failure(ex, _, _, _), _) =>
                fail(s"Unexpected failure: $ex", ex)
            case _ => fail(s"Unexpected result: $result, expected: $expected")
    }

    def testSerialization[A: FromData: ToData: ClassTag: Arbitrary](sir: SIR) = {
        import scala.language.implicitConversions
        // println(sir.pretty.render(100))
        val term = sir.toUplc()
        test(s"Serialization of ${summon[ClassTag[A]].runtimeClass.getSimpleName}") {
            forAll { (a: A) =>
                assertEval(Program((1, 0, 0), term $ a.toData), Success(a.toData))
            }
        }
    }

    private def evalValidator[A](state: OnChainState, action: Action)(
        pf: PartialFunction[eval.Result, A]
    ): A = {
        import scalus.ledger.api.v2.ToDataInstances.given
        val validator = CosmexValidator.mkCosmexValidator(ExchangeParams(PubKeyHash(hex"1234"), hex"5678", 5000))
        val datum = state.toData
        val redeemer = action.toData
        val scriptContext = makeScriptContext(datum, redeemer, Seq.empty)
        val term = validator.term $ datum $ redeemer $ scriptContext.toData
        val result = VM.evaluateDebug(term)
        pf(result)
    }

    private def makeScriptContext(
        datum: Data,
        redeemer: Data,
        signatories: Seq[PubKeyHash]
    ): ScriptContext = {
        import scala.jdk.CollectionConverters.*
        val cosmexValidator = CosmexValidator.mkCosmexValidator(ExchangeParams(PubKeyHash(hex"1234"), hex"5678", 5000))
        val cosmexPlutusScript = PlutusV2Script
            .builder()
            .`type`("PlutusScriptV2")
            .cborHex(cosmexValidator.doubleCborHex)
            .build()
            .asInstanceOf[PlutusV2Script]
        val crypto = summon[PlatformSpecific]
        val rdmr = PlutusRedeemer
            .builder()
            .tag(RedeemerTag.Spend)
            .data(Interop.toPlutusData(redeemer))
            .index(BigInteger.valueOf(0))
            .exUnits(
              ExUnits
                  .builder()
                  .steps(BigInteger.valueOf(1000))
                  .mem(BigInteger.valueOf(1000))
                  .build()
            )
            .build()

        val input = TransactionInput
            .builder()
            .transactionId("1ab6879fc08345f51dc9571ac4f530bf8673e0d798758c470f9af6f98e2f3982")
            .index(0)
            .build()
        val inputs = util.List.of(input)

        val utxo = Map(
          input -> TransactionOutput
              .builder()
              .value(spec.Value.builder().coin(BigInteger.valueOf(20)).build())
              .address("addr1aldkfjlkjaldfj")
              .inlineDatum(Interop.toPlutusData(datum))
              .build()
        )
        val tx = Transaction
            .builder()
            .body(
              TransactionBody
                  .builder()
                  .fee(ADAConversionUtil.adaToLovelace(0.2))
                  .inputs(inputs)
                  .requiredSigners(signatories.map(_.hash.bytes).asJava)
                  .build()
            )
            .witnessSet(
              TransactionWitnessSet
                  .builder()
                  .plutusV2Scripts(util.List.of(cosmexPlutusScript))
                  .redeemers(util.List.of(rdmr))
                  .build()
            )
            .build()

        val purpose = Interop.getScriptPurpose(
          rdmr,
          tx.getBody().getInputs(),
          util.List.of(),
          util.List.of(),
          util.List.of()
        )
        val datumCbor = ByteString.fromArray(Cbor.encode(datum).toByteArray)
        val datumHash = crypto.blake2b_256(datumCbor)
        val datums = Seq((datumHash, datum))
        val protocolVersion = 8
        val txInfo = Interop.getTxInfoV2(tx, datums, utxo, SlotConfig.default, protocolVersion)
        val scriptContext = ScriptContext(txInfo, purpose)
        scriptContext
    }
}

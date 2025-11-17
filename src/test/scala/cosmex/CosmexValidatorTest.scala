package cosmex

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import org.scalacheck.Arbitrary
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.*
import scalus.Compiler.*
import scalus.builtin.Data.{toData, FromData, ToData}
import scalus.builtin.{Builtins, ByteString, Data}
import scalus.cardano.ledger.*
import scalus.cardano.txbuilder.Environment
import scalus.compiler.sir.TargetLoweringBackend.SumOfProductsLowering
import scalus.ledger.api.v3.*
import scalus.sir.SIR
import scalus.uplc.*
import scalus.uplc.Term.asTerm
import scalus.uplc.TermDSL.given
import scalus.uplc.eval.{ExBudget, ExCPU, ExMemory, PlutusVM, Result}

import scala.language.implicitConversions
import scala.reflect.ClassTag

enum Expected {
    case Success(value: Term)
    case Failure(reason: String)
}

class CosmexValidatorTest
    extends AnyFunSuite
    with ScalaCheckPropertyChecks
    with cosmex.ArbitraryInstances {
    import Expected.*

    private given PlutusVM = PlutusVM.makePlutusV3VM()
    import CosmexContract.given // for compiler options

    private val exchangeAccount = new Account(Networks.preview(), 1)
    private val exchangePubKey = ByteString.fromArray(exchangeAccount.publicKeyBytes())
    private val exchangePubKeyHash =
        ByteString.fromArray(exchangeAccount.hdKeyPair().getPublicKey.getKeyHash)
    private val exchangeParams = ExchangeParams(
      exchangePkh = PubKeyHash(exchangePubKeyHash),
      contestationPeriodInMilliseconds = 5000,
      exchangePubKey = exchangePubKey
    )
    private val clientAccount = new Account(Networks.preview(), 2)
    private val clientPubKey = ByteString.fromArray(clientAccount.publicKeyBytes())
    private val clientPubKeyHash =
        ByteString.fromArray(clientAccount.hdKeyPair().getPublicKey.getKeyHash)
    private val clientPkh = PubKeyHash(clientPubKeyHash)
    private val clientTxOutRef =
        TxOutRef(TxId(Builtins.blake2b_256(ByteString.fromString("client tx"))), 0)
    private val program = CosmexContract.mkCosmexProgram(exchangeParams)
    private val testProtocolParams: ProtocolParams = CardanoInfo.mainnet.protocolParams

    private val testEnv: Environment = CardanoInfo.mainnet
    private val txbuilder = Transactions(exchangeParams, testEnv)

    test(s"Cosmex Validator size is ${program.cborEncoded.length}") {
//        println(CosmexValidator.compiledValidator.showHighlighted)
        val length = program.cborEncoded.length
        assert(length == 10909)
    }

    testSerialization[Action]()
    testSerialization[Party]()
    testSerialization[LimitOrder]()
    testSerialization[PendingTxType]()
    testSerialization[PendingTx]()
    testSerialization[TradingState]()
    testSerialization[Snapshot]()
    testSerialization[SignedSnapshot]()
    testSerialization[OnChainChannelState]()
    testSerialization[OnChainState]()

    test("validRange") {
        val sir = compile { (i: Interval) =>
            CosmexValidator.validRange(i)._2
        }
        val uplc = sir.toUplcOptimized(generateErrorTraces = true).plutusV3
        val i = compile(Interval.between(1, 10)).toUplc().evaluate
        assertEval(uplc $ i, Success(10))
    }

    test("Update succeeds when there are both signatures") {
        val state = mkOnChainState(OnChainChannelState.OpenState)
        val tx = txbuilder.update(state, Seq(state.clientPkh, exchangeParams.exchangePkh))
        evalCosmexValidator(state, tx) { case Result.Success(_, _, _, _) => }
    }

    test("Update fails when there is no client signature") {
        val state = mkOnChainState(OnChainChannelState.OpenState)
        val tx = txbuilder.update(state, Seq(exchangeParams.exchangePkh))
        evalCosmexValidator(state, tx) { case Result.Failure(_, _, _, logs) =>
            assert(logs.mkString("").contains("clientSigned ? False"))
        }
    }

    test("Update fails when there is no exchange signature") {
        val state = mkOnChainState(OnChainChannelState.OpenState)
        val tx = txbuilder.update(state, Seq(clientPkh))
        evalCosmexValidator(state, tx) { case Result.Failure(_, _, _, logs) =>
            assert(logs.mkString("").contains("exchangeSigned ? False"))
        }
    }

    test("it's much cheaper to compute hash vs store in datum") {
        // what's cheaper: storing clientPubKeyHash in datum vs computing it from clientPubKey?
        val sir = compileWithOptions(
          Compiler.Options.default.copy(targetLoweringBackend = SumOfProductsLowering),
          { Builtins.blake2b_224 }
        )
        val uplc = sir.toUplcOptimized(generateErrorTraces = true).plutusV3 $ clientPubKey.asTerm
        val result = uplc.term.evaluateDebug
        assert(result.budget == ExBudget(cpu = ExCPU(288956), memory = ExMemory(404)))
        val executionUnitPrices = testProtocolParams.executionUnitPrices
        val exUnits = result.budget
        val computationFee =
            (executionUnitPrices.priceMemory * exUnits.memory + executionUnitPrices.priceSteps * exUnits.cpu).ceil
        assert(computationFee == 45L)
        val datumFee = clientPubKeyHash.toData.toCbor.length * testProtocolParams.txFeePerByte
        assert(datumFee == 1320L)
        assert(computationFee < datumFee)
    }

    private def mkOnChainState(channelState: OnChainChannelState) = {
        OnChainState(
          clientPkh = clientPkh,
          clientPubKey = clientPubKey,
          clientTxOutRef = clientTxOutRef,
          channelState = channelState
        )
    }

    private def assertEval(p: Program, expected: Expected) = {
        val result = p.term.evaluateDebug
        (result, expected) match
            case (Result.Success(result, budget, _, _), Expected.Success(expected)) =>
                assert(result == expected)
            case (Result.Failure(_, _, _, _), Expected.Failure(_)) =>
            case (Result.Failure(ex, _, _, _), _) =>
                fail(s"Unexpected failure: $ex", ex)
            case _ => fail(s"Unexpected result: $result, expected: $expected")
    }

    private inline def testSerialization[A: FromData: ToData: ClassTag: Arbitrary](using
        options: Compiler.Options
    ): Unit = {
        import scala.language.implicitConversions
        val program =
            compileInlineWithOptions(options, (d: Data) => d.to[A].toData).toUplc().plutusV3
        test(s"Serialization of ${summon[ClassTag[A]].runtimeClass.getSimpleName}") {
            forAll { (a: A) =>
                assertEval(program $ a.toData, Success(a.toData))
            }
        }
    }

    private def evalCosmexValidator[A](state: OnChainState, tx: Transaction)(
        pf: PartialFunction[Result, Any]
    ): Any = {
        // Get the first redeemer from the transaction
        val redeemer = tx.witnessSet.redeemers.get.value.toSeq.head

        // Build the UTxO map from transaction inputs and outputs
        // Note: This is a simplified mapping for tests - inputs and outputs are zipped together
        val inputs = tx.body.value.inputs.toSeq
        val outputs = tx.body.value.outputs.map(_.value)
        val utxos: Map[TransactionInput, TransactionOutput] = inputs.zip(outputs).toMap

        // Convert Scalus Transaction to ScriptContext using LedgerToPlutusTranslation
        val scriptContext =
            LedgerToPlutusTranslation.getScriptContextV3(
              redeemer = redeemer,
              datum = Some(state.toData),
              tx = tx,
              utxos = utxos,
              slotConfig = SlotConfig.Preprod,
              protocolVersion =
                  scalus.cardano.ledger.MajorProtocolVersion(txbuilder.protocolVersion)
            )

        val applied = program $ scriptContext.toData
        val result = applied.evaluateDebug
        if pf.isDefinedAt(result) then pf(result)
        else fail(s"Unexpected result: $result")
    }
}

package cosmex

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.transaction.spec.Transaction
import com.bloxbean.cardano.client.transaction.util.TransactionUtil
import cosmex.CosmexFromDataInstances.given
import cosmex.CosmexToDataInstances.given
import org.scalacheck.Arbitrary
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.*
import scalus.Compiler.*
import scalus.bloxbean.Interop
import scalus.builtin.{Builtins, ByteString, Data}
import scalus.cardano.ledger.SlotConfig
import scalus.builtin.Data.{toData, FromData, ToData}
import scalus.ledger.api.v3.*
import scalus.sir.SIR
import scalus.uplc.*
import scalus.uplc.TermDSL.given
import scalus.uplc.eval.{PlutusVM, Result}

import scala.language.implicitConversions
import scala.reflect.ClassTag

enum Expected {
    case Success(value: Term)
    case Failure(reason: String)
}

class CosmexContractTest extends AnyFunSuite with ScalaCheckPropertyChecks with cosmex.ArbitraryInstances {
    import Expected.*

    private given PlutusVM = PlutusVM.makePlutusV3VM()

    private val exchangeAccount = new Account(Networks.preview(), 1)
    private val exchagePubKey = ByteString.fromArray(exchangeAccount.publicKeyBytes())
    private val exchagePubKeyHash = ByteString.fromArray(exchangeAccount.hdKeyPair().getPublicKey.getKeyHash)
    private val exchangeParams = ExchangeParams(
      exchangePkh = PubKeyHash(exchagePubKeyHash),
      contestationPeriodInMilliseconds = 5000,
      exchangePubKey = exchagePubKey
    )
    private val clientAccount = new Account(Networks.preview(), 2)
    private val clientPubKey = ByteString.fromArray(clientAccount.publicKeyBytes())
    private val clientPubKeyHash = ByteString.fromArray(clientAccount.hdKeyPair().getPublicKey.getKeyHash)
    private val clientPkh = PubKeyHash(clientPubKeyHash)
    private val clientTxOutRef = TxOutRef(TxId(Builtins.blake2b_256(ByteString.fromString("client tx"))), 0)
    private val validatorUplc = CosmexValidator.mkCosmexValidator(exchangeParams)
    private val txbuilder = TxBuilder(exchangeParams)

    test(s"Cosmex Validator size is ${validatorUplc.cborEncoded.length}") {
//        println(CosmexValidator.compiledValidator.showHighlighted)
        val length = validatorUplc.cborEncoded.length
        assert(length == 10358)
    }

    testSerialization[Action]
    testSerialization[Party]
    testSerialization[LimitOrder]
    testSerialization[PendingTxType]
    testSerialization[PendingTx]
    testSerialization[TradingState]
    testSerialization[Snapshot]
    testSerialization[SignedSnapshot]
    testSerialization[OnChainChannelState]
    testSerialization[OnChainState]

    test("validRange") {
        val sir = compile { (i: Interval) =>
            CosmexContract.validRange(i)._2
        }
        // println(sir.prettyXTerm.render(100))
        val uplc = sir.toUplcOptimized(generateErrorTraces = true).plutusV3
        // println(uplc.prettyXTerm.render(100))
        val i = compile(Interval.between(1, 10)).toUplc().evaluate
        assertEval(uplc $ i, Success(10))
    }

    test("Update succeeds when there are both signatures") {
        val state = mkOnChainState(OnChainChannelState.OpenState)
        val action = Action.Update
        val tx = txbuilder.mkTx(state.toData, action.toData, Seq(state.clientPkh, exchangeParams.exchangePkh))
        evalCosmexValidator(state, tx)({ case Result.Success(_, _, _, _) => })
    }

    test("Update fails when there is no client signature") {
        val state = mkOnChainState(OnChainChannelState.OpenState)
        val action = Action.Update
        val tx = txbuilder.mkTx(state.toData, action.toData, Seq(exchangeParams.exchangePkh))
        evalCosmexValidator(state, tx)({ case Result.Failure(_, _, _, logs) =>
            assert(logs.mkString("").contains("clientSigned ? False"))
        })
    }

    test("Update fails when there is no exchange signature") {
        val state = mkOnChainState(OnChainChannelState.OpenState)
        val action = Action.Update
        val tx = txbuilder.mkTx(state.toData, action.toData, Seq(clientPkh))
        evalCosmexValidator(state, tx)({ case Result.Failure(_, _, _, logs) =>
            assert(logs.mkString("").contains("exchangeSigned ? False"))
        })
    }

    private def mkOnChainState(channelState: OnChainChannelState) = {
        OnChainState(
          clientPkh = clientPkh,
          clientPubKey = clientPubKey,
          clientTxOutRef = clientTxOutRef,
          channelState = channelState
        )
    }

    def assertEval(p: Program, expected: Expected) = {
        val result = p.term.evaluateDebug
        (result, expected) match
            case (Result.Success(result, budget, _, _), Expected.Success(expected)) =>
                assert(result == expected)
            case (Result.Failure(_, _, _, _), Expected.Failure(_)) =>
            case (Result.Failure(ex, _, _, _), _) =>
                fail(s"Unexpected failure: $ex", ex)
            case _ => fail(s"Unexpected result: $result, expected: $expected")
    }

    private inline def testSerialization[A: FromData: ToData: ClassTag: Arbitrary]: Unit = {
        import scala.language.implicitConversions
        val program = compileInline((d: Data) => d.to[A].toData).toUplc().plutusV3
        test(s"Serialization of ${summon[ClassTag[A]].runtimeClass.getSimpleName}") {
            forAll { (a: A) =>
                assertEval(program $ a.toData, Success(a.toData))
            }
        }
    }

    private def evalCosmexValidator[A](state: OnChainState, tx: Transaction)(pf: PartialFunction[Result, Any]): Any = {
        val validator = CosmexValidator.mkCosmexValidator(exchangeParams)
        val utxos = Map(tx.getBody.getInputs.get(0) -> tx.getBody.getOutputs.get(0))
        val scriptContext =
            Interop.getScriptContextV3(
              tx.getWitnessSet.getRedeemers.get(0),
              Some(state.toData),
              tx,
              TransactionUtil.getTxHash(tx),
              utxos,
              SlotConfig.Preprod,
              protocolVersion = txbuilder.protocolVersion
            )
//        try CosmexContract.validator(exchangeParams)(state.toData, action.toData, scriptContext.toData)
//        catch
//            case e: Throwable =>
//                e.printStackTrace()
        val program = validator $ scriptContext.toData
//        CosmexContract.validate(exchangeParams.toData)(scriptContext.toData)
        val result = program.evaluateDebug
        if pf.isDefinedAt(result) then pf(result)
        else fail(s"Unexpected result: $result")
    }
}

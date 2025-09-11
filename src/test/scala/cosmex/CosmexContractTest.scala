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
import scalus.Compiler.compile
import scalus.bloxbean.Interop
import scalus.bloxbean.SlotConfig
import scalus.builtin.Builtins
import scalus.builtin.given
import scalus.builtin.ByteString
import scalus.builtin.Data
import scalus.builtin.Data.FromData
import scalus.builtin.Data.ToData
import scalus.builtin.Data.toData
import scalus.ledger.api.v3.*
import scalus.sir.SIR
import scalus.uplc.*
import scalus.uplc.TermDSL.{*, given}
import scalus.uplc.eval.{PlutusVM, Result}

import scala.language.implicitConversions
import scala.reflect.ClassTag

enum Expected {
    case Success(value: Term)
    case Failure(reason: String)
}

class CosmexContractTest extends AnyFunSuite with ScalaCheckPropertyChecks with cosmex.ArbitraryInstances {
    import Expected.*

    private given PlutusVM = PlutusVM.makePlutusV2VM()

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

    test(s"Cosmex Validator size is ${validatorUplc.doubleCborEncoded.length}") {
//        println(CosmexValidator.compiledValidator.showHighlighted)
        val length = validatorUplc.doubleCborEncoded.length

        assert(length == 11529)
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
        }
        // println(sir.prettyXTerm.render(100))
        val uplc = sir.toUplcOptimized(generateErrorTraces = true).plutusV2
        // println(uplc.prettyXTerm.render(100))
        val i = compile(Interval.between(1, 10)).toUplc().evaluate
        assertEval(uplc $ i, Success(10))
    }

    test("Update succeeds when there are both signatures") {
        val state = mkOnChainState(OnChainChannelState.OpenState)
        val action = Action.Update
        val tx = txbuilder.mkTx(state.toData, action.toData, Seq(state.clientPkh, exchangeParams.exchangePkh))
        evalCosmexValidator(state, action, tx) { case Result.Success(_, _, _, _) => }
    }

    test("Update fails when there is no client signature") {
        val state = mkOnChainState(OnChainChannelState.OpenState)
        val action = Action.Update
        val tx = txbuilder.mkTx(state.toData, action.toData, Seq(exchangeParams.exchangePkh))
        evalCosmexValidator(state, action, tx) { case Result.Failure(_, _, _, logs) =>
            assert(logs.mkString("").contains("clientSigned ? False"))
        }
    }

    test("Update fails when there is no exchange signature") {
        val state = mkOnChainState(OnChainChannelState.OpenState)
        val action = Action.Update
        val tx = txbuilder.mkTx(state.toData, action.toData, Seq(clientPkh))
        evalCosmexValidator(state, action, tx) { case Result.Failure(_, _, _, logs) =>
            assert(logs.mkString("").contains("exchangeSigned ? False"))
        }
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
        val result = p.evaluateDebug
        (result, expected) match
            case (Result.Success(result, budget, _, _), Expected.Success(expected)) =>
                assert(result == expected)
            case (Result.Failure(ex, _, _, _), Expected.Failure(expected)) =>
            case (Result.Failure(ex, _, _, _), _) =>
                fail(s"Unexpected failure: $ex", ex)
            case _ => fail(s"Unexpected result: $result, expected: $expected")
    }

    private def testSerialization[A: FromData: ToData: ClassTag: Arbitrary](sir: SIR) = {
        import scala.language.implicitConversions
        // println(sir.pretty.render(100))
        val term = sir.toUplc()
        test(s"Serialization of ${summon[ClassTag[A]].runtimeClass.getSimpleName}") {
            forAll { (a: A) =>
                assertEval(Program((1, 0, 0), term $ a.toData), Success(a.toData))
            }
        }
    }

    private def evalCosmexValidator[A](state: OnChainState, action: Action, tx: Transaction)(
        pf: PartialFunction[scalus.uplc.eval.Result, A]
    ): A = {
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

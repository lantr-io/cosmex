package cosmex

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.builtin.{Builtins, ByteString}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.cardano.node.{LedgerProvider, Provider}
import scalus.cardano.txbuilder.{Environment, TransactionUnspentOutput}
import scalus.ledger.api.v1.PubKeyHash
import scalus.ledger.api.v3.{TxId, TxOutRef}
import scalus.uplc.eval.ExBudget

import scala.language.implicitConversions

class CosmexTest extends AnyFunSuite with ScalaCheckPropertyChecks with cosmex.ArbitraryInstances {

    val cardanoInfo: CardanoInfo = CardanoInfo.mainnet
    val testProtocolParams: ProtocolParams = cardanoInfo.protocolParams
    val testEnvironmentWithoutEvaluator: Environment = Environment(
      cardanoInfo = cardanoInfo,
      evaluator = (_: Transaction, _: Map[TransactionInput, TransactionOutput]) => Seq.empty
    )

    val testEnvironmentWithEvaluator: Environment = Environment(
      cardanoInfo = cardanoInfo,
      evaluator = PlutusScriptEvaluator(
        slotConfig = cardanoInfo.slotConfig,
        initialBudget = ExBudget.enormous,
        protocolMajorVersion = cardanoInfo.majorProtocolVersion,
        costModels = testProtocolParams.costModels
      )
    )

    private val exchangeAccount = new Account(Networks.preview(), 1)
    private val exchangePubKey = ByteString.fromArray(exchangeAccount.publicKeyBytes())
    private val exchangePubKeyHash = ByteString.fromArray(exchangeAccount.hdKeyPair().getPublicKey.getKeyHash)
    private val exchangeParams = ExchangeParams(
      exchangePkh = PubKeyHash(exchangePubKeyHash),
      contestationPeriodInMilliseconds = 5000,
      exchangePubKey = exchangePubKey
    )
    private val clientAccount = new Account(Networks.preview(), 2)
    private val clientPubKey = ByteString.fromArray(clientAccount.publicKeyBytes())
    private val clientPubKeyHash = ByteString.fromArray(clientAccount.hdKeyPair().getPublicKey.getKeyHash)
    private val clientPkh = PubKeyHash(clientPubKeyHash)
    private val clientTxOutRef = TxOutRef(TxId(Builtins.blake2b_256(ByteString.fromString("client tx"))), 0)
    private val validatorUplc = CosmexContract.mkCosmexProgram(exchangeParams)
    private val txbuilder = TxBuilder(exchangeParams, testEnvironmentWithoutEvaluator)

    private val clientAddress = Address(
      cardanoInfo.network,
      Credential.KeyHash(AddrKeyHash.fromByteString(clientPubKeyHash))
    )

    val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))

    val initialUtxos = Map(
      TransactionInput(genesisHash, 0) ->
          TransactionOutput(
            address = clientAddress,
            value = Value.ada(1000)
          )
    )

    private def provider(): Provider = {
        LedgerProvider(
          initialUtxos = initialUtxos,
          context = Context.testMainnet(slot = 1000),
          validators =
              LedgerProvider.defaultValidators - MissingKeyHashesValidator - ProtocolParamsViewHashesMatchValidator - MissingRequiredDatumsValidator,
          mutators = LedgerProvider.defaultMutators - PlutusScriptsTransactionMutator
        )
    }

    test("open channel") {
        val provider = this.provider()
        val depositUtxo = provider
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(500))
            )
            .toOption
            .get

        val openChannelTx = txbuilder.openChannel(
          clientInput = TransactionUnspentOutput(depositUtxo),
          clientPubKey = clientPubKey,
          depositAmount = Value.ada(500L),
          validityStartSlot = 1000,
          validityEndSlot = 2000
        )

        val value = provider.submit(openChannelTx)
        pprint.pprintln(value)
        assert(value.isRight)

//        val lockUtxo = provider
//            .findUtxo(
//              address = scriptAddress,
//              transactionId = Some(openChannelTx.id),
//              datum = Some(DatumOption.Inline(datum)),
//              minAmount = Some(Coin(lockAmount))
//            )
//            .toOption
//            .get
//        assert(lockUtxo._2.value.coin == Coin(lockAmount))
//
//        val revealTx = revealHtlc(provider, lockUtxo, validPreimage, receiverPkh, beforeTimeout)
//        provider.setSlot(env.slotConfig.timeToSlot(beforeTimeout.toLong))
//        assert(provider.submit(revealTx).isRight)
    }
}

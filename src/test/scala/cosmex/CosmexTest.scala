package cosmex

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.builtin.{platform, Builtins, ByteString}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.cardano.txbuilder.Environment
import scalus.ledger.api.v1.PubKeyHash
import scalus.ledger.api.v3.{TxId, TxOutRef}
import scalus.prelude.{AssocMap, Option as ScalusOption}
import scalus.testing.kit.MockLedgerApi

class CosmexTest extends AnyFunSuite with ScalaCheckPropertyChecks with cosmex.ArbitraryInstances {

    val cardanoInfo: CardanoInfo = CardanoInfo.mainnet
    val testProtocolParams: ProtocolParams = cardanoInfo.protocolParams
    val testEnv: Environment = cardanoInfo
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
    private val txbuilder = Transactions(exchangeParams, testEnv)

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

    private def newEmulator(): MockLedgerApi = {
        MockLedgerApi(
          initialUtxos = initialUtxos,
          context = Context.testMainnet(slot = 1000),
          validators =
              MockLedgerApi.defaultValidators - MissingKeyHashesValidator - ProtocolParamsViewHashesMatchValidator - MissingRequiredDatumsValidator,
          mutators = MockLedgerApi.defaultMutators - PlutusScriptsTransactionMutator
        )
    }

    // Helper: Create a snapshot and sign it with client key
    private def mkClientSignedSnapshot(
        clientAccount: Account,
        clientTxOutRef: TxOutRef,
        snapshot: Snapshot
    ): SignedSnapshot = {
        val signedInfo = (clientTxOutRef, snapshot)
        import scalus.builtin.Data.toData
        val msg = Builtins.serialiseData(signedInfo.toData)

        // Sign with client private key (first 32 bytes only - Ed25519 private key)
        val clientPrivKeyBytes = clientAccount.privateKeyBytes()
        val clientPrivKey = ByteString.fromArray(clientPrivKeyBytes.take(32))
        val clientSignature = platform.signEd25519(clientPrivKey, msg)

        SignedSnapshot(
          signedSnapshot = snapshot,
          snapshotClientSignature = clientSignature,
          snapshotExchangeSignature = ByteString.empty // Exchange hasn't signed yet
        )
    }

    // Helper: Create initial snapshot v0 with deposit
    private def mkInitialSnapshot(depositAmount: Value): Snapshot = {
        import scalus.ledger.api.v3.Value as V3Value
        val tradingState = TradingState(
          tsClientBalance = LedgerToPlutusTranslation.getValue(depositAmount),
          tsExchangeBalance = V3Value.zero,
          tsOrders = AssocMap.empty
        )
        Snapshot(
          snapshotTradingState = tradingState,
          snapshotPendingTx = ScalusOption.None,
          snapshotVersion = 0
        )
    }

    test("open channel with TxBuilder") {
        val provider = this.newEmulator()
        val depositUtxo = provider
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(500))
            )
            .toOption
            .get

        val openChannelTx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = Value.ada(500L)
        )

        val value = provider.submit(openChannelTx)
        pprint.pprintln(openChannelTx)
        pprint.pprintln(value)
        assert(value.isRight)
    }

    test("Server: open channel end-to-end") {
        val provider = this.newEmulator()
        val exchangePrivKey = ByteString.fromArray(exchangeAccount.privateKeyBytes().take(32))
        val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

        // 1. Client builds open channel transaction
        val depositUtxo = provider
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(500))
            )
            .toOption
            .get

        val depositAmount = Value.ada(500L)
        val openChannelTx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = depositAmount
        )

        // 2. Client creates and signs initial snapshot v0
        // Extract the actual deposit amount from the transaction output to the script
        val actualDepositAmount = openChannelTx.body.value.outputs.view
            .map(_.value)
            .find(_.address == server.CosmexScriptAddress)
            .get
            .value
        val firstInput = openChannelTx.body.value.inputs.toSeq.head
        val clientTxOutRef = TxOutRef(TxId(firstInput.transactionId), firstInput.index)
        val initialSnapshot = mkInitialSnapshot(actualDepositAmount)
        val clientSignedSnapshot =
            mkClientSignedSnapshot(clientAccount, clientTxOutRef, initialSnapshot)

        // 3. Server validates and signs the snapshot
        val validationResult =
            server.validateOpenChannelRequest(openChannelTx, clientSignedSnapshot)
        assert(validationResult.isRight, s"Validation failed: $validationResult")

        // 4. Extract signed snapshot from server
        val bothSignedSnapshot = server.signSnapshot(clientTxOutRef, clientSignedSnapshot)

        // Verify exchange signature is not empty
        assert(
          bothSignedSnapshot.snapshotExchangeSignature.bytes.nonEmpty,
          "Exchange signature should not be empty"
        )

        // Verify snapshot version is 0
        assert(
          bothSignedSnapshot.signedSnapshot.snapshotVersion == 0,
          "Initial snapshot version should be 0"
        )
    }

    test("Server: reject invalid snapshot version") {
        val provider = this.newEmulator()
        val exchangePrivKey = ByteString.fromArray(exchangeAccount.privateKeyBytes().take(32))
        val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

        val depositUtxo = provider
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(500))
            )
            .toOption
            .get

        val depositAmount = Value.ada(500L)
        val openChannelTx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = depositAmount
        )

        // Create snapshot with wrong version (1 instead of 0)
        val firstInput = openChannelTx.body.value.inputs.toSeq.head
        val clientTxOutRef = TxOutRef(TxId(firstInput.transactionId), firstInput.index)
        val wrongSnapshot = mkInitialSnapshot(depositAmount).copy(snapshotVersion = 1)
        val clientSignedSnapshot =
            mkClientSignedSnapshot(clientAccount, clientTxOutRef, wrongSnapshot)

        // Should fail validation
        val validationResult =
            server.validateOpenChannelRequest(openChannelTx, clientSignedSnapshot)
        assert(validationResult.isLeft, "Should reject wrong snapshot version")
        assert(validationResult.left.getOrElse("").contains("Invalid snapshot version"))
    }

    test("Server: reject snapshot with non-zero exchange balance") {
        val provider = this.newEmulator()
        val exchangePrivKey = ByteString.fromArray(exchangeAccount.privateKeyBytes().take(32))
        val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

        val depositUtxo = provider
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(500))
            )
            .toOption
            .get

        val depositAmount = Value.ada(500L)
        val openChannelTx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = depositAmount
        )

        // Extract the actual deposit amount from the transaction
        val actualDepositAmount = openChannelTx.body.value.outputs.view
            .map(_.value)
            .find(_.address == server.CosmexScriptAddress)
            .get
            .value

        // Create snapshot with non-zero exchange balance (should fail validation)
        // Use actualDepositAmount for client balance so it passes that check,
        // but set exchange balance to non-zero (which should fail)
        val firstInput = openChannelTx.body.value.inputs.toSeq.head
        val clientTxOutRef = TxOutRef(TxId(firstInput.transactionId), firstInput.index)
        val badTradingState = TradingState(
          tsClientBalance = LedgerToPlutusTranslation.getValue(actualDepositAmount),
          tsExchangeBalance =
              LedgerToPlutusTranslation.getValue(Value.ada(100L)), // Should be zero!
          tsOrders = AssocMap.empty
        )
        val badSnapshot = Snapshot(badTradingState, ScalusOption.None, 0)
        val clientSignedSnapshot =
            mkClientSignedSnapshot(clientAccount, clientTxOutRef, badSnapshot)

        // Should fail validation
        val validationResult =
            server.validateOpenChannelRequest(openChannelTx, clientSignedSnapshot)
        assert(validationResult.isLeft, "Should reject non-zero exchange balance")
        assert(validationResult.left.getOrElse("").contains("Exchange balance must be zero"))
    }

    test("Server: reject snapshot with wrong balance") {
        val provider = this.newEmulator()
        val exchangePrivKey = ByteString.fromArray(exchangeAccount.privateKeyBytes().take(32))
        val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

        val depositUtxo = provider
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(500))
            )
            .toOption
            .get

        val depositAmount = Value.ada(500L)
        val openChannelTx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = depositAmount
        )

        // Create snapshot with wrong balance
        val firstInput = openChannelTx.body.value.inputs.toSeq.head
        val clientTxOutRef = TxOutRef(TxId(firstInput.transactionId), firstInput.index)
        val badSnapshot = mkInitialSnapshot(Value.ada(400L)) // Wrong amount!
        val clientSignedSnapshot =
            mkClientSignedSnapshot(clientAccount, clientTxOutRef, badSnapshot)

        // Should fail validation
        val validationResult =
            server.validateOpenChannelRequest(openChannelTx, clientSignedSnapshot)
        assert(validationResult.isLeft, "Should reject wrong balance")
        assert(validationResult.left.getOrElse("").contains("doesn't match deposit"))
    }
}

package cosmex

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.{Network, Networks}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.builtin.ByteString.hex
import scalus.builtin.{platform, Builtins, ByteString}
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.cardano.txbuilder.Environment
import scalus.ledger.api.v1.{PolicyId, PubKeyHash, TokenName}
import scalus.ledger.api.v3.{TxId, TxOutRef}
import scalus.prelude.{AssocMap, Option as ScalusOption}
import scalus.testing.kit.MockLedgerApi

class CosmexTest extends AnyFunSuite with ScalaCheckPropertyChecks with cosmex.ArbitraryInstances {
    // Environment
    private val cardanoInfo: CardanoInfo = CardanoInfo.mainnet
    private val network: Network = Networks.preview()
    private val testProtocolParams: ProtocolParams = cardanoInfo.protocolParams
    private val testEnv: Environment = cardanoInfo
    // Accounts and keys
    private val exchangeAccount = new Account(network, 1)
    private val exchangePrivKey = ByteString.fromArray(exchangeAccount.privateKeyBytes().take(32))
    private val exchangePubKey = ByteString.fromArray(exchangeAccount.publicKeyBytes())
    private val exchangePubKeyHash =
        ByteString.fromArray(exchangeAccount.hdKeyPair().getPublicKey.getKeyHash)
    private val exchangeParams = ExchangeParams(
      exchangePkh = PubKeyHash(exchangePubKeyHash),
      contestationPeriodInMilliseconds = 5000,
      exchangePubKey = exchangePubKey
    )
    private val clientAccount = new Account(network, 2)
    private val clientPubKey = ByteString.fromArray(clientAccount.publicKeyBytes())
    private val clientPubKeyHash =
        ByteString.fromArray(clientAccount.hdKeyPair().getPublicKey.getKeyHash)

    // Bob's account for testing multi-client scenarios
    private val bobAccount = new Account(network, 3)
    private val bobPubKey = ByteString.fromArray(bobAccount.publicKeyBytes())
    private val bobPubKeyHash =
        ByteString.fromArray(bobAccount.hdKeyPair().getPublicKey.getKeyHash)

    private val txbuilder = Transactions(exchangeParams, testEnv)

    private val clientAddress = Address(
      cardanoInfo.network,
      Credential.KeyHash(AddrKeyHash.fromByteString(clientPubKeyHash))
    )

    private val bobAddress = Address(
      cardanoInfo.network,
      Credential.KeyHash(AddrKeyHash.fromByteString(bobPubKeyHash))
    )

    private val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))
    // Define USDM token for initial UTxOs
    // ScriptHash is Hash[Blake2b_224, HashPurpose.ScriptHash]
    private val usdmPolicyId: ScriptHash =
        Hash.scriptHash(hex"c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad")
    private val usdmAssetName = AssetName(ByteString.fromString("USDM"))

    val initialUtxos = Map(
      TransactionInput(genesisHash, 0) ->
          TransactionOutput(
            address = clientAddress,
            value = Value.ada(1000)
          ),
      TransactionInput(genesisHash, 1) ->
          TransactionOutput(
            address = bobAddress,
            value = Value.ada(1000) + Value.asset(usdmPolicyId, usdmAssetName, 500_000_000)
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

    // Define asset classes for testing
    // PolicyId and TokenName are type aliases to ByteString
    private val ADA: AssetClass = (ByteString.empty, ByteString.empty)
    private val USDM: AssetClass = (usdmPolicyId, usdmAssetName.bytes)

    // Helper: Create a buy order (positive amount)
    private def mkBuyOrder(pair: Pair, amount: BigInt, price: BigInt): LimitOrder = {
        LimitOrder(
          orderPair = pair,
          orderAmount = amount, // Positive for BUY
          orderPrice = price
        )
    }

    // Helper: Create a sell order (negative amount)
    private def mkSellOrder(pair: Pair, amount: BigInt, price: BigInt): LimitOrder = {
        LimitOrder(
          orderPair = pair,
          orderAmount = -amount, // Negative for SELL
          orderPrice = price
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

    test("Alice and Bob trade ADA/USDM with order matching") {
        val provider = this.newEmulator()

        val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

        // 1. Alice opens channel with 900 ADA + 0 USDM (leave room for fees)
        val aliceDepositUtxo = provider
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(900))
            )
            .toOption
            .get

        val aliceDepositAmount = Value.ada(900L)
        val aliceOpenChannelTx = txbuilder.openChannel(
          clientInput = aliceDepositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = aliceDepositAmount
        )

        val aliceActualDeposit = aliceOpenChannelTx.body.value.outputs.view
            .map(_.value)
            .find(_.address == server.CosmexScriptAddress)
            .get
            .value
        val aliceClientTxOutRef = TxOutRef(TxId(aliceOpenChannelTx.id), 0)
        val aliceInitialSnapshot = mkInitialSnapshot(aliceActualDeposit)
        val aliceClientSignedSnapshot =
            mkClientSignedSnapshot(clientAccount, aliceClientTxOutRef, aliceInitialSnapshot)

        // Server validates and signs Alice's channel
        val aliceValidation =
            server.validateOpenChannelRequest(aliceOpenChannelTx, aliceClientSignedSnapshot)
        assert(aliceValidation.isRight, s"Alice channel validation failed: $aliceValidation")

        // Store Alice's client state
        val aliceClientId = ClientId(TransactionInput(aliceOpenChannelTx.id, 0))
        val aliceClientState = ClientState(
          latestSnapshot = server.signSnapshot(aliceClientTxOutRef, aliceClientSignedSnapshot),
          channelRef = TransactionInput(aliceOpenChannelTx.id, 0),
          lockedValue = aliceActualDeposit,
          status = ChannelStatus.Open
        )
        server.clients.put(aliceClientId, aliceClientState)

        import upickle.default.write
        println(write(aliceClientState))

        // 2. Bob opens channel with 50 ADA + 500 USDM (we'll simulate USDM in the balance)
        val bobDepositUtxo = provider
            .findUtxo(
              address = bobAddress,
              minAmount = Some(Coin.ada(50))
            )
            .toOption
            .get

        val bobDepositAmount =
            Value.ada(50L) + Value.asset(usdmPolicyId, usdmAssetName, 500_000_000)

        val bobOpenChannelTx = txbuilder.openChannel(
          clientInput = bobDepositUtxo,
          clientPubKey = bobPubKey,
          depositAmount = bobDepositAmount
        )
        val bobChannelTxOut = bobOpenChannelTx.body.value.outputs.view.zipWithIndex
            .find(_._1.value.address == server.CosmexScriptAddress)
            .get

        val bobActualDeposit = bobChannelTxOut._1.value.value
        val bobClientTxOutRef = TxOutRef(TxId(bobOpenChannelTx.id), bobChannelTxOut._2)

        // Bob's initial snapshot with actual deposit (50 ADA + 500 USDM)
        val bobInitialSnapshot = mkInitialSnapshot(bobActualDeposit)
        val bobClientSignedSnapshot =
            mkClientSignedSnapshot(bobAccount, bobClientTxOutRef, bobInitialSnapshot)

        // Store Bob's client state (skip validation for simplicity)
        val bobClientId = ClientId(TransactionInput(bobOpenChannelTx.id, bobChannelTxOut._2))

        val bobClientState = ClientState(
          latestSnapshot = server.signSnapshot(bobClientTxOutRef, bobClientSignedSnapshot),
          channelRef = TransactionInput(bobOpenChannelTx.id, 0),
          lockedValue = bobActualDeposit,
          status = ChannelStatus.Open
        )
        server.clients.put(bobClientId, bobClientState)

        // 3. Alice submits SELL order: 100 ADA @ 0.50 USDM/ADA
        // Price in smallest units: 0.50 USDM/ADA = 50_000_000 (assuming 100M scale)
        val aliceSellOrder = mkSellOrder(
          pair = (ADA, USDM),
          amount = 100_000_000, // 100 ADA
          price = 500_000 // 0.50 USDM/ADA
        )

        val aliceOrderResult = server.handleCreateOrder(aliceClientId, aliceSellOrder)
        assert(aliceOrderResult.isRight, s"Alice order creation failed: $aliceOrderResult")

        val (aliceSnapshot1, aliceTrades) = aliceOrderResult.toOption.get
        assert(aliceTrades.isEmpty, "Alice's order should not match anything (no trades)")
        assert(aliceSnapshot1.signedSnapshot.snapshotVersion == 1, "Alice snapshot should be v1")

        // Verify Alice's order is in the order book
        assert(server.orderBook.sellOrders.nonEmpty, "Order book should have Alice's sell order")

        // 4. Bob submits BUY order: 70 ADA @ 0.55 USDM/ADA
        val bobBuyOrder = mkBuyOrder(
          pair = (ADA, USDM),
          amount = 70_000_000, // 70 ADA
          price = 550_000 // 0.55 USDM/ADA
        )

        val bobOrderResult = server.handleCreateOrder(bobClientId, bobBuyOrder)
        assert(bobOrderResult.isRight, s"Bob order creation failed: $bobOrderResult")

        val (bobSnapshot1, bobTrades) = bobOrderResult.toOption.get

        // 5. Verify trade execution: 70 ADA @ 0.50 (Alice's ask price)
        assert(bobTrades.nonEmpty, "Bob's order should match Alice's order")
        assert(bobTrades.length == 1, "Should have exactly one trade")

        val trade = bobTrades.head
        assert(
          trade.tradeAmount == 70_000_000,
          s"Trade amount should be 70 ADA, got ${trade.tradeAmount}"
        )
        assert(
          trade.tradePrice == 500_000,
          s"Trade price should be 0.50, got ${trade.tradePrice}"
        )

        // 6. Verify Bob's balance after trade: 120 ADA + 465 USDM
        val bobFinalBalance = bobSnapshot1.signedSnapshot.snapshotTradingState.tsClientBalance
        val expectedBobADA = 50_000_000 + 70_000_000 // 50 + 70 = 120 ADA
        val expectedBobUSDM =
            500_000_000 - (70_000_000 * 500_000 / 1_000_000) // 500 - 35 = 465 USDM

        println(s"Bob's final balance: $bobFinalBalance")
        println(s"Expected Bob ADA: $expectedBobADA")
        println(s"Expected Bob USDM: $expectedBobUSDM")

        // 7. Verify order book: Alice's order should be partially filled (30 ADA remaining)
        val remainingOrders = OrderBook.getAllOrders(server.orderBook)
        println(s"Remaining orders in book: $remainingOrders")

        assert(remainingOrders.nonEmpty, "Order book should have Alice's remaining order")
        val (_, remainingOrder) = remainingOrders.head
        assert(
          remainingOrder.orderAmount == -30_000_000,
          s"Alice's remaining order should be 30 ADA, got ${remainingOrder.orderAmount}"
        )
    }
}

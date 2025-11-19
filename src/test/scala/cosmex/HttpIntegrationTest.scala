package cosmex

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.{Network, Networks}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach
import scalus.builtin.{platform, Builtins, ByteString}
import scalus.builtin.ByteString.hex
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.cardano.txbuilder.Environment
import scalus.ledger.api.v1.{PolicyId, PubKeyHash, TokenName}
import scalus.ledger.api.v3.{TxId, TxOutRef}
import scalus.prelude.{AssocMap, Option as ScalusOption}
import scalus.testing.kit.MockLedgerApi

import scala.concurrent.duration.*

class HttpIntegrationTest extends AnyFunSuite with BeforeAndAfterEach {

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

  // Alice's account
  private val aliceAccount = new Account(network, 2)
  private val alicePubKey = ByteString.fromArray(aliceAccount.publicKeyBytes())
  private val alicePubKeyHash =
    ByteString.fromArray(aliceAccount.hdKeyPair().getPublicKey.getKeyHash)
  private val aliceAddress = Address(
    cardanoInfo.network,
    Credential.KeyHash(AddrKeyHash.fromByteString(alicePubKeyHash))
  )

  // Bob's account
  private val bobAccount = new Account(network, 3)
  private val bobPubKey = ByteString.fromArray(bobAccount.publicKeyBytes())
  private val bobPubKeyHash =
    ByteString.fromArray(bobAccount.hdKeyPair().getPublicKey.getKeyHash)
  private val bobAddress = Address(
    cardanoInfo.network,
    Credential.KeyHash(AddrKeyHash.fromByteString(bobPubKeyHash))
  )

  private val txbuilder = Transactions(exchangeParams, testEnv)

  private val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))

  // Define USDM token
  private val usdmPolicyId: ScriptHash =
    Hash.scriptHash(hex"c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad")
  private val usdmAssetName = AssetName(ByteString.fromString("USDM"))

  val initialUtxos = Map(
    TransactionInput(genesisHash, 0) ->
      TransactionOutput(
        address = aliceAddress,
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

    val clientPrivKeyBytes = clientAccount.privateKeyBytes()
    val clientPrivKey = ByteString.fromArray(clientPrivKeyBytes.take(32))
    val clientSignature = platform.signEd25519(clientPrivKey, msg)

    SignedSnapshot(
      signedSnapshot = snapshot,
      snapshotClientSignature = clientSignature,
      snapshotExchangeSignature = ByteString.empty
    )
  }

  // Helper: Create initial snapshot v0 with deposit
  private def mkInitialSnapshot(depositAmount: Value): Snapshot = {
    import scalus.ledger.api.v3.Value as V3Value
    val tradingState = TradingState(
      tsClientBalance = scalus.cardano.ledger.LedgerToPlutusTranslation.getValue(depositAmount),
      tsExchangeBalance = V3Value.zero,
      tsOrders = AssocMap.empty
    )
    Snapshot(
      snapshotTradingState = tradingState,
      snapshotPendingTx = ScalusOption.None,
      snapshotVersion = 0
    )
  }

  // Define asset classes
  private val ADA: AssetClass = (ByteString.empty, ByteString.empty)
  private val USDM: AssetClass = (usdmPolicyId, usdmAssetName.bytes)

  // Helper: Create a buy order (positive amount)
  private def mkBuyOrder(pair: Pair, amount: BigInt, price: BigInt): LimitOrder = {
    LimitOrder(
      orderPair = pair,
      orderAmount = amount,
      orderPrice = price
    )
  }

  // Helper: Create a sell order (negative amount)
  private def mkSellOrder(pair: Pair, amount: BigInt, price: BigInt): LimitOrder = {
    LimitOrder(
      orderPair = pair,
      orderAmount = -amount,
      orderPrice = price
    )
  }

  private var httpServer: Option[HttpServer] = None

  override def beforeEach(): Unit = {
    httpServer.foreach(_.stop())
    httpServer = None
  }

  override def afterEach(): Unit = {
    httpServer.foreach(_.stop())
    httpServer = None
  }

  test("Alice and Bob trade ADA/USDM via HTTP") {
    val provider = newEmulator()
    val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

    // 1. Setup HTTP server
    val port = 18080
    val http = HttpServer(port, server)
    httpServer = Some(http)

    // 2. Alice opens channel with 900 ADA
    val aliceDepositUtxo = provider
      .findUtxo(
        address = aliceAddress,
        minAmount = Some(Coin.ada(900))
      )
      .toOption
      .get

    val aliceDepositAmount = Value.ada(900L)
    val aliceOpenChannelTx = txbuilder.openChannel(
      clientInput = aliceDepositUtxo,
      clientPubKey = alicePubKey,
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
      mkClientSignedSnapshot(aliceAccount, aliceClientTxOutRef, aliceInitialSnapshot)

    val aliceValidation =
      server.validateOpenChannelRequest(aliceOpenChannelTx, aliceClientSignedSnapshot)
    assert(aliceValidation.isRight, s"Alice channel validation failed: $aliceValidation")

    val aliceClientId = ClientId(TransactionInput(aliceOpenChannelTx.id, 0))
    val aliceClientState = ClientState(
      latestSnapshot = server.signSnapshot(aliceClientTxOutRef, aliceClientSignedSnapshot),
      channelRef = TransactionInput(aliceOpenChannelTx.id, 0),
      lockedValue = aliceActualDeposit,
      status = ChannelStatus.Open
    )
    server.clients.put(aliceClientId, aliceClientState)

    // 3. Bob opens channel with 50 ADA + 500 USDM
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
    val bobInitialSnapshot = mkInitialSnapshot(bobActualDeposit)
    val bobClientSignedSnapshot =
      mkClientSignedSnapshot(bobAccount, bobClientTxOutRef, bobInitialSnapshot)

    val bobClientId = ClientId(TransactionInput(bobOpenChannelTx.id, bobChannelTxOut._2))
    val bobClientState = ClientState(
      latestSnapshot = server.signSnapshot(bobClientTxOutRef, bobClientSignedSnapshot),
      channelRef = TransactionInput(bobOpenChannelTx.id, 0),
      lockedValue = bobActualDeposit,
      status = ChannelStatus.Open
    )
    server.clients.put(bobClientId, bobClientState)

    // 4. Register clients with HTTP server
    http.registerClient("alice", aliceClientId)
    http.registerClient("bob", bobClientId)

    // 5. Start HTTP server
    http.start()

    // Give server time to start
    Thread.sleep(2000)

    // 6. Create HTTP clients
    val aliceClient = HttpTestClient("alice", "localhost", port)
    val bobClient = HttpTestClient("bob", "localhost", port)

    try {
      // 7. Alice submits SELL order: 100 ADA @ 0.50 USDM/ADA
      val aliceSellOrder = mkSellOrder(
        pair = (ADA, USDM),
        amount = 100_000_000, // 100 ADA
        price = 500_000 // 0.50 USDM/ADA
      )

      val aliceOrderResponse = aliceClient.createOrder(aliceSellOrder)
      assert(aliceOrderResponse.isRight, s"Alice order creation failed: $aliceOrderResponse")

      aliceOrderResponse.toOption.get match {
        case ClientResponse.OrderCreated(orderId) =>
          println(s"Alice's order created with ID: $orderId")
        case ClientResponse.Error(msg) =>
          fail(s"Alice order creation failed: $msg")
        case other =>
          fail(s"Unexpected response from Alice's order: $other")
      }

      // 8. Bob submits BUY order: 70 ADA @ 0.55 USDM/ADA (should match Alice's order)
      val bobBuyOrder = mkBuyOrder(
        pair = (ADA, USDM),
        amount = 70_000_000, // 70 ADA
        price = 550_000 // 0.55 USDM/ADA
      )

      val bobOrderResponse = bobClient.createOrder(bobBuyOrder)
      assert(bobOrderResponse.isRight, s"Bob order creation failed: $bobOrderResponse")

      bobOrderResponse.toOption.get match {
        case ClientResponse.OrderCreated(orderId) =>
          println(s"Bob's order created with ID: $orderId")
        case ClientResponse.Error(msg) =>
          fail(s"Bob order creation failed: $msg")
        case other =>
          fail(s"Unexpected response from Bob's order: $other")
      }

      // 9. Verify order book state: Alice's order should be partially filled (30 ADA remaining)
      val remainingOrders = OrderBook.getAllOrders(server.orderBook)
      println(s"Remaining orders in book: $remainingOrders")

      assert(remainingOrders.nonEmpty, "Order book should have Alice's remaining order")
      val (_, remainingOrder) = remainingOrders.head
      assert(
        remainingOrder.orderAmount == -30_000_000,
        s"Alice's remaining order should be 30 ADA, got ${remainingOrder.orderAmount}"
      )

      // 10. Verify Bob's snapshot shows the trade was executed
      val bobSnapshot = server.getLatestSnapshot(bobClientId)
      assert(bobSnapshot.isDefined, "Bob should have a snapshot")
      assert(
        bobSnapshot.get.signedSnapshot.snapshotVersion > 0,
        s"Bob's snapshot version should be > 0, got ${bobSnapshot.get.signedSnapshot.snapshotVersion}"
      )

      println("✓ HTTP integration test passed!")

    } finally {
      // Cleanup
      aliceClient.close()
      bobClient.close()
    }
  }
}

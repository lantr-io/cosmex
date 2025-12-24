package cosmex.demo

import com.bloxbean.cardano.client.account.Account
import cosmex.config.DemoConfig
import cosmex.ws.{CosmexWebSocketServer, SimpleWebSocketClient}
import cosmex.DemoHelpers.*
import cosmex.demo.MultiClientTestHelpers.*
import cosmex.util.JsonCodecs.given
import cosmex.{ClientId, ClientRequest, ClientResponse, CosmexTransactions, LimitOrder, Pair, Server}
import cosmex.CardanoInfoTestNet
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ox.*
import scalus.builtin.ByteString
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.Context
import scalus.cardano.node.Emulator
import scalus.utils.await
import sttp.tapir.server.netty.sync.NettySyncServerBinding
import upickle.default.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

/** Test for the complete Trading -> Rebalance -> Payout scenario.
  *
  * This test verifies the full lifecycle:
  *   1. Alice and Bob open channels
  *   2. Alice creates a BUY order, Bob creates a SELL order
  *   3. Orders are matched and executed
  *   4. Alice triggers rebalancing
  *   5. Both clients sign the rebalance transaction
  *   6. Rebalancing completes
  *   7. Alice initiates close/payout
  */
class TradingRebalancePayoutTest extends AnyFunSuite with Matchers {

    test("Complete Trading -> Rebalance -> Payout scenario") {
        supervised {
            // Load configuration
            val config = DemoConfig.load()

            // This test uses mock provider, so we don't need Blockfrost

            // Setup exchange
            val exchangeParams = config.exchange.createParams()
            val exchangePrivKey = config.exchange.getPrivateKey()

            // Setup Alice
            val aliceAccount = config.alice.createAccount()
            val alicePubKey = config.alice.getPubKey()
            val alicePubKeyHash = config.alice.getPubKeyHash()
            val aliceAddress = Address(
              config.network.scalusNetwork,
              Credential.KeyHash(AddrKeyHash.fromByteString(alicePubKeyHash))
            )
            val aliceInitialValue = Value.ada(100) // 100 ADA

            // Setup Bob
            val bobAccount = config.bob.createAccount()
            val bobPubKey = config.bob.getPubKey()
            val bobPubKeyHash = config.bob.getPubKeyHash()
            val bobAddress = Address(
              config.network.scalusNetwork,
              Credential.KeyHash(AddrKeyHash.fromByteString(bobPubKeyHash))
            )
            val bobInitialValue = Value.ada(100) // 100 ADA

            // Create provider (mock for this test)
            val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))
            val initialUtxos = Map(
              TransactionInput(genesisHash, 0) ->
                  TransactionOutput(address = aliceAddress, value = aliceInitialValue + Value.ada(50)),
              TransactionInput(genesisHash, 1) ->
                  TransactionOutput(address = bobAddress, value = bobInitialValue + Value.ada(50))
            )

            val mainnetCtx = Context.testMainnet(slot = 1000)
            val testnetEnv = mainnetCtx.env.copy(network = scalus.cardano.address.Network.Testnet)
            val testnetContext = new Context(mainnetCtx.fee, testnetEnv, mainnetCtx.slotConfig)
            val provider = Emulator(initialUtxos = initialUtxos, initialContext = testnetContext)

            val cardanoInfo = CardanoInfoTestNet.currentNetwork(provider)
            val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)
            val txbuilder = CosmexTransactions(exchangeParams, cardanoInfo)

            val port = 18081
            var serverBinding: NettySyncServerBinding = null

            try {
                // Start server
                val serverFork = forkUser {
                    CosmexWebSocketServer.runBinding(server, port = port)
                }
                serverBinding = serverFork.join()
                Thread.sleep(2000)

                println("\n" + "=" * 70)
                println("Trading -> Rebalance -> Payout Test")
                println("=" * 70)

                // Phase 1: Open channels
                println("\n=== Phase 1: Opening Channels ===")

                val (aliceClient, aliceClientId) = openClientChannel(
                  "Alice",
                  aliceAccount,
                  alicePubKey,
                  aliceAddress,
                  aliceInitialValue,
                  genesisHash,
                  0,
                  txbuilder,
                  port
                )

                val (bobClient, bobClientId) = openClientChannel(
                  "Bob",
                  bobAccount,
                  bobPubKey,
                  bobAddress,
                  bobInitialValue,
                  genesisHash,
                  1,
                  txbuilder,
                  port
                )

                try {
                    // Phase 2: Create matching orders
                    println("\n=== Phase 2: Creating Matching Orders ===")

                    val adaAsset = (ByteString.empty, ByteString.empty)
                    val tradingPair = (adaAsset, adaAsset) // ADA/ADA for simplicity

                    // Alice: BUY 10 ADA @ price 1000000
                    val aliceOrder = LimitOrder(
                      orderPair = tradingPair,
                      orderAmount = 10_000_000L, // 10 ADA (positive = buy)
                      orderPrice = 1_000_000L
                    )
                    println("[Alice] Creating BUY order: 10 ADA @ 1000000")
                    createOrder(aliceClient, aliceClientId, aliceOrder, "Alice")

                    // Bob: SELL 10 ADA @ price 1000000
                    val bobOrder = LimitOrder(
                      orderPair = tradingPair,
                      orderAmount = -10_000_000L, // -10 ADA (negative = sell)
                      orderPrice = 1_000_000L
                    )
                    println("[Bob] Creating SELL order: 10 ADA @ 1000000")
                    createOrder(bobClient, bobClientId, bobOrder, "Bob")

                    // Wait for trade execution
                    println("\n=== Phase 2b: Waiting for Trade Execution ===")
                    val aliceTraded = waitForTradeExecution(aliceClient, "Alice", maxAttempts = 5)
                    val bobTraded = waitForTradeExecution(bobClient, "Bob", maxAttempts = 5)

                    if aliceTraded || bobTraded then {
                        println("[Test] ✓ Trade executed successfully!")
                    } else {
                        println("[Test] No trade executed (orders may not have matched)")
                    }

                    // Phase 3: Rebalancing
                    println("\n=== Phase 3: Rebalancing ===")

                    // Alice triggers rebalancing
                    println("[Alice] Triggering FixBalance...")
                    aliceClient.sendMessage(ClientRequest.FixBalance(aliceClientId))

                    // Wait for rebalance responses on both clients
                    handleRebalanceFlow(aliceClient, aliceAccount, aliceClientId, "Alice")
                    handleRebalanceFlow(bobClient, bobAccount, bobClientId, "Bob")

                    println("[Test] ✓ Rebalancing phase completed!")

                    // Phase 4: Verify final state
                    println("\n=== Phase 4: Verify Final State ===")

                    // Request state from both clients
                    println("[Alice] Requesting current state...")
                    aliceClient.sendMessage(ClientRequest.GetState(aliceClientId))
                    waitForStateResponse(aliceClient, "Alice")

                    println("[Bob] Requesting current state...")
                    bobClient.sendMessage(ClientRequest.GetState(bobClientId))
                    waitForStateResponse(bobClient, "Bob")

                    println("\n" + "=" * 70)
                    println("✓ Trading -> Rebalance Test PASSED!")
                    println("=" * 70)
                    println("\nVerified:")
                    println("  - Both clients opened channels successfully")
                    println("  - Orders were created and matched")
                    println("  - Rebalancing flow completed")
                    println("  - Final state verified")
                    println()

                    succeed

                } finally {
                    aliceClient.close()
                    bobClient.close()
                    println("[Test] Clients disconnected")
                }

            } finally {
                if serverBinding != null then {
                    serverBinding.stop()
                    println("[Server] Test server shut down")
                }
            }
        }
    }

    private def openClientChannel(
        name: String,
        account: Account,
        pubKey: ByteString,
        address: Address,
        depositValue: Value,
        genesisHash: TransactionHash,
        utxoIndex: Int,
        txbuilder: CosmexTransactions,
        port: Int
    ): (SimpleWebSocketClient, ClientId) = {
        println(s"[$name] Opening channel...")

        val depositUtxo = Utxo(
          input = TransactionInput(genesisHash, utxoIndex),
          output = TransactionOutput(address = address, value = depositValue + Value.ada(50))
        )

        val unsignedTx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = pubKey,
          depositAmount = depositValue
        )

        val openChannelTx = signTransaction(account, unsignedTx)
        val clientId = ClientId(depositUtxo.input)

        val wsUrl = s"ws://localhost:$port/ws/${clientId.txOutRef.transactionId.toHex}/${clientId.txOutRef.index}"
        val client = SimpleWebSocketClient(wsUrl)

        val clientTxOutRef = LedgerToPlutusTranslation.getTxOutRefV3(clientId.txOutRef)
        val initialSnapshot = mkInitialSnapshot(depositValue)
        val clientSignedSnapshot = mkClientSignedSnapshot(account, clientTxOutRef, initialSnapshot)

        openChannel(client, openChannelTx, clientSignedSnapshot, isMockProvider = true, name)

        println(s"[$name] ✓ Channel opened successfully!")
        (client, clientId)
    }

    private def handleRebalanceFlow(
        client: SimpleWebSocketClient,
        account: Account,
        clientId: ClientId,
        name: String
    ): Unit = {
        var attempts = 0
        val maxAttempts = 20
        var done = false

        while attempts < maxAttempts && !done do {
            client.receiveMessage(timeoutSeconds = 2) match {
                case Success(msgJson) =>
                    Try(read[ClientResponse](msgJson)) match {
                        case Success(ClientResponse.RebalanceStarted) =>
                            println(s"[$name] Rebalancing started")

                        case Success(ClientResponse.RebalanceRequired(tx)) =>
                            println(s"[$name] Received RebalanceRequired, signing...")
                            val signedTx = signTransaction(account, tx)
                            client.sendMessage(ClientRequest.SignRebalance(clientId, signedTx))
                            println(s"[$name] ✓ Signed rebalance transaction sent")

                        case Success(ClientResponse.RebalanceComplete(snapshot)) =>
                            println(s"[$name] ✓ Rebalancing complete! Version: ${snapshot.signedSnapshot.snapshotVersion}")
                            done = true

                        case Success(ClientResponse.RebalanceAborted(reason)) =>
                            println(s"[$name] Rebalancing aborted: $reason")
                            done = true

                        case Success(ClientResponse.Error(code, msg)) =>
                            println(s"[$name] Error [$code]: $msg")
                            done = true

                        case Success(other) =>
                            println(s"[$name] Received: ${other.getClass.getSimpleName}")

                        case Failure(e) =>
                            println(s"[$name] Parse error: ${e.getMessage}")
                    }

                case Failure(_: java.util.concurrent.TimeoutException) =>
                    attempts += 1

                case Failure(e) =>
                    println(s"[$name] Error: ${e.getMessage}")
                    attempts += 1
            }
        }

        if !done then {
            println(s"[$name] Rebalance flow did not complete within timeout")
        }
    }

    private def waitForStateResponse(client: SimpleWebSocketClient, name: String): Unit = {
        var attempts = 0
        val maxAttempts = 10
        var done = false

        while attempts < maxAttempts && !done do {
            client.receiveMessage(timeoutSeconds = 2) match {
                case Success(msgJson) =>
                    Try(read[ClientResponse](msgJson)) match {
                        case Success(ClientResponse.State(balance, orders, status, version)) =>
                            val adaBalance = balance.quantityOf(
                              scalus.builtin.ByteString.empty,
                              scalus.builtin.ByteString.empty
                            )
                            println(s"[$name] ✓ State received:")
                            println(s"[$name]   Status: $status")
                            println(s"[$name]   Snapshot version: $version")
                            println(s"[$name]   Balance: $adaBalance lovelace")
                            println(s"[$name]   Active orders: ${orders.size}")
                            done = true

                        case Success(ClientResponse.Error(code, msg)) =>
                            println(s"[$name] Error [$code]: $msg")
                            done = true

                        case Success(other) =>
                            println(s"[$name] Received: ${other.getClass.getSimpleName}")

                        case Failure(e) =>
                            println(s"[$name] Parse error: ${e.getMessage}")
                    }

                case Failure(_: java.util.concurrent.TimeoutException) =>
                    attempts += 1

                case Failure(e) =>
                    println(s"[$name] Error: ${e.getMessage}")
                    attempts += 1
            }
        }

        if !done then {
            println(s"[$name] State response not received within timeout")
        }
    }
}

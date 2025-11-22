package cosmex.demo

import com.bloxbean.cardano.client.account.Account
import cosmex.*
import cosmex.DemoHelpers.*
import cosmex.config.DemoConfig
import cosmex.ws.SimpleWebSocketClient
import scalus.builtin.ByteString
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.ledger.api.v1.PubKeyHash
import scalus.ledger.api.v3.{TxId, TxOutRef}
import scalus.prelude.{AssocMap, Option as ScalusOption}
import scalus.testing.kit.MockLedgerApi

import scala.util.{Failure, Success}

/** Demonstration of Bob opening a channel and creating a BUY order
  *
  * Bob's scenario:
  *   - Opens channel with 500 ADA + 1000 USDM
  *   - Creates BUY order: 100 ADA @ 0.55 USDM/ADA
  *   - Waits for matching with Alice's SELL order
  *   - Displays trade execution and final balances
  */
object BobDemo {

    def main(args: Array[String]): Unit = {
        // Load configuration
        val config = DemoConfig.load()

        println("=" * 60)
        println("COSMEX Demo: Bob Opens Channel & Creates BUY Order")
        println("=" * 60)

        // Setup
        val cardanoInfo = CardanoInfo.mainnet

        // Create Bob's account from config
        println("\n[1] Creating Bob's account from config...")
        val bobAccount = config.bob.createAccount()
        val bobPubKey = config.bob.getPubKey()
        val bobPubKeyHash = config.bob.getPubKeyHash()
        println(s"    Bob Seed: ${config.bob.seed}")
        println(s"    Bob PubKeyHash: ${bobPubKeyHash.toHex}")

        // Create exchange parameters from config
        val exchangeParams = config.exchange.createParams()
        val exchangeAccount = config.exchange.createAccount()
        val exchangePubKeyHash = ByteString.fromArray(
          exchangeAccount.hdKeyPair().getPublicKey.getKeyHash
        )
        println(s"    Exchange PubKeyHash: ${exchangePubKeyHash.toHex}")

        // Create Bob's address
        val bobAddress = Address(
          config.network.scalusNetwork,
          Credential.KeyHash(AddrKeyHash.fromByteString(bobPubKeyHash))
        )

        // Setup mock ledger with Bob's initial UTxO
        val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))

        // Get Bob's initial balance from config
        val bobInitialValue = config.bob.getInitialValue()
        println(s"\n    Bob's Initial Balance:")
        config.bob.initialBalance.foreach { case (asset, amount) =>
            val assetInfo = config.assets.getAsset(asset)
            val displayAmount = amount.toDouble / math.pow(10, assetInfo.decimals)
            println(s"      - $displayAmount ${assetInfo.symbol}")
        }

        val initialUtxos = Map(
          TransactionInput(genesisHash, 0) ->
              TransactionOutput(
                address = bobAddress,
                value = bobInitialValue
              )
        )

        val provider = MockLedgerApi(
          initialUtxos = initialUtxos,
          context = Context.testMainnet(slot = 1000),
          validators = MockLedgerApi.defaultValidators -
              MissingKeyHashesValidator -
              ProtocolParamsViewHashesMatchValidator -
              MissingRequiredDatumsValidator,
          mutators = MockLedgerApi.defaultMutators -
              PlutusScriptsTransactionMutator
        )

        // Create transaction builder
        val txbuilder = CosmexTransactions(exchangeParams, cardanoInfo)

        // Connect to WebSocket server
        println("\n[2] Connecting to WebSocket server...")
        val serverUrl = config.server.websocketUrl
        println(s"    Server URL: $serverUrl")

        val client =
            try {
                SimpleWebSocketClient(serverUrl)
            } catch {
                case e: Exception =>
                    println(s"    ERROR: Could not connect to server: ${e.getMessage}")
                    println("    Make sure the server is running:")
                    println("    sbtn \"Test/runMain cosmex.ws.CosmexWebSocketServerDemo\"")
                    sys.exit(1)
            }

        try {
            // Step 1: Open channel with initial deposit
            println("\n[3] Opening channel with deposit...")

            // Find Bob's UTxO
            val depositUtxo = provider
                .findUtxo(
                  address = bobAddress,
                  minAmount = Some(Coin(100_000L)) // Minimum for tx
                )
                .toOption
                .get

            // Build opening transaction with full balance
            val depositAmount = bobInitialValue
            val openChannelTx = txbuilder.openChannel(
              clientInput = depositUtxo,
              clientPubKey = bobPubKey,
              depositAmount = depositAmount
            )

            println(s"    Transaction built: ${openChannelTx.id.toHex.take(16)}...")

            // Extract actual deposit amount from transaction output
            val actualDepositAmount = openChannelTx.body.value.outputs.view
                .map(_.value)
                .headOption
                .getOrElse(openChannelTx.body.value.outputs.toSeq.head.value)

            // Create client TxOutRef from first input
            val firstInput = openChannelTx.body.value.inputs.toSeq.head
            val clientTxOutRef = TxOutRef(TxId(firstInput.transactionId), firstInput.index)

            // Create and sign initial snapshot (version 0)
            val initialSnapshot = mkInitialSnapshot(actualDepositAmount.value)
            val clientSignedSnapshot =
                mkClientSignedSnapshot(bobAccount, clientTxOutRef, initialSnapshot)

            println(s"    Snapshot v${initialSnapshot.snapshotVersion} created and signed")

            // Send OpenChannel request
            val openRequest = ClientRequest.OpenChannel(openChannelTx, clientSignedSnapshot)
            client.sendRequest(openRequest, timeoutSeconds = 10) match {
                case Success(ClientResponse.ChannelOpened(signedSnapshot)) =>
                    println(s"    ✓ Channel opened successfully!")
                    println(
                      s"    Snapshot version: ${signedSnapshot.signedSnapshot.snapshotVersion}"
                    )
                    println(
                      s"    Exchange signature: ${signedSnapshot.snapshotExchangeSignature.toHex.take(32)}..."
                    )

                    // Step 2: Create a BUY order from config
                    println("\n[4] Creating BUY order from config...")

                    val orderConfig = config.bob.defaultOrder.getOrElse {
                        throw new IllegalStateException("No default order configured for Bob")
                    }

                    println(
                      s"    Order: ${orderConfig.side} ${orderConfig.amount} ${orderConfig.baseAsset} @ ${orderConfig.price}"
                    )

                    // Create the BUY order
                    val buyOrder = LimitOrder(
                      orderPair = orderConfig.getPair(),
                      orderAmount = orderConfig.getSignedAmount(),
                      orderPrice = orderConfig.price
                    )

                    // Derive clientId from the transaction
                    val clientId = ClientId(TransactionInput(openChannelTx.id, 0))

                    // Send CreateOrder request
                    val orderRequest = ClientRequest.CreateOrder(clientId, buyOrder)
                    client.sendRequest(orderRequest, timeoutSeconds = 10) match {
                        case Success(ClientResponse.OrderCreated(orderId)) =>
                            println(s"    ✓ Order created successfully!")
                            println(s"    Order ID: $orderId")

                            val baseAsset = config.assets.getAsset(orderConfig.baseAsset)
                            val quoteAsset = config.assets.getAsset(orderConfig.quoteAsset)
                            val displayAmount =
                                orderConfig.amount.toDouble / math.pow(10, baseAsset.decimals)
                            val displayPrice =
                                orderConfig.price.toDouble / math.pow(10, quoteAsset.decimals)

                            println(f"    Amount: $displayAmount%.2f ${baseAsset.symbol}")
                            println(
                              f"    Price: $displayPrice%.2f ${quoteAsset.symbol}/${baseAsset.symbol}"
                            )

                            // Wait for potential trade execution
                            println("\n[5] Waiting for order matching...")
                            println(
                              "    (If Alice creates a matching SELL order, trade will execute automatically)"
                            )

                            // Listen for trade notifications
                            Thread.sleep(5000) // Wait 5 seconds for potential matches

                            // Query final balance
                            println("\n[6] Querying final balance...")
                            val balanceRequest = ClientRequest.GetBalance(clientId)
                            client.sendRequest(balanceRequest, timeoutSeconds = 10) match {
                                case Success(ClientResponse.Balance(balance)) =>
                                    println(s"    Current balance:")
                                    // TODO: Parse and display balance properly
                                    println(s"    $balance")

                                case Success(other) =>
                                    println(s"    Unexpected response: $other")

                                case Failure(e) =>
                                    println(s"    Error: ${e.getMessage}")
                            }

                            println("\n" + "=" * 60)
                            println("Demo completed successfully!")
                            println("=" * 60)

                        case Success(ClientResponse.Error(msg)) =>
                            println(s"    ✗ Order creation failed: $msg")

                        case Success(other) =>
                            println(s"    ✗ Unexpected response: $other")

                        case Failure(e) =>
                            println(s"    ✗ Error: ${e.getMessage}")
                    }

                case Success(ClientResponse.Error(msg)) =>
                    println(s"    ✗ Channel opening failed: $msg")

                case Success(other) =>
                    println(s"    ✗ Unexpected response: $other")

                case Failure(e) =>
                    println(s"    ✗ Error: ${e.getMessage}")
            }

        } finally {
            // Cleanup
            client.close()
            println("\n[7] Disconnected from server")
        }
    }
}

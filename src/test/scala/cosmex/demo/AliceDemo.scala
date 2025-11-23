package cosmex.demo

import com.bloxbean.cardano.client.account.Account
import cosmex.*
import cosmex.DemoHelpers.*
import cosmex.config.DemoConfig
import cosmex.ws.SimpleWebSocketClient
import scalus.builtin.ByteString
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.ledger.api.v1.PubKeyHash
import scalus.ledger.api.v3.{TxId, TxOutRef}
import scalus.prelude.{AssocMap, Option as ScalusOption}

import scala.util.{Failure, Success, Try}
import upickle.default.*

/** Demonstration of Alice opening a channel and creating a SELL order
  *
  * Alice's scenario:
  *   - Opens channel with 1000 ADA
  *   - Creates SELL order: 100 ADA @ 0.50 USDM/ADA
  *   - Waits for matching with Bob's BUY order
  *   - Displays trade execution and final balances
  */
object AliceDemo {

    def main(args: Array[String]): Unit = {
        // Load configuration
        val config = DemoConfig.load()

        println("=" * 60)
        println("COSMEX Demo: Alice Opens Channel & Creates SELL Order")
        println("=" * 60)

        // Setup
        val cardanoInfo = CardanoInfo.mainnet

        // Create Alice's account from config
        println("\n[1] Creating Alice's account from config...")
        val aliceAccount = config.alice.createAccount()
        val alicePubKey = config.alice.getPubKey()
        val alicePubKeyHash = config.alice.getPubKeyHash()
        println(s"    Alice Seed: ${config.alice.seed}")
        println(s"    Alice PubKeyHash: ${alicePubKeyHash.toHex}")

        // Create exchange parameters from config
        val exchangeParams = config.exchange.createParams()
        val exchangeAccount = config.exchange.createAccount()
        val exchangePubKeyHash = ByteString.fromArray(
          exchangeAccount.hdKeyPair().getPublicKey.getKeyHash
        )
        println(s"    Exchange PubKeyHash: ${exchangePubKeyHash.toHex}")

        // Create Alice's address
        val aliceAddress = Address(
          config.network.scalusNetwork,
          Credential.KeyHash(AddrKeyHash.fromByteString(alicePubKeyHash))
        )

        // Get Alice's initial balance from config
        val aliceInitialValue = config.alice.getInitialValue()
        println(s"\n    Alice's Initial Balance:")
        config.alice.initialBalance.foreach { case (asset, amount) =>
            val assetInfo = config.assets.getAsset(asset)
            val displayAmount = amount.toDouble / math.pow(10, assetInfo.decimals)
            println(s"      - $displayAmount ${assetInfo.symbol}")
        }

        // Create provider from config
        // Note: For mock provider, you need to set up initial UTxOs manually
        // For Yaci DevKit or Blockfrost, initial funding comes from configuration
        val provider = config.createProvider()

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
            // Step 1: Open channel with deposit
            println("\n[3] Opening channel with deposit...")

            // Find Alice's UTxO
            val depositUtxo = provider
                .findUtxo(
                  address = aliceAddress,
                  minAmount = Some(Coin(100_000L)) // Minimum for tx
                )
                .toOption
                .get

            // Build opening transaction with full balance
            val depositAmount = aliceInitialValue
            val openChannelTx = txbuilder.openChannel(
              clientInput = depositUtxo,
              clientPubKey = alicePubKey,
              depositAmount = depositAmount
            )

            println(s"    Transaction built: ${openChannelTx.id.toHex.take(16)}...")

            // Extract actual deposit amount from transaction output
            // (The server's CosmexScriptAddress)
            val actualDepositAmount = openChannelTx.body.value.outputs.view
                .map(_.value)
                .headOption // For mock, just take first output
                .getOrElse(openChannelTx.body.value.outputs.toSeq.head.value)

            // Create client TxOutRef from first input
            val firstInput = openChannelTx.body.value.inputs.toSeq.head
            val clientTxOutRef = TxOutRef(TxId(firstInput.transactionId), firstInput.index)

            // Create and sign initial snapshot (version 0)
            val initialSnapshot = mkInitialSnapshot(actualDepositAmount.value)
            val clientSignedSnapshot =
                mkClientSignedSnapshot(aliceAccount, clientTxOutRef, initialSnapshot)

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

                    // Step 2: Create a SELL order from config
                    println("\n[4] Creating SELL order from config...")

                    val orderConfig = config.alice.defaultOrder.getOrElse {
                        throw new IllegalStateException("No default order configured for Alice")
                    }

                    println(
                      s"    Order: ${orderConfig.side} ${orderConfig.amount} ${orderConfig.baseAsset} @ ${orderConfig.price}"
                    )

                    // Create the SELL order
                    val sellOrder = LimitOrder(
                      orderPair = orderConfig.getPair(),
                      orderAmount = orderConfig.getSignedAmount(),
                      orderPrice = orderConfig.price
                    )

                    // Derive clientId from the transaction
                    val clientId = ClientId(TransactionInput(openChannelTx.id, 0))

                    // Send CreateOrder request
                    val orderRequest = ClientRequest.CreateOrder(clientId, sellOrder)
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
                              "    (If Bob creates a matching BUY order, trade will execute automatically)"
                            )

                            // Listen for trade notifications
                            var orderExecuted = false
                            var attempts = 0
                            val maxAttempts = 10
                            
                            while (!orderExecuted && attempts < maxAttempts) {
                                client.receiveMessage(1) match {
                                    case Success(msgJson) =>
                                        Try(read[ClientResponse](msgJson)) match {
                                            case Success(ClientResponse.OrderExecuted(trade)) =>
                                                println("\n    ✓ Order matched and executed!")
                                                println(f"      Trade ID: ${trade.orderId}")
                                                println(f"      Amount: ${trade.tradeAmount}")
                                                println(f"      Price: ${trade.tradePrice}")
                                                orderExecuted = true
                                                
                                            case Success(other) =>
                                                println(s"    Received: ${other.getClass.getSimpleName}")
                                                
                                            case Failure(e) =>
                                                println(s"    Failed to parse message: ${e.getMessage}")
                                        }
                                        
                                    case Failure(_) =>
                                        // Timeout, continue waiting
                                        attempts += 1
                                }
                            }
                            
                            if (!orderExecuted) {
                                println("\n    No matching orders found within timeout period")
                                println("    Order remains in the order book")
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
            println("\n[6] Disconnected from server")
        }
    }
}

package cosmex.demo

import cosmex.config.DemoConfig
import cosmex.ws.{CosmexWebSocketServer, SimpleWebSocketClient}
import cosmex.DemoHelpers.*
import cosmex.{ClientId, ClientRequest, ClientResponse, CosmexTransactions, LimitOrder, Server}
import cosmex.CardanoInfoTestNet
import ox.*
import scalus.builtin.ByteString
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.ledger.api.v3.TxOutRef
import sttp.tapir.server.netty.sync.NettySyncServerBinding
import upickle.default.*

import scala.io.StdIn
import scala.util.{Failure, Success, Try}

/** Interactive command-line demo for COSMEX
  *
  * Allows users to:
  *   - Choose which party to represent (Alice or Bob)
  *   - Mint custom tokens (if configured)
  *   - Connect to the exchange
  *   - Buy/sell assets
  *   - Quit
  *
  * Usage:
  *   sbt "Test/runMain cosmex.demo.InteractiveDemo"                    # Starts own server
  *   sbt "Test/runMain cosmex.demo.InteractiveDemo --external-server"  # Connects to external server
  */
object InteractiveDemo extends App {

    // Check if we should connect to an external server
    val useExternalServer = Option(args).exists(_.contains("--external-server"))

    println("""
        |================================================================
        |       COSMEX Interactive Demo - Decentralized Exchange
        |================================================================
        |
        |Welcome to COSMEX! This demo allows you to interact with a
        |decentralized exchange running on Cardano.
        |
        |You can choose to play as Alice or Bob, each with different
        |initial balances and trading capabilities.
        |
        |================================================================
        |""".stripMargin)

    // Load configuration
    val config = DemoConfig.load()
    println(config.summary())

    // Check if party was pre-selected (via AliceDemo/BobDemo)
    val preselectedParty = Option(System.getProperty("cosmex.demo.party"))

    val (partyName, clientConfig) = preselectedParty match {
        case Some("alice") =>
            ("Alice", config.alice)
        case Some("bob") =>
            ("Bob", config.bob)
        case _ =>
            // Ask user to choose party
            println("\nWho would you like to be?")
            println("  1) Alice")
            println("  2) Bob")
            print("\nEnter choice (1 or 2): ")

            val partyChoice = StdIn.readLine().trim

            partyChoice match {
                case "1" => ("Alice", config.alice)
                case "2" => ("Bob", config.bob)
                case _ =>
                    println("Invalid choice. Defaulting to Alice.")
                    ("Alice", config.alice)
            }
    }

    println(s"\n✓ You are now playing as $partyName")
    println(s"  Initial balance: ${clientConfig.initialBalance.mkString(", ")}")

    // Set up the environment
    supervised {
        var serverBinding: NettySyncServerBinding = null
        var client: SimpleWebSocketClient = null
        var clientId: Option[ClientId] = None
        var isConnected = false
        var mintedPolicyId: Option[ByteString] = None

        try {
            // Create exchange
            val exchangeParams = config.exchange.createParams()
            val exchangePrivKey = config.exchange.getPrivateKey()

            // Create client's address
            val clientAccount = clientConfig.createAccount()
            val clientPubKey = clientConfig.getPubKey()
            val clientPubKeyHash = clientConfig.getPubKeyHash()
            val clientAddress = Address(
              config.network.scalusNetwork,
              Credential.KeyHash(AddrKeyHash.fromByteString(clientPubKeyHash))
            )
            val clientInitialValue = clientConfig.getInitialValue()

            // Create blockchain provider and server (only if not using external server)
            val (provider, cardanoInfo, _, port) = if (!useExternalServer) {
                val prov = config.blockchain.provider.toLowerCase match {
                    case "mock" =>
                        import scalus.testing.kit.MockLedgerApi
                        import scalus.cardano.ledger.rules.*

                        val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))
                        val initialUtxos = Map(
                          TransactionInput(genesisHash, 0) ->
                              TransactionOutput(
                                address = clientAddress,
                                value = clientInitialValue + Value.lovelace(100_000_000L)
                              )
                        )

                        MockLedgerApi(
                          initialUtxos = initialUtxos,
                          context = Context.testMainnet(slot = 1000),
                          validators = MockLedgerApi.defaultValidators -
                              MissingKeyHashesValidator -
                              ProtocolParamsViewHashesMatchValidator -
                              MissingRequiredDatumsValidator -
                              WrongNetworkValidator -
                              VerifiedSignaturesInWitnessesValidator,
                          mutators = MockLedgerApi.defaultMutators -
                              PlutusScriptsTransactionMutator
                        )

                    case "yaci-devkit" | "yaci" =>
                        import scalus.cardano.address.ShelleyAddress
                        val clientBech32 = clientAddress.asInstanceOf[ShelleyAddress].toBech32.get
                        val clientFunding = clientInitialValue.coin.value + 100_000_000L

                        println(s"\n[Setup] Funding $partyName with ${clientFunding / 1_000_000} ADA")

                        config.createProviderWithFunding(Seq((clientBech32, clientFunding)))

                    case other =>
                        throw new IllegalArgumentException(s"Unsupported provider for demo: $other")
                }

                // Create CardanoInfo
                val cInfo = CardanoInfoTestNet.currentNetwork(prov)

                // Create server
                val srv = Server(cInfo, exchangeParams, prov, exchangePrivKey)

                // Start WebSocket server
                val serverPort = 18080
                println(s"\n[Server] Starting COSMEX exchange on port $serverPort...")

                val serverFork = forkUser {
                    CosmexWebSocketServer.runBinding(srv, port = serverPort)
                }
                serverBinding = serverFork.join()
                Thread.sleep(2000)
                println(s"[Server] ✓ Exchange is running at ws://localhost:$serverPort")

                (prov, cInfo, srv, serverPort)
            } else {
                // Use external server - use config provider
                println(s"\n[Setup] Connecting to external server at ws://localhost:${config.server.port}")
                val prov = config.createProvider()
                val cInfo = CardanoInfoTestNet.currentNetwork(prov)
                val srv = Server(cInfo, exchangeParams, prov, exchangePrivKey)
                (prov, cInfo, srv, config.server.port)
            }

            // Transaction builder
            val txbuilder = CosmexTransactions(exchangeParams, cardanoInfo)

            // Helper to find client's UTxO
            def findClientUtxo(txIdFilter: Option[TransactionHash] = None): Utxo = {
                config.blockchain.provider.toLowerCase match {
                    case "mock" =>
                        val genesisHash =
                            TransactionHash.fromByteString(ByteString.fromHex("0" * 64))
                        val genesisInput = TransactionInput(genesisHash, 0)
                        Utxo(
                          input = genesisInput,
                          output = TransactionOutput(
                            address = clientAddress,
                            value = clientInitialValue + Value.lovelace(100_000_000L)
                          )
                        )

                    case "yaci-devkit" | "yaci" | "preprod" | "preview" =>
                        import scalus.cardano.address.ShelleyAddress
                        val addressBech32 = clientAddress.asInstanceOf[ShelleyAddress].toBech32.get

                        println(s"[$partyName] Looking for UTxO at address: $addressBech32")

                        provider.findUtxo(
                          address = clientAddress,
                          transactionId = txIdFilter,
                          datum = None,
                          minAmount = Some(Coin(2_000_000L))
                        ) match {
                            case Right(utxo) =>
                                println(s"[$partyName] ✓ Found UTxO with ${utxo.output.value.coin.value / 1_000_000} ADA")
                                utxo
                            case Left(err) =>
                                println(s"\n[$partyName] ✗ ERROR: Could not find funded UTxO")
                                println(s"[$partyName] Wallet address: $addressBech32")
                                println(s"[$partyName] Error: ${err.getMessage}")
                                println(s"\n[$partyName] Please fund this wallet from the faucet:")
                                println(s"[$partyName]   1. Visit: https://docs.cardano.org/cardano-testnet/tools/faucet/")
                                println(s"[$partyName]   2. Request at least 100 ADA for: $addressBech32")
                                println(s"[$partyName]   3. Wait for confirmation (1-2 minutes)")
                                println(s"[$partyName]   4. Verify at: https://preprod.cardanoscan.io/address/$addressBech32")
                                throw new Exception(s"Wallet not funded. Please fund $addressBech32 from the faucet.")
                        }

                    case other =>
                        throw new IllegalArgumentException(s"Unsupported provider: $other")
                }
            }

            // Helper to mint tokens
            def mintTokens(tokenName: String, amount: Long): (TransactionHash, ByteString) = {
                println(s"\n[Mint] Minting $amount units of token '$tokenName'...")

                val depositUtxo = findClientUtxo()

                import cosmex.demo.MintingHelper
                val mintTx = MintingHelper.mintTokens(
                  env = cardanoInfo,
                  utxoToSpend = depositUtxo,
                  collateralUtxo = depositUtxo,
                  recipientAddress = clientAddress,
                  tokenName = ByteString.fromString(tokenName),
                  amount = amount
                )

                // Sign the transaction
                import com.bloxbean.cardano.client.crypto.Blake2bUtil
                import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration
                import com.bloxbean.cardano.client.transaction.util.TransactionBytes

                val hdKeyPair = clientAccount.hdKeyPair()
                val txBytes = TransactionBytes(mintTx.toCbor)
                val txBodyHash = Blake2bUtil.blake2bHash256(txBytes.getTxBodyBytes)
                val signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider
                val signature =
                    signingProvider.signExtended(txBodyHash, hdKeyPair.getPrivateKey.getKeyData)

                val witness = VKeyWitness(
                  signature = ByteString.fromArray(signature),
                  vkey = ByteString.fromArray(hdKeyPair.getPublicKey.getKeyData.take(32))
                )
                val witnessSet = mintTx.witnessSet.copy(
                  vkeyWitnesses = scalus.cardano.ledger.TaggedSortedSet.from(Seq(witness))
                )
                val signedMintTx = mintTx.copy(witnessSet = witnessSet)

                // Submit
                provider.submit(signedMintTx) match {
                    case Right(_) =>
                        println(
                          s"[Mint] ✓ Transaction submitted: ${signedMintTx.id.toHex.take(16)}..."
                        )
                    case Left(error) =>
                        throw new Exception(
                          s"Failed to submit minting transaction: ${error.getMessage}"
                        )
                }

                // Wait for confirmation
                println(s"[Mint] Waiting for confirmation...")
                Thread.sleep(config.blockchain.provider.toLowerCase match {
                    case "yaci-devkit" | "yaci" => 20000
                    case _                      => 2000
                })

                // Calculate policy ID
                val utxoRef = scalus.ledger.api.v3.TxOutRef(
                  scalus.ledger.api.v3.TxId(depositUtxo.input.transactionId),
                  depositUtxo.input.index
                )
                val policyId = MintingHelper.getPolicyId(utxoRef)

                println(s"[Mint] ✓ Minted token policy ID: ${policyId.toHex}")

                (signedMintTx.id, policyId)
            }

            // Helper to connect to exchange
            def connectToExchange(utxoFilter: Option[TransactionHash]): Unit = {
                println(s"\n[Connect] Opening channel with exchange...")

                val depositUtxo = findClientUtxo(utxoFilter)
                val depositAmount = depositUtxo.output.value

                println(s"[Connect] Depositing: ${depositAmount.coin.value / 1_000_000} ADA")

                val unsignedTx = txbuilder.openChannel(
                  clientInput = depositUtxo,
                  clientPubKey = clientPubKey,
                  depositAmount = depositAmount
                )

                // Sign the transaction
                import com.bloxbean.cardano.client.crypto.Blake2bUtil
                import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration
                import com.bloxbean.cardano.client.transaction.util.TransactionBytes

                val hdKeyPair = clientAccount.hdKeyPair()
                val txBytes = TransactionBytes(unsignedTx.toCbor)
                val txBodyHash = Blake2bUtil.blake2bHash256(txBytes.getTxBodyBytes)
                val signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider
                val signature =
                    signingProvider.signExtended(txBodyHash, hdKeyPair.getPrivateKey.getKeyData)

                val witness = VKeyWitness(
                  signature = ByteString.fromArray(signature),
                  vkey = ByteString.fromArray(hdKeyPair.getPublicKey.getKeyData.take(32))
                )
                val witnessSet = unsignedTx.witnessSet.copy(
                  vkeyWitnesses = scalus.cardano.ledger.TaggedSortedSet.from(Seq(witness))
                )
                val openChannelTx = unsignedTx.copy(witnessSet = witnessSet)

                // Create client ID and connect
                val cId = ClientId(TransactionInput(openChannelTx.id, 0))
                val wsUrl =
                    s"ws://localhost:$port/ws/${cId.txOutRef.transactionId.toHex}/${cId.txOutRef.index}"

                client = SimpleWebSocketClient(wsUrl)
                clientId = Some(cId)

                // Create and sign initial snapshot
                val clientTxOutRef = TxOutRef(scalus.ledger.api.v3.TxId(openChannelTx.id), 0)
                val initialSnapshot = mkInitialSnapshot(depositAmount)
                val clientSignedSnapshot =
                    mkClientSignedSnapshot(clientAccount, clientTxOutRef, initialSnapshot)

                // Send OpenChannel request
                client.sendMessage(ClientRequest.OpenChannel(openChannelTx, clientSignedSnapshot))

                // Wait for ChannelPending
                client.receiveMessage(timeoutSeconds = 10) match {
                    case Success(responseJson) =>
                        read[ClientResponse](responseJson) match {
                            case ClientResponse.ChannelPending(txId) =>
                                println(s"[Connect] ✓ Channel pending, txId: ${txId.take(16)}...")
                            case ClientResponse.Error(msg) =>
                                throw new Exception(s"Channel opening failed: $msg")
                            case other =>
                                throw new Exception(s"Expected ChannelPending, got: $other")
                        }
                    case Failure(e) =>
                        throw new Exception(s"Error waiting for ChannelPending: ${e.getMessage}")
                }

                // Wait for ChannelOpened
                println(s"[Connect] Waiting for channel confirmation...")
                client.receiveMessage(timeoutSeconds = 70) match {
                    case Success(responseJson) =>
                        read[ClientResponse](responseJson) match {
                            case ClientResponse.ChannelOpened(_) =>
                                println(s"[Connect] ✓ Channel opened successfully!")
                                isConnected = true
                            case ClientResponse.Error(msg) =>
                                throw new Exception(s"Channel opening failed: $msg")
                            case other =>
                                throw new Exception(s"Unexpected response: $other")
                        }
                    case Failure(e) =>
                        throw new Exception(s"Error waiting for ChannelOpened: ${e.getMessage}")
                }
            }

            // Helper to create buy/sell orders
            def createOrder(
                side: String,
                baseAsset: String,
                quoteAsset: String,
                amount: Long,
                price: Long
            ): Unit = {
                if !isConnected then {
                    println("[Order] ERROR: You must connect to the exchange first!")
                    return
                }

                println(s"\n[Order] Creating $side order: $amount $baseAsset/$quoteAsset @ $price")

                // Determine the pair
                val (base, quote) = (baseAsset.toLowerCase, quoteAsset.toLowerCase) match {
                    case ("ada", other) =>
                        val ada = (ByteString.empty, ByteString.empty)
                        // If we have a minted token, use it
                        mintedPolicyId match {
                            case Some(policyId) if other != "ada" =>
                                val token = (policyId, ByteString.fromString(other.toUpperCase))
                                (ada, token)
                            case _ =>
                                // Try to use configured asset
                                try {
                                    val asset = config.assets.getAsset(other)
                                    (ada, asset.assetClass)
                                } catch {
                                    case _: Exception =>
                                        println(
                                          s"[Order] ERROR: Unknown asset '$other'. Available: ADA, USDM"
                                        )
                                        return
                                }
                        }

                    case (base, quote) =>
                        try {
                            val baseAssetClass = config.assets.getAsset(base).assetClass
                            val quoteAssetClass = config.assets.getAsset(quote).assetClass
                            (baseAssetClass, quoteAssetClass)
                        } catch {
                            case _: Exception =>
                                println(s"[Order] ERROR: Unknown assets. Available: ADA, USDM")
                                return
                        }
                }

                val signedAmount =
                    if side.equalsIgnoreCase("BUY") then BigInt(amount) else BigInt(-amount)
                val order = LimitOrder(
                  orderPair = (base, quote),
                  orderAmount = signedAmount,
                  orderPrice = price
                )

                client.sendRequest(
                  ClientRequest.CreateOrder(clientId.get, order),
                  timeoutSeconds = 10
                ) match {
                    case Success(ClientResponse.OrderCreated(orderId)) =>
                        println(s"[Order] ✓ Order created! OrderID: $orderId")

                        // Listen for potential trade execution
                        println(s"[Order] Waiting for potential matches...")
                        var attempts = 0
                        while attempts < 5 do {
                            client.receiveMessage(timeoutSeconds = 1) match {
                                case Success(msgJson) =>
                                    Try(read[ClientResponse](msgJson)) match {
                                        case Success(ClientResponse.OrderExecuted(trade)) =>
                                            println(
                                              s"[Order] ✓ Order executed! Trade amount: ${trade.tradeAmount}, price: ${trade.tradePrice}"
                                            )
                                            return
                                        case Success(other) =>
                                            println(
                                              s"[Order] Received: ${other.getClass.getSimpleName}"
                                            )
                                        case Failure(_) =>
                                    }
                                case Failure(_) =>
                                    attempts += 1
                            }
                        }
                        println(s"[Order] No matching orders found")

                    case Success(ClientResponse.Error(msg)) =>
                        println(s"[Order] ERROR: $msg")

                    case Success(other) =>
                        println(s"[Order] Unexpected response: $other")

                    case Failure(e) =>
                        println(s"[Order] ERROR: ${e.getMessage}")
                }
            }

            // Main command loop
            println(s"\n${"=" * 60}")
            println(s"$partyName's Trading Terminal")
            println(s"${"=" * 60}")
            println("\nAvailable commands:")
            println("  mint <tokenName> <amount>  - Mint custom tokens")
            println("  connect                     - Connect to the exchange")
            println("  buy <base> <quote> <amount> <price>   - Create buy order")
            println("  sell <base> <quote> <amount> <price>  - Create sell order")
            println("  help                        - Show this help")
            println("  quit                        - Exit the demo")
            println()

            var running = true
            var lastMintTxId: Option[TransactionHash] = None

            while running do {
                print(s"$partyName> ")
                val input = StdIn.readLine()

                if input == null then {
                    running = false
                } else {
                    val parts = input.trim.split("\\s+")

                    parts.headOption.map(_.toLowerCase) match {
                        case Some("mint") if parts.length == 3 =>
                            Try {
                                val tokenName = parts(1)
                                val amount = parts(2).toLong
                                val (txId, policyId) = mintTokens(tokenName, amount)
                                mintedPolicyId = Some(policyId)
                                lastMintTxId = Some(txId)
                            }.recover { case e: Exception =>
                                println(s"[Mint] ERROR: ${e.getMessage}")
                            }

                        case Some("connect") =>
                            Try {
                                connectToExchange(lastMintTxId)
                            }.recover { case e: Exception =>
                                println(s"[Connect] ERROR: ${e.getMessage}")
                            }

                        case Some("buy") if parts.length == 5 =>
                            Try {
                                val base = parts(1)
                                val quote = parts(2)
                                val amount = parts(3).toLong
                                val price = parts(4).toLong
                                createOrder("BUY", base, quote, amount, price)
                            }.recover { case e: Exception =>
                                println(s"[Buy] ERROR: ${e.getMessage}")
                            }

                        case Some("sell") if parts.length == 5 =>
                            Try {
                                val base = parts(1)
                                val quote = parts(2)
                                val amount = parts(3).toLong
                                val price = parts(4).toLong
                                createOrder("SELL", base, quote, amount, price)
                            }.recover { case e: Exception =>
                                println(s"[Sell] ERROR: ${e.getMessage}")
                            }

                        case Some("help") =>
                            println("\nAvailable commands:")
                            println("  mint <tokenName> <amount>              - Mint custom tokens")
                            println(
                              "  connect                                 - Connect to the exchange"
                            )
                            println("  buy <base> <quote> <amount> <price>    - Create buy order")
                            println("  sell <base> <quote> <amount> <price>   - Create sell order")
                            println("  help                                    - Show this help")
                            println("  quit                                    - Exit the demo")
                            println()

                        case Some("quit") | Some("exit") =>
                            println("\nGoodbye!")
                            running = false

                        case Some(cmd) =>
                            println(s"Unknown command: $cmd. Type 'help' for available commands.")

                        case None =>
                        // Empty input, continue
                    }
                }
            }

        } finally {
            if client != null then {
                client.close()
            }
            if serverBinding != null then {
                serverBinding.stop()
                println("\n[Server] Exchange shut down.")
            }
        }
    }

    println("\n" + "=" * 60)
    println("Thank you for using COSMEX!")
    println("=" * 60)
}

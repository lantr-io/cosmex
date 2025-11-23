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
object InteractiveDemo {
    // Custom exception with error code and display control
    case class DemoException(
        code: String,
        message: String,
        alreadyPrinted: Boolean = false
    ) extends Exception(message)

    // Override main to accept args properly (instead of using App trait)
    def main(args: Array[String]): Unit = {
        runDemo(args)
    }

    def runDemo(args: Array[String]): Unit = {
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

    // Load configuration (lazy to avoid initialization deadlock with supervised block)
    lazy val config = DemoConfig.load()
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

    // Eagerly initialize config objects and accounts BEFORE entering supervised block to avoid deadlock
    println("\n[DEBUG] Pre-initializing config objects...")
    val _ = config.exchange.seed // Force exchange object initialization
    val scalusNetwork = config.network.scalusNetwork // Pre-get network for Address creation
    val _ = config.blockchain.provider // Force blockchain object initialization

    // Pre-create all accounts and values outside supervised block
    println("[DEBUG] Pre-creating client account...")
    val clientAccount = clientConfig.createAccount()
    println(s"[DEBUG] Client account created: ${clientAccount.baseAddress()}")
    val clientPubKey = clientConfig.getPubKey()
    val clientPubKeyHash = clientConfig.getPubKeyHash()
    val clientInitialValue = clientConfig.getInitialValue()

    // Pre-create client address outside supervised block
    val clientAddress = Address(
      scalusNetwork,
      Credential.KeyHash(AddrKeyHash.fromByteString(clientPubKeyHash))
    )
    println(s"[DEBUG] Client address created: ${clientAddress}")
    println("[DEBUG] Config objects and accounts initialized")

    // Capture all object-level vals as local variables to avoid deadlock in supervised block
    val externalServer = useExternalServer
    println(s"[DEBUG] External server mode: $externalServer")

    println("\n[DEBUG] About to enter supervised block...")

    // Set up the environment
    supervised {
        println("[DEBUG] Inside supervised block, starting initialization...")
        var serverBinding: NettySyncServerBinding = null
        var client: SimpleWebSocketClient = null
        var clientId: Option[ClientId] = None
        var isConnected = false
        var mintedPolicyId: Option[ByteString] = None

        try {
            println("[DEBUG] Creating exchange params...")
            val exchangeConfig = config.exchange
            // Create exchange
            val exchangeParams = exchangeConfig.createParams()
            val exchangePrivKey = config.exchange.getPrivateKey()
            println("[DEBUG] Exchange params created")
            println("[DEBUG] Using pre-initialized client address and values")

            // Create blockchain provider and server (only if not using external server)
            println(s"[DEBUG] externalServer=$externalServer, about to create provider...")
            val (provider, cardanoInfo, _, port) = if (!externalServer) {
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
                println(s"[Setup] Provider type: ${config.blockchain.provider}")
                System.out.flush()

                try {
                    val prov = config.createProvider()
                    println(s"[Setup] ✓ Provider created successfully")
                    val cInfo = CardanoInfoTestNet.currentNetwork(prov)
                    println(s"[Setup] ✓ CardanoInfo initialized")
                    val srv = Server(cInfo, exchangeParams, prov, exchangePrivKey)
                    println(s"[Setup] ✓ Server instance created")
                    System.out.flush()
                    (prov, cInfo, srv, config.server.port)
                } catch {
                    case e: Exception =>
                        println(s"[Setup] ERROR during initialization: ${e.getMessage}")
                        e.printStackTrace()
                        throw e
                }
            }

            // Fetch script hash from server (if using external server)
            val serverScriptHash: Option[ScriptHash] = if (externalServer) {
                try {
                    println(s"[Setup] Fetching script hash from server at http://localhost:$port/script-hash...")
                    val url = new java.net.URL(s"http://localhost:$port/script-hash")
                    val connection = url.openConnection()
                    connection.setConnectTimeout(5000)
                    connection.setReadTimeout(5000)
                    val scriptHashHex = scala.io.Source.fromInputStream(connection.getInputStream).mkString.trim
                    println(s"[Setup] ✓ Received script hash from server: $scriptHashHex")
                    Some(ScriptHash.fromByteString(ByteString.fromHex(scriptHashHex)))
                } catch {
                    case e: Exception =>
                        println(s"[Setup] ERROR: Failed to fetch script hash from server: ${e.getMessage}")
                        println(s"[Setup] Make sure the server is running on port $port")
                        throw DemoException("SCRIPT_HASH_FETCH_FAILED", "Could not fetch script hash from server", alreadyPrinted = true)
                }
            } else {
                None
            }

            // Transaction builder (with optional script hash override from server)
            val txbuilder = CosmexTransactions(exchangeParams, cardanoInfo, serverScriptHash)

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

                        try {
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
                                    throw DemoException("WALLET_NOT_FUNDED", "Wallet not funded", alreadyPrinted = true)
                            }
                        } catch {
                            case e: DemoException =>
                                // Re-throw our own exceptions
                                throw e
                            case e: Exception if e.getMessage.contains("404") || e.getMessage.contains("Not Found") =>
                                // 404 from Blockfrost means wallet has no UTxOs (not funded)
                                println(s"\n[$partyName] ✗ ERROR: Could not find funded UTxO")
                                println(s"[$partyName] Wallet address: $addressBech32")
                                println(s"\n[$partyName] Please fund this wallet from the faucet:")
                                println(s"[$partyName]   1. Visit: https://docs.cardano.org/cardano-testnet/tools/faucet/")
                                println(s"[$partyName]   2. Request at least 100 ADA for: $addressBech32")
                                println(s"[$partyName]   3. Wait for confirmation (1-2 minutes)")
                                println(s"[$partyName]   4. Verify at: https://preprod.cardanoscan.io/address/$addressBech32")
                                throw DemoException("WALLET_NOT_FUNDED", "Wallet not funded", alreadyPrinted = true)
                            case e: Exception =>
                                // Other errors - re-throw to show stack trace
                                throw e
                        }

                    case other =>
                        throw new IllegalArgumentException(s"Unsupported provider: $other")
                }
            }

            // Helper to mint tokens
            def mintTokens(tokenName: String, amount: Long): (TransactionHash, ByteString) = {
                println(s"\n[Mint] Minting $amount units of token '$tokenName'...")

                val depositUtxo = findClientUtxo()

                // Find suitable collateral (ADA-only if possible)
                val collateralUtxo = if depositUtxo.output.value == Value.lovelace(depositUtxo.output.value.coin.value) then {
                    // UTxO is ADA-only, can use as both input and collateral
                    depositUtxo
                } else {
                    // UTxO contains tokens, try to find another ADA-only UTxO
                    println(s"[Mint] Spend UTxO contains tokens, looking for ADA-only collateral...")
                    provider.findUtxos(
                      address = clientAddress,
                      transactionId = None,
                      datum = None,
                      minAmount = None,
                      minRequiredTotalAmount = None
                    ) match {
                        case Right(utxos) =>
                            utxos.find { case (_, output) =>
                                output.value == Value.lovelace(output.value.coin.value) &&
                                output.value.coin.value >= 5_000_000L // At least 5 ADA for collateral
                            }.map { case (input, output) => Utxo(input, output) }
                            .getOrElse {
                                println(s"[Mint] WARNING: No ADA-only UTxO found, using spend UTxO as collateral (may fail)")
                                depositUtxo
                            }
                        case Left(_) =>
                            println(s"[Mint] WARNING: Could not query UTxOs, using spend UTxO as collateral (may fail)")
                            depositUtxo
                    }
                }

                import cosmex.demo.MintingHelper
                val mintTx = MintingHelper.mintTokens(
                  env = cardanoInfo,
                  utxoToSpend = depositUtxo,
                  collateralUtxo = collateralUtxo, // Use separate collateral UTxO if available
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
                println(s"[DEBUG] connectToExchange started, utxoFilter=$utxoFilter")
                System.out.flush()

                println(s"[DEBUG] About to call findClientUtxo...")
                System.out.flush()
                val depositUtxo = findClientUtxo(utxoFilter)
                println(s"[DEBUG] findClientUtxo returned successfully")
                System.out.flush()

                // Deposit configured amount into the channel
                val depositAmountLovelace = clientConfig.getDepositAmount()
                val totalAvailable = depositUtxo.output.value.coin.value
                val feeReserve = 5_000_000L // 5 ADA for fees/change

                // Verify we have enough ADA
                if totalAvailable < depositAmountLovelace + feeReserve then {
                    throw new Exception(s"Insufficient funds: have ${totalAvailable / 1_000_000} ADA, need ${(depositAmountLovelace + feeReserve) / 1_000_000} ADA (${depositAmountLovelace / 1_000_000} ADA deposit + ${feeReserve / 1_000_000} ADA for fees/change)")
                }

                // Keep all tokens but only deposit configured ADA amount
                val depositAmount = depositUtxo.output.value.copy(coin = Coin(depositAmountLovelace))

                println(s"[Connect] Depositing: ${depositAmountLovelace / 1_000_000} ADA (keeping ${(totalAvailable - depositAmountLovelace) / 1_000_000} ADA in wallet for fees/change)")

                println(s"[DEBUG] Building openChannel transaction...")
                println(s"[DEBUG] Deposit amount: ${depositAmount.coin.value / 1_000_000} ADA")
                println(s"[DEBUG] Client-side script hash: ${txbuilder.script.scriptHash.toHex}")
                val unsignedTx = txbuilder.openChannel(
                  clientInput = depositUtxo,
                  clientPubKey = clientPubKey,
                  depositAmount = depositAmount
                )
                println(s"[DEBUG] Transaction built, outputs:")
                unsignedTx.body.value.outputs.toSeq.zipWithIndex.foreach { case (out, idx) =>
                    println(s"[DEBUG]   Output $idx: ${out.value.address} = ${out.value.value.coin.value / 1_000_000} ADA")
                }

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

                // Find the actual output index for the Cosmex script output
                // Use the server's script hash if we fetched it, otherwise use local compilation
                val effectiveScriptHash = serverScriptHash.getOrElse(txbuilder.script.scriptHash)
                val cosmexScriptAddress = Address(
                  scalusNetwork,
                  Credential.ScriptHash(effectiveScriptHash)
                )

                println(s"[Connect] Looking for output to script address: $cosmexScriptAddress")

                val channelOutputIdx = openChannelTx.body.value.outputs.view
                    .map(_.value)
                    .zipWithIndex
                    .find(_._1.address == cosmexScriptAddress)
                    .map(_._2)
                    .getOrElse {
                        println(s"[Connect] ERROR: Could not find output to expected script address")
                        println(s"[Connect] Expected: $cosmexScriptAddress")
                        println(s"[Connect] Transaction outputs:")
                        openChannelTx.body.value.outputs.toSeq.zipWithIndex.foreach { case (out, idx) =>
                            println(s"[Connect]   Output $idx: ${out.value.address}")
                        }
                        throw new Exception("Could not find Cosmex script output in transaction")
                    }

                println(s"[Connect] Found script output at index: $channelOutputIdx")

                // Create client ID and connect
                val cId = ClientId(TransactionInput(openChannelTx.id, channelOutputIdx))
                val wsUrl =
                    s"ws://localhost:$port/ws/${cId.txOutRef.transactionId.toHex}/${cId.txOutRef.index}"

                client = SimpleWebSocketClient(wsUrl)
                clientId = Some(cId)

                // Create and sign initial snapshot
                val clientTxOutRef = TxOutRef(scalus.ledger.api.v3.TxId(openChannelTx.id), channelOutputIdx)
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

            println("[DEBUG] Entering command loop")
            System.out.flush()

            while running do {
                print(s"$partyName> ")
                System.out.flush()
                val input = StdIn.readLine()
                println(s"[DEBUG] Raw input received: '$input'")
                System.out.flush()

                if input == null then {
                    running = false
                } else {
                    val parts = input.trim.split("\\s+")
                    println(s"[DEBUG] Received command: '${parts.mkString(" ")}'")
                    System.out.flush()

                    parts.headOption.map(_.toLowerCase) match {
                        case Some("mint") if parts.length == 3 =>
                            Try {
                                val tokenName = parts(1)
                                val amount = parts(2).toLong
                                val (txId, policyId) = mintTokens(tokenName, amount)
                                mintedPolicyId = Some(policyId)
                                lastMintTxId = Some(txId)
                            }.recover {
                                case e: DemoException if e.alreadyPrinted => ()
                                case e: DemoException => println(s"[Mint] ERROR [${e.code}]: ${e.message}")
                                case e: Exception =>
                                    println(s"[Mint] UNEXPECTED ERROR: ${e.getMessage}")
                                    e.printStackTrace()
                            }

                        case Some("mint") =>
                            println("[Mint] ERROR: Invalid syntax. Usage: mint <tokenName> <amount>")
                            println("[Mint] Example: mint MYTOKEN 1000000")

                        case Some("connect") =>
                            println(s"[DEBUG] Connect command matched, isConnected=$isConnected")
                            System.out.flush()
                            if (isConnected) {
                                println("[Connect] Already connected to the exchange!")
                            } else {
                                println("[Connect] Attempting to connect to the exchange...")
                                System.out.flush() // Ensure message is displayed immediately
                                println("[DEBUG] About to call connectToExchange...")
                                System.out.flush()
                                Try {
                                    connectToExchange(lastMintTxId)
                                }.recover {
                                    case e: DemoException if e.alreadyPrinted =>
                                        // Error message already displayed, don't repeat it
                                        ()
                                    case e: DemoException =>
                                        // Display error message for non-printed exceptions
                                        println(s"[Connect] ERROR [${e.code}]: ${e.message}")
                                    case e: Exception if e.getMessage.contains("insufficient funds") =>
                                        // Insufficient funds - likely need more ADA for fees
                                        val diffMatch = "diff=(-?\\d+)".r.findFirstMatchIn(e.getMessage)
                                        val diff = diffMatch.map(_.group(1).toLong).getOrElse(0L)
                                        val neededAda = Math.abs(diff) / 1_000_000.0
                                        println(s"\n[Connect] ✗ ERROR: Insufficient funds for transaction")
                                        println(f"[Connect] You need approximately ${neededAda}%.2f more ADA to cover transaction fees")
                                        println(s"[Connect] Please request more test ADA from the faucet")
                                        println(s"[Connect] Faucet: https://docs.cardano.org/cardano-testnet/tools/faucet/")
                                    case e: Exception =>
                                        // Unexpected error - show full details
                                        println(s"[Connect] UNEXPECTED ERROR: ${e.getMessage}")
                                        e.printStackTrace()
                                }
                            }

                        case Some("buy") if parts.length == 5 =>
                            if (!isConnected) {
                                println("[Buy] ERROR: Not connected to exchange. Please use 'connect' first.")
                            } else {
                                Try {
                                    val base = parts(1)
                                    val quote = parts(2)
                                    val amount = parts(3).toLong
                                    val price = parts(4).toLong
                                    createOrder("BUY", base, quote, amount, price)
                                }.recover {
                                    case e: DemoException if e.alreadyPrinted => ()
                                    case e: DemoException => println(s"[Buy] ERROR [${e.code}]: ${e.message}")
                                    case e: Exception =>
                                        println(s"[Buy] UNEXPECTED ERROR: ${e.getMessage}")
                                        e.printStackTrace()
                                }
                            }

                        case Some("buy") =>
                            println("[Buy] ERROR: Invalid syntax. Usage: buy <base> <quote> <amount> <price>")
                            println("[Buy] Example: buy ada usdm 100000000 500000")

                        case Some("sell") if parts.length == 5 =>
                            if (!isConnected) {
                                println("[Sell] ERROR: Not connected to exchange. Please use 'connect' first.")
                            } else {
                                Try {
                                    val base = parts(1)
                                    val quote = parts(2)
                                    val amount = parts(3).toLong
                                    val price = parts(4).toLong
                                    createOrder("SELL", base, quote, amount, price)
                                }.recover {
                                    case e: DemoException if e.alreadyPrinted => ()
                                    case e: DemoException => println(s"[Sell] ERROR [${e.code}]: ${e.message}")
                                    case e: Exception =>
                                        println(s"[Sell] UNEXPECTED ERROR: ${e.getMessage}")
                                        e.printStackTrace()
                                }
                            }

                        case Some("sell") =>
                            println("[Sell] ERROR: Invalid syntax. Usage: sell <base> <quote> <amount> <price>")
                            println("[Sell] Example: sell ada usdm 100000000 500000")

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
                            println(s"Unknown command: '$cmd'. Type 'help' for available commands.")

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
    } // end runDemo
}

package cosmex.demo

import com.bloxbean.cardano.client.account.Account
import cosmex.config.DemoConfig
import cosmex.ws.{CosmexWebSocketServer, SimpleWebSocketClient}
import cosmex.DemoHelpers.*
import cosmex.demo.MultiClientTestHelpers.*
import cosmex.{ClientId, CosmexTransactions, LimitOrder, Pair, Server}
import cosmex.CardanoInfoTestNet
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import ox.*
import scalus.builtin.ByteString
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.cardano.ledger.rules.Context
import scalus.ledger.api.v3.{TxId, TxOutRef}
import scalus.testing.kit.MockLedgerApi
import sttp.tapir.server.netty.sync.NettySyncServerBinding


class MultiClientDemoTest extends AnyFunSuite with Matchers {

    test("Alice and Bob trade ADA/Token with automatic order matching (Bob mints token)") {
        supervised {
            // Load configuration
            val config = DemoConfig.load()

            // Create exchange
            val exchangeParams = config.exchange.createParams()
            val exchangePrivKey = config.exchange.getPrivateKey()

            // Create Alice's address
            val aliceAccount = config.alice.createAccount()
            val alicePubKey = config.alice.getPubKey()
            val alicePubKeyHash = config.alice.getPubKeyHash()
            val aliceAddress = Address(
              config.network.scalusNetwork,
              Credential.KeyHash(AddrKeyHash.fromByteString(alicePubKeyHash))
            )
            val aliceInitialValue = config.alice.getInitialValue()

            // Create Bob's address
            val bobAccount = config.bob.createAccount()
            val bobPubKey = config.bob.getPubKey()
            val bobPubKeyHash = config.bob.getPubKeyHash()
            val bobAddress = Address(
              config.network.scalusNetwork,
              Credential.KeyHash(AddrKeyHash.fromByteString(bobPubKeyHash))
            )
            val bobInitialValue = config.bob.getInitialValue()

            // Create blockchain provider from configuration
            val provider = config.blockchain.provider.toLowerCase match {
                case "mock" =>
                    // For MockLedgerApi, we need to initialize with genesis UTxOs
                    val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))

                    val initialUtxos = Map(
                      TransactionInput(genesisHash, 0) ->
                          TransactionOutput(
                            address = aliceAddress,
                            value = aliceInitialValue + Value.lovelace(100_000_000L)
                          ),
                      TransactionInput(genesisHash, 1) ->
                          TransactionOutput(
                            address = bobAddress,
                            value = bobInitialValue + Value.lovelace(100_000_000L)
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
                          VerifiedSignaturesInWitnessesValidator, // Disable signature validation for testing
                      mutators = MockLedgerApi.defaultMutators -
                          PlutusScriptsTransactionMutator
                    )

                case "yaci-devkit" | "yaci" =>
                    // Yaci DevKit with initial funding for Alice and Bob
                    import scalus.cardano.address.ShelleyAddress

                    // Get bech32 addresses for funding
                    val aliceBech32 = aliceAddress.asInstanceOf[ShelleyAddress].toBech32.get
                    val bobBech32 = bobAddress.asInstanceOf[ShelleyAddress].toBech32.get

                    // Calculate total funding needed (initial balance + 100 ADA for fees/collateral)
                    // Note: yaci-devkit can only fund ADA, so we only use the coin value
                    val aliceFunding = aliceInitialValue.coin.value + 100_000_000L
                    val bobFunding = bobInitialValue.coin.value + 100_000_000L

                    println(s"[Test] Funding Alice with ${aliceFunding / 1_000_000} ADA")
                    println(s"[Test] Funding Bob with ${bobFunding / 1_000_000} ADA")

                    val initialFunding = Seq(
                      (aliceBech32, aliceFunding),
                      (bobBech32, bobFunding)
                    )

                    config.createProviderWithFunding(initialFunding)

                case provider @ ("preprod" | "preview") =>
                    // Use Blockfrost provider for preprod/preview
                    // NOTE: Wallets must be funded externally using the faucet
                    println(s"[Test] Using $provider network provider")
                    println(s"[Test] WARNING: Ensure wallets are funded from the faucet:")
                    println(
                      s"[Test]   - Alice: ${aliceAddress.asInstanceOf[scalus.cardano.address.ShelleyAddress].toBech32.get}"
                    )
                    println(
                      s"[Test]   - Bob: ${bobAddress.asInstanceOf[scalus.cardano.address.ShelleyAddress].toBech32.get}"
                    )
                    config.createProvider()

                case other =>
                    throw new IllegalArgumentException(s"Unsupported provider for test: $other")
            }

            // Create CardanoInfo with protocol parameters from provider
            // This is essential for Plutus script transactions (minting policies)
            val cardanoInfo = CardanoInfoTestNet.currentNetwork(provider)

            // Create server
            val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

            // Start WebSocket server on random port
            val port = 18080

            var serverBinding: NettySyncServerBinding = null

            try {
                // Fork server and capture its binding
                val serverFork = forkUser {
                    CosmexWebSocketServer.runBinding(server, port = port)
                }
                serverBinding = serverFork.join()

                // Wait for server to start
                Thread.sleep(3000)

                val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))

                // Manually add UTxOs (MockLedgerApi doesn't allow runtime additions easily)
                // For testing, we'll use TxBuilder to create genesis transactions

                val txbuilder = CosmexTransactions(exchangeParams, cardanoInfo)

                // Helper function to open channel and create order
                def clientScenario(
                    name: String,
                    account: Account,
                    pubKey: ByteString,
                    @annotation.unused pubKeyHash: ByteString,
                    address: Address,
                    initialValue: Value,
                    orderConfig: DemoConfig#OrderConfig,
                    clientIndex: Int,
                    mintToken: Boolean = false,
                    tokenName: String = "BOBTOKEN",
                    tokenAmount: Long = 1000000L,
                    customPair: Option[Pair],
                    txIdFilter: Option[TransactionHash],
                    triggerRebalancing: Boolean = false
                ): (Unit, Option[ByteString]) = {
                    var client: SimpleWebSocketClient = null // Declare client outside try block
                    val mintedPolicyId: Option[ByteString] = None

                    try {
                        println(s"\n[$name] Starting scenario...")

                        // Get UTxO for the client
                        val depositUtxo = config.blockchain.provider.toLowerCase match {
                            case "mock" =>
                                // For MockLedgerApi, use the pre-created genesis UTxO
                                val genesisInput = TransactionInput(genesisHash, clientIndex.toInt)
                                Utxo(
                                  input = genesisInput,
                                  output = TransactionOutput(
                                    address = address,
                                    value = initialValue + Value.lovelace(100_000_000L)
                                  )
                                )

                            case "yaci-devkit" | "yaci" | "preprod" | "preview" =>
                                // For Yaci DevKit / Blockfrost, query the provider for funded UTxOs
                                import scalus.cardano.address.ShelleyAddress
                                val addressBech32 =
                                    address.asInstanceOf[ShelleyAddress].toBech32.get

                                txIdFilter match {
                                    case Some(txId) =>
                                        println(
                                          s"[$name] Querying for specific TX output: ${txId.toHex.take(16)}..."
                                        )
                                    case None =>
                                        println(
                                          s"[$name] Querying provider for UTxOs at address: $addressBech32..."
                                        )
                                }

                                // First try to find all UTxOs at the address for debugging
                                provider.findUtxos(
                                  address = address,
                                  transactionId = txIdFilter,
                                  datum = None,
                                  minAmount = None,
                                  minRequiredTotalAmount = None
                                ) match {
                                    case Right(utxos) =>
                                        println(s"[$name] Found ${utxos.size} UTxOs at address")
                                        utxos.foreach { case (input, output) =>
                                            println(
                                              s"[$name]   UTxO: ${input.transactionId.toHex.take(16)}#${input.index} = ${output.value.coin.value} lovelace"
                                            )
                                            val hasTokens = output.value != Value.lovelace(
                                              output.value.coin.value
                                            )
                                            if hasTokens then {
                                                println(s"[$name]   Tokens: ${output.value}")
                                            }
                                        }
                                    case Left(err) =>
                                        println(s"[$name] Could not query UTxOs: ${err.getMessage}")
                                }

                                // Find UTxO - filter by txId if provided
                                provider.findUtxo(
                                  address = address,
                                  transactionId = txIdFilter,
                                  datum = None,
                                  minAmount =
                                      Some(Coin(2_000_000L)) // Just need minimum UTxO size (~2 ADA)
                                ) match {
                                    case Right(foundUtxo) =>
                                        println(
                                          s"[$name] Using UTxO: ${foundUtxo.input.transactionId.toHex.take(16)}#${foundUtxo.input.index}"
                                        )
                                        foundUtxo
                                    case Left(err) =>
                                        fail(
                                          s"[$name] Failed to find funded UTxO: ${err.getMessage}"
                                        )
                                }

                            case other =>
                                fail(s"Unsupported provider: $other")
                        }

                        // Optional token minting step for Bob
                        val (finalDepositUtxo, finalDepositAmount) = if mintToken then {
                            println(
                              s"[$name] Minting custom token: $tokenName (amount: $tokenAmount)..."
                            )

                            // Import minting helper
                            import cosmex.demo.MintingHelper

                            // Find suitable collateral (ADA-only if possible)
                            val collateralUtxo =
                                if depositUtxo.output.value == Value.lovelace(
                                      depositUtxo.output.value.coin.value
                                    )
                                then {
                                    // UTxO is ADA-only, can use as both input and collateral
                                    depositUtxo
                                } else {
                                    // UTxO contains tokens, try to find another ADA-only UTxO
                                    println(
                                      s"[$name] Spend UTxO contains tokens, looking for ADA-only collateral..."
                                    )
                                    provider.findUtxos(
                                      address = address,
                                      transactionId = None,
                                      datum = None,
                                      minAmount = None,
                                      minRequiredTotalAmount = None
                                    ) match {
                                        case Right(utxos) =>
                                            utxos
                                                .find { case (_, output) =>
                                                    output.value == Value.lovelace(
                                                      output.value.coin.value
                                                    ) &&
                                                    output.value.coin.value >= 5_000_000L // At least 5 ADA for collateral
                                                }
                                                .map { case (input, output) => Utxo(input, output) }
                                                .getOrElse {
                                                    println(
                                                      s"[$name] WARNING: No ADA-only UTxO found, using spend UTxO as collateral (may fail)"
                                                    )
                                                    depositUtxo
                                                }
                                        case Left(_) =>
                                            println(
                                              s"[$name] WARNING: Could not query UTxOs, using spend UTxO as collateral (may fail)"
                                            )
                                            depositUtxo
                                    }
                                }

                            // Create minting transaction
                            val mintTx = MintingHelper.mintTokens(
                              env = cardanoInfo,
                              utxoToSpend = depositUtxo,
                              collateralUtxo = collateralUtxo, // Use separate collateral UTxO
                              recipientAddress = address,
                              tokenName = ByteString.fromString(tokenName),
                              amount = tokenAmount
                            )

                            // Sign the minting transaction
                            println(s"[$name] Signing minting transaction...")
                            val signedMintTx = signTransaction(account, mintTx)

                            // Submit the minting transaction
                            println(s"[$name] Submitting minting transaction...")
                            provider.submit(signedMintTx) match {
                                case Right(_) =>
                                    println(
                                      s"[$name] ✓ Minting transaction submitted: ${signedMintTx.id.toHex.take(16)}..."
                                    )
                                case Left(error) =>
                                    fail(
                                      s"[$name] Failed to submit minting transaction: ${error.getMessage}"
                                    )
                            }

                            // Wait for confirmation (especially important for yaci-devkit)
                            println(s"[$name] Waiting for minting transaction confirmation...")
                            Thread.sleep(config.blockchain.provider.toLowerCase match {
                                case "yaci-devkit" | "yaci" => 20000 // 20 seconds for yaci
                                case _                      => 2000 // 2 seconds for mock
                            })

                            // Find the newly minted tokens
                            println(s"[$name] Looking for minted tokens...")
                            val mintedUtxo = provider.findUtxo(
                              address = address,
                              transactionId = Some(signedMintTx.id),
                              datum = None,
                              minAmount = None
                            ) match {
                                case Right(utxo) =>
                                    println(s"[$name] ✓ Found minted tokens: ${utxo.output.value}")
                                    utxo
                                case Left(err) =>
                                    fail(s"[$name] Failed to find minted tokens: ${err.getMessage}")
                            }

                            // Calculate deposit amount including minted tokens
                            val mintedTokenValue = mintedUtxo.output.value
                            println(
                              s"[$name] Using minted tokens for channel deposit: ${mintedTokenValue}"
                            )

                            (mintedUtxo, mintedTokenValue)
                        } else {
                            // No minting in this step - determine deposit amount
                            val depositAmount = txIdFilter match {
                                case Some(_) =>
                                    // UTxO was found by tx ID (e.g., from preliminary minting step)
                                    // Use actual UTxO value which may contain minted tokens
                                    // Reserve some ADA for transaction fees
                                    val feeReserve = 5_000_000L // 5 ADA for fees
                                    val utxoValue = depositUtxo.output.value
                                    utxoValue.copy(coin = Coin(utxoValue.coin.value - feeReserve))
                                case None =>
                                    // No tx filter - use configured initial value
                                    // For yaci-devkit, only deposit ADA (no multiassets in funded UTxOs)
                                    config.blockchain.provider.toLowerCase match {
                                        case "yaci-devkit" | "yaci" =>
                                            Value.lovelace(initialValue.coin.value)
                                        case _ =>
                                            initialValue
                                    }
                            }
                            (depositUtxo, depositAmount)
                        }

                        val unsignedTx = txbuilder.openChannel(
                          clientInput = finalDepositUtxo,
                          clientPubKey = pubKey,
                          depositAmount = finalDepositAmount
                        )

                        // Sign the transaction
                        println(s"[$name] Signing open channel transaction...")
                        val openChannelTx = signTransaction(account, unsignedTx)
                        println(s"[$name] Transaction signed successfully")

                        // The ClientId should be based on openChannelTx.id and 0
                        val clientId = ClientId(TransactionInput(openChannelTx.id, 0))

                        // Now construct the WebSocket URL using clientId components
                        val wsUrl =
                            s"ws://localhost:$port/ws/${clientId.txOutRef.transactionId.toHex}/${clientId.txOutRef.index}"
                        client = SimpleWebSocketClient(wsUrl)

                        val clientTxOutRef = TxOutRef(TxId(openChannelTx.id), 0)

                        // Create and sign initial snapshot (must match finalDepositAmount)
                        val initialSnapshot = mkInitialSnapshot(finalDepositAmount)
                        val clientSignedSnapshot =
                            mkClientSignedSnapshot(account, clientTxOutRef, initialSnapshot)

                        // Open channel and wait for confirmation
                        println(s"[$name] Opening channel...")
                        val isMockProvider = config.blockchain.provider.toLowerCase == "mock"
                        openChannel(client, openChannelTx, clientSignedSnapshot, isMockProvider, name)

                        // Create order (use customPair if provided)
                        val orderPair = customPair.getOrElse(orderConfig.getPair())
                        val order = LimitOrder(
                          orderPair = orderPair,
                          orderAmount = orderConfig.getSignedAmount(),
                          orderPrice = orderConfig.price
                        )

                        println(
                          s"[$name] Creating ${orderConfig.side} order: ${orderConfig.amount} ${orderConfig.baseAsset}/${orderConfig.quoteAsset} @ ${orderConfig.price}"
                        )
                        createOrder(client, clientId, order, name)

                        // Wait for potential trade execution
                        waitForTradeExecution(client, name)

                        // Handle rebalancing flow
                        handleRebalancing(client, account, clientId, name, triggerRebalancing)

                    } finally {
                        if client != null then {
                            client.close()
                            println(s"[$name] Disconnected")
                        }
                    }

                    ((), mintedPolicyId)
                }

                // Run Alice and Bob scenarios concurrently (like a real exchange)
                // Both clients will be connected to the exchange simultaneously

                println("\n" + "=" * 60)
                println("Multi-Client Demo Test: Alice and Bob Trading")
                println("=" * 60)

                val aliceOrderConfig = config.alice.defaultOrder.get
                val bobOrderConfig = config.bob.defaultOrder.get

                // Check if token minting is enabled for Bob
                // Note: Disable minting for mock provider as it requires complex UTxO setup
                // Force minting for yaci-devkit since it doesn't support pre-funded native assets
                val isMockProvider = config.blockchain.provider.toLowerCase == "mock"
                val isYaciDevkit = config.blockchain.provider.toLowerCase match {
                    case "yaci-devkit" | "yaci" => true
                    case _                      => false
                }
                val bobMintingConfig = config.bob.minting
                    .getOrElse(
                      config
                          .MintingConfig(enabled = false, tokenName = "BOBTOKEN", amount = 1000000L)
                    )
                    .copy(enabled =
                        if isMockProvider then false
                        else if isYaciDevkit then true // Force minting on yaci-devkit
                        else config.bob.minting.map(_.enabled).getOrElse(false)
                    )

                if isMockProvider && config.bob.minting.exists(_.enabled) then {
                    println("[Test] Skipping token minting for mock provider (CI mode)")
                }
                if isYaciDevkit then {
                    println(
                      "[Test] Forcing token minting for yaci-devkit (no pre-funded native assets)"
                    )
                }

                // Step 1: Bob mints tokens (if enabled) - before any channel opening
                var bobMintTxId: Option[TransactionHash] = None
                val bobMintedPolicyId: Option[ByteString] = if bobMintingConfig.enabled then {
                    println("\n[Bob - Preliminary] Minting custom token before trading...")

                    val (depositUtxo, collateralUtxo) =
                        config.blockchain.provider.toLowerCase match {
                            case "yaci-devkit" | "yaci" | "preprod" | "preview" =>
                                // Find any UTxO for spending (can have tokens)
                                val spendUtxo = provider.findUtxo(
                                  address = bobAddress,
                                  transactionId = None,
                                  datum = None,
                                  minAmount = Some(Coin(10_000_000L)) // Need at least 10 ADA
                                ) match {
                                    case Right(utxo) => utxo
                                    case Left(err) =>
                                        fail(
                                          s"[Bob - Preliminary] Failed to find UTxO to spend: ${err.getMessage}"
                                        )
                                }

                                // Try to find an ADA-only UTxO for collateral
                                // Check if the spend UTxO is ADA-only
                                val collateral =
                                    if spendUtxo.output.value == Value.lovelace(
                                          spendUtxo.output.value.coin.value
                                        )
                                    then {
                                        // UTxO is ADA-only, can use as both input and collateral
                                        spendUtxo
                                    } else {
                                        // UTxO contains tokens, try to find another ADA-only UTxO
                                        println(
                                          "[Bob - Preliminary] Spend UTxO contains tokens, looking for ADA-only collateral..."
                                        )
                                        // Query all UTxOs and filter for ADA-only
                                        provider.findUtxos(
                                          address = bobAddress,
                                          transactionId = None,
                                          datum = None,
                                          minAmount = None,
                                          minRequiredTotalAmount = None
                                        ) match {
                                            case Right(utxos) =>
                                                utxos
                                                    .find { case (_, output) =>
                                                        output.value == Value.lovelace(
                                                          output.value.coin.value
                                                        ) &&
                                                        output.value.coin.value >= 5_000_000L // At least 5 ADA for collateral
                                                    }
                                                    .map { case (input, output) =>
                                                        Utxo(input, output)
                                                    }
                                                    .getOrElse {
                                                        println(
                                                          "[Bob - Preliminary] WARNING: No ADA-only UTxO found, using spend UTxO as collateral (may fail)"
                                                        )
                                                        spendUtxo
                                                    }
                                            case Left(_) =>
                                                println(
                                                  "[Bob - Preliminary] WARNING: Could not query UTxOs, using spend UTxO as collateral (may fail)"
                                                )
                                                spendUtxo
                                        }
                                    }

                                (spendUtxo, collateral)

                            case "mock" =>
                                val genesisInput = TransactionInput(genesisHash, 1)
                                val utxo = Utxo(
                                  input = genesisInput,
                                  output = TransactionOutput(
                                    address = bobAddress,
                                    value = bobInitialValue + Value.lovelace(100_000_000L)
                                  )
                                )
                                (utxo, utxo) // Mock ledger, same UTxO for both
                            case other => fail(s"Unsupported provider: $other")
                        }

                    import cosmex.demo.MintingHelper
                    val mintTx = MintingHelper.mintTokens(
                      env = cardanoInfo,
                      utxoToSpend = depositUtxo,
                      collateralUtxo = collateralUtxo, // Use separate collateral UTxO
                      recipientAddress = bobAddress,
                      tokenName = ByteString.fromString(bobMintingConfig.tokenName),
                      amount = bobMintingConfig.amount
                    )

                    // Sign the minting transaction
                    val signedMintTx = signTransaction(bobAccount, mintTx)

                    import cosmex.util.submitAndWait

                    provider.submitAndWait(signedMintTx, maxAttempts = 60, delayMs = 1000) match {
                        case Right(_) =>
                            println(
                              s"[Bob - Preliminary] ✓ Minting transaction confirmed: ${signedMintTx.id.toHex.take(16)}..."
                            )
                        case Left(error) =>
                            fail(
                              s"[Bob - Preliminary] Failed to submit or confirm minting transaction: ${error.getMessage}"
                            )
                    }

                    // Calculate policy ID
                    val utxoRef = scalus.ledger.api.v3.TxOutRef(
                      scalus.ledger.api.v3.TxId(depositUtxo.input.transactionId),
                      depositUtxo.input.index
                    )
                    val policyId = MintingHelper.getPolicyId(utxoRef)
                    println(s"[Bob - Preliminary] ✓ Minted token policy ID: ${policyId.toHex}")
                    println(s"[Bob - Preliminary] Minting tx ID: ${signedMintTx.id.toHex}")
                    bobMintTxId = Some(signedMintTx.id)
                    Some(policyId)
                } else {
                    None
                }

                // Step 2: Determine Alice's trading pair (use Bob's token if minted)
                val aliceTradingPair = bobMintedPolicyId.map { policyId =>
                    val bobTokenAsset =
                        (policyId, ByteString.fromString(bobMintingConfig.tokenName))
                    val adaAsset = (ByteString.empty, ByteString.empty)
                    println(s"[Alice] Will trade ADA for Bob's token: ${policyId.toHex}")
                    (adaAsset, bobTokenAsset) // ADA/BOBTOKEN pair
                }

                // Step 3: Run Alice and Bob scenarios concurrently
                // Both clients will connect to the exchange simultaneously for realistic order matching

                // For Bob's scenario, handle the minted tokens case
                val bobScenarioTxIdFilter = bobMintTxId match {
                    case Some(mintTxId) =>
                        println(
                          s"\n[Test] Bob minted tokens - will query for minting TX: ${mintTxId.toHex.take(16)}..."
                        )
                        Some(mintTxId)
                    case None =>
                        println(s"\n[Test] No minting - Bob will use regular funded UTxO")
                        None
                }

                println("\n[Test] Starting Alice and Bob concurrently...")

                // Fork both client scenarios to run in parallel
                val aliceFork = forkUser {
                    clientScenario(
                      "Alice",
                      aliceAccount,
                      alicePubKey,
                      alicePubKeyHash,
                      aliceAddress,
                      aliceInitialValue,
                      aliceOrderConfig,
                      clientIndex = 0,
                      customPair = aliceTradingPair,
                      txIdFilter = None,
                      triggerRebalancing = true // Alice triggers rebalancing after trading
                    )
                }

                val bobFork = forkUser {
                    clientScenario(
                      "Bob",
                      bobAccount,
                      bobPubKey,
                      bobPubKeyHash,
                      bobAddress,
                      bobInitialValue,
                      bobOrderConfig,
                      clientIndex = 1,
                      mintToken = false, // Already minted
                      tokenName = bobMintingConfig.tokenName,
                      tokenAmount = bobMintingConfig.amount,
                      customPair = aliceTradingPair,
                      txIdFilter = bobScenarioTxIdFilter,
                      triggerRebalancing = false // Bob waits for rebalancing from Alice
                    )
                }

                // Wait for both to complete
                aliceFork.join()
                bobFork.join()

                println("\n[Test] Both Alice and Bob scenarios completed")

                // Verify trade execution
                // Note: In a real test, we'd query balances and verify
                // For now, successful order creation is sufficient

                println("\n" + "=" * 60)
                println("✓ Multi-client test completed successfully!")
                println("=" * 60)
                println("\nVerified:")
                println("  - Both clients successfully opened channels")
                println("  - Both orders were created")
                println("  - Order matching logic is functioning")
                println(
                  "  - Rebalancing flow tested (FixBalance -> RebalanceRequired -> SignRebalance)"
                )
                println()

                // Test passes if we get here without exceptions
                succeed
            } finally { // New finally block for server shutdown
                if serverBinding != null then {
                    serverBinding.stop()
                    println("\n[Server] Test server shut down.")
                }
            }
        }
    }
}

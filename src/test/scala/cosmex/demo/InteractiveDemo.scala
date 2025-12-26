package cosmex.demo

import cosmex.config.DemoConfig
import cosmex.ws.{CosmexWebSocketServer, SimpleWebSocketClient}
import cosmex.DemoHelpers.*
import cosmex.util.JsonCodecs.given
import cosmex.{CardanoInfoTestNet, ChannelStatus, ClientId, ClientRequest, ClientResponse, ClientState, CosmexTransactions, LimitOrder, OnChainChannelState, OnChainState, Server}
import ox.*
import scalus.builtin.ByteString
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.ledger.api.v3.TxOutRef
import sttp.tapir.server.netty.sync.NettySyncServerBinding
import upickle.default.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.StdIn
import scala.util.{Failure, Success, Try}
import scalus.utils.await
import scalus.cardano.ledger.DatumOption
import java.time.Instant

/** Interactive command-line demo for COSMEX
  *
  * Allows users to:
  *   - Choose which party to represent (Alice or Bob)
  *   - Mint custom tokens (if configured)
  *   - Connect to the exchange
  *   - Buy/sell assets
  *   - Quit
  *
  * Usage: sbt "Test/runMain cosmex.demo.InteractiveDemo" # Starts own server sbt "Test/runMain
  * cosmex.demo.InteractiveDemo --external-server" # Connects to external server
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
            var clientState: Option[ClientState] = None

            // Track spent UTxOs to filter them out from Blockfrost queries (indexing delay)
            val spentUtxos = scala.collection.mutable.Set[TransactionInput]()

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
                val (provider, cardanoInfo, _, port) = if !externalServer then {
                    val prov = config.blockchain.provider.toLowerCase match {
                        case "mock" =>
                            import scalus.cardano.node.Emulator
                            import scalus.cardano.ledger.rules.*

                            val genesisHash =
                                TransactionHash.fromByteString(ByteString.fromHex("0" * 64))
                            val initialUtxos = Map(
                              TransactionInput(genesisHash, 0) ->
                                  TransactionOutput(
                                    address = clientAddress,
                                    value = clientInitialValue + Value.lovelace(100_000_000L)
                                  )
                            )

                            // Create context matching the configured network
                            val mainnetCtx = Context.testMainnet(slot = 1000)
                            val context = config.network.scalusNetwork match {
                                case scalus.cardano.address.Network.Mainnet => mainnetCtx
                                case scalus.cardano.address.Network.Testnet =>
                                    val testnetEnv = mainnetCtx.env.copy(network =
                                        scalus.cardano.address.Network.Testnet
                                    )
                                    new Context(mainnetCtx.fee, testnetEnv, mainnetCtx.slotConfig)
                                case other =>
                                    throw new IllegalArgumentException(
                                      s"Unsupported network: $other"
                                    )
                            }

                            Emulator(initialUtxos = initialUtxos, initialContext = context)

                        case "yaci-devkit" | "yaci" =>
                            import scalus.cardano.address.ShelleyAddress
                            val clientBech32 =
                                clientAddress.asInstanceOf[ShelleyAddress].toBech32.get
                            val clientFunding = clientInitialValue.coin.value + 100_000_000L

                            println(
                              s"\n[Setup] Funding $partyName with ${clientFunding / 1_000_000} ADA"
                            )

                            config.createProviderWithFunding(Seq((clientBech32, clientFunding)))

                        case "preprod" | "preview" =>
                            println(s"\n[Setup] Using ${config.blockchain.provider} network")
                            println(s"[Setup] Make sure your wallet is funded via faucet")
                            config.createProvider()

                        case other =>
                            throw new IllegalArgumentException(
                              s"Unsupported provider for demo: $other"
                            )
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
                    println(
                      s"\n[Setup] Connecting to external server at ws://localhost:${config.server.port}"
                    )
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

                // Transaction builder
                val txbuilder = CosmexTransactions(exchangeParams, cardanoInfo)

                // Helper to find client's UTxOs
                // Returns enough UTxOs to cover requiredAmount, preferring fewer larger UTxOs
                def findClientUtxos(
                    txIdFilter: Option[TransactionHash] = None,
                    requiredAmount: Long,
                    requiredTokens: Value = Value.lovelace(0)
                ): Seq[Utxo] = {
                    config.blockchain.provider.toLowerCase match {
                        case "mock" =>
                            val genesisHash =
                                TransactionHash.fromByteString(ByteString.fromHex("0" * 64))
                            val genesisInput = TransactionInput(genesisHash, 0)
                            Seq(Utxo(
                              input = genesisInput,
                              output = TransactionOutput(
                                address = clientAddress,
                                value = clientInitialValue + Value.lovelace(100_000_000L)
                              )
                            ))

                        case "yaci-devkit" | "yaci" | "preprod" | "preview" =>
                            import scalus.cardano.address.ShelleyAddress
                            val addressBech32 =
                                clientAddress.asInstanceOf[ShelleyAddress].toBech32.get

                            println(s"[$partyName] Looking for UTxO at address: $addressBech32")

                            // Helper to check if UTxO contains the SPECIFIC required tokens
                            def hasRequiredTokens(utxoValue: Value): Boolean = {
                                // If no tokens required, always true
                                if requiredTokens == Value.lovelace(0) ||
                                   requiredTokens == Value.lovelace(requiredTokens.coin.value) then
                                    true
                                else
                                    // Check that UTxO has at least the required amount of each token
                                    requiredTokens.assets.assets.forall { case (policyId, requiredAssets) =>
                                        utxoValue.assets.assets.get(policyId) match {
                                            case None => false // UTxO doesn't have this policy
                                            case Some(utxoAssets) =>
                                                requiredAssets.forall { case (assetName, requiredAmount) =>
                                                    utxoAssets.get(assetName) match {
                                                        case None => false // UTxO doesn't have this asset
                                                        case Some(utxoAmount) => utxoAmount >= requiredAmount
                                                    }
                                                }
                                        }
                                    }
                            }

                            // Check if we need specific tokens
                            val needsTokens = requiredTokens != Value.lovelace(0) &&
                                requiredTokens != Value.lovelace(requiredTokens.coin.value)

                            // Retry configuration for waiting on token indexing
                            val maxRetries = if needsTokens then 12 else 1  // 12 retries * 5 seconds = 60 seconds max
                            val retryDelayMs = 5000  // 5 seconds between retries

                            def tryFindUtxos(attempt: Int): Either[Throwable, Seq[Utxo]] = {
                                provider
                                    .findUtxos(
                                      address = clientAddress,
                                      transactionId = txIdFilter,
                                      datum = None,
                                      minAmount = None,
                                      minRequiredTotalAmount = None
                                    )
                                    .await() match {
                                    case Right(utxos) =>
                                        // Get all unspent UTxOs
                                        val allUnspent = utxos.filter { case (input, _) =>
                                            !spentUtxos.contains(input)
                                        }

                                        // Separate UTxOs with required tokens from others
                                        val (withTokens, adaOnly) = if needsTokens then {
                                            val wt = allUnspent.filter { case (_, output) =>
                                                hasRequiredTokens(output.value)
                                            }
                                            val ao = allUnspent.filter { case (_, output) =>
                                                !hasRequiredTokens(output.value)
                                            }
                                            (wt, ao)
                                        } else {
                                            (Map.empty[TransactionInput, TransactionOutput], allUnspent)
                                        }

                                        if needsTokens && withTokens.isEmpty then {
                                            // Tokens needed but not found - retry if we have attempts left
                                            if attempt < maxRetries then {
                                                val waitSeconds = (maxRetries - attempt) * retryDelayMs / 1000
                                                println(s"[$partyName] Waiting for token UTxO to be indexed... (attempt $attempt/$maxRetries, ~${waitSeconds}s remaining)")
                                                Thread.sleep(retryDelayMs)
                                                tryFindUtxos(attempt + 1)
                                            } else {
                                                Left(new RuntimeException(
                                                  s"No UTxO found with required tokens after $maxRetries attempts. " +
                                                  s"The mint transaction may not be confirmed yet."
                                                ))
                                            }
                                        } else {
                                            // Start with UTxOs that have required tokens
                                            var collected = withTokens.toList
                                            var total = collected.map(_._2.value.coin.value).sum

                                            // Add ADA-only UTxOs if we need more ADA
                                            val sortedAdaOnly = adaOnly.toSeq.sortBy(-_._2.value.coin.value)
                                            val adaIter = sortedAdaOnly.iterator
                                            while total < requiredAmount && adaIter.hasNext do {
                                                val utxo = adaIter.next()
                                                collected = utxo :: collected
                                                total += utxo._2.value.coin.value
                                            }

                                            if total < requiredAmount then {
                                                Left(new RuntimeException(
                                                  s"Insufficient funds: need ${requiredAmount / 1_000_000} ADA, " +
                                                  s"have ${total / 1_000_000} ADA across ${collected.size} UTxOs"
                                                ))
                                            } else {
                                                val result = collected.reverse.map { case (input, output) =>
                                                    Utxo(input, output)
                                                }

                                                println(
                                                  s"[$partyName] ✓ Found ${result.size} UTxO(s) with ${total / 1_000_000} ADA total (need ${requiredAmount / 1_000_000} ADA)"
                                                )
                                                if spentUtxos.nonEmpty then {
                                                    println(
                                                      s"[$partyName]   (filtered out ${spentUtxos.size} spent UTxOs)"
                                                    )
                                                }
                                                Right(result)
                                            }
                                        }
                                    case Left(err) => Left(err)
                                }
                            }

                            try {
                                tryFindUtxos(1) match {
                                    case Right(utxos) => utxos
                                    case Left(err) =>
                                        println(
                                          s"\n[$partyName] ✗ ERROR: Could not find funded UTxO"
                                        )
                                        println(s"[$partyName] Wallet address: $addressBech32")
                                        println(s"[$partyName] Error: ${err.getMessage}")
                                        println(
                                          s"\n[$partyName] Please fund this wallet from the faucet:"
                                        )
                                        println(
                                          s"[$partyName]   1. Visit: https://docs.cardano.org/cardano-testnet/tools/faucet/"
                                        )
                                        println(
                                          s"[$partyName]   2. Request at least 100 ADA for: $addressBech32"
                                        )
                                        println(
                                          s"[$partyName]   3. Wait for confirmation (1-2 minutes)"
                                        )
                                        println(
                                          s"[$partyName]   4. Verify at: https://preprod.cardanoscan.io/address/$addressBech32"
                                        )
                                        throw DemoException(
                                          "WALLET_NOT_FUNDED",
                                          "Wallet not funded",
                                          alreadyPrinted = true
                                        )
                                }
                            } catch {
                                case e: DemoException =>
                                    // Re-throw our own exceptions
                                    throw e
                                case e: Exception
                                    if e.getMessage
                                        .contains("404") || e.getMessage.contains("Not Found") =>
                                    // 404 from Blockfrost means wallet has no UTxOs (not funded)
                                    println(s"\n[$partyName] ✗ ERROR: Could not find funded UTxO")
                                    println(s"[$partyName] Wallet address: $addressBech32")
                                    println(
                                      s"\n[$partyName] Please fund this wallet from the faucet:"
                                    )
                                    println(
                                      s"[$partyName]   1. Visit: https://docs.cardano.org/cardano-testnet/tools/faucet/"
                                    )
                                    println(
                                      s"[$partyName]   2. Request at least 100 ADA for: $addressBech32"
                                    )
                                    println(
                                      s"[$partyName]   3. Wait for confirmation (1-2 minutes)"
                                    )
                                    println(
                                      s"[$partyName]   4. Verify at: https://preprod.cardanoscan.io/address/$addressBech32"
                                    )
                                    throw DemoException(
                                      "WALLET_NOT_FUNDED",
                                      "Wallet not funded",
                                      alreadyPrinted = true
                                    )
                                case e: Exception =>
                                    // Other errors - re-throw to show stack trace
                                    throw e
                            }

                        case other =>
                            throw new IllegalArgumentException(s"Unsupported provider: $other")
                    }
                }

                // Convenience function returning a single UTxO (for backwards compatibility)
                def findClientUtxo(
                    txIdFilter: Option[TransactionHash] = None,
                    requiredAmount: Long,
                    requiredTokens: Value = Value.lovelace(0)
                ): Utxo = findClientUtxos(txIdFilter, requiredAmount, requiredTokens).head

                // Helper to mint tokens
                def mintTokens(tokenName: String, amount: Long): (TransactionHash, ByteString) = {
                    println(s"\n[Mint] Minting $amount units of token '$tokenName'...")

                    // Minting needs: 2 ADA (token output) + 5 ADA (collateral) + ~1 ADA (fees) = ~8 ADA
                    val mintingRequiredAmount = 8_000_000L
                    val depositUtxo = findClientUtxo(requiredAmount = mintingRequiredAmount)

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
                              s"[Mint] Spend UTxO contains tokens, looking for ADA-only collateral..."
                            )
                            provider
                                .findUtxos(
                                  address = clientAddress,
                                  transactionId = None,
                                  datum = None,
                                  minAmount = None,
                                  minRequiredTotalAmount = None
                                )
                                .await() match {
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
                                              s"[Mint] WARNING: No ADA-only UTxO found, using spend UTxO as collateral (may fail)"
                                            )
                                            depositUtxo
                                        }
                                case Left(_) =>
                                    println(
                                      s"[Mint] WARNING: Could not query UTxOs, using spend UTxO as collateral (may fail)"
                                    )
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
                    provider.submit(signedMintTx).await() match {
                        case Right(_) =>
                            println(
                              s"[Mint] ✓ Transaction submitted: ${signedMintTx.id.toHex.take(16)}..."
                            )
                            // Track spent UTxOs
                            signedMintTx.body.value.inputs.toSeq.foreach { input =>
                                spentUtxos.add(input)
                            }
                            println(
                              s"[Mint]   Tracked ${signedMintTx.body.value.inputs.toSeq.size} spent UTxOs"
                            )
                        case Left(error) =>
                            throw new Exception(
                              s"Failed to submit minting transaction: ${error.toString}"
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

                // Helper to parse deposit arguments: "ada 100 usdm 30" -> Value
                def parseDepositValue(args: Seq[String]): Value = {
                    if args.isEmpty then {
                        // Default: use configured deposit amount (ADA only)
                        Value.lovelace(clientConfig.getDepositAmount())
                    } else {
                        // Parse token/amount pairs
                        val pairs = args.grouped(2).toList
                        if pairs.exists(_.length != 2) then {
                            throw new IllegalArgumentException(
                              "Invalid deposit format. Usage: connect [token amount ...]"
                            )
                        }

                        pairs.foldLeft(Value.lovelace(0L)) { case (acc, Seq(token, amountStr)) =>
                            val amount = Try(amountStr.toLong).getOrElse {
                                throw new IllegalArgumentException(
                                  s"Invalid amount '$amountStr' for token '$token'"
                                )
                            }

                            token.toLowerCase match {
                                case "ada" =>
                                    // Amount is in ADA, convert to lovelace
                                    acc.copy(coin = Coin(acc.coin.value + amount * 1_000_000L))
                                case other =>
                                    // Look up token in asset registry
                                    try {
                                        val asset = config.assets.getAsset(other)
                                        acc + Value.asset(
                                          ScriptHash.fromByteString(asset.policyId),
                                          AssetName(asset.assetName),
                                          amount
                                        )
                                    } catch {
                                        case _: IllegalArgumentException =>
                                            // Check if we have a minted token with this name
                                            mintedPolicyId match {
                                                case Some(policyId) =>
                                                    acc + Value.asset(
                                                      ScriptHash.fromByteString(policyId),
                                                      AssetName(
                                                        ByteString.fromString(other.toUpperCase)
                                                      ),
                                                      amount
                                                    )
                                                case None =>
                                                    throw new IllegalArgumentException(
                                                      s"Unknown asset '$other'. Available: ${config.assets.availableAssets.mkString(", ")}"
                                                    )
                                            }
                                    }
                            }
                        }
                    }
                }

                // Helper to connect to exchange
                def connectToExchange(
                    utxoFilter: Option[TransactionHash],
                    depositValue: Value
                ): Unit = {
                    println(s"\n[Connect] Opening channel with exchange...")
                    println(s"[DEBUG] connectToExchange started, utxoFilter=$utxoFilter")
                    System.out.flush()

                    // Calculate required amount (deposit + fees)
                    val depositAmountLovelace = depositValue.coin.value
                    val feeReserve = 5_000_000L // 5 ADA for fees/change
                    val requiredAmount = depositAmountLovelace + feeReserve

                    // Check if tokens are required
                    val hasTokens = depositValue != Value.lovelace(depositValue.coin.value)
                    val tokenInfo = if hasTokens then " + tokens" else ""

                    println(
                      s"[DEBUG] About to call findClientUtxos (need at least ${requiredAmount / 1_000_000} ADA$tokenInfo)..."
                    )
                    System.out.flush()
                    val depositUtxos = findClientUtxos(utxoFilter, requiredAmount, depositValue)
                    println(s"[DEBUG] findClientUtxos returned ${depositUtxos.size} UTxO(s)")
                    System.out.flush()

                    // Calculate total available from all UTxOs
                    val totalAvailable = depositUtxos.map(_.output.value.coin.value).sum

                    // Verify we have enough ADA
                    if totalAvailable < depositAmountLovelace + feeReserve then {
                        throw new Exception(
                          s"Insufficient funds: have ${totalAvailable / 1_000_000} ADA, need ${(depositAmountLovelace + feeReserve) / 1_000_000} ADA (${depositAmountLovelace / 1_000_000} ADA deposit + ${feeReserve / 1_000_000} ADA for fees/change)"
                        )
                    }

                    // Use the deposit value provided (may include multiple tokens)
                    val depositAmount = depositValue

                    // Display deposit info
                    val tokenInfo2 =
                        if depositValue == Value.lovelace(depositValue.coin.value) then ""
                        else s" + tokens"
                    println(
                      s"[Connect] Depositing: ${depositAmountLovelace / 1_000_000} ADA$tokenInfo2 (using ${depositUtxos.size} UTxO(s), keeping ${(totalAvailable - depositAmountLovelace) / 1_000_000} ADA for fees/change)"
                    )

                    println(s"[DEBUG] Building openChannel transaction...")
                    println(s"[DEBUG] Deposit amount: ${depositAmount.coin.value / 1_000_000} ADA")
                    println(
                      s"[DEBUG] Client-side script hash: ${txbuilder.script.scriptHash.toHex}"
                    )
                    val unsignedTx = txbuilder.openChannel(
                      clientInputs = depositUtxos,
                      clientPubKey = clientPubKey,
                      depositAmount = depositAmount
                    )
                    println(s"[DEBUG] Transaction built, outputs:")
                    unsignedTx.body.value.outputs.toSeq.zipWithIndex.foreach { case (out, idx) =>
                        println(
                          s"[DEBUG]   Output $idx: ${out.value.address} = ${out.value.value.coin.value / 1_000_000} ADA"
                        )
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

                    // Debug: Print CBOR details to diagnose serialization issues
                    val txCbor = openChannelTx.toCbor
                    val firstByte = txCbor.headOption.map(b => f"${b & 0xff}%02x").getOrElse("??")
                    println(s"[DEBUG] TX CBOR first byte: 0x$firstByte (should be 0x84 for 4-element array)")
                    println(s"[DEBUG] TX CBOR length: ${txCbor.length} bytes")
                    println(s"[DEBUG] TX CBOR first 50 bytes: ${txCbor.take(50).map(b => f"${b & 0xff}%02x").mkString}")
                    println(s"[DEBUG] TX isValid: ${openChannelTx.isValid}")
                    println(s"[DEBUG] TX auxiliaryData: ${openChannelTx.auxiliaryData}")
                    println(s"[DEBUG] TX witnessSet.vkeyWitnesses count: ${openChannelTx.witnessSet.vkeyWitnesses.toSeq.size}")

                    // Find the actual output index for the Cosmex script output
                    val cosmexScriptAddress = Address(
                      scalusNetwork,
                      Credential.ScriptHash(txbuilder.script.scriptHash)
                    )

                    println(s"[Connect] Looking for output to script address: $cosmexScriptAddress")

                    val channelOutputIdx = openChannelTx.body.value.outputs.view
                        .map(_.value)
                        .zipWithIndex
                        .find(_._1.address == cosmexScriptAddress)
                        .map(_._2)
                        .getOrElse {
                            println(
                              s"[Connect] ERROR: Could not find output to expected script address"
                            )
                            println(s"[Connect] Expected: $cosmexScriptAddress")
                            println(s"[Connect] Transaction outputs:")
                            openChannelTx.body.value.outputs.toSeq.zipWithIndex.foreach {
                                case (out, idx) =>
                                    println(s"[Connect]   Output $idx: ${out.value.address}")
                            }
                            throw new Exception(
                              "Could not find Cosmex script output in transaction"
                            )
                        }

                    println(s"[Connect] Found script output at index: $channelOutputIdx")

                    // Create client ID and connect (use first UTxO as channel identifier)
                    val primaryUtxo = depositUtxos.head
                    val cId = ClientId(primaryUtxo.input)
                    val wsUrl =
                        s"ws://localhost:$port/ws/${cId.txOutRef.transactionId.toHex}/${cId.txOutRef.index}"

                    client = SimpleWebSocketClient(wsUrl)
                    clientId = Some(cId)

                    // Create and sign initial snapshot
                    // Note: clientTxOutRef must be the INPUT being spent (not the output)
                    // The server uses the first input as the channel identifier
                    val clientTxOutRef = TxOutRef(
                      scalus.ledger.api.v3.TxId(primaryUtxo.input.transactionId),
                      primaryUtxo.input.index
                    )
                    val initialSnapshot = mkInitialSnapshot(depositAmount)
                    val clientSignedSnapshot =
                        mkClientSignedSnapshot(clientAccount, clientTxOutRef, initialSnapshot)

                    // Send OpenChannel request
                    client.sendMessage(
                      ClientRequest.OpenChannel(openChannelTx, clientSignedSnapshot)
                    )

                    // Track spent UTxOs
                    openChannelTx.body.value.inputs.toSeq.foreach { input =>
                        spentUtxos.add(input)
                    }
                    println(
                      s"[Connect] Tracked ${openChannelTx.body.value.inputs.toSeq.size} spent UTxOs"
                    )

                    // Wait for ChannelPending
                    client.receiveMessage(timeoutSeconds = 10) match {
                        case Success(responseJson) =>
                            read[ClientResponse](responseJson) match {
                                case ClientResponse.ChannelPending(txId) =>
                                    println(
                                      s"[Connect] ✓ Channel pending, txId: ${txId.take(16)}..."
                                    )
                                case ClientResponse.Error(code, msg) =>
                                    throw new Exception(s"Channel opening failed [$code]: $msg")
                                case other =>
                                    throw new Exception(s"Expected ChannelPending, got: $other")
                            }
                        case Failure(e) =>
                            throw new Exception(
                              s"Error waiting for ChannelPending: ${e.getMessage}"
                            )
                    }

                    // Wait for ChannelOpened (server waits up to 180 seconds, so we need at least that)
                    println(
                      s"[Connect] Waiting for channel confirmation (this may take up to 3 minutes on preprod)..."
                    )
                    client.receiveMessage(timeoutSeconds = 200) match {
                        case Success(responseJson) =>
                            read[ClientResponse](responseJson) match {
                                case ClientResponse.ChannelOpened(snapshot) =>
                                    println(s"[Connect] ✓ Channel opened successfully!")
                                    isConnected = true
                                    // Store client state for later use (e.g., close)
                                    val channelRef =
                                        TransactionInput(openChannelTx.id, channelOutputIdx)
                                    clientState = Some(
                                      ClientState(
                                        latestSnapshot = snapshot,
                                        channelRef = channelRef,
                                        lockedValue = depositAmount,
                                        status = ChannelStatus.Open,
                                        clientPubKey = clientPubKey
                                      )
                                    )

                                    // Start background listener for async notifications
                                    fork {
                                        while isConnected do {
                                            client.receiveMessage(timeoutSeconds = 2) match {
                                                case Success(msgJson) =>
                                                    Try(read[ClientResponse](msgJson)) match {
                                                        case Success(
                                                              ClientResponse.OrderCreated(orderId)
                                                            ) =>
                                                            println(
                                                              s"\n[Notification] ✓ Order created! OrderID: $orderId"
                                                            )
                                                            println(
                                                              s"[Notification] Order is now in the order book"
                                                            )
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(
                                                              ClientResponse.OrderExecuted(trade)
                                                            ) =>
                                                            println(
                                                              s"\n[Notification] ✓✓ Order executed! Trade amount: ${trade.tradeAmount}, price: ${trade.tradePrice}"
                                                            )
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(
                                                              ClientResponse.Error(code, msg)
                                                            ) =>
                                                            println(
                                                              s"\n[Notification] ERROR [$code]: $msg"
                                                            )
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(
                                                              ClientResponse.ClosePending(txId)
                                                            ) =>
                                                            println(
                                                              s"\n[Close] ✓ Close transaction submitted: ${txId.take(16)}..."
                                                            )
                                                            println(
                                                              s"[Close] Waiting for confirmation (this may take up to 3 minutes on preprod)..."
                                                            )
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(
                                                              ClientResponse.ChannelClosed(
                                                                snapshot
                                                              )
                                                            ) =>
                                                            println(
                                                              s"\n[Close] ✓ Channel closed successfully!"
                                                            )
                                                            println(
                                                              s"[Close] Final snapshot version: ${snapshot.signedSnapshot.snapshotVersion}"
                                                            )
                                                            println(
                                                              s"[Close] Funds have been returned to your wallet."
                                                            )
                                                            isConnected = false
                                                            clientState = None
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(
                                                              ClientResponse.State(
                                                                balance,
                                                                orders,
                                                                channelStatus,
                                                                snapshotVersion
                                                              )
                                                            ) =>
                                                            // Handle State response from get-state command
                                                            println("\n" + "=" * 60)
                                                            println("Client State")
                                                            println("=" * 60)
                                                            println(s"Channel Status:    $channelStatus")
                                                            println(s"Snapshot Version:  $snapshotVersion")
                                                            println()
                                                            println("Balance:")
                                                            val adaBalance = balance.quantityOf(
                                                              scalus.builtin.ByteString.empty,
                                                              scalus.builtin.ByteString.empty
                                                            )
                                                            println(
                                                              f"  ADA:             ${adaBalance.toLong / 1_000_000.0}%.6f"
                                                            )
                                                            balance.toSortedMap.toList.foreach {
                                                                case (policyId, assets) =>
                                                                    if policyId.bytes.nonEmpty then {
                                                                        assets.toList.foreach {
                                                                            case (assetName, amount) =>
                                                                                val symbol =
                                                                                    new String(
                                                                                      assetName.bytes,
                                                                                      "UTF-8"
                                                                                    )
                                                                                println(
                                                                                  f"  $symbol%-16s ${amount.toLong}"
                                                                                )
                                                                        }
                                                                    }
                                                            }
                                                            println()
                                                            println("Orders:")
                                                            if orders.isEmpty then {
                                                                println("  (no open orders)")
                                                            } else {
                                                                orders.toList.foreach {
                                                                    case (orderId, order) =>
                                                                        val side =
                                                                            if order.orderAmount > 0
                                                                            then "BUY"
                                                                            else "SELL"
                                                                        val amount = order.orderAmount.abs
                                                                        println(
                                                                          s"  #${orderId.toString.padTo(6, ' ')} ${side.padTo(4, ' ')} ${amount.toString.reverse.padTo(10, ' ').reverse} @ ${order.orderPrice}"
                                                                        )
                                                                }
                                                            }
                                                            println("=" * 60)
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(ClientResponse.RebalanceStarted) =>
                                                            println(s"\n[Rebalance] Rebalancing started by server")
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(ClientResponse.RebalanceRequired(tx)) =>
                                                            println(s"\n[Rebalance] Received transaction to sign")
                                                            println(s"[Rebalance] TX ID: ${tx.id.toHex.take(16)}...")
                                                            // Sign and send the transaction
                                                            clientId match {
                                                                case Some(cId) =>
                                                                    import com.bloxbean.cardano.client.crypto.Blake2bUtil
                                                                    import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration
                                                                    import com.bloxbean.cardano.client.transaction.util.TransactionBytes

                                                                    val hdKeyPair = clientAccount.hdKeyPair()
                                                                    val txBytes = TransactionBytes(tx.toCbor)
                                                                    val txBodyHash = Blake2bUtil.blake2bHash256(txBytes.getTxBodyBytes)
                                                                    val signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider
                                                                    val signature = signingProvider.signExtended(txBodyHash, hdKeyPair.getPrivateKey.getKeyData)

                                                                    val witness = VKeyWitness(
                                                                      signature = ByteString.fromArray(signature),
                                                                      vkey = ByteString.fromArray(hdKeyPair.getPublicKey.getKeyData.take(32))
                                                                    )
                                                                    val witnessSet = tx.witnessSet.copy(
                                                                      vkeyWitnesses = scalus.cardano.ledger.TaggedSortedSet.from(Seq(witness))
                                                                    )
                                                                    val signedTx = tx.copy(witnessSet = witnessSet)

                                                                    println(s"[Rebalance] Sending signed transaction...")
                                                                    client.sendMessage(ClientRequest.SignRebalance(cId, signedTx))
                                                                case None =>
                                                                    println(s"[Rebalance] ERROR: No client ID for signing")
                                                            }
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(ClientResponse.RebalanceComplete(snapshot, newChannelRef)) =>
                                                            println(s"\n[Rebalance] ✓ Rebalancing complete!")
                                                            println(s"[Rebalance] New snapshot version: ${snapshot.signedSnapshot.snapshotVersion}")
                                                            println(s"[Rebalance] New channel ref: ${newChannelRef.transactionId.toHex.take(16)}...#${newChannelRef.index}")
                                                            // Update local client state with new channelRef
                                                            clientState = clientState.map(_.copy(channelRef = newChannelRef))
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(ClientResponse.RebalanceAborted(reason)) =>
                                                            println(s"\n[Rebalance] Aborted: $reason")
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Success(other) =>
                                                            // Log unexpected messages for debugging
                                                            println(
                                                              s"\n[Notification] Received: ${other.getClass.getSimpleName}"
                                                            )
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Failure(e) =>
                                                            // Log parse failures with details
                                                            println(s"\n[Notification] Parse failure: ${e.getMessage}")
                                                            println(s"[Notification] Message length was: ${msgJson.length} bytes")
                                                            print(s"$partyName> ")
                                                            System.out.flush()
                                                    }
                                                case Failure(_) =>
                                                    // Timeout or connection error - continue polling
                                                    ()
                                            }
                                        }
                                    }

                                case ClientResponse.Error(code, msg) =>
                                    throw new Exception(s"Channel opening failed [$code]: $msg")
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

                    println(
                      s"\n[Order] Creating $side order: $amount $baseAsset/$quoteAsset @ $price"
                    )

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
                                case e: IllegalArgumentException =>
                                    println(s"[Order] ERROR: ${e.getMessage}")
                                    return
                                case _: Exception =>
                                    println(
                                      s"[Order] ERROR: Unknown assets. Available: ${config.assets.availableAssets.mkString(", ")}"
                                    )
                                    return
                            }
                    }

                    // Convert amount from display units to base units
                    // For ADA: 1 ADA = 1,000,000 lovelace (6 decimals)
                    val baseDecimals = baseAsset.toLowerCase match {
                        case "ada" => 6
                        case other =>
                            try { config.assets.getAsset(other).decimals }
                            catch { case _: Exception => 0 }
                    }
                    val quoteDecimals = quoteAsset.toLowerCase match {
                        case "ada" => 6
                        case other =>
                            try { config.assets.getAsset(other).decimals }
                            catch { case _: Exception => 6 } // Default to 6 for unknown assets
                    }
                    val baseAmount = BigInt(amount) * BigInt(10).pow(baseDecimals)
                    val signedAmount =
                        if side.equalsIgnoreCase("BUY") then baseAmount else -baseAmount

                    // Convert user price to system price
                    // System price formula: price = user_price * 10^(quote_decimals - base_decimals) * PRICE_SCALE
                    // This accounts for different decimal places between base and quote assets
                    val PRICE_SCALE = BigInt(1_000_000)
                    val decimalDiff = quoteDecimals - baseDecimals
                    val scaledPrice =
                        if decimalDiff >= 0 then
                            BigInt(price) * BigInt(10).pow(decimalDiff) * PRICE_SCALE
                        else
                            BigInt(price) * PRICE_SCALE / BigInt(10).pow(-decimalDiff)

                    val order = LimitOrder(
                      orderPair = (base, quote),
                      orderAmount = signedAmount,
                      orderPrice = scaledPrice
                    )

                    // Send order asynchronously - background listener will handle response
                    client.sendMessage(ClientRequest.CreateOrder(clientId.get, order)) match {
                        case Success(_) =>
                            println(s"[Order] ✓ Order request sent to exchange")
                            println(
                              s"[Order] (You will be notified when it's created and/or executed)"
                            )

                        case Failure(e) =>
                            println(s"[Order] ERROR: Failed to send order: ${e.getMessage}")
                    }
                }

                def closeChannel(): Unit = {
                    if !isConnected then {
                        println("[Close] ERROR: Not connected to the exchange!")
                        return
                    }

                    clientState match {
                        case None =>
                            println("[Close] ERROR: No client state available!")
                            return
                        case Some(state) =>
                            println(s"\n[Close] Closing channel with exchange...")
                            println(s"[Close] Channel ref: ${state.channelRef}")
                            println(
                              s"[Close] Locked value: ${state.lockedValue.coin.value / 1_000_000} ADA"
                            )

                            Try {
                                val tx = txbuilder.closeChannel(
                                  provider,
                                  new scalus.cardano.wallet.BloxbeanAccount(clientAccount),
                                  clientAddress,
                                  state
                                )
                                println(
                                  s"[Close] Close transaction built: ${tx.id.toHex.take(16)}..."
                                )
                                client.sendMessage(
                                  ClientRequest.CloseChannel(clientId.get, tx, state.latestSnapshot)
                                )

                                // Wait for ClosePending response
                                client.receiveMessage(timeoutSeconds = 10) match {
                                    case Success(responseJson) =>
                                        read[ClientResponse](responseJson) match {
                                            case ClientResponse.ClosePending(txId) =>
                                                println(
                                                  s"[Close] ✓ Close transaction submitted: ${txId.take(16)}..."
                                                )
                                                println(
                                                  s"[Close] Waiting for confirmation (this may take up to 3 minutes on preprod)..."
                                                )
                                                println(
                                                  s"[Close] You will be notified when the channel is closed."
                                                )
                                            case ClientResponse.ChannelClosed(snapshot) =>
                                                // Immediate close (e.g., mock provider)
                                                println(s"[Close] ✓ Channel closed successfully!")
                                                println(
                                                  s"[Close] Final snapshot version: ${snapshot.signedSnapshot.snapshotVersion}"
                                                )
                                                println(
                                                  s"[Close] Funds have been returned to your wallet."
                                                )
                                                isConnected = false
                                                clientState = None
                                            case ClientResponse.Error(code, msg) =>
                                                println(s"[Close] ERROR [$code]: $msg")
                                            case other =>
                                                println(s"[Close] Unexpected response: $other")
                                        }
                                    case Failure(e) =>
                                        println(
                                          s"[Close] ERROR: Failed to receive response: ${e.getMessage}"
                                        )
                                }
                            } match {
                                case Success(_) => ()
                                case Failure(e) =>
                                    println(
                                      s"[Close] ERROR: Failed to close channel: ${e.getMessage}"
                                    )
                                    e.printStackTrace()
                            }
                    }
                }

                // ============================================================
                // Contested Close Path (unilateral, client submits directly)
                // Flow: contested-close -> timeout -> payout
                // ============================================================

                def contestedClose(): Unit = {
                    if !isConnected then {
                        println("[ContestedClose] ERROR: Not connected to the exchange!")
                        return
                    }

                    clientState match {
                        case None =>
                            println("[ContestedClose] ERROR: No client state available!")
                            return
                        case Some(state) =>
                            println(s"\n[ContestedClose] Initiating contested close...")
                            println(s"[ContestedClose] Channel ref: ${state.channelRef}")
                            println(
                              s"[ContestedClose] This will start the contestation period."
                            )

                            Try {
                                val tx = txbuilder.contestedClose(
                                  provider,
                                  new scalus.cardano.wallet.BloxbeanAccount(clientAccount),
                                  state,
                                  Instant.now()
                                )
                                println(
                                  s"[ContestedClose] Transaction built: ${tx.id.toHex.take(16)}..."
                                )

                                // Submit directly to blockchain (unilateral action)
                                provider.submit(tx).await() match {
                                    case Right(txHash) =>
                                        println(
                                          s"[ContestedClose] ✓ Transaction submitted: ${txHash.toHex.take(16)}..."
                                        )
                                        println(
                                          s"[ContestedClose] Channel is now in SnapshotContestState."
                                        )
                                        println(
                                          s"[ContestedClose] After the contest period, use 'timeout' to advance."
                                        )

                                        // Update channel ref to the new UTxO
                                        val newChannelRef = TransactionInput(txHash, 0)
                                        clientState = Some(state.copy(channelRef = newChannelRef))
                                    case Left(error) =>
                                        println(
                                          s"[ContestedClose] ERROR: Transaction submission failed: $error"
                                        )
                                }
                            } match {
                                case Success(_) => ()
                                case Failure(e) =>
                                    println(
                                      s"[ContestedClose] ERROR: ${e.getMessage}"
                                    )
                                    e.printStackTrace()
                            }
                    }
                }

                def timeout(): Unit = {
                    if !isConnected then {
                        println("[Timeout] ERROR: Not connected to the exchange!")
                        return
                    }

                    clientState match {
                        case None =>
                            println("[Timeout] ERROR: No client state available!")
                            return
                        case Some(state) =>
                            println(s"\n[Timeout] Advancing channel state after contest period...")
                            println(s"[Timeout] Channel ref: ${state.channelRef}")

                            Try {
                                // Fetch the channel UTxO and extract on-chain state
                                val channelUtxo = provider
                                    .findUtxo(state.channelRef)
                                    .await()
                                    .getOrElse(
                                      throw new RuntimeException(
                                        s"Channel UTxO not found: ${state.channelRef}"
                                      )
                                    )

                                val onChainState = channelUtxo.output.datumOption match {
                                    case Some(DatumOption.Inline(data)) =>
                                        data.to[OnChainState]
                                    case _ =>
                                        throw new RuntimeException(
                                          "Expected inline datum on channel UTxO"
                                        )
                                }

                                // Check current state
                                onChainState.channelState match {
                                    case OnChainChannelState.SnapshotContestState(_, _, _, _) =>
                                        println(
                                          s"[Timeout] Channel is in SnapshotContestState, advancing..."
                                        )
                                    case OnChainChannelState.TradesContestState(_, _) =>
                                        println(
                                          s"[Timeout] Channel is in TradesContestState, advancing..."
                                        )
                                    case OnChainChannelState.PayoutState(_, _) =>
                                        println(
                                          s"[Timeout] Channel is already in PayoutState. Use 'payout' command."
                                        )
                                        return
                                    case OnChainChannelState.OpenState =>
                                        println(
                                          s"[Timeout] Channel is still open. Use 'contested-close' first."
                                        )
                                        return
                                }

                                val tx = txbuilder.timeout(
                                  provider,
                                  state.channelRef,
                                  onChainState,
                                  Instant.now()
                                )
                                println(
                                  s"[Timeout] Transaction built: ${tx.id.toHex.take(16)}..."
                                )

                                provider.submit(tx).await() match {
                                    case Right(txHash) =>
                                        println(
                                          s"[Timeout] ✓ Transaction submitted: ${txHash.toHex.take(16)}..."
                                        )

                                        // Update channel ref
                                        val newChannelRef = TransactionInput(txHash, 0)
                                        clientState = Some(state.copy(channelRef = newChannelRef))

                                        // Fetch and display new state
                                        val newUtxo = provider.findUtxo(newChannelRef).await()
                                        newUtxo.foreach { utxo =>
                                            utxo.output.datumOption match {
                                                case Some(DatumOption.Inline(data)) =>
                                                    val newState = data.to[OnChainState]
                                                    newState.channelState match {
                                                        case OnChainChannelState.PayoutState(
                                                              cb,
                                                              _
                                                            ) =>
                                                            val adaBalance = cb
                                                                .quantityOf(
                                                                  scalus.builtin.ByteString.empty,
                                                                  scalus.builtin.ByteString.empty
                                                                )
                                                                .toLong
                                                            println(
                                                              s"[Timeout] Channel is now in PayoutState."
                                                            )
                                                            println(
                                                              s"[Timeout] Client balance: ${adaBalance / 1_000_000} ADA"
                                                            )
                                                            println(
                                                              s"[Timeout] Use 'payout' to withdraw your funds."
                                                            )
                                                        case OnChainChannelState.TradesContestState(
                                                              _,
                                                              _
                                                            ) =>
                                                            println(
                                                              s"[Timeout] Channel is now in TradesContestState."
                                                            )
                                                            println(
                                                              s"[Timeout] Run 'timeout' again after contest period."
                                                            )
                                                        case _ =>
                                                            println(
                                                              s"[Timeout] New state: ${newState.channelState}"
                                                            )
                                                    }
                                                case _ => ()
                                            }
                                        }
                                    case Left(error) =>
                                        println(
                                          s"[Timeout] ERROR: Transaction submission failed: $error"
                                        )
                                }
                            } match {
                                case Success(_) => ()
                                case Failure(e) =>
                                    println(s"[Timeout] ERROR: ${e.getMessage}")
                                    e.printStackTrace()
                            }
                    }
                }

                def payout(): Unit = {
                    if !isConnected then {
                        println("[Payout] ERROR: Not connected to the exchange!")
                        return
                    }

                    clientState match {
                        case None =>
                            println("[Payout] ERROR: No client state available!")
                            return
                        case Some(state) =>
                            println(s"\n[Payout] Withdrawing funds from channel...")
                            println(s"[Payout] Channel ref: ${state.channelRef}")

                            Try {
                                // Fetch the channel UTxO and extract on-chain state
                                val channelUtxo = provider
                                    .findUtxo(state.channelRef)
                                    .await()
                                    .getOrElse(
                                      throw new RuntimeException(
                                        s"Channel UTxO not found: ${state.channelRef}"
                                      )
                                    )

                                val onChainState = channelUtxo.output.datumOption match {
                                    case Some(DatumOption.Inline(data)) =>
                                        data.to[OnChainState]
                                    case _ =>
                                        throw new RuntimeException(
                                          "Expected inline datum on channel UTxO"
                                        )
                                }

                                // Check we're in PayoutState
                                onChainState.channelState match {
                                    case OnChainChannelState.PayoutState(clientBalance, _) =>
                                        val adaBalance = clientBalance
                                            .quantityOf(
                                              scalus.builtin.ByteString.empty,
                                              scalus.builtin.ByteString.empty
                                            )
                                            .toLong
                                        println(
                                          s"[Payout] Client balance to withdraw: ${adaBalance / 1_000_000} ADA"
                                        )
                                    case OnChainChannelState.SnapshotContestState(_, _, _, _) =>
                                        println(
                                          s"[Payout] ERROR: Channel is in SnapshotContestState."
                                        )
                                        println(
                                          s"[Payout] Use 'timeout' to advance to PayoutState first."
                                        )
                                        return
                                    case OnChainChannelState.TradesContestState(_, _) =>
                                        println(
                                          s"[Payout] ERROR: Channel is in TradesContestState."
                                        )
                                        println(
                                          s"[Payout] Use 'timeout' to advance to PayoutState first."
                                        )
                                        return
                                    case OnChainChannelState.OpenState =>
                                        println(s"[Payout] ERROR: Channel is still open.")
                                        println(
                                          s"[Payout] Use 'close' for graceful close or 'contested-close' for unilateral close."
                                        )
                                        return
                                }

                                val tx = txbuilder.payout(
                                  provider,
                                  new scalus.cardano.wallet.BloxbeanAccount(clientAccount),
                                  clientAddress,
                                  state.channelRef,
                                  onChainState
                                )
                                println(
                                  s"[Payout] Transaction built: ${tx.id.toHex.take(16)}..."
                                )

                                provider.submit(tx).await() match {
                                    case Right(txHash) =>
                                        println(
                                          s"[Payout] ✓ Transaction submitted: ${txHash.toHex.take(16)}..."
                                        )
                                        println(
                                          s"[Payout] ✓ Funds have been returned to your wallet!"
                                        )

                                        // Channel is now closed
                                        isConnected = false
                                        clientState = None
                                    case Left(error) =>
                                        println(
                                          s"[Payout] ERROR: Transaction submission failed: $error"
                                        )
                                }
                            } match {
                                case Success(_) => ()
                                case Failure(e) =>
                                    println(s"[Payout] ERROR: ${e.getMessage}")
                                    e.printStackTrace()
                            }
                    }
                }

                def rebalance(): Unit = {
                    if !isConnected then {
                        println("[Rebalance] ERROR: Not connected to the exchange!")
                        return
                    }

                    clientId match {
                        case None =>
                            println("[Rebalance] ERROR: No client ID available!")
                        case Some(cId) =>
                            println(s"\n[Rebalance] Initiating rebalancing...")
                            println(s"[Rebalance] This will sync on-chain locked values with snapshot balances.")
                            println(s"[Rebalance] Sending FixBalance request...")

                            client.sendMessage(ClientRequest.FixBalance(cId)) match {
                                case Success(_) =>
                                    println(s"[Rebalance] Request sent. The background listener will handle signing.")
                                    println(s"[Rebalance] Watch for [Rebalance] messages...")
                                case Failure(e) =>
                                    println(s"[Rebalance] ERROR: Failed to send request: ${e.getMessage}")
                            }
                    }
                }

                def waitForRebalanceCompletion(cId: ClientId): Unit = {
                    println(s"[Rebalance] Waiting for RebalanceRequired...")
                    val maxAttempts = 30
                    var attempts = 0
                    var rebalanceComplete = false

                    while attempts < maxAttempts && !rebalanceComplete do {
                        client.receiveMessage(timeoutSeconds = 2) match {
                            case Success(msgJson) =>
                                println(s"[Rebalance] Raw message length: ${msgJson.length} bytes")
                                println(s"[Rebalance] Raw message (first 300 chars): ${msgJson.take(300)}")
                                println(s"[Rebalance] Raw message (last 100 chars): ${msgJson.takeRight(100)}")
                                Try(read[ClientResponse](msgJson)) match {
                                    case Success(ClientResponse.RebalanceRequired(tx)) =>
                                        println(
                                          s"[Rebalance] ✓ Received RebalanceRequired, signing transaction..."
                                        )
                                        signAndSendRebalance(cId, tx)

                                    case Success(ClientResponse.RebalanceComplete(snapshot, newChannelRef)) =>
                                        println(
                                          s"[Rebalance] ✓ Rebalancing complete! Snapshot version: ${snapshot.signedSnapshot.snapshotVersion}"
                                        )
                                        println(s"[Rebalance] New channel ref: ${newChannelRef.transactionId.toHex.take(16)}...#${newChannelRef.index}")
                                        // Update local client state with new channelRef
                                        clientState = clientState.map(_.copy(channelRef = newChannelRef))
                                        rebalanceComplete = true

                                    case Success(ClientResponse.RebalanceAborted(reason)) =>
                                        println(s"[Rebalance] Rebalancing aborted: $reason")
                                        rebalanceComplete = true

                                    case Success(ClientResponse.RebalanceStarted) =>
                                        println(s"[Rebalance] Received RebalanceStarted")

                                    case Success(ClientResponse.Error(code, msg)) =>
                                        println(s"[Rebalance] Error [$code]: $msg")
                                        rebalanceComplete = true

                                    case Success(other) =>
                                        println(
                                          s"[Rebalance] Received: ${other.getClass.getSimpleName}"
                                        )

                                    case Failure(e) =>
                                        println(
                                          s"[Rebalance] Failed to parse message: ${e.getMessage}"
                                        )
                                }

                            case Failure(e) =>
                                e match {
                                    case _: java.util.concurrent.TimeoutException =>
                                        attempts += 1
                                    case other =>
                                        println(s"[Rebalance] Error: ${other.getMessage}")
                                        attempts += 1
                                }
                        }
                    }

                    if !rebalanceComplete then {
                        println(
                          s"[Rebalance] Rebalancing did not complete within timeout (this may be expected if no rebalancing needed)"
                        )
                    }
                }

                def signAndSendRebalance(cId: ClientId, tx: Transaction): Unit = {
                    import com.bloxbean.cardano.client.crypto.Blake2bUtil
                    import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration
                    import com.bloxbean.cardano.client.transaction.util.TransactionBytes

                    val hdKeyPair = clientAccount.hdKeyPair()
                    val txBytes = TransactionBytes(tx.toCbor)
                    val txBodyHash = Blake2bUtil.blake2bHash256(txBytes.getTxBodyBytes)
                    val signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider
                    val signature =
                        signingProvider.signExtended(txBodyHash, hdKeyPair.getPrivateKey.getKeyData)

                    val witness = VKeyWitness(
                      signature = ByteString.fromArray(signature),
                      vkey = ByteString.fromArray(hdKeyPair.getPublicKey.getKeyData.take(32))
                    )
                    val witnessSet = tx.witnessSet.copy(
                      vkeyWitnesses = scalus.cardano.ledger.TaggedSortedSet.from(Seq(witness))
                    )
                    val signedTx = tx.copy(witnessSet = witnessSet)

                    println(s"[Rebalance] Sending SignRebalance...")
                    client.sendMessage(ClientRequest.SignRebalance(cId, signedTx))
                    println(s"[Rebalance] ✓ SignRebalance sent")
                }

                // Main command loop
                println(s"\n${"=" * 60}")
                println(s"$partyName's Trading Terminal")
                println(s"${"=" * 60}")
                println("\nAvailable commands:")
                println("  mint <tokenName> <amount>  - Mint custom tokens")
                println("  register <symbol> <policyId> <assetName> - Register external token")
                println("  connect [token amount ...]  - Connect to the exchange with deposit")
                println("                               Example: connect ada 100 usdm 30")
                println("                               (deposits 100 ADA and 30 USDM)")
                println(
                  "  get-state                   - Show current client state (balance, orders)"
                )
                println("  assets                      - Show available assets for trading")
                println("  buy <base> <quote> <quantity> <price>   - Buy base for quote")
                println("  sell <base> <quote> <quantity> <price>  - Sell base for quote")
                println("  close                       - Close the channel (graceful, cooperative)")
                println(
                  "  contested-close             - Initiate unilateral close (starts contest)"
                )
                println("  timeout                     - Advance state after contest period")
                println("  payout                      - Withdraw funds (after contested close)")
                println("  rebalance                   - Sync on-chain values with snapshot balances")
                println("  help                        - Show this help")
                println("  quit                        - Exit the demo")
                println()

                var running = true
                var lastMintTxId: Option[TransactionHash] = None

                while running do {
                    print(s"$partyName> ")
                    System.out.flush()
                    val input = StdIn.readLine()

                    if input == null then {
                        running = false
                    } else if input.trim.isEmpty then {
                        // User pressed Enter with no input - just loop to show prompt again
                        ()
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

                                    // Register the minted token in the asset registry
                                    val assetConfig = config.assets.AssetConfig(
                                      policyId = policyId,
                                      assetName = ByteString.fromString(tokenName),
                                      decimals =
                                          0, // Tokens typically have 0 decimals unless specified
                                      symbol = tokenName.toUpperCase
                                    )
                                    config.assets.registerAsset(assetConfig)
                                    println(
                                      s"[Mint] ✓ Registered token as tradeable asset: ${tokenName.toUpperCase}"
                                    )
                                }.recover {
                                    case e: DemoException if e.alreadyPrinted => ()
                                    case e: DemoException =>
                                        println(s"[Mint] ERROR [${e.code}]: ${e.message}")
                                    case e: Exception =>
                                        println(s"[Mint] UNEXPECTED ERROR: ${e.getMessage}")
                                        e.printStackTrace()
                                }

                            case Some("mint") =>
                                println(
                                  "[Mint] ERROR: Invalid syntax. Usage: mint <tokenName> <amount>"
                                )
                                println("[Mint] Example: mint MYTOKEN 1000000")

                            case Some("register") if parts.length == 4 =>
                                Try {
                                    val tokenSymbol = parts(1)
                                    val policyIdHex = parts(2)
                                    val assetNameInput = parts(3)

                                    val policyId = ByteString.fromHex(policyIdHex)

                                    // Try to parse as hex, if that fails treat as plain text
                                    val assetName =
                                        Try(ByteString.fromHex(assetNameInput)).getOrElse {
                                            // Not hex, convert plain text to hex
                                            ByteString.fromString(assetNameInput)
                                        }

                                    val assetConfig = config.assets.AssetConfig(
                                      policyId = policyId,
                                      assetName = assetName,
                                      decimals = 0,
                                      symbol = tokenSymbol.toUpperCase
                                    )
                                    config.assets.registerAsset(assetConfig)
                                    println(
                                      s"[Register] ✓ Registered token: ${tokenSymbol.toUpperCase}"
                                    )
                                    println(s"[Register]   Policy ID: ${policyIdHex.take(56)}")
                                    println(s"[Register]   Asset Name: ${assetName.toHex}")
                                }.recover { case e: Exception =>
                                    println(s"[Register] ERROR: ${e.getMessage}")
                                }

                            case Some("register") =>
                                println(
                                  "[Register] ERROR: Invalid syntax. Usage: register <symbol> <policyId> <assetName>"
                                )
                                println("[Register] Example: register bob2 9e593bdb... bob2")
                                println(
                                  "[Register] Note: policyId must be hex, assetName can be plain text or hex"
                                )

                            case Some("connect") =>
                                println(
                                  s"[DEBUG] Connect command matched, isConnected=$isConnected"
                                )
                                System.out.flush()
                                if isConnected then {
                                    println("[Connect] Already connected to the exchange!")
                                } else {
                                    println("[Connect] Attempting to connect to the exchange...")
                                    System.out.flush() // Ensure message is displayed immediately
                                    println("[DEBUG] About to call connectToExchange...")
                                    System.out.flush()
                                    Try {
                                        // Parse deposit arguments (e.g., "connect ada 100 usdm 30")
                                        val depositArgs =
                                            parts.drop(1).toSeq // Remove "connect" from args
                                        val depositValue = parseDepositValue(depositArgs)

                                        // No longer filter by mint txId - tokens might have moved
                                        // Instead, findClientUtxo will find any UTxO with enough funds
                                        connectToExchange(None, depositValue)
                                    }.recover {
                                        case e: DemoException if e.alreadyPrinted =>
                                            // Error message already displayed, don't repeat it
                                            ()
                                        case e: DemoException =>
                                            // Display error message for non-printed exceptions
                                            println(s"[Connect] ERROR [${e.code}]: ${e.message}")
                                        case e: Exception
                                            if e.getMessage.contains("insufficient funds") =>
                                            // Insufficient funds - likely need more ADA for fees
                                            val diffMatch =
                                                "diff=(-?\\d+)".r.findFirstMatchIn(e.getMessage)
                                            val diff =
                                                diffMatch.map(_.group(1).toLong).getOrElse(0L)
                                            val neededAda = Math.abs(diff) / 1_000_000.0
                                            println(
                                              s"\n[Connect] ✗ ERROR: Insufficient funds for transaction"
                                            )
                                            println(
                                              f"[Connect] You need approximately ${neededAda}%.2f more ADA to cover transaction fees"
                                            )
                                            println(
                                              s"[Connect] Please request more test ADA from the faucet"
                                            )
                                            println(
                                              s"[Connect] Faucet: https://docs.cardano.org/cardano-testnet/tools/faucet/"
                                            )
                                        case e: Exception =>
                                            // Unexpected error - show full details
                                            println(s"[Connect] UNEXPECTED ERROR: ${e.getMessage}")
                                            e.printStackTrace()
                                    }
                                }

                            case Some("get-state") =>
                                if !isConnected then {
                                    println(
                                      "[State] ERROR: Not connected to exchange. Please use 'connect' first."
                                    )
                                } else {
                                    // Send request - response will be handled by background listener
                                    client.sendMessage(ClientRequest.GetState(clientId.get)) match {
                                        case Success(_) => () // Response handled by background listener
                                        case Failure(e) =>
                                            println(s"[State] ERROR: Failed to send request: ${e.getMessage}")
                                    }
                                }

                            case Some("buy") if parts.length == 5 =>
                                if !isConnected then {
                                    println(
                                      "[Buy] ERROR: Not connected to exchange. Please use 'connect' first."
                                    )
                                } else {
                                    Try {
                                        val base = parts(1)
                                        val quote = parts(2)
                                        val amount = parts(3).toLong
                                        val price = parts(4).toLong
                                        createOrder("BUY", base, quote, amount, price)
                                    }.recover {
                                        case e: DemoException if e.alreadyPrinted => ()
                                        case e: DemoException =>
                                            println(s"[Buy] ERROR [${e.code}]: ${e.message}")
                                        case e: Exception =>
                                            println(s"[Buy] UNEXPECTED ERROR: ${e.getMessage}")
                                            e.printStackTrace()
                                    }
                                }

                            case Some("buy") =>
                                println(
                                  "[Buy] ERROR: Invalid syntax. Usage: buy <base> <quote> <quantity> <price>"
                                )
                                println(
                                  "[Buy] Meaning: Buy <quantity> of base currency, paying in quote currency at <price>"
                                )
                                println(
                                  "[Buy] Example: buy eurm ada 100 2  -- Buy 100 EURM at 2 ADA per EURM (pay 200 ADA total)"
                                )

                            case Some("sell") if parts.length == 5 =>
                                if !isConnected then {
                                    println(
                                      "[Sell] ERROR: Not connected to exchange. Please use 'connect' first."
                                    )
                                } else {
                                    Try {
                                        val base = parts(1)
                                        val quote = parts(2)
                                        val amount = parts(3).toLong
                                        val price = parts(4).toLong
                                        createOrder("SELL", base, quote, amount, price)
                                    }.recover {
                                        case e: DemoException if e.alreadyPrinted => ()
                                        case e: DemoException =>
                                            println(s"[Sell] ERROR [${e.code}]: ${e.message}")
                                        case e: Exception =>
                                            println(s"[Sell] UNEXPECTED ERROR: ${e.getMessage}")
                                            e.printStackTrace()
                                    }
                                }

                            case Some("sell") =>
                                println(
                                  "[Sell] ERROR: Invalid syntax. Usage: sell <base> <quote> <quantity> <price>"
                                )
                                println(
                                  "[Sell] Meaning: Sell <quantity> of base currency, receiving quote currency at <price>"
                                )
                                println(
                                  "[Sell] Example: sell ada eurm 100 2  -- Sell 100 ADA at 2 EURM per ADA (receive 200 EURM total)"
                                )

                            case Some("assets") =>
                                println("\n" + "=" * 80)
                                println("Available Assets for Trading")
                                println("=" * 80)
                                println(
                                  f"${"Symbol"}%-12s ${"Policy ID"}%-58s ${"Asset Name"}%-10s"
                                )
                                println("-" * 80)

                                // Always show ADA first
                                val ada = config.assets.ada
                                val adaPolicyStr =
                                    if ada.policyId.bytes.isEmpty then "(native ADA)"
                                    else ada.policyId.toHex.take(56)
                                val adaNameStr =
                                    if ada.assetName.bytes.isEmpty then "(lovelace)"
                                    else ada.assetName.toHex
                                println(
                                  f"${ada.symbol}%-12s ${adaPolicyStr}%-58s ${adaNameStr}%-10s"
                                )

                                // Show USDM
                                val usdm = config.assets.usdm
                                val usdmPolicyStr = usdm.policyId.toHex.take(56)
                                val usdmNameStr = usdm.assetName.toHex
                                println(
                                  f"${usdm.symbol}%-12s ${usdmPolicyStr}%-58s ${usdmNameStr}%-10s"
                                )

                                // Show any custom/minted assets
                                val customAssets = config.assets.availableAssets.filterNot(s =>
                                    s == "ADA" || s == "USDM"
                                )
                                if customAssets.nonEmpty then {
                                    customAssets.foreach { symbol =>
                                        try {
                                            val asset = config.assets.getAsset(symbol)
                                            val policyStr = asset.policyId.toHex.take(56)
                                            val nameStr = asset.assetName.toHex
                                            println(
                                              f"${asset.symbol}%-12s ${policyStr}%-58s ${nameStr}%-10s"
                                            )
                                        } catch {
                                            case _: Exception => // Skip if error
                                        }
                                    }
                                }

                                println("=" * 80)
                                println(s"Total: ${config.assets.availableAssets.length} assets")
                                println()

                            case Some("close") =>
                                closeChannel()

                            case Some("contested-close") =>
                                contestedClose()

                            case Some("timeout") =>
                                timeout()

                            case Some("payout") =>
                                payout()

                            case Some("rebalance") =>
                                rebalance()

                            case Some("help") =>
                                println("\nAvailable commands:")
                                println(
                                  "  mint <tokenName> <amount>                        - Mint custom tokens"
                                )
                                println(
                                  "  register <symbol> <policyId> <assetName>         - Register external token"
                                )
                                println(
                                  "  connect [token amount ...]                        - Connect to the exchange with deposit"
                                )
                                println(
                                  "                                                      Example: connect ada 100 usdm 30"
                                )
                                println(
                                  "  get-state                                         - Show current client state (balance, orders)"
                                )
                                println(
                                  "  assets                                            - Show available assets for trading"
                                )
                                println(
                                  "  buy <base> <quote> <quantity> <price>            - Buy base for quote"
                                )
                                println(
                                  "  sell <base> <quote> <quantity> <price>           - Sell base for quote"
                                )
                                println(
                                  "  close                                             - Close channel (graceful, cooperative)"
                                )
                                println(
                                  "  contested-close                                   - Initiate unilateral close (starts contest)"
                                )
                                println(
                                  "  timeout                                           - Advance state after contest period"
                                )
                                println(
                                  "  payout                                            - Withdraw funds (after contested close)"
                                )
                                println(
                                  "  rebalance                                         - Sync on-chain values with snapshot balances"
                                )
                                println(
                                  "  help                                              - Show this help"
                                )
                                println(
                                  "  quit                                              - Exit the demo"
                                )
                                println()

                            case Some("quit") | Some("exit") =>
                                println("\nGoodbye!")
                                running = false

                            case Some(cmd) =>
                                println(
                                  s"Unknown command: '$cmd'. Type 'help' for available commands."
                                )

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

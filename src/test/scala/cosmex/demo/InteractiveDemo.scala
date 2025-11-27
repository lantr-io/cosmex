package cosmex.demo

import cosmex.config.DemoConfig
import cosmex.ws.{CosmexWebSocketServer, SimpleWebSocketClient}
import cosmex.DemoHelpers.*
import cosmex.{CardanoInfoTestNet, ChannelStatus, ClientId, ClientRequest, ClientResponse, ClientState, CosmexTransactions, LimitOrder, Server, SignedSnapshot}
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
                            import scalus.testing.kit.MockLedgerApi
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
                            val clientBech32 =
                                clientAddress.asInstanceOf[ShelleyAddress].toBech32.get
                            val clientFunding = clientInitialValue.coin.value + 100_000_000L

                            println(
                              s"\n[Setup] Funding $partyName with ${clientFunding / 1_000_000} ADA"
                            )

                            config.createProviderWithFunding(Seq((clientBech32, clientFunding)))

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

                // Helper to find client's UTxO
                // Selects the minimum UTxO that has at least requiredAmount (to avoid fragmentation)
                def findClientUtxo(
                    txIdFilter: Option[TransactionHash] = None,
                    requiredAmount: Long
                ): Utxo = {
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
                            val addressBech32 =
                                clientAddress.asInstanceOf[ShelleyAddress].toBech32.get

                            println(s"[$partyName] Looking for UTxO at address: $addressBech32")

                            try {
                                // Query all UTxOs and filter out ones we've already spent
                                provider.findUtxos(
                                  address = clientAddress,
                                  transactionId = txIdFilter,
                                  datum = None,
                                  minAmount = None,
                                  minRequiredTotalAmount = None
                                ) match {
                                    case Right(utxos) =>
                                        // Filter: 1) not spent, 2) has enough ADA
                                        val available = utxos.filter { case (input, output) =>
                                            !spentUtxos.contains(input) &&
                                            output.value.coin.value >= requiredAmount
                                        }

                                        if available.isEmpty then {
                                            Left(
                                              new RuntimeException(
                                                s"No available UTxOs (all are spent or below ${requiredAmount / 1_000_000} ADA)"
                                              )
                                            )
                                        } else {
                                            // Select the MINIMUM UTxO that's large enough (avoid fragmentation)
                                            val (input, output) = available.minBy {
                                                case (_, output) =>
                                                    output.value.coin.value
                                            }
                                            val utxo = Utxo(input, output)
                                            println(
                                              s"[$partyName] ✓ Found UTxO with ${utxo.output.value.coin.value / 1_000_000} ADA (min from ${available.size} UTxOs >= ${requiredAmount / 1_000_000} ADA)"
                                            )
                                            if spentUtxos.nonEmpty then {
                                                println(
                                                  s"[$partyName]   (filtered out ${spentUtxos.size} spent UTxOs)"
                                                )
                                            }
                                            Right(utxo)
                                        }
                                    case Left(err) => Left(err)
                                } match {
                                    case Right(utxo) => utxo
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
                            provider.findUtxos(
                              address = clientAddress,
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
                    provider.submit(signedMintTx) match {
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

                    println(
                      s"[DEBUG] About to call findClientUtxo (need at least ${requiredAmount / 1_000_000} ADA)..."
                    )
                    System.out.flush()
                    val depositUtxo = findClientUtxo(utxoFilter, requiredAmount)
                    println(s"[DEBUG] findClientUtxo returned successfully")
                    System.out.flush()

                    // Deposit configured amount into the channel
                    val totalAvailable = depositUtxo.output.value.coin.value

                    // Verify we have enough ADA
                    if totalAvailable < depositAmountLovelace + feeReserve then {
                        throw new Exception(
                          s"Insufficient funds: have ${totalAvailable / 1_000_000} ADA, need ${(depositAmountLovelace + feeReserve) / 1_000_000} ADA (${depositAmountLovelace / 1_000_000} ADA deposit + ${feeReserve / 1_000_000} ADA for fees/change)"
                        )
                    }

                    // Use the deposit value provided (may include multiple tokens)
                    val depositAmount = depositValue

                    // Display deposit info
                    val tokenInfo =
                        if depositAmount == Value.lovelace(depositAmount.coin.value) then ""
                        else s" + tokens"
                    println(
                      s"[Connect] Depositing: ${depositAmountLovelace / 1_000_000} ADA$tokenInfo (keeping ${(totalAvailable - depositAmountLovelace) / 1_000_000} ADA in wallet for fees/change)"
                    )

                    println(s"[DEBUG] Building openChannel transaction...")
                    println(s"[DEBUG] Deposit amount: ${depositAmount.coin.value / 1_000_000} ADA")
                    println(
                      s"[DEBUG] Client-side script hash: ${txbuilder.script.scriptHash.toHex}"
                    )
                    val unsignedTx = txbuilder.openChannel(
                      clientInput = depositUtxo,
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

                    // Create client ID and connect
                    val cId = ClientId(depositUtxo.input)
                    val wsUrl =
                        s"ws://localhost:$port/ws/${cId.txOutRef.transactionId.toHex}/${cId.txOutRef.index}"

                    client = SimpleWebSocketClient(wsUrl)
                    clientId = Some(cId)

                    // Create and sign initial snapshot
                    val clientTxOutRef =
                        TxOutRef(scalus.ledger.api.v3.TxId(openChannelTx.id), channelOutputIdx)
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

                                                        case Success(other) =>
                                                            // Log unexpected messages for debugging
                                                            println(
                                                              s"\n[Notification] Received: ${other.getClass.getSimpleName}"
                                                            )
                                                            print(s"$partyName> ")
                                                            System.out.flush()

                                                        case Failure(_) =>
                                                            // Ignore malformed messages
                                                            ()
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

                    val signedAmount =
                        if side.equalsIgnoreCase("BUY") then BigInt(amount) else BigInt(-amount)
                    val order = LimitOrder(
                      orderPair = (base, quote),
                      orderAmount = signedAmount,
                      orderPrice = price
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
                                println(s"[Close] Close request sent to exchange")
                            } match {
                                case Success(_) => ()
                                case Failure(e) =>
                                    println(
                                      s"[Close] ERROR: Failed to build close transaction: ${e.getMessage}"
                                    )
                                    e.printStackTrace()
                            }
                    }
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
                println("  close                       - Close the channel and withdraw funds")
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
                                        connectToExchange(lastMintTxId, depositValue)
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
                                    Try {
                                        client.sendMessage(ClientRequest.GetState(clientId.get))
                                        client.receiveMessage(timeoutSeconds = 10) match {
                                            case Success(responseJson) =>
                                                read[ClientResponse](responseJson) match {
                                                    case ClientResponse.State(
                                                          balance,
                                                          orders,
                                                          channelStatus,
                                                          snapshotVersion
                                                        ) =>
                                                        println("\n" + "=" * 60)
                                                        println("Client State")
                                                        println("=" * 60)
                                                        println(
                                                          s"Channel Status:    ${channelStatus}"
                                                        )
                                                        println(
                                                          s"Snapshot Version:  ${snapshotVersion}"
                                                        )
                                                        println()
                                                        println("Balance:")
                                                        // Display ADA balance
                                                        val adaBalance = balance.quantityOf(
                                                          scalus.builtin.ByteString.empty,
                                                          scalus.builtin.ByteString.empty
                                                        )
                                                        println(
                                                          f"  ADA:             ${adaBalance.toLong / 1_000_000.0}%.6f"
                                                        )
                                                        // Display other token balances from the Value map
                                                        balance.toSortedMap.toList.foreach {
                                                            case (policyId, assets) =>
                                                                if policyId.bytes.nonEmpty then {
                                                                    assets.toList.foreach {
                                                                        case (assetName, amount) =>
                                                                            // Try to find symbol and decimals from registered assets
                                                                            val (symbol, decimals) =
                                                                                try {
                                                                                    config.assets
                                                                                        .getAssetByPolicyId(
                                                                                          policyId
                                                                                        )
                                                                                        .map(a =>
                                                                                            (
                                                                                              a.symbol,
                                                                                              a.decimals
                                                                                            )
                                                                                        )
                                                                                        .getOrElse(
                                                                                          (
                                                                                            assetName.toHex
                                                                                                .take(
                                                                                                  16
                                                                                                ),
                                                                                            0
                                                                                          )
                                                                                        )
                                                                                } catch {
                                                                                    case _: Exception =>
                                                                                        (
                                                                                          assetName.toHex
                                                                                              .take(
                                                                                                16
                                                                                              ),
                                                                                          0
                                                                                        )
                                                                                }
                                                                            val displayAmount =
                                                                                if decimals > 0
                                                                                then
                                                                                    amount.toLong / Math
                                                                                        .pow(
                                                                                          10,
                                                                                          decimals
                                                                                        )
                                                                                else amount.toDouble
                                                                            val formatStr =
                                                                                s"  %-16s %.${decimals}f"
                                                                            println(
                                                                              formatStr.format(
                                                                                symbol,
                                                                                displayAmount
                                                                              )
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
                                                                    val amount =
                                                                        order.orderAmount.abs
                                                                    println(
                                                                      f"  #${orderId}%-6s ${side}%-4s ${amount}%10d @ ${order.orderPrice}"
                                                                    )
                                                            }
                                                        }
                                                        println("=" * 60)

                                                    case ClientResponse.Error(code, msg) =>
                                                        println(
                                                          s"[State] ERROR [$code]: $msg"
                                                        )
                                                    case other =>
                                                        println(
                                                          s"[State] Unexpected response: $other"
                                                        )
                                                }
                                            case Failure(e) =>
                                                println(
                                                  s"[State] ERROR: Failed to get state: ${e.getMessage}"
                                                )
                                        }
                                    }.recover {
                                        case e: DemoException if e.alreadyPrinted => ()
                                        case e: DemoException =>
                                            println(s"[State] ERROR [${e.code}]: ${e.message}")
                                        case e: Exception =>
                                            println(s"[State] UNEXPECTED ERROR: ${e.getMessage}")
                                            e.printStackTrace()
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
                                  "  close                                             - Close channel and withdraw funds"
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

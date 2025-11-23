package cosmex.ws

import com.monovore.decline.*
import cats.syntax.all.*
import cosmex.*
import cosmex.config.DemoConfig
import ox.*
import scalus.builtin.ByteString
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.testing.kit.MockLedgerApi

/** WebSocket Server that uses provider from test configuration
  *
  * Supports:
  *   - mock: MockLedgerApi with pre-funded Alice and Bob
  *   - yaci-devkit: Docker-based local blockchain with auto-funding
  *   - preprod: Cardano preprod testnet via Blockfrost (requires funded wallets)
  *   - preview: Cardano preview testnet via Blockfrost (requires funded wallets)
  *
  * Usage:
  *   sbt "Test/runMain cosmex.ws.CosmexWebSocketServerFromConfig"
  *   sbt "Test/runMain cosmex.ws.CosmexWebSocketServerFromConfig --port 9090"
  */
object CosmexWebSocketServerFromConfig {

    // CLI options
    private val configFileOpt = Opts
        .option[String](
          "config",
          short = "c",
          help = "Path to configuration file (defaults to application.conf)"
        )
        .orNone

    private val portOpt = Opts
        .option[Int](
          "port",
          short = "p",
          help = "WebSocket server port (overrides config)"
        )
        .orNone

    private val command = com.monovore.decline.Command(
      name = "cosmex-server",
      header = "COSMEX WebSocket Server (from test config)"
    ) {
        (configFileOpt, portOpt).tupled
    }

    def main(args: Array[String]): Unit = {
        command.parse(args) match {
            case Left(help) =>
                System.err.println(help.show)
                sys.exit(1)

            case Right((configFile, portOverride)) =>
                runServer(configFile, portOverride)
        }
    }

    private def runServer(configFile: Option[String], portOverride: Option[Int]): Unit =
        supervised {
            // Load configuration
            val config = configFile match {
                case Some(path) =>
                    println(s"Loading configuration from: $path")
                    DemoConfig.loadFile(path)
                case None =>
                    println("Loading configuration from: application.conf (classpath)")
                    DemoConfig.load()
            }

            // Display configuration summary
            println(config.summary())

            // Create exchange parameters from config
            val exchangeParams = config.exchange.createParams()
            val exchangePrivKey = config.exchange.getPrivateKey()

            val exchangeAccount = config.exchange.createAccount()
            val exchangePubKeyHash = ByteString.fromArray(
              exchangeAccount.hdKeyPair().getPublicKey.getKeyHash
            )

            println("=" * 60)
            println("COSMEX WebSocket Server Starting...")
            println("=" * 60)
            println(s"Exchange PubKeyHash: ${exchangePubKeyHash.toHex}")
            println(s"Network: ${config.network.networkType}")
            println(s"Provider: ${config.blockchain.provider}")

            // Create Alice's and Bob's addresses for funding display
            val alicePubKeyHash = config.alice.getPubKeyHash()
            val aliceAddress = Address(
              config.network.scalusNetwork,
              Credential.KeyHash(AddrKeyHash.fromByteString(alicePubKeyHash))
            )
            val aliceInitialValue = config.alice.getInitialValue()

            val bobPubKeyHash = config.bob.getPubKeyHash()
            val bobAddress = Address(
              config.network.scalusNetwork,
              Credential.KeyHash(AddrKeyHash.fromByteString(bobPubKeyHash))
            )
            val bobInitialValue = config.bob.getInitialValue()

            // Create blockchain provider from configuration (same logic as MultiClientDemoTest)
            val provider = config.blockchain.provider.toLowerCase match {
                case "mock" =>
                    println("[Server] Using MockLedgerApi with pre-funded Alice and Bob")
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
                    println("[Server] Using Yaci DevKit with auto-funding")
                    // Yaci DevKit with initial funding for Alice and Bob
                    import scalus.cardano.address.ShelleyAddress

                    // Get bech32 addresses for funding
                    val aliceBech32 = aliceAddress.asInstanceOf[ShelleyAddress].toBech32.get
                    val bobBech32 = bobAddress.asInstanceOf[ShelleyAddress].toBech32.get

                    // Calculate total funding needed (initial balance + 100 ADA for fees/collateral)
                    // Note: yaci-devkit can only fund ADA, so we only use the coin value
                    val aliceFunding = aliceInitialValue.coin.value + 100_000_000L
                    val bobFunding = bobInitialValue.coin.value + 100_000_000L

                    println(s"[Server] Funding Alice with ${aliceFunding / 1_000_000} ADA")
                    println(s"[Server] Funding Bob with ${bobFunding / 1_000_000} ADA")

                    val initialFunding = Seq(
                      (aliceBech32, aliceFunding),
                      (bobBech32, bobFunding)
                    )

                    config.createProviderWithFunding(initialFunding)

                case provider @ ("preprod" | "preview") =>
                    println(s"[Server] Using $provider network via Blockfrost")
                    // Use Blockfrost provider for preprod/preview
                    // NOTE: Wallets must be funded externally using the faucet
                    println(s"[Server] WARNING: Ensure wallets are funded from the faucet:")
                    import scalus.cardano.address.ShelleyAddress
                    println(s"[Server]   - Alice: ${aliceAddress.asInstanceOf[ShelleyAddress].toBech32.get}")
                    println(s"[Server]   - Bob: ${bobAddress.asInstanceOf[ShelleyAddress].toBech32.get}")
                    config.createProvider()

                case other =>
                    throw new IllegalArgumentException(s"Unsupported provider: $other")
            }

            // Create CardanoInfo with protocol parameters from provider
            // This is essential for Plutus script transactions (minting policies)
            val cardanoInfo = CardanoInfoTestNet.currentNetwork(provider)

            // Create server instance
            val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

            // Determine port (CLI override or config)
            val port = portOverride.getOrElse(config.server.port)

            println("=" * 60)
            println(s"Starting WebSocket server on port $port...")
            println(s"WebSocket endpoint: ws://localhost:$port/ws/{{txId}}/{{txIdx}}")
            println("=" * 60)
            println()

            // Run the WebSocket server (blocks until interrupted)
            CosmexWebSocketServer.run(server, port = port)
        }
}

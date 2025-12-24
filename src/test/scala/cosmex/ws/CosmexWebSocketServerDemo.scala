package cosmex.ws

import com.monovore.decline.*
import cats.syntax.all.*
import cosmex.*
import cosmex.config.DemoConfig
import ox.*
import scalus.builtin.ByteString
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.cardano.node.Emulator

/** Demo main method that uses MockLedgerApi for testing
  *
  * Reads configuration from application.conf and starts WebSocket server
  */
object CosmexWebSocketServerDemo {

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
      header = "COSMEX WebSocket Server Demo"
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

            // Initialize server components
            val cardanoInfo = CardanoInfo.mainnet

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

            // Create context matching the configured network
            val mainnetCtx = Context.testMainnet(slot = 1000)
            val context = config.network.scalusNetwork match {
                case scalus.cardano.address.Network.Mainnet => mainnetCtx
                case scalus.cardano.address.Network.Testnet =>
                    val testnetEnv = mainnetCtx.env.copy(network = scalus.cardano.address.Network.Testnet)
                    new Context(mainnetCtx.fee, testnetEnv, mainnetCtx.slotConfig)
                case other =>
                    throw new IllegalArgumentException(s"Unsupported network: $other")
            }

            // Create mock ledger for testing
            val provider = Emulator(
              initialUtxos = Map.empty,
              initialContext = context,
              validators = Emulator.defaultValidators,
              mutators = Emulator.defaultMutators
            )

            // Create server instance
            val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

            // Determine port (CLI override or config)
            val port = portOverride.getOrElse(config.server.port)

            println(s"Starting WebSocket server on port $port...")
            println(s"WebSocket endpoint: ws://localhost:$port/ws/{{txId}}/{{txIdx}}")
            println("=" * 60)
            println()

            // Run the WebSocket server
            CosmexWebSocketServer.run(server, port = port)
        }
}

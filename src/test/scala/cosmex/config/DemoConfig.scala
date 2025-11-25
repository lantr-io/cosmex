package cosmex.config

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import com.typesafe.config.{Config, ConfigFactory}
import cosmex.{ExchangeParams, Pair}
import scalus.builtin.ByteString
import scalus.cardano.ledger.Value
import scalus.ledger.api.v1.PubKeyHash

import scala.jdk.CollectionConverters.*

/** Configuration for COSMEX demo applications
  *
  * Loads configuration from application.conf (HOCON format)
  */
object DemoConfig {

    /** Load configuration from default resources (application.conf) */
    def load(): DemoConfig = {
        val config = ConfigFactory.load()
        DemoConfig(config)
    }

    /** Load configuration from specific file */
    def loadFile(path: String): DemoConfig = {
        val config = ConfigFactory.parseFile(new java.io.File(path)).resolve()
        DemoConfig(config)
    }

    /** Asset class (PolicyId, AssetName) */
    type AssetClass = (ByteString, ByteString)

    /** Parse hex string to ByteString */
    def parseHex(hex: String): ByteString = {
        if hex.isEmpty then ByteString.empty
        else ByteString.fromHex(hex)
    }
}

/** Type-safe configuration wrapper */
case class DemoConfig(config: Config) {
    import DemoConfig.*

    // Server Configuration
    object server {
        val host: String = config.getString("server.host")
        val port: Int = config.getInt("server.port")
        val websocketBasePath: String = config.getString("server.websocket.basePath")

        def websocketUrl: String = s"ws://$host:$port$websocketBasePath"
    }

    // Network Configuration
    object network {
        val networkType: String = config.getString("network.type")
        val magic: Int = config.getInt("network.magic")
        val slotLength: Int = config.getInt("network.slotLength")

        /** Get Bloxbean Network instance */
        def bloxbeanNetwork: com.bloxbean.cardano.client.common.model.Network =
            networkType.toLowerCase match {
                case "mainnet" => Networks.mainnet()
                case "preview" => Networks.preview()
                case "preprod" => Networks.preprod()
                case "testnet" => Networks.testnet()
                case other => throw new IllegalArgumentException(s"Unknown network type: $other")
            }

        /** Get Scalus Network */
        def scalusNetwork: scalus.cardano.address.Network =
            networkType.toLowerCase match {
                case "mainnet" => scalus.cardano.address.Network.Mainnet
                case _         => scalus.cardano.address.Network.Testnet
            }
    }

    // Blockchain Provider Configuration
    object blockchain {
        // In CI environment, use yaci-devkit for reliable tests
        val provider: String = sys.env.get("CI") match {
            case Some("true") =>
                println("[Config] CI environment detected - using yaci-devkit provider")
                "yaci-devkit"
            case _ =>
                config.getString("blockchain.provider")
        }

        object yaciDevkit {
            val startupTimeout: Int = config.getInt("blockchain.yaciDevkit.startupTimeout")
            val reuseContainer: Boolean = config.getBoolean("blockchain.yaciDevkit.reuseContainer")
            val enableLogs: Boolean = config.getBoolean("blockchain.yaciDevkit.enableLogs")
        }

        object networkProvider {
            val providerType: String = config.getString("blockchain.networkProvider.type")

            object blockfrost {
                val url: String = config.getString("blockchain.networkProvider.blockfrost.url")
                // Project ID from environment variable
                def projectId: Option[String] = sys.env.get("BLOCKFROST_PROJECT_ID")
            }
        }
    }

    // Asset Definitions
    object assets {
        case class AssetConfig(
            policyId: ByteString,
            assetName: ByteString,
            decimals: Int,
            symbol: String
        ) {
            def assetClass: AssetClass = (policyId, assetName)
        }

        lazy val ada: AssetConfig = AssetConfig(
          policyId = ByteString.empty,
          assetName = ByteString.empty,
          decimals = 6,
          symbol = "ADA"
        )

        lazy val usdm: AssetConfig = AssetConfig(
          policyId = parseHex(config.getString("assets.usdm.policyId")),
          assetName = parseHex(config.getString("assets.usdm.assetName")),
          decimals = config.getInt("assets.usdm.decimals"),
          symbol = config.getString("assets.usdm.symbol")
        )

        // Mutable registry for dynamically registered assets (e.g., minted tokens)
        private val customAssets = scala.collection.mutable.Map[String, AssetConfig]()

        /** Register a custom asset (e.g., after minting) */
        def registerAsset(asset: AssetConfig): Unit = {
            customAssets(asset.symbol.toLowerCase) = asset
        }

        /** Get all available asset symbols */
        def availableAssets: List[String] = {
            List("ADA", "USDM") ++ customAssets.keys.map(_.toUpperCase).toList.sorted
        }

        /** Get asset by symbol */
        def getAsset(symbol: String): AssetConfig = symbol.toLowerCase match {
            case "ada"  => ada
            case "usdm" => usdm
            case other =>
                customAssets.get(other).getOrElse {
                    throw new IllegalArgumentException(
                      s"Unknown asset: $other. Available: ${availableAssets.mkString(", ")}"
                    )
                }
        }
    }

    // Exchange Configuration
    object exchange {
        val seed: Int = config.getInt("exchange.seed")
        val mnemonic: Option[String] =
            if config.hasPath("exchange.mnemonic") then Some(config.getString("exchange.mnemonic"))
            else None
        val minDeposit: Long = config.getLong("exchange.params.minDeposit")
        val snapshotContestPeriod: Int = config.getInt("exchange.params.snapshotContestPeriod")
        val tradesContestPeriod: Int = config.getInt("exchange.params.tradesContestPeriod")

        /** Create exchange account from config (using mnemonic if available, otherwise seed) */
        def createAccount(): Account = {
            mnemonic match {
                case Some(words) =>
                    import com.bloxbean.cardano.client.crypto.cip1852.DerivationPath.createExternalAddressDerivationPathForAccount
                    Account.createFromMnemonic(
                      network.bloxbeanNetwork,
                      words,
                      createExternalAddressDerivationPathForAccount(seed)
                    )
                case None =>
                    new Account(network.bloxbeanNetwork, seed)
            }
        }

        /** Create ExchangeParams from config */
        def createParams(): ExchangeParams = {
            println(s"[DEBUG] createParams: about to create account with seed=$seed")
            System.out.flush()
            val account = createAccount()
            println(s"[DEBUG] createParams: account created, getting hdKeyPair...")
            System.out.flush()
            val pubKeyHash = ByteString.fromArray(account.hdKeyPair().getPublicKey.getKeyHash)
            println(s"[DEBUG] createParams: got hdKeyPair, getting publicKeyBytes...")
            System.out.flush()
            val pubKey = ByteString.fromArray(account.publicKeyBytes())
            println(s"[DEBUG] createParams: creating ExchangeParams...")
            System.out.flush()

            ExchangeParams(
              exchangePkh = PubKeyHash(pubKeyHash),
              contestationPeriodInMilliseconds = snapshotContestPeriod * network.slotLength,
              exchangePubKey = pubKey
            )
        }

        /** Get exchange private key (Ed25519) */
        def getPrivateKey(): ByteString = {
            val account = createAccount()
            ByteString.fromArray(account.privateKeyBytes().take(32))
        }
    }

    // Client Configurations
    sealed trait ClientConfig {
        def name: String
        def seed: Int
        def mnemonic: Option[String]
        def initialBalance: Map[String, Long]
        def channelDepositAmount: Long // How much ADA to deposit when opening channel (lovelace)
        def defaultOrder: Option[OrderConfig]

        /** Create account from config (using mnemonic if available, otherwise seed) */
        def createAccount(): Account = {
            mnemonic match {
                case Some(words) =>
                    // Create account from mnemonic phrase with seed as account index
                    // This allows different addresses from the same mnemonic
                    import com.bloxbean.cardano.client.crypto.cip1852.DerivationPath.createExternalAddressDerivationPathForAccount
                    Account.createFromMnemonic(
                      network.bloxbeanNetwork,
                      words,
                      createExternalAddressDerivationPathForAccount(seed)
                    )
                case None =>
                    // Fallback to seed-based account
                    new Account(network.bloxbeanNetwork, seed)
            }
        }

        /** Get public key hash */
        def getPubKeyHash(): ByteString = {
            val account = createAccount()
            ByteString.fromArray(account.hdKeyPair().getPublicKey.getKeyHash)
        }

        /** Get public key */
        def getPubKey(): ByteString = {
            val account = createAccount()
            ByteString.fromArray(account.publicKeyBytes())
        }

        /** Get private key (Ed25519) */
        def getPrivKey(): ByteString = {
            val account = createAccount()
            ByteString.fromArray(account.privateKeyBytes().take(32))
        }

        /** Get initial balance as Value */
        def getInitialValue(): Value = {
            var value = Value.lovelace(0)

            // Add each asset from initialBalance
            initialBalance.foreach { case (assetSymbol, amount) =>
                assetSymbol.toLowerCase match {
                    case "ada" =>
                        value = Value.lovelace(amount)

                    case other =>
                        val asset = assets.getAsset(other)
                        value = value + Value.asset(
                          scalus.cardano.ledger.ScriptHash.fromByteString(asset.policyId),
                          scalus.cardano.ledger.AssetName(asset.assetName),
                          amount
                        )
                }
            }

            value
        }

        /** Get deposit amount (for channel opening) - returns fixed ADA amount configured */
        def getDepositAmount(): Long = channelDepositAmount
    }

    case class OrderConfig(
        side: String, // "BUY" or "SELL"
        baseAsset: String,
        quoteAsset: String,
        amount: Long,
        price: Long
    ) {
        def isBuy: Boolean = side.equalsIgnoreCase("BUY")
        def isSell: Boolean = side.equalsIgnoreCase("SELL")

        /** Get the trading pair */
        def getPair(): Pair = {
            val base = assets.getAsset(baseAsset).assetClass
            val quote = assets.getAsset(quoteAsset).assetClass
            (base, quote)
        }

        /** Get signed amount (positive for BUY, negative for SELL) */
        def getSignedAmount(): BigInt = {
            if isBuy then BigInt(amount) else BigInt(-amount)
        }
    }

    object alice extends ClientConfig {
        val name = "Alice"
        val seed: Int = config.getInt("alice.seed")

        val mnemonic: Option[String] = {
            if config.hasPath("alice.mnemonic") then {
                Some(config.getString("alice.mnemonic"))
            } else {
                None
            }
        }

        val channelDepositAmount: Long = config.getLong("alice.channel.depositAmount")

        val initialBalance: Map[String, Long] = {
            val balanceConfig = config.getConfig("alice.initialBalance")
            balanceConfig
                .entrySet()
                .asScala
                .map { entry =>
                    entry.getKey -> balanceConfig.getLong(entry.getKey)
                }
                .toMap
        }

        val defaultOrder: Option[OrderConfig] = {
            if config.hasPath("alice.trading.defaultOrder") then {
                Some(
                  OrderConfig(
                    side = config.getString("alice.trading.defaultOrder.side"),
                    baseAsset = config.getString("alice.trading.defaultOrder.baseAsset"),
                    quoteAsset = config.getString("alice.trading.defaultOrder.quoteAsset"),
                    amount = config.getLong("alice.trading.defaultOrder.amount"),
                    price = config.getLong("alice.trading.defaultOrder.price")
                  )
                )
            } else None
        }
    }

    case class MintingConfig(
        enabled: Boolean,
        tokenName: String,
        amount: Long
    )

    object bob extends ClientConfig {
        val name = "Bob"
        val seed: Int = config.getInt("bob.seed")

        val mnemonic: Option[String] = {
            if config.hasPath("bob.mnemonic") then {
                Some(config.getString("bob.mnemonic"))
            } else {
                None
            }
        }

        val channelDepositAmount: Long = config.getLong("bob.channel.depositAmount")

        val initialBalance: Map[String, Long] = {
            val balanceConfig = config.getConfig("bob.initialBalance")
            balanceConfig
                .entrySet()
                .asScala
                .map { entry =>
                    entry.getKey -> balanceConfig.getLong(entry.getKey)
                }
                .toMap
        }

        val defaultOrder: Option[OrderConfig] = {
            if config.hasPath("bob.trading.defaultOrder") then {
                Some(
                  OrderConfig(
                    side = config.getString("bob.trading.defaultOrder.side"),
                    baseAsset = config.getString("bob.trading.defaultOrder.baseAsset"),
                    quoteAsset = config.getString("bob.trading.defaultOrder.quoteAsset"),
                    amount = config.getLong("bob.trading.defaultOrder.amount"),
                    price = config.getLong("bob.trading.defaultOrder.price")
                  )
                )
            } else None
        }

        val minting: Option[MintingConfig] = {
            if config.hasPath("bob.minting") then {
                Some(
                  MintingConfig(
                    enabled = config.getBoolean("bob.minting.enabled"),
                    tokenName = config.getString("bob.minting.tokenName"),
                    amount = config.getLong("bob.minting.amount")
                  )
                )
            } else None
        }
    }

    // Logging Configuration
    object logging {
        val level: String = config.getString("logging.level")
        val verboseServer: Boolean = config.getBoolean("logging.verbose.server")
        val verboseClients: Boolean = config.getBoolean("logging.verbose.clients")
        val verboseOrderMatching: Boolean = config.getBoolean("logging.verbose.orderMatching")
        val verboseBlockchain: Boolean = config.getBoolean("logging.verbose.blockchain")
    }

    // Demo Configuration
    object demo {
        val interactive: Boolean = config.getBoolean("demo.interactive")
        val actionDelay: Int = config.getInt("demo.actionDelay")
        val autoClose: Boolean = config.getBoolean("demo.autoClose")

        val showSnapshots: Boolean = config.getBoolean("demo.display.showSnapshots")
        val showBalances: Boolean = config.getBoolean("demo.display.showBalances")
        val showOrderBook: Boolean = config.getBoolean("demo.display.showOrderBook")
        val showTrades: Boolean = config.getBoolean("demo.display.showTrades")
    }

    /** Create blockchain provider based on configuration
      *
      * Note: For mock provider with initial UTxOs, use createMockProvider() instead
      */
    def createProvider(): scalus.cardano.node.Provider = {
        import cosmex.cardano.{YaciTestcontainerProvider, BlockfrostProvider}
        import scalus.testing.kit.MockLedgerApi
        import scalus.cardano.ledger.rules.{Context, WrongNetworkValidator}

        blockchain.provider.toLowerCase match {
            case "mock" =>
                println(s"[Config] Using MockLedgerApi provider (empty initial state)")
                MockLedgerApi(
                  initialUtxos = Map.empty,
                  context = Context.testMainnet(slot = 1000),
                  validators = MockLedgerApi.defaultValidators - WrongNetworkValidator,
                  mutators = MockLedgerApi.defaultMutators
                )

            case "yaci-devkit" | "yaci" =>
                println(s"[Config] Using Yaci DevKit provider (without initial funding)")
                println(s"[Config] Use createProviderWithFunding() to provide initial funding")
                YaciTestcontainerProvider.start()

            case "preprod" =>
                blockchain.networkProvider.blockfrost.projectId match {
                    case Some(apiKey) =>
                        println(s"[Config] Using Blockfrost provider for Preprod testnet")
                        BlockfrostProvider.preprod(apiKey)
                    case None =>
                        throw new IllegalArgumentException(
                          "BLOCKFROST_PROJECT_ID environment variable not set. " +
                              "Set it to your Blockfrost API key for preprod network."
                        )
                }

            case "preview" =>
                blockchain.networkProvider.blockfrost.projectId match {
                    case Some(apiKey) =>
                        println(s"[Config] Using Blockfrost provider for Preview testnet")
                        BlockfrostProvider.preview(apiKey)
                    case None =>
                        throw new IllegalArgumentException(
                          "BLOCKFROST_PROJECT_ID environment variable not set. " +
                              "Set it to your Blockfrost API key for preview network."
                        )
                }

            case other =>
                throw new IllegalArgumentException(
                  s"Unsupported blockchain provider: $other. " +
                      s"Supported providers: mock, yaci-devkit, preprod, preview"
                )
        }
    }

    /** Create blockchain provider with initial funding for specific addresses
      *
      * @param initialFunding
      *   List of (bech32 address, amount in lovelace) pairs
      */
    def createProviderWithFunding(
        initialFunding: Seq[(String, Long)]
    ): scalus.cardano.node.Provider = {
        import cosmex.cardano.YaciTestcontainerProvider

        blockchain.provider.toLowerCase match {
            case "mock" =>
                // Create MockLedgerApi with initial funding UTxOs
                println(s"[Config] Using MockLedgerApi provider with initial funding")
                import scalus.testing.kit.MockLedgerApi
                import scalus.cardano.ledger.rules.{Context, WrongNetworkValidator}
                import scalus.cardano.address.Address
                import scalus.cardano.ledger.{TransactionInput, TransactionHash, TransactionOutput, Value}

                // Convert funding addresses to initial UTxOs
                val initialUtxos = initialFunding.zipWithIndex.map { case ((bech32, amount), idx) =>
                    val address = Address.fromBech32(bech32)
                    // Create unique transaction hash for each funding UTxO
                    val txHashHex = "00" * 31 + f"$idx%02x"
                    val txHash = TransactionHash.fromHex(txHashHex)
                    val txIn = TransactionInput(txHash, 0)
                    val txOut = TransactionOutput(address, Value.lovelace(amount), None)
                    txIn -> txOut
                }.toMap

                println(s"[Config] Created ${initialUtxos.size} initial UTxOs for MockLedgerApi")
                MockLedgerApi(
                  initialUtxos = initialUtxos,
                  context = Context.testMainnet(slot = 1000),
                  validators = MockLedgerApi.defaultValidators - WrongNetworkValidator,
                  mutators = MockLedgerApi.defaultMutators
                )

            case "yaci-devkit" | "yaci" =>
                println(s"[Config] Using Yaci DevKit provider with initial funding")
                YaciTestcontainerProvider.start(initialFunding)

            case other =>
                throw new IllegalArgumentException(
                  s"Unsupported blockchain provider: $other. " +
                      s"Supported providers: mock, yaci-devkit"
                )
        }
    }

    /** Pretty-print configuration summary */
    def summary(): String = {
        s"""
       |COSMEX Demo Configuration
       |========================
       |
       |Server:
       |  URL: ${server.websocketUrl}
       |  Host: ${server.host}
       |  Port: ${server.port}
       |
       |Network:
       |  Type: ${network.networkType}
       |  Magic: ${network.magic}
       |
       |Blockchain Provider:
       |  Type: ${blockchain.provider}
       |
       |Exchange:
       |  Seed: ${exchange.seed}
       |  Min Deposit: ${exchange.minDeposit} lovelace
       |  Snapshot Contest Period: ${exchange.snapshotContestPeriod} slots
       |
       |Alice:
       |  Seed: ${alice.seed}
       |  Initial Balance: ${alice.initialBalance.mkString(", ")}
       |  Default Order: ${alice.defaultOrder
              .map(o => s"${o.side} ${o.amount} ${o.baseAsset} @ ${o.price}")
              .getOrElse("None")}
       |
       |Bob:
       |  Seed: ${bob.seed}
       |  Initial Balance: ${bob.initialBalance.mkString(", ")}
       |  Default Order: ${bob.defaultOrder
              .map(o => s"${o.side} ${o.amount} ${o.baseAsset} @ ${o.price}")
              .getOrElse("None")}
       |
       |Demo Settings:
       |  Interactive: ${demo.interactive}
       |  Action Delay: ${demo.actionDelay}ms
       |""".stripMargin
    }
}

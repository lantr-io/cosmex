package cosmex.config

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import com.typesafe.config.{Config, ConfigFactory}
import cosmex.{ExchangeParams, Pair}
import scalus.builtin.ByteString
import scalus.cardano.ledger.Value
import scalus.ledger.api.v1.PubKeyHash


import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

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
    if (hex.isEmpty) ByteString.empty
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
        case _ => scalus.cardano.address.Network.Testnet
      }
  }
  
  // Blockchain Provider Configuration
  object blockchain {
    val provider: String = config.getString("blockchain.provider")
    
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

    /** Get asset by symbol */
    def getAsset(symbol: String): AssetConfig = symbol.toLowerCase match {
      case "ada" => ada
      case "usdm" => usdm
      case other => throw new IllegalArgumentException(s"Unknown asset: $other")
    }
  }

  // Exchange Configuration
  object exchange {
    val seed: Int = config.getInt("exchange.seed")
    val minDeposit: Long = config.getLong("exchange.params.minDeposit")
    val snapshotContestPeriod: Int = config.getInt("exchange.params.snapshotContestPeriod")
    val tradesContestPeriod: Int = config.getInt("exchange.params.tradesContestPeriod")

    /** Create exchange account from config */
    def createAccount(): Account = {
      new Account(network.bloxbeanNetwork, seed)
    }

    /** Create ExchangeParams from config */
    def createParams(): ExchangeParams = {
      val account = createAccount()
      val pubKeyHash = ByteString.fromArray(account.hdKeyPair().getPublicKey.getKeyHash)
      val pubKey = ByteString.fromArray(account.publicKeyBytes())

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
    def initialBalance: Map[String, Long]
    def defaultOrder: Option[OrderConfig]

    /** Create account from config */
    def createAccount(): Account = new Account(network.bloxbeanNetwork, seed)

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

    /** Get deposit amount (for channel opening) */
    def getDepositAmount(): Value = getInitialValue()
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
      if (isBuy) BigInt(amount) else BigInt(-amount)
    }
  }

  object alice extends ClientConfig {
    val name = "Alice"
    val seed: Int = config.getInt("alice.seed")

    val initialBalance: Map[String, Long] = {
      val balanceConfig = config.getConfig("alice.initialBalance")
      balanceConfig.entrySet().asScala.map { entry =>
        entry.getKey -> balanceConfig.getLong(entry.getKey)
      }.toMap
    }

    val defaultOrder: Option[OrderConfig] = {
      if (config.hasPath("alice.trading.defaultOrder")) {
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

  object bob extends ClientConfig {
    val name = "Bob"
    val seed: Int = config.getInt("bob.seed")

    val initialBalance: Map[String, Long] = {
      val balanceConfig = config.getConfig("bob.initialBalance")
      balanceConfig.entrySet().asScala.map { entry =>
        entry.getKey -> balanceConfig.getLong(entry.getKey)
      }.toMap
    }

    val defaultOrder: Option[OrderConfig] = {
      if (config.hasPath("bob.trading.defaultOrder")) {
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
    import cosmex.cardano.YaciTestcontainerProvider
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
        println(s"[Config] Using Yaci DevKit provider")
        YaciTestcontainerProvider.start()
        
      case other =>
        throw new IllegalArgumentException(
          s"Unsupported blockchain provider: $other. " +
          s"Supported providers: mock, yaci-devkit, preprod, preview"
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
       |  Default Order: ${alice.defaultOrder.map(o => s"${o.side} ${o.amount} ${o.baseAsset} @ ${o.price}").getOrElse("None")}
       |
       |Bob:
       |  Seed: ${bob.seed}
       |  Initial Balance: ${bob.initialBalance.mkString(", ")}
       |  Default Order: ${bob.defaultOrder.map(o => s"${o.side} ${o.amount} ${o.baseAsset} @ ${o.price}").getOrElse("None")}
       |
       |Demo Settings:
       |  Interactive: ${demo.interactive}
       |  Action Delay: ${demo.actionDelay}ms
       |""".stripMargin
  }
}

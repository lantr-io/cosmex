package cosmex.cardano

import com.bloxbean.cardano.client.api.UtxoSupplier
import com.bloxbean.cardano.client.backend.api.BackendService
import com.bloxbean.cardano.yaci.test.YaciCardanoContainer
import scalus.cardano.ledger.{Coin, Transaction, Utxo, ProtocolParams, TransactionHash, DatumOption, TransactionInput, Utxos, TransactionOutput, Value}
import scalus.cardano.address.Address
import scalus.builtin.ByteString

import scala.jdk.CollectionConverters.*
import scala.util.{Try, Success, Failure}

/** Provider implementation using Yaci DevKit testcontainers
  *
  * Provides a local Cardano blockchain for testing using Docker containers.
  *
  * Usage:
  * ```scala
  * val provider = YaciTestcontainerProvider.start()
  * try {
  *   // Use provider for testing
  *   provider.submit(tx)
  * } finally {
  *   provider.stop()
  * }
  * ```
  */
class YaciTestcontainerProvider private (
    private val container: YaciCardanoContainer,
    private val backendService: BackendService,
    private val utxoSupplier: UtxoSupplier
) extends scalus.cardano.node.Provider {


  /** Submit a transaction to the Yaci testcontainer blockchain */
  override def submit(tx: Transaction): Either[RuntimeException, Unit] = {
    Try {
      println(s"[YaciProvider] Submitting transaction: ${tx.id.toHex.take(16)}...")
      
      // Convert Scalus Transaction to CBOR bytes
      val txCborBytes = tx.toCbor
      
      // Submit via backend service
      val result = backendService.getTransactionService.submitTransaction(txCborBytes)
      
      if (result.isSuccessful) {
        println(s"[YaciProvider] Transaction submitted: ${result.getValue}")
        ()
      } else {
        throw new RuntimeException(s"Transaction submission failed: ${result.getResponse}")
      }
    }.toEither.left.map {
      case e: RuntimeException => e
      case e: Throwable => new RuntimeException(s"Transaction submission failed: ${e.getMessage}", e)
    }
  }

  override def findUtxo(input: TransactionInput): Either[RuntimeException, Utxo] = {
    Try {
      val txHash = input.transactionId.toHex
      val outputIndex = input.index
      
      // Query all UTxOs for this txHash and filter by index
      val allUtxos = utxoSupplier.getAll(txHash).asScala.toSeq
      val maybeUtxo = allUtxos.find(_.getOutputIndex == outputIndex.toInt)
      
      maybeUtxo.map { bloxbeanUtxo =>
        convertBloxbeanUtxo(bloxbeanUtxo, txHash, outputIndex)
      }.getOrElse {
        throw new RuntimeException(s"UTxO not found: $txHash#$outputIndex")
      }
    }.toEither.left.map {
      case e: RuntimeException => e
      case e: Throwable => new RuntimeException(s"Failed to find UTxO: ${e.getMessage}", e)
    }
  }

  override def findUtxos(inputs: Set[TransactionInput]): Either[RuntimeException, Utxos] = {
    Try {
      val utxos = inputs.map { input =>
        findUtxo(input) match {
          case Right(utxo) => utxo
          case Left(e) => throw e
        }
      }
      Map.from(utxos.map(u => u.input -> u.output))
    }.toEither.left.map {
      case e: RuntimeException => e
      case e: Throwable => new RuntimeException(s"Failed to find UTxOs: ${e.getMessage}", e)
    }
  }

  override def findUtxos(
      address: Address,
      transactionId: Option[TransactionHash],
      datum: Option[DatumOption],
      minAmount: Option[Coin],
      minRequiredTotalAmount: Option[Coin]
  ): Either[RuntimeException, Utxos] = {
    import scala.math.BigInt.javaBigInteger2bigInt
    import scalus.cardano.address.{ShelleyAddress, StakeAddress}
    
    Try {
      // Query all UTxOs for address
      val addressBech32 = address match {
        case sa: ShelleyAddress => sa.toBech32.get
        case sta: StakeAddress => sta.toBech32.get
        case other => throw new RuntimeException(s"Unsupported address type: ${other.getClass}")
      }
      val bloxbeanUtxos = utxoSupplier.getAll(addressBech32).asScala.toSeq
      
      // Filter by criteria
      val filtered = bloxbeanUtxos
        .filter { utxo =>
          transactionId.forall(txId => utxo.getTxHash == txId.toHex)
        }
        .filter { utxo =>
          minAmount.forall(min => utxo.getAmount.asScala.headOption.exists(amt => BigInt(amt.getQuantity).toLong >= min.value))
        }
      
      // Convert to Scalus UTxOs
      val utxos = filtered.map { bloxbeanUtxo =>
        val txHash = bloxbeanUtxo.getTxHash
        val index = bloxbeanUtxo.getOutputIndex.toLong
        convertBloxbeanUtxo(bloxbeanUtxo, txHash, index)
      }.map(u => u.input -> u.output).toMap
      utxos
    }.toEither.left.map {
      case e: RuntimeException => e
      case e: Throwable => new RuntimeException(s"Failed to find UTxOs: ${e.getMessage}", e)
    }
  }

  /** Find a UTxO at the given address with minimum amount */
  override def findUtxo(address: Address, transactionId: Option[TransactionHash], datum: Option[DatumOption], minAmount: Option[Coin]): Either[RuntimeException, Utxo] = {
    findUtxos(address, transactionId, datum, minAmount, None).flatMap { utxos =>
      utxos.headOption match {
        case Some((input, output)) => Right(Utxo(input, output))
        case None => Left(new RuntimeException(s"No UTxO found at address $address"))
      }
    }
  }
  
  /** Convert Bloxbean UTxO to Scalus UTxO */
  private def convertBloxbeanUtxo(
      bloxbeanUtxo: com.bloxbean.cardano.client.api.model.Utxo,
      txHashHex: String,
      outputIndex: Long
  ): Utxo = {
    import scala.math.BigInt.javaBigInteger2bigInt
    
    val txHash = TransactionHash.fromHex(txHashHex)
    val input = TransactionInput(txHash, outputIndex.toInt)
    
    // Parse address
    val address = Try(Address.fromBech32(bloxbeanUtxo.getAddress)).getOrElse(
      throw new RuntimeException(s"Invalid address: ${bloxbeanUtxo.getAddress}")
    )
    
    // Parse value (ADA lovelace amount)
    val lovelace = bloxbeanUtxo.getAmount.asScala.headOption
      .map(amt => BigInt(amt.getQuantity).toLong)
      .getOrElse(0L)
    
    val value = Value.lovelace(lovelace)
    
    val output = TransactionOutput(address, value)
    
    Utxo(input, output)
  }

  /** Get all UTxOs at the given address
    *
    * TODO: Implement using Yaci API
    */
  def getAllUtxos(address: Address): Either[RuntimeException, Seq[Utxo]] = {
    // TODO: Query all UTxOs from Yaci for address

    Left(
      new RuntimeException(
        "YaciTestcontainerProvider.getAllUtxos() not yet implemented"
      )
    )
  }

  /** Get current slot number
    *
    * TODO: Implement using Yaci API
    */
  def getCurrentSlot(): Either[RuntimeException, Long] = {
    // TODO: Query current slot from Yaci

    Left(new RuntimeException("getCurrentSlot() not yet implemented"))
  }

  /** Get protocol parameters
    *
    * TODO: Implement using Yaci API
    */
  def getProtocolParams(): Either[RuntimeException, ProtocolParams] = {
    // TODO: Query protocol params from Yaci
    // TODO: Convert to Scalus ProtocolParams format

    Left(new RuntimeException("getProtocolParams() not yet implemented"))
  }

  /** Stop the testcontainer and cleanup resources */
  def stop(): Unit = {
    println("[YaciProvider] Stopping testcontainer...")
    Try {
      container.stop()
    }.recover {
      case e => println(s"[YaciProvider] Error stopping container: ${e.getMessage}")
    }
  }
}

object YaciTestcontainerProvider {

  /** Start a new Yaci DevKit testcontainer */
  def start(): YaciTestcontainerProvider = {
    println("[YaciProvider] Starting Yaci DevKit testcontainer...")
    
    // Create and start container
    val container = new YaciCardanoContainer()
    container.start()
    
    println("[YaciProvider] Container started, waiting for node to be ready...")
    
    // Wait for node to initialize
    Thread.sleep(10000)
    
    // Get backend service and UTXO supplier
    val backendService = container.getBackendService
    val utxoSupplier = container.getUtxoSupplier
    
    println("[YaciProvider] Yaci DevKit testcontainer ready")
    
    new YaciTestcontainerProvider(container, backendService, utxoSupplier)
  }

  /** Create provider with custom configuration */
  def startWithConfig(
      network: String = "preview",
      slotLength: Int = 1000,
      epochLength: Int = 432000
  ): YaciTestcontainerProvider = {
    println(s"[YaciProvider] Starting with config: network=$network, slotLength=$slotLength")
    
    // For now, use default start - custom config can be added later
    start()
  }
}

/** Alternative implementation notes:
  *
  * If yaci-devkit-test is not available, alternative approaches:
  *
  * 1. Use MockLedgerApi (already implemented in scalus-testkit)
  *    - Pros: Already works, no dependencies
  *    - Cons: In-memory only, not a real blockchain
  *
  * 2. Use Cardano-testnet Docker compose
  *    - Pros: Real Cardano node
  *    - Cons: Slower startup, more complex setup
  *
  * 3. Use Plutip (Plutus Application Backend testnet)
  *    - Pros: Designed for testing
  *    - Cons: Additional dependency
  *
  * 4. Use Ogmios + Kupo via testcontainers
  *    - Pros: Standard Cardano tools
  *    - Cons: Multiple containers needed
  *
  * Current recommendation: Start with MockLedgerApi (already working),
  * then migrate to Yaci or other solution when ready.
  */

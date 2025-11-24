package cosmex.cardano

import com.bloxbean.cardano.client.api.UtxoSupplier
import com.bloxbean.cardano.client.backend.api.BackendService
import com.bloxbean.cardano.yaci.test.{Funding, YaciCardanoContainer}
import cosmex.util.TransactionStatusProvider
import scalus.cardano.ledger.{AssetName, Coin, DatumOption, ProtocolParams, ScriptHash, Transaction, TransactionHash, TransactionInput, TransactionOutput, Utxo, Utxos, Value}
import scalus.cardano.address.Address

import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Provider implementation using Yaci DevKit testcontainers
  *
  * Provides a local Cardano blockchain for testing using Docker containers.
  *
  * Usage:
  * ```scala
  * val provider = YaciTestcontainerProvider.start()
  * try {
  *     // Use provider for testing
  *     provider.submit(tx)
  * } finally {
  *     provider.stop()
  * }
  * ```
  */
class YaciTestcontainerProvider private (
    private val container: YaciCardanoContainer,
    private val backendService: BackendService,
    private val utxoSupplier: UtxoSupplier
) extends scalus.cardano.node.Provider
    with TransactionStatusProvider {

    /** Submit a transaction to the Yaci testcontainer blockchain */
    override def submit(tx: Transaction): Either[RuntimeException, Unit] = {
        Try {
            println(s"[YaciProvider] Submitting transaction: ${tx.id.toHex.take(16)}...")

            // Convert Scalus Transaction to CBOR bytes
            val txCborBytes = tx.toCbor

            // Use BackendService (Blockfrost-compatible API) for better error handling
            val result = backendService.getTransactionService.submitTransaction(txCborBytes)

            println(s"[YaciProvider] Submit result - isSuccessful: ${result.isSuccessful}")
            println(s"[YaciProvider] Submit result - response: ${result.getResponse}")

            if result.getValue != null then {
                println(s"[YaciProvider] Submit result - value (txHash): ${result.getValue}")
            }

            if result.isSuccessful then {
                val txHash = result.getValue
                if txHash != null && txHash.matches("[0-9a-fA-F]{64}") then {
                    println(s"[YaciProvider] Transaction accepted: $txHash")
                    ()
                } else {
                    throw new RuntimeException(
                      s"Submit API returned invalid transaction hash: $txHash"
                    )
                }
            } else {
                throw new RuntimeException(s"Transaction submission failed: ${result.getResponse}")
            }
        }.toEither.left.map {
            case e: RuntimeException => e
            case e: Throwable =>
                new RuntimeException(s"Transaction submission failed: ${e.getMessage}", e)
        }
    }

    /** Check if a transaction has been confirmed on-chain
      *
      * @param txHash
      *   Transaction hash to check
      * @return
      *   Right(true) if confirmed, Right(false) if not found, Left(error) on failure
      */
    def isTransactionConfirmed(txHash: String): Either[RuntimeException, Boolean] = {
        Try {
            val txService = backendService.getTransactionService
            println(s"[YaciProvider] Checking transaction status for: ${txHash.take(16)}...")
            val txContent = txService.getTransaction(txHash)

            println(s"[YaciProvider] getTransaction() isSuccessful: ${txContent.isSuccessful}")
            if !txContent.isSuccessful then {
                println(s"[YaciProvider] getTransaction() response: ${txContent.getResponse}")
            }

            if txContent.isSuccessful then {
                val txData = txContent.getValue
                // Transaction is confirmed if it has a block number/hash
                val isConfirmed = txData.getBlock != null && !txData.getBlock.isEmpty
                println(
                  s"[YaciProvider] Transaction block: ${txData.getBlock}, isConfirmed: $isConfirmed"
                )
                if isConfirmed then {
                    println(
                      s"[YaciProvider] Transaction ${txHash.take(16)}... confirmed in block ${txData.getBlock}"
                    )
                }
                isConfirmed
            } else {
                // Transaction not found or error
                println(s"[YaciProvider] Transaction ${txHash.take(16)}... not found in blockchain")
                false
            }
        }.toEither.left.map {
            case e: RuntimeException => e
            case e: Throwable =>
                new RuntimeException(s"Failed to check transaction status: ${e.getMessage}", e)
        }
    }

    override def findUtxo(input: TransactionInput): Either[RuntimeException, Utxo] = {
        Try {
            val txHash = input.transactionId.toHex
            val outputIndex = input.index

            // Use getTxOutput (same as QuickTxBuilder.completeAndWait uses)
            val maybeUtxo = utxoSupplier.getTxOutput(txHash, outputIndex.toInt)

            if maybeUtxo.isPresent then {
                val bloxbeanUtxo = maybeUtxo.get()
                convertBloxbeanUtxo(bloxbeanUtxo, txHash, outputIndex)
            } else {
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
                    case Left(e)     => throw e
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
        import scalus.cardano.address.{ShelleyAddress, StakeAddress}

        Try {
            // Query all UTxOs for address
            val addressBech32 = address match {
                case sa: ShelleyAddress => sa.toBech32.get
                case sta: StakeAddress  => sta.toBech32.get
                case other =>
                    throw new RuntimeException(s"Unsupported address type: ${other.getClass}")
            }

            println(s"[YaciProvider] Querying UTxOs for address: $addressBech32")
            val bloxbeanUtxos = utxoSupplier.getAll(addressBech32).asScala.toSeq
            println(s"[YaciProvider] Found ${bloxbeanUtxos.size} UTxOs at address")

            bloxbeanUtxos.foreach { utxo =>
                val lovelace = utxo.getAmount.asScala.headOption
                    .map(amt => BigInt(amt.getQuantity).toLong)
                    .getOrElse(0L)
                println(
                  s"[YaciProvider]   ${utxo.getTxHash.take(16)}#${utxo.getOutputIndex} = $lovelace lovelace"
                )
            }

            // Filter by criteria
            val filtered = bloxbeanUtxos
                .filter { utxo =>
                    transactionId.forall(txId => utxo.getTxHash == txId.toHex)
                }
                .filter { utxo =>
                    minAmount.forall(min =>
                        utxo.getAmount.asScala.headOption.exists(amt =>
                            BigInt(amt.getQuantity).toLong >= min.value
                        )
                    )
                }

            println(s"[YaciProvider] After filtering: ${filtered.size} UTxOs match criteria")

            // Convert to Scalus UTxOs
            val utxos = filtered
                .map { bloxbeanUtxo =>
                    val txHash = bloxbeanUtxo.getTxHash
                    val index = bloxbeanUtxo.getOutputIndex.toLong
                    convertBloxbeanUtxo(bloxbeanUtxo, txHash, index)
                }
                .map(u => u.input -> u.output)
                .toMap
            utxos
        }.toEither.left.map {
            case e: RuntimeException => e
            case e: Throwable => new RuntimeException(s"Failed to find UTxOs: ${e.getMessage}", e)
        }
    }

    /** Find a UTxO at the given address with minimum amount */
    override def findUtxo(
        address: Address,
        transactionId: Option[TransactionHash],
        datum: Option[DatumOption],
        minAmount: Option[Coin]
    ): Either[RuntimeException, Utxo] = {
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
        import scalus.builtin.ByteString

        val txHash = TransactionHash.fromHex(txHashHex)
        val input = TransactionInput(txHash, outputIndex.toInt)

        // Parse address
        val address = Try(Address.fromBech32(bloxbeanUtxo.getAddress)).getOrElse(
          throw new RuntimeException(s"Invalid address: ${bloxbeanUtxo.getAddress}")
        )

        // Parse value (ADA + all multi-assets)
        val amounts = bloxbeanUtxo.getAmount.asScala.toSeq

        // First amount is always ADA
        val lovelace = amounts.headOption
            .map(amt => BigInt(amt.getQuantity).toLong)
            .getOrElse(0L)

        // Start with ADA value
        var value = Value.lovelace(lovelace)

        // Add any multi-assets (tokens)
        amounts.tail.foreach { amt =>
            val policyIdHex = amt.getUnit.take(56) // First 56 chars = policy ID
            val assetNameHex = amt.getUnit.drop(56) // Rest = asset name
            val quantity = BigInt(amt.getQuantity).toLong

            val policyId = ScriptHash.fromHex(policyIdHex)
            val assetName = AssetName(
              if assetNameHex.isEmpty then ByteString.empty else ByteString.fromHex(assetNameHex)
            )

            value = value + Value.asset(policyId, assetName, quantity)
        }

        val output = TransactionOutput(address, value)

        Utxo(input, output)
    }

    /** Get all UTxOs at the given address
      *
      * TODO: Implement using Yaci API
      */
    def getAllUtxos(@annotation.unused address: Address): Either[RuntimeException, Seq[Utxo]] = {
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

    /** Get protocol parameters from yaci-devkit
      *
      * Uses BackendService to fetch current protocol parameters, then converts to JSON for Scalus's
      * fromBlockfrostJson parser
      */
    def getProtocolParams(): Either[RuntimeException, ProtocolParams] = {
        import com.fasterxml.jackson.databind.ObjectMapper

        Try {
            println(s"[YaciProvider] Fetching protocol parameters using BackendService...")

            // Use BackendService (similar to how submit() uses it)
            val epochService = backendService.getEpochService
            val result = epochService.getProtocolParameters()

            println(s"[YaciProvider] Protocol params result - isSuccessful: ${result.isSuccessful}")

            if result.isSuccessful then {
                val params = result.getValue
                println(s"[YaciProvider] Successfully fetched protocol parameters")

                // Convert Bloxbean ProtocolParams object to JSON
                val objectMapper = new ObjectMapper()
                val jsonString = objectMapper.writeValueAsString(params)

                println(s"[YaciProvider] Converted to JSON, parsing with Scalus...")

                // Use Scalus's fromBlockfrostJson to parse
                ProtocolParams.fromBlockfrostJson(jsonString)
            } else {
                throw new RuntimeException(
                  s"Failed to fetch protocol parameters: ${result.getResponse}"
                )
            }
        }.toEither.left.map {
            case e: RuntimeException => e
            case e: Throwable =>
                new RuntimeException(s"Failed to get protocol parameters: ${e.getMessage}", e)
        }
    }

    /** Stop the testcontainer and cleanup resources */
    def stop(): Unit = {
        println("[YaciProvider] Stopping testcontainer...")
        Try {
            container.stop()
        }.recover { case e =>
            println(s"[YaciProvider] Error stopping container: ${e.getMessage}")
        }
    }
}

object YaciTestcontainerProvider {

    /** Start a new Yaci DevKit testcontainer */
    def start(): YaciTestcontainerProvider = {
        start(Seq.empty)
    }

    /** Start a new Yaci DevKit testcontainer with initial funding
      *
      * @param initialFunding
      *   List of (address, amount in lovelace) pairs for initial funding
      */
    def start(initialFunding: Seq[(String, Long)]): YaciTestcontainerProvider = {
        println("[YaciProvider] Starting Yaci DevKit testcontainer...")

        // Create container with initial funding
        val container = new YaciCardanoContainer()
            .withLogConsumer(frame => println(s"[YaciContainer] ${frame.getUtf8String.trim}"))

        // Prepare all funding entries
        val fundingEntries = initialFunding.map { case (address, amount) =>
            val amountInAda = amount / 1_000_000
            println(s"[YaciProvider] Adding initial funding: $address -> $amountInAda ADA")
            new Funding(address, amountInAda)
        }

        // Add all funding at once (withInitialFunding accepts varargs)
        val containerWithFunding = if fundingEntries.nonEmpty then {
            container.withInitialFunding(fundingEntries*)
        } else {
            container
        }

        containerWithFunding.start()

        println("[YaciProvider] Container started, waiting for node to be ready...")

        // Get backend service and UTXO supplier
        val backendService = containerWithFunding.getBackendService
        val utxoSupplier = containerWithFunding.getUtxoSupplier

        // Poll for initial funding transactions with timeout
        if initialFunding.nonEmpty then {
            println("[YaciProvider] Polling for initial funding transactions...")
            val maxWaitSeconds = 30
            val pollIntervalMs = 2000
            val maxAttempts = (maxWaitSeconds * 1000) / pollIntervalMs

            var attempt = 0
            var fundingComplete = false

            while attempt < maxAttempts && !fundingComplete do {
                attempt += 1

                // Check if all funded addresses have UTxOs
                val addressesWithUtxos = initialFunding.count { case (address, _) =>
                    val utxoCount = Try(utxoSupplier.getAll(address).size()).getOrElse(0)
                    val hasUtxos = utxoCount > 0
                    if attempt == 1 || attempt % 5 == 0 then {
                        println(s"[YaciProvider]   $address: $utxoCount UTxOs")
                    }
                    hasUtxos
                }

                if addressesWithUtxos == initialFunding.size then {
                    fundingComplete = true
                    println(
                      s"[YaciProvider] All ${initialFunding.size} addresses funded successfully"
                    )
                } else {
                    println(
                      s"[YaciProvider] Attempt $attempt: $addressesWithUtxos/${initialFunding.size} addresses funded, waiting..."
                    )
                    Thread.sleep(pollIntervalMs)
                }
            }

            if !fundingComplete then {
                println(
                  s"[YaciProvider] Warning: Funding may not be complete after ${maxWaitSeconds}s"
                )
            }
        }

        println("[YaciProvider] Yaci DevKit testcontainer ready")

        new YaciTestcontainerProvider(containerWithFunding, backendService, utxoSupplier)
    }

    /** Create provider with custom configuration */
    def startWithConfig(
        network: String = "preview",
        slotLength: Int = 1000,
        @annotation.unused epochLength: Int = 432000
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
  *   1. Use MockLedgerApi (already implemented in scalus-testkit)
  *      - Pros: Already works, no dependencies
  *      - Cons: In-memory only, not a real blockchain
  *   2. Use Cardano-testnet Docker compose
  *      - Pros: Real Cardano node
  *      - Cons: Slower startup, more complex setup
  *   3. Use Plutip (Plutus Application Backend testnet)
  *      - Pros: Designed for testing
  *      - Cons: Additional dependency
  *   4. Use Ogmios + Kupo via testcontainers
  *      - Pros: Standard Cardano tools
  *      - Cons: Multiple containers needed
  *
  * Current recommendation: Start with MockLedgerApi (already working), then migrate to Yaci or
  * other solution when ready.
  */

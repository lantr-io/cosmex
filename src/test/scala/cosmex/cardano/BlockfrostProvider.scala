package cosmex.cardano

import com.bloxbean.cardano.client.api.model as bloxbean
import com.fasterxml.jackson.databind.ObjectMapper
import cosmex.util.TransactionStatusProvider
import scalus.builtin.{ByteString, Data}
import scalus.cardano.address.{Address, ShelleyAddress}
import scalus.cardano.ledger.*
import scalus.cardano.ledger.BloxbeanToLedgerTranslation.*
import scalus.cardano.node.{Provider, SubmitError}
import scalus.utils.Hex.hexToBytes

import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** Blockfrost-based provider for interacting with Cardano networks (preprod, preview, mainnet)
  *
  * This provider uses the Blockfrost API to interact with real Cardano networks. It requires a
  * Blockfrost API key (project ID) to function.
  *
  * Usage:
  * ```scala
  * val provider = BlockfrostProvider.preprod(apiKey = "your-project-id")
  * val utxos = provider.findUtxos(address)
  * provider.submit(transaction)
  * ```
  *
  * @param apiKey
  *   Blockfrost project ID (API key)
  * @param baseUrl
  *   Blockfrost API base URL
  */
class BlockfrostProvider(apiKey: String, baseUrl: String)
    extends Provider
    with TransactionStatusProvider {

    private val mapper = new ObjectMapper()

    /** Detect the network type from the base URL */
    val networkType: BlockfrostProvider.NetworkType = baseUrl match {
        case BlockfrostProvider.MainnetUrl => BlockfrostProvider.NetworkType.Mainnet
        case BlockfrostProvider.PreprodUrl => BlockfrostProvider.NetworkType.Preprod
        case BlockfrostProvider.PreviewUrl => BlockfrostProvider.NetworkType.Preview
        case _                             => BlockfrostProvider.NetworkType.Unknown
    }

    /** Submit a transaction to the blockchain */
    override def submit(
        tx: Transaction
    )(using ExecutionContext): Future[Either[SubmitError, TransactionHash]] = {
        Future {
            val url = s"$baseUrl/tx/submit"
            val txCbor = tx.toCbor

            // Log detailed TX info for debugging
            val computedTxId = tx.id.toHex
            println(s"[BlockfrostProvider] Submitting transaction:")
            println(s"[BlockfrostProvider]   Computed TX ID: $computedTxId")
            println(s"[BlockfrostProvider]   TX CBOR size: ${txCbor.length} bytes")
            println(
              s"[BlockfrostProvider]   Inputs: ${tx.body.value.inputs.toSeq.map(i => s"${i.transactionId.toHex.take(16)}...#${i.index}").mkString(", ")}"
            )
            println(s"[BlockfrostProvider]   Outputs: ${tx.body.value.outputs.size}")

            Try {
                // Use check = false to prevent throwing RequestFailedException for non-2xx
                // This allows us to access the response body with the actual error message
                val response = requests.post(
                  url,
                  data = txCbor,
                  headers = Map("project_id" -> apiKey, "Content-Type" -> "application/cbor"),
                  check = false
                )
                if response.is2xx then {
                    // Blockfrost returns the TX hash in the response - verify it matches
                    val responseText = response.text().trim.replace("\"", "")
                    if responseText != computedTxId then {
                        println(s"[BlockfrostProvider] WARNING: TX ID mismatch!")
                        println(s"[BlockfrostProvider]   Computed: $computedTxId")
                        println(s"[BlockfrostProvider]   Blockfrost returned: $responseText")
                    }
                    println(
                      s"[BlockfrostProvider] Transaction accepted by Blockfrost: ${responseText.take(16)}..."
                    )
                    Right(tx.id)
                } else {
                    // Now we can access the response body with the actual rejection reason
                    val errorBody = response.text()
                    println(s"[BlockfrostProvider] Transaction REJECTED: ${response.statusCode}")
                    println(s"[BlockfrostProvider] Error response: $errorBody")

                    // Try to parse Blockfrost error format for better messages
                    val errorMsg = Try {
                        val json = ujson.read(errorBody)
                        val message = json.obj.get("message").map(_.str).getOrElse(errorBody)
                        val error = json.obj.get("error").map(_.str).getOrElse("")
                        s"$error: $message"
                    }.getOrElse(errorBody)

                    Left(
                      SubmitError.NodeError(
                        s"Transaction submission failed (${response.statusCode}): $errorMsg"
                      )
                    )
                }
            }.toEither.left.map(e => SubmitError.NetworkError(e.getMessage, Some(e))).flatten
        }
    }

    /** Check if a transaction is in the mempool */
    def checkMempool(txHash: String): Either[String, String] = {
        Try {
            val url = s"$baseUrl/mempool/$txHash"
            val response = requests.get(url, headers = Map("project_id" -> apiKey), check = false)
            if response.is2xx then {
                Right("Transaction is in mempool (pending)")
            } else if response.statusCode == 404 then {
                Right("Transaction not in mempool")
            } else {
                Left(s"Mempool check failed: ${response.statusCode} - ${response.text()}")
            }
        }.toEither.left.map(_.getMessage).flatten
    }

    /** Check if a transaction exists and get its status */
    def getTransactionStatus(txHash: String): Either[String, String] = {
        Try {
            val url = s"$baseUrl/txs/$txHash"
            val response = requests.get(url, headers = Map("project_id" -> apiKey), check = false)
            if response.is2xx then {
                val json = ujson.read(response.text())
                val blockHeight = json.obj.get("block_height").map(_.num.toLong)
                val blockTime = json.obj.get("block_time").map(_.num.toLong)
                Right(
                  s"Confirmed in block ${blockHeight.getOrElse("?")} at time ${blockTime.getOrElse("?")}"
                )
            } else if response.statusCode == 404 then {
                // Check mempool
                checkMempool(txHash) match {
                    case Right(mempoolStatus) => Right(s"Not on chain. $mempoolStatus")
                    case Left(err)            => Right(s"Not on chain. Mempool check failed: $err")
                }
            } else {
                Left(s"Error checking status: ${response.statusCode} - ${response.text()}")
            }
        }.toEither.left.map(_.getMessage).flatten
    }

    /** Find UTxOs at a specific address */
    override def findUtxos(
        address: Address,
        transactionId: Option[TransactionHash],
        datum: Option[DatumOption],
        minAmount: Option[Coin],
        minRequiredTotalAmount: Option[Coin]
    )(using ExecutionContext): Future[Either[RuntimeException, Utxos]] = {
        Future {
            val bech32 = address match {
                case sh @ ShelleyAddress(network, payment, delegation) => sh.toBech32.get
                case _ =>
                    return Future.successful(Left(new RuntimeException("Shelley addresses only")))
            }

            println(s"[BlockfrostProvider] Querying UTxOs for address: $bech32")
            val url = s"$baseUrl/addresses/$bech32/utxos"
            val response = requests.get(url, headers = Map("project_id" -> apiKey))

            if response.is2xx then {
                val utxos = BlockfrostProvider.parseUtxos(mapper, response.text())
                println(s"[BlockfrostProvider] Found ${utxos.size} UTxOs at address")

                // Apply filters
                val filtered = utxos.filter { case (input, output) =>
                    val txIdMatch = transactionId.forall(txId => input.transactionId == txId)
                    val minAmountMatch =
                        minAmount.forall(min => output.value.coin.value >= min.value)
                    txIdMatch && minAmountMatch
                }

                println(
                  s"[BlockfrostProvider] After filtering: ${filtered.size} UTxOs match criteria"
                )
                Right(filtered)
            } else {
                Left(
                  RuntimeException(
                    s"Failed to fetch UTXOs for address $address. Status: ${response.statusCode}, Body: ${response.text()}"
                  )
                )
            }
        }
    }

    /** Find a single UTxO by transaction input */
    override def findUtxo(
        input: TransactionInput
    )(using ExecutionContext): Future[Either[RuntimeException, Utxo]] = {
        Future {
            // Query Blockfrost for the specific transaction output
            val txHash = input.transactionId.toHex
            val outputIndex = input.index

            Try {
                val url = s"$baseUrl/txs/$txHash/utxos"
                val response = requests.get(url, headers = Map("project_id" -> apiKey))

                if response.is2xx then {
                    val json = ujson.read(response.text())
                    val outputs = json("outputs").arr

                    if outputIndex >= outputs.size then {
                        throw new RuntimeException(
                          s"Output index $outputIndex out of bounds (tx has ${outputs.size} outputs)"
                        )
                    }

                    val outputJson = outputs(outputIndex)
                    val address = Address.fromBech32(outputJson("address").str)

                    // Parse value
                    val amountArray = outputJson("amount").arr
                    var lovelace = 0L
                    val multiAssetBuilder = scala.collection.mutable
                        .Map[ScriptHash, scala.collection.mutable.Map[AssetName, Long]]()

                    amountArray.foreach { item =>
                        val unit = item("unit").str
                        val quantity = item("quantity").str.toLong

                        if unit == "lovelace" then {
                            lovelace = quantity
                        } else {
                            // Parse multi-asset: first 56 chars = policy ID (28 bytes hex), rest = asset name
                            val policyId = ScriptHash.fromHex(unit.take(56))
                            val assetNameHex = unit.drop(56)
                            val assetName = AssetName(ByteString.fromHex(assetNameHex))

                            multiAssetBuilder
                                .getOrElseUpdate(policyId, scala.collection.mutable.Map())
                                .update(assetName, quantity)
                        }
                    }

                    val value = if multiAssetBuilder.isEmpty then {
                        Value.lovelace(lovelace)
                    } else {
                        // Convert mutable maps to immutable SortedMaps
                        val immutableAssets: SortedMap[ScriptHash, SortedMap[AssetName, Long]] =
                            SortedMap.from(
                              multiAssetBuilder.view.mapValues(m => SortedMap.from(m))
                            )
                        Value(Coin(lovelace), MultiAsset(immutableAssets))
                    }

                    // Parse datum if present
                    val datumOption: Option[DatumOption] =
                        (
                          outputJson.obj.get("data_hash"),
                          outputJson.obj.get("inline_datum")
                        ) match {
                            case (_, Some(inlineDatum)) =>
                                Some(DatumOption.Inline(Data.fromCbor(hexToBytes(inlineDatum.str))))
                            case (Some(dataHash), None) =>
                                Some(DatumOption.Hash(Hash(ByteString.fromHex(dataHash.str))))
                            case (None, None) => None
                        }

                    val output = TransactionOutput(
                      address = address,
                      value = value,
                      datumOption = datumOption,
                      scriptRef = None
                    )

                    Right(Utxo(input, output))
                } else if response.statusCode == 404 then {
                    Left(new RuntimeException(s"Transaction ${txHash.take(16)}... not found"))
                } else {
                    Left(new RuntimeException(s"Failed to fetch UTxO: ${response.text()}"))
                }
            }.toEither.left.map {
                case e: RuntimeException => e
                case e: Throwable =>
                    new RuntimeException(s"Failed to find UTxO: ${e.getMessage}", e)
            }.flatten
        }
    }

    /** Find multiple UTxOs by transaction inputs */
    override def findUtxos(inputs: Set[TransactionInput])(using
        ExecutionContext
    ): Future[Either[RuntimeException, Utxos]] =
        Future.successful(Left(new RuntimeException("Unimplemented, use `findUtxos(address)`")))

    /** Find a single UTxO at an address */
    override def findUtxo(
        address: Address,
        transactionId: Option[TransactionHash],
        datum: Option[DatumOption],
        minAmount: Option[Coin]
    )(using ExecutionContext): Future[Either[RuntimeException, Utxo]] = {
        findUtxos(address, transactionId, datum, minAmount, None).map { result =>
            result.flatMap { utxos =>
                utxos.headOption match {
                    case Some((input, output)) => Right(Utxo(input, output))
                    case None => Left(new RuntimeException(s"No UTxO found at address $address"))
                }
            }
        }
    }

    /** Check if a transaction has been confirmed on-chain */
    def isTransactionConfirmed(txHash: String): Either[RuntimeException, Boolean] = {
        Try {
            println(s"[BlockfrostProvider] Checking transaction status for: ${txHash.take(16)}...")
            val url = s"$baseUrl/txs/$txHash"
            val response = requests.get(url, headers = Map("project_id" -> apiKey))

            println(
              s"[BlockfrostProvider] Transaction status response - status: ${response.statusCode}"
            )

            if response.is2xx then {
                // Transaction exists and is confirmed
                val json = ujson.read(response.text(), trace = false)
                val blockHeight = json.obj.get("block_height")
                val isConfirmed = blockHeight.exists(_.num > 0)
                println(
                  s"[BlockfrostProvider] Transaction ${txHash.take(16)}... confirmed: $isConfirmed"
                )
                isConfirmed
            } else if response.statusCode == 404 then {
                // Transaction not found (not yet submitted or pending)
                println(
                  s"[BlockfrostProvider] Transaction ${txHash.take(16)}... not found in blockchain"
                )
                false
            } else {
                throw new RuntimeException(
                  s"Failed to check transaction status: ${response.text()}"
                )
            }
        }.toEither.left.map {
            case e: RuntimeException => e
            case e: Throwable =>
                new RuntimeException(s"Failed to check transaction status: ${e.getMessage}", e)
        }
    }

    /** Get the latest block info (current slot and time) from Blockfrost */
    def getLatestBlock(): Either[RuntimeException, (Long, Long)] = {
        Try {
            val url = s"$baseUrl/blocks/latest"
            val response = requests.get(url, headers = Map("project_id" -> apiKey), check = false)

            if response.is2xx then {
                val json = ujson.read(response.text())
                val slot = json("slot").num.toLong
                val time = json("time").num.toLong
                println(
                  s"[BlockfrostProvider] Current blockchain: slot=$slot, time=$time (${java.time.Instant.ofEpochSecond(time)})"
                )
                (slot, time)
            } else {
                throw RuntimeException(
                  s"Failed to get latest block. Status: ${response.statusCode}, Body: ${response.text()}"
                )
            }
        }.toEither.left.map {
            case e: RuntimeException => e
            case e: Throwable =>
                new RuntimeException(s"Failed to get latest block: ${e.getMessage}", e)
        }
    }

    /** Validate transaction before submission - checks validity interval against current blockchain
      * state
      */
    def validateBeforeSubmit(tx: Transaction, slotConfig: SlotConfig): Either[String, Unit] = {
        getLatestBlock() match {
            case Left(err) =>
                println(s"[BlockfrostProvider] WARNING: Could not validate - ${err.getMessage}")
                Right(()) // Continue anyway if we can't validate

            case Right((currentSlot, currentTime)) =>
                val txBody = tx.body.value

                // Check validity interval
                val validFromSlot = txBody.validityStartSlot.getOrElse(0L)
                val validToSlot = txBody.ttl.getOrElse(Long.MaxValue)

                val validFromTime = slotConfig.slotToTime(validFromSlot)
                val validToTime = slotConfig.slotToTime(validToSlot)

                println(s"[BlockfrostProvider] TX validity interval:")
                println(
                  s"[BlockfrostProvider]   From: slot $validFromSlot (${java.time.Instant.ofEpochMilli(validFromTime)})"
                )
                println(
                  s"[BlockfrostProvider]   To:   slot $validToSlot (${java.time.Instant.ofEpochMilli(validToTime)})"
                )
                println(s"[BlockfrostProvider]   Current: slot $currentSlot")

                if currentSlot < validFromSlot then {
                    val diff = validFromSlot - currentSlot
                    Left(
                      s"Transaction validity not started yet! Current slot $currentSlot < validFrom $validFromSlot (diff: $diff slots)"
                    )
                } else if currentSlot > validToSlot then {
                    val diff = currentSlot - validToSlot
                    Left(
                      s"Transaction validity expired! Current slot $currentSlot > validTo $validToSlot (diff: $diff slots)"
                    )
                } else {
                    println(s"[BlockfrostProvider] ✓ Validity interval OK")
                    Right(())
                }
        }
    }

    /** Fetch latest protocol parameters (required by Provider trait) */
    override def fetchLatestParams(using ExecutionContext): Future[ProtocolParams] = {
        Future {
            getProtocolParams() match {
                case Right(params) => params
                case Left(err)     => throw err
            }
        }
    }

    /** Get protocol parameters from Blockfrost */
    def getProtocolParams(): Either[RuntimeException, ProtocolParams] = {
        Try {
            println(s"[BlockfrostProvider] Fetching protocol parameters...")
            val url = s"$baseUrl/epochs/latest/parameters"
            val response = requests.get(url, headers = Map("project_id" -> apiKey))

            if response.is2xx then {
                println(s"[BlockfrostProvider] Successfully fetched protocol parameters")
                // Debug: compare raw JSON values vs parsed values
                val json = ujson.read(response.text())
                val rawPriceMem = json("price_mem").num
                val rawPriceStep = json("price_step").num
                println(s"[BlockfrostProvider] Raw price_mem: $rawPriceMem")
                println(s"[BlockfrostProvider] Raw price_step: $rawPriceStep")

                val params = ProtocolParams.fromBlockfrostJson(response.text())
                val parsedMem = params.executionUnitPrices.priceMemory
                val parsedStep = params.executionUnitPrices.priceSteps
                println(
                  s"[BlockfrostProvider] Parsed priceMemory: $parsedMem (${parsedMem.numerator}/${parsedMem.denominator} = ${parsedMem.toDouble})"
                )
                println(
                  s"[BlockfrostProvider] Parsed priceSteps: $parsedStep (${parsedStep.numerator}/${parsedStep.denominator} = ${parsedStep.toDouble})"
                )

                // Check for precision loss
                if math.abs(rawPriceMem - parsedMem.toDouble) > 1e-15 then
                    println(
                      s"[BlockfrostProvider] WARNING: price_mem precision loss! Raw=$rawPriceMem, Parsed=${parsedMem.toDouble}, Diff=${rawPriceMem - parsedMem.toDouble}"
                    )
                if math.abs(rawPriceStep - parsedStep.toDouble) > 1e-15 then
                    println(
                      s"[BlockfrostProvider] WARNING: price_step precision loss! Raw=$rawPriceStep, Parsed=${parsedStep.toDouble}, Diff=${rawPriceStep - parsedStep.toDouble}"
                    )

                params
            } else {
                throw RuntimeException(
                  s"Failed to fetch protocol parameters. Status: ${response.statusCode}, Body: ${response.text()}"
                )
            }
        }.toEither.left.map {
            case e: RuntimeException => e
            case e: Throwable =>
                new RuntimeException(s"Failed to get protocol parameters: ${e.getMessage}", e)
        }
    }
}

object BlockfrostProvider {

    /** Network type enumeration */
    enum NetworkType {
        case Mainnet, Preprod, Preview, Unknown
    }

    val MainnetUrl = "https://cardano-mainnet.blockfrost.io/api/v0"
    val PreviewUrl = "https://cardano-preview.blockfrost.io/api/v0"
    val PreprodUrl = "https://cardano-preprod.blockfrost.io/api/v0"

    /** Create a Blockfrost provider for mainnet */
    def mainnet(apiKey: String) = new BlockfrostProvider(apiKey, MainnetUrl)

    /** Create a Blockfrost provider for preview testnet */
    def preview(apiKey: String) = new BlockfrostProvider(apiKey, PreviewUrl)

    /** Create a Blockfrost provider for preprod testnet */
    def preprod(apiKey: String) = new BlockfrostProvider(apiKey, PreprodUrl)

    /** Parse UTxOs from Blockfrost JSON response */
    def parseUtxos(mapper: ObjectMapper, json: String): Utxos = {
        val utxos = mapper.readValue(json, classOf[Array[bloxbean.Utxo]])
        utxos.map { utxo =>
            val txInput = TransactionInput(
              TransactionHash.fromHex(utxo.getTxHash),
              utxo.getOutputIndex
            )

            val address = Address.fromBech32(utxo.getAddress)
            val value = utxo.toValue.toLedgerValue

            // Parse datum if present
            val datumOption: Option[DatumOption] =
                Option(utxo.getDataHash) -> Option(utxo.getInlineDatum) match
                    case (_, Some(inlineDatum)) =>
                        Some(DatumOption.Inline(Data.fromCbor(inlineDatum.hexToBytes)))
                    case (Some(dataHash), None) =>
                        Some(DatumOption.Hash(Hash(ByteString.fromHex(dataHash))))
                    case (None, None) => None

            val txOutput = TransactionOutput(
              address = address,
              value = value,
              datumOption = datumOption,
              scriptRef = None
            )

            txInput -> txOutput
        }.toMap
    }
}

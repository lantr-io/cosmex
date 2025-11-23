package cosmex.cardano

import com.bloxbean.cardano.client.api.model as bloxbean
import com.fasterxml.jackson.databind.ObjectMapper
import cosmex.util.TransactionStatusProvider
import scalus.builtin.{ByteString, Data}
import scalus.cardano.address.{Address, ShelleyAddress}
import scalus.cardano.ledger.*
import scalus.cardano.ledger.BloxbeanToLedgerTranslation.*
import scalus.cardano.node.Provider
import scalus.utils.Hex.hexToBytes

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

    /** Submit a transaction to the blockchain */
    override def submit(tx: Transaction): Either[RuntimeException, Unit] = {
        val url = s"$baseUrl/tx/submit"
        val txCbor = tx.toCbor

        Try {
            val response = requests.post(
              url,
              data = txCbor,
              headers = Map("project_id" -> apiKey, "Content-Type" -> "application/cbor")
            )
            if response.is2xx then {
                println(s"[BlockfrostProvider] Transaction submitted: ${tx.id.toHex.take(16)}...")
                Right(())
            } else {
                Left(new RuntimeException(s"Transaction submission failed: ${response.text()}"))
            }
        }.toEither.left.map(new RuntimeException(_)).flatten
    }

    /** Find UTxOs at a specific address */
    override def findUtxos(
        address: Address,
        transactionId: Option[TransactionHash] = None,
        datum: Option[DatumOption] = None,
        minAmount: Option[Coin] = None,
        minRequiredTotalAmount: Option[Coin] = None
    ): Either[RuntimeException, Utxos] = {
        val bech32 = address match {
            case sh @ ShelleyAddress(network, payment, delegation) => sh.toBech32.get
            case _ => return Left(new RuntimeException("Shelley addresses only"))
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
                val minAmountMatch = minAmount.forall(min => output.value.coin.value >= min.value)
                txIdMatch && minAmountMatch
            }

            println(s"[BlockfrostProvider] After filtering: ${filtered.size} UTxOs match criteria")
            Right(filtered)
        } else {
            Left(
              RuntimeException(
                s"Failed to fetch UTXOs for address $address. Status: ${response.statusCode}, Body: ${response.text()}"
              )
            )
        }
    }

    /** Find a single UTxO by transaction input */
    override def findUtxo(
        input: TransactionInput
    ): Either[RuntimeException, Utxo] =
        Left(new RuntimeException("Unimplemented, use `findUtxos(address)`"))

    /** Find multiple UTxOs by transaction inputs */
    override def findUtxos(inputs: Set[TransactionInput]): Either[RuntimeException, Utxos] = Left(
      new RuntimeException("Unimplemented, use `findUtxos(address)`")
    )

    /** Find a single UTxO at an address */
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

    /** Get protocol parameters from Blockfrost */
    def getProtocolParams(): Either[RuntimeException, ProtocolParams] = {
        Try {
            println(s"[BlockfrostProvider] Fetching protocol parameters...")
            val url = s"$baseUrl/epochs/latest/parameters"
            val response = requests.get(url, headers = Map("project_id" -> apiKey))

            if response.is2xx then {
                println(s"[BlockfrostProvider] Successfully fetched protocol parameters")
                ProtocolParams.fromBlockfrostJson(response.text())
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

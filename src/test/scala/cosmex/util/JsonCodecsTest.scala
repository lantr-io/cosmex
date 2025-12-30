package cosmex.util

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import cosmex.*
import cosmex.util.JsonCodecs.given
import org.scalatest.funsuite.AnyFunSuite
import scalus.builtin.ByteString
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.Context
import scalus.cardano.node.Emulator
import scalus.ledger.api.v1.PubKeyHash
import scalus.utils.await
import upickle.default.*

import scala.concurrent.ExecutionContext.Implicits.global

class JsonCodecsTest extends AnyFunSuite {

    // Environment - similar to CosmexTest
    private val cardanoInfo: CardanoInfo = CardanoInfo.mainnet
    private val network = Networks.preview()

    // Exchange setup
    private val exchangeAccount = new Account(network, 1)
    private val exchangePrivKey =
        ByteString.fromArray(exchangeAccount.hdKeyPair().getPrivateKey.getKeyData)
    private val exchangePubKey = ByteString.fromArray(exchangeAccount.publicKeyBytes())
    private val exchangePubKeyHash =
        ByteString.fromArray(exchangeAccount.hdKeyPair().getPublicKey.getKeyHash)
    private val exchangeParams = ExchangeParams(
      exchangePkh = PubKeyHash(exchangePubKeyHash),
      contestationPeriodInMilliseconds = 5000,
      exchangePubKey = exchangePubKey
    )

    // Client setup
    private val clientAccount = new Account(network, 2)
    private val clientPubKey = ByteString.fromArray(clientAccount.publicKeyBytes())
    private val clientPubKeyHash =
        ByteString.fromArray(clientAccount.hdKeyPair().getPublicKey.getKeyHash)
    private val clientAddress = Address(
      cardanoInfo.network,
      Credential.KeyHash(AddrKeyHash.fromByteString(clientPubKeyHash))
    )

    private val txbuilder = CosmexTransactions(exchangeParams, cardanoInfo)

    private val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))
    private val initialUtxos = Map(
      TransactionInput(genesisHash, 0) ->
          TransactionOutput(
            address = clientAddress,
            value = Value.ada(1000)
          )
    )

    private def newEmulator(): Emulator =
        Emulator(initialUtxos = initialUtxos, initialContext = Context.testMainnet(slot = 1000))

    test("Transaction serialization round-trip") {
        val emulator = newEmulator()

        // Get a UTxO
        val depositUtxo = emulator
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(100))
            )
            .await()
            .toOption
            .get

        // Build a transaction
        val tx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = Value.ada(100L)
        )

        println(s"Original TX ID: ${tx.id.toHex}")
        println(s"Original TX fee: ${tx.body.value.fee}")
        println(s"Original TX CBOR length: ${tx.toCbor.length}")

        // Serialize using JsonCodecs
        val json = write(tx)
        println(s"Serialized Transaction JSON length: ${json.length}")
        println(s"Serialized Transaction JSON (first 200 chars): ${json.take(200)}...")

        // Deserialize
        val txBack = read[Transaction](json)

        // Verify
        assert(txBack.id == tx.id, s"Transaction ID mismatch: ${txBack.id.toHex} != ${tx.id.toHex}")
        assert(txBack.body.value.fee == tx.body.value.fee)
        assert(txBack.isValid == tx.isValid)
        println("Transaction round-trip: SUCCESS")
    }

    test("ClientResponse.RebalanceRequired serialization round-trip") {
        val emulator = newEmulator()

        val depositUtxo = emulator
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(100))
            )
            .await()
            .toOption
            .get

        val tx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = Value.ada(100L)
        )

        val response: ClientResponse = ClientResponse.RebalanceRequired(tx)

        // Serialize
        val json = write(response)
        println(s"Serialized ClientResponse.RebalanceRequired JSON length: ${json.length}")
        println(s"Serialized JSON (first 300 chars): ${json.take(300)}...")

        // Deserialize
        val responseBack = read[ClientResponse](json)

        // Verify
        assert(responseBack.isInstanceOf[ClientResponse.RebalanceRequired])
        val txBack = responseBack.asInstanceOf[ClientResponse.RebalanceRequired].tx
        assert(txBack.id == tx.id, s"Transaction ID mismatch: ${txBack.id.toHex} != ${tx.id.toHex}")
        println("ClientResponse.RebalanceRequired round-trip: SUCCESS")
    }

    test("ClientRequest.SignRebalance serialization round-trip") {
        val emulator = newEmulator()

        val depositUtxo = emulator
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(100))
            )
            .await()
            .toOption
            .get

        val tx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = Value.ada(100L)
        )

        val clientId = ClientId(
          TransactionInput(
            TransactionHash.fromByteString(ByteString.fromHex("1" * 64)),
            0
          )
        )

        val request: ClientRequest = ClientRequest.SignRebalance(clientId, tx)

        // Serialize
        val json = write(request)
        println(s"Serialized ClientRequest.SignRebalance JSON length: ${json.length}")
        println(s"Serialized JSON (first 300 chars): ${json.take(300)}...")

        // Deserialize
        val requestBack = read[ClientRequest](json)

        // Verify
        assert(requestBack.isInstanceOf[ClientRequest.SignRebalance])
        val (cidBack, txBack) = requestBack match {
            case ClientRequest.SignRebalance(c, t) => (c, t)
            case _                                 => fail("Expected SignRebalance")
        }
        assert(txBack.id == tx.id, s"Transaction ID mismatch")
        assert(cidBack.txOutRef == clientId.txOutRef)
        println("ClientRequest.SignRebalance round-trip: SUCCESS")
    }

    test("Verify JSON structure of RebalanceRequired") {
        val emulator = newEmulator()

        val depositUtxo = emulator
            .findUtxo(
              address = clientAddress,
              minAmount = Some(Coin.ada(100))
            )
            .await()
            .toOption
            .get

        val tx = txbuilder.openChannel(
          clientInput = depositUtxo,
          clientPubKey = clientPubKey,
          depositAmount = Value.ada(100L)
        )

        val response: ClientResponse = ClientResponse.RebalanceRequired(tx)

        // Serialize
        val json = write(response)

        // Parse as raw JSON to inspect structure
        val parsed = ujson.read(json)
        println(s"JSON structure: ${parsed.render(indent = 2).take(500)}...")

        // Verify structure
        assert(parsed.obj.contains("$type"), "Should have $$type field")
        assert(parsed("$type").str == "RebalanceRequired", "$$type should be RebalanceRequired")
        assert(parsed.obj.contains("tx"), "Should have tx field")

        val txField = parsed("tx").str
        println(s"tx field length: ${txField.length}")
        println(s"tx field (first 100 chars): ${txField.take(100)}")

        // tx should be a hex-encoded CBOR string
        assert(txField.nonEmpty, "tx field should not be empty")
        assert(txField.matches("[0-9a-f]+"), "tx field should be hex-encoded")
        println("JSON structure verification: SUCCESS")
    }
}

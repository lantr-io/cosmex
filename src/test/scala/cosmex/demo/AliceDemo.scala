package cosmex.demo

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import cosmex.*
import cosmex.DemoHelpers.*
import cosmex.ws.SimpleWebSocketClient
import scalus.builtin.ByteString
import scalus.cardano.address.Address
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.ledger.api.v1.PubKeyHash
import scalus.ledger.api.v3.{TxId, TxOutRef}
import scalus.prelude.{AssocMap, Option as ScalusOption}
import scalus.testing.kit.MockLedgerApi

import scala.util.{Failure, Success}

/** Demonstration of Alice opening a channel and creating a limit order */
object AliceDemo {

    def main(args: Array[String]): Unit = {
        println("=" * 60)
        println("COSMEX Demo: Alice Opens Channel & Creates Order")
        println("=" * 60)

        // Setup
        val cardanoInfo = CardanoInfo.mainnet
        val network = Networks.preview()

        // Create Alice's account (same seed as tests for consistency)
        println("\n[1] Creating Alice's account...")
        val aliceAccount = new Account(network, 2)
        val alicePubKey = getPubKey(aliceAccount)
        val alicePubKeyHash = getPubKeyHash(aliceAccount)
        println(s"    Alice PubKeyHash: ${alicePubKeyHash.toHex}")

        // Create exchange account
        val exchangeAccount = new Account(network, 1)
        val exchangePubKey = getPubKey(exchangeAccount)
        val exchangePubKeyHash = getPubKeyHash(exchangeAccount)
        val exchangeParams = ExchangeParams(
          exchangePkh = PubKeyHash(exchangePubKeyHash),
          contestationPeriodInMilliseconds = 5000,
          exchangePubKey = exchangePubKey
        )
        println(s"    Exchange PubKeyHash: ${exchangePubKeyHash.toHex}")

        // Create Alice's address
        val aliceAddress = Address(
          cardanoInfo.network,
          Credential.KeyHash(AddrKeyHash.fromByteString(alicePubKeyHash))
        )

        // Setup mock ledger with Alice's initial UTxO
        val genesisHash = TransactionHash.fromByteString(ByteString.fromHex("0" * 64))
        val initialUtxos = Map(
          TransactionInput(genesisHash, 0) ->
              TransactionOutput(
                address = aliceAddress,
                value = Value.ada(1000) // Alice starts with 1000 ADA
              )
        )

        val provider = MockLedgerApi(
          initialUtxos = initialUtxos,
          context = Context.testMainnet(slot = 1000),
          validators = MockLedgerApi.defaultValidators -
              MissingKeyHashesValidator -
              ProtocolParamsViewHashesMatchValidator -
              MissingRequiredDatumsValidator,
          mutators = MockLedgerApi.defaultMutators -
              PlutusScriptsTransactionMutator
        )

        // Create transaction builder
        val txbuilder = Transactions(exchangeParams, cardanoInfo)

        // Connect to WebSocket server
        println("\n[2] Connecting to WebSocket server...")
        val client =
            try {
                SimpleWebSocketClient("ws://localhost:8080/ws")
            } catch {
                case e: Exception =>
                    println(s"    ERROR: Could not connect to server: ${e.getMessage}")
                    println("    Make sure the server is running:")
                    println("    sbtn \"runMain cosmex.ws.CosmexWebSocketServer\"")
                    sys.exit(1)
            }

        try {
            // Step 1: Open channel with 500 ADA
            println("\n[3] Opening channel with 500 ADA deposit...")

            // Find Alice's UTxO
            val depositUtxo = provider
                .findUtxo(
                  address = aliceAddress,
                  minAmount = Some(Coin.ada(500))
                )
                .toOption
                .get

            // Build opening transaction
            val depositAmount = Value.ada(500L)
            val openChannelTx = txbuilder.openChannel(
              clientInput = depositUtxo,
              clientPubKey = alicePubKey,
              depositAmount = depositAmount
            )

            println(s"    Transaction built: ${openChannelTx.id.toHex.take(16)}...")

            // Extract actual deposit amount from transaction output
            // (The server's CosmexScriptAddress)
            val actualDepositAmount = openChannelTx.body.value.outputs.view
                .map(_.value)
                .headOption // For mock, just take first output
                .getOrElse(openChannelTx.body.value.outputs.toSeq.head.value)

            // Create client TxOutRef from first input
            val firstInput = openChannelTx.body.value.inputs.toSeq.head
            val clientTxOutRef = TxOutRef(TxId(firstInput.transactionId), firstInput.index)

            // Create and sign initial snapshot (version 0)
            val initialSnapshot = mkInitialSnapshot(actualDepositAmount.value)
            val clientSignedSnapshot =
                mkClientSignedSnapshot(aliceAccount, clientTxOutRef, initialSnapshot)

            println(s"    Snapshot v${initialSnapshot.snapshotVersion} created and signed")

            // Send OpenChannel request
            val openRequest = ClientRequest.OpenChannel(openChannelTx, clientSignedSnapshot)
            client.sendRequest(openRequest, timeoutSeconds = 10) match {
                case Success(ClientResponse.ChannelOpened(signedSnapshot)) =>
                    println(s"    ✓ Channel opened successfully!")
                    println(
                      s"    Snapshot version: ${signedSnapshot.signedSnapshot.snapshotVersion}"
                    )
                    println(
                      s"    Exchange signature: ${signedSnapshot.snapshotExchangeSignature.toHex.take(32)}..."
                    )

                    // Step 2: Create a buy order
                    println("\n[4] Creating BUY order: 100 ADA @ 0.50 USDM/ADA...")

                    // Create the order (BUY 100 ADA at price 500000)
                    val buyOrder = mkBuyOrder(
                      pair = (DemoHelpers.ADA, DemoHelpers.ADA), // Using ADA/ADA for simplicity
                      amount = 100_000_000, // 100 ADA (in lovelace)
                      price = 500_000 // 0.50 price units
                    )

                    // Derive clientId from the transaction
                    val clientId = ClientId(TransactionInput(openChannelTx.id, 0))

                    // Send CreateOrder request
                    val orderRequest = ClientRequest.CreateOrder(clientId, buyOrder)
                    client.sendRequest(orderRequest, timeoutSeconds = 10) match {
                        case Success(ClientResponse.OrderCreated(orderId)) =>
                            println(s"    ✓ Order created successfully!")
                            println(s"    Order ID: $orderId")
                            println(s"    Amount: 100 ADA")
                            println(s"    Price: 0.50 units")

                            println("\n" + "=" * 60)
                            println("Demo completed successfully!")
                            println("=" * 60)

                        case Success(ClientResponse.Error(msg)) =>
                            println(s"    ✗ Order creation failed: $msg")

                        case Success(other) =>
                            println(s"    ✗ Unexpected response: $other")

                        case Failure(e) =>
                            println(s"    ✗ Error: ${e.getMessage}")
                    }

                case Success(ClientResponse.Error(msg)) =>
                    println(s"    ✗ Channel opening failed: $msg")

                case Success(other) =>
                    println(s"    ✗ Unexpected response: $other")

                case Failure(e) =>
                    println(s"    ✗ Error: ${e.getMessage}")
            }

        } finally {
            // Cleanup
            client.close()
            println("\n[5] Disconnected from server")
        }
    }
}

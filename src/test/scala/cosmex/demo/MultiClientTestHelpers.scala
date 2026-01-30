package cosmex.demo

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.crypto.Blake2bUtil
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration
import com.bloxbean.cardano.client.transaction.util.TransactionBytes
import cosmex.ws.SimpleWebSocketClient
import cosmex.{ClientId, ClientRequest, ClientResponse, LimitOrder}
import scalus.builtin.ByteString
import scalus.cardano.ledger.*
import scalus.ledger.api.v3.TxOutRef
import upickle.default.*

import scala.util.{Failure, Success, Try}

/** Helper utilities for multi-client demo tests */
object MultiClientTestHelpers {

    /** Sign a transaction using Bloxbean's extended Ed25519 signing */
    def signTransaction(account: Account, tx: Transaction): Transaction = {
        val hdKeyPair = account.hdKeyPair()
        val txBytes = TransactionBytes(tx.toCbor)
        val txBodyHash = Blake2bUtil.blake2bHash256(txBytes.getTxBodyBytes)

        val signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider
        val signature = signingProvider.signExtended(
          txBodyHash,
          hdKeyPair.getPrivateKey.getKeyData
        )

        val witness = VKeyWitness(
          signature = ByteString.fromArray(signature),
          vkey = ByteString.fromArray(hdKeyPair.getPublicKey.getKeyData.take(32))
        )

        val witnessSet = tx.witnessSet.copy(
          vkeyWitnesses = TaggedSortedSet.from(Seq(witness))
        )
        tx.withWitness(witnessSet)
    }

    /** Result of channel opening */
    case class ChannelOpenResult(
        clientId: ClientId,
        clientTxOutRef: TxOutRef
    )

    /** Open a channel via WebSocket and wait for confirmation. Returns the server-assigned ClientId
      * (based on channel output reference).
      */
    def openChannel(
        client: SimpleWebSocketClient,
        openChannelTx: Transaction,
        clientSignedSnapshot: cosmex.SignedSnapshot,
        isMockProvider: Boolean,
        name: String
    ): ClientId = {
        client.sendMessage(ClientRequest.OpenChannel(openChannelTx, clientSignedSnapshot))

        val firstResponse = client.receiveMessage(timeoutSeconds = 10)
        firstResponse match {
            case Success(responseJson) =>
                read[ClientResponse](responseJson) match {
                    case ClientResponse.ChannelPending(txId) =>
                        println(s"[$name] ✓ Channel pending, txId: ${txId.take(16)}...")
                        waitForChannelOpened(client, name)

                    case ClientResponse.ChannelOpened(_, channelRef) if isMockProvider =>
                        println(s"[$name] ✓ Channel opened instantly (mock provider)")
                        ClientId(channelRef)

                    case ClientResponse.Error(code, msg) =>
                        throw new RuntimeException(s"[$name] Channel opening failed [$code]: $msg")

                    case other =>
                        throw new RuntimeException(s"[$name] Unexpected response: $other")
                }
            case Failure(e) =>
                throw new RuntimeException(s"[$name] Error receiving response: ${e.getMessage}")
        }
    }

    private def waitForChannelOpened(client: SimpleWebSocketClient, name: String): ClientId = {
        println(s"[$name] Waiting for channel confirmation...")
        val openResponse = client.receiveMessage(timeoutSeconds = 200)
        openResponse match {
            case Success(openJson) =>
                read[ClientResponse](openJson) match {
                    case ClientResponse.ChannelOpened(_, channelRef) =>
                        println(s"[$name] ✓ Channel opened!")
                        ClientId(channelRef)
                    case ClientResponse.Error(code, msg) =>
                        throw new RuntimeException(
                          s"[$name] Channel confirmation failed [$code]: $msg"
                        )
                    case other =>
                        throw new RuntimeException(s"[$name] Expected ChannelOpened, got: $other")
                }
            case Failure(e) =>
                throw new RuntimeException(
                  s"[$name] Error waiting for ChannelOpened: ${e.getMessage}"
                )
        }
    }

    /** Create and submit an order, handling stale messages */
    def createOrder(
        client: SimpleWebSocketClient,
        clientId: ClientId,
        order: LimitOrder,
        name: String
    ): Unit = {
        val orderRequest = ClientRequest.CreateOrder(clientId, order)
        client.sendRequest(orderRequest, timeoutSeconds = 10) match {
            case Success(ClientResponse.OrderCreated(orderId)) =>
                println(s"[$name] ✓ Order created! OrderID: $orderId")

            case Success(ClientResponse.ChannelPending(_)) =>
                // Ignore stale ChannelPending (can arrive out of order with MockLedgerApi)
                println(s"[$name] Ignoring stale ChannelPending, retrying...")
                handleAsyncMessages(client, name)

            case Success(ClientResponse.SnapshotToSign(_, _)) =>
                // SnapshotToSign is sent async after order creation - ignore and wait for OrderCreated
                println(s"[$name] Ignoring async SnapshotToSign, retrying...")
                handleAsyncMessages(client, name)

            case Success(ClientResponse.Error(code, msg)) =>
                throw new RuntimeException(s"[$name] Order creation failed [$code]: $msg")

            case Success(other) =>
                throw new RuntimeException(s"[$name] Unexpected response: $other")

            case Failure(e) =>
                throw new RuntimeException(s"[$name] Error: ${e.getMessage}")
        }
    }

    private def handleAsyncMessages(client: SimpleWebSocketClient, name: String): Unit = {
        client.receiveMessage(timeoutSeconds = 10) match {
            case Success(msgJson) =>
                read[ClientResponse](msgJson) match {
                    case ClientResponse.OrderCreated(orderId) =>
                        println(s"[$name] ✓ Order created! OrderID: $orderId")
                    case ClientResponse.SnapshotToSign(_, _) =>
                        // Keep waiting for OrderCreated
                        println(s"[$name] Ignoring async SnapshotToSign, retrying...")
                        handleAsyncMessages(client, name)
                    case ClientResponse.Error(code, msg) =>
                        throw new RuntimeException(s"[$name] Order creation failed [$code]: $msg")
                    case other =>
                        throw new RuntimeException(
                          s"[$name] Unexpected response after retry: $other"
                        )
                }
            case Failure(e) =>
                throw new RuntimeException(s"[$name] Error receiving OrderCreated: ${e.getMessage}")
        }
    }

    /** Wait for potential trade execution (OrderExecuted notifications) */
    def waitForTradeExecution(
        client: SimpleWebSocketClient,
        name: String,
        maxAttempts: Int = 10
    ): Boolean = {
        println(s"[$name] Waiting for potential order matching...")
        var attempts = 0
        var tradeReceived = false

        while attempts < maxAttempts && !tradeReceived do {
            client.receiveMessage(timeoutSeconds = 1) match {
                case Success(msgJson) =>
                    Try(read[ClientResponse](msgJson)) match {
                        case Success(ClientResponse.OrderExecuted(trade)) =>
                            println(
                              s"[$name] ✓ Order executed! Trade: ${trade.orderId}, amount: ${trade.tradeAmount}, price: ${trade.tradePrice}"
                            )
                            tradeReceived = true
                        case Success(ClientResponse.ChannelPending(_)) =>
                            println(s"[$name] Ignoring stale ChannelPending message")
                        case Success(other) =>
                            println(
                              s"[$name] Received other message: ${other.getClass.getSimpleName}"
                            )
                        case Failure(e) =>
                            println(s"[$name] Failed to parse message: ${e.getMessage}")
                    }
                case Failure(e) =>
                    e match {
                        case _: java.util.concurrent.TimeoutException =>
                            attempts += 1
                        case other =>
                            println(
                              s"[$name] Unexpected error receiving message: ${other.getMessage}"
                            )
                            other.printStackTrace()
                            attempts += 1
                    }
            }
        }

        if !tradeReceived && attempts >= maxAttempts then {
            println(s"[$name] No trade executed within timeout")
        }
        tradeReceived
    }

    /** Handle the rebalancing flow */
    def handleRebalancing(
        client: SimpleWebSocketClient,
        account: Account,
        clientId: ClientId,
        name: String,
        triggerRebalancing: Boolean,
        maxAttempts: Int = 15
    ): Unit = {
        if triggerRebalancing then {
            println(s"\n[$name] === Starting Rebalancing Phase ===")
            Thread.sleep(1000)
            triggerFixBalance(client, clientId, name)
        }

        waitForRebalanceCompletion(client, account, clientId, name, maxAttempts)
        println(s"[$name] === Rebalancing Phase Complete ===\n")
    }

    private def triggerFixBalance(
        client: SimpleWebSocketClient,
        clientId: ClientId,
        name: String
    ): Unit = {
        println(s"[$name] Triggering FixBalance...")
        client.sendMessage(ClientRequest.FixBalance(clientId))

        client.receiveMessage(timeoutSeconds = 10) match {
            case Success(msgJson) =>
                Try(read[ClientResponse](msgJson)) match {
                    case Success(ClientResponse.RebalanceStarted) =>
                        println(s"[$name] ✓ Rebalancing started!")
                    case Success(ClientResponse.Error(code, msg)) =>
                        println(s"[$name] Rebalancing error [$code]: $msg")
                    case Success(other) =>
                        println(s"[$name] Unexpected response: ${other.getClass.getSimpleName}")
                    case Failure(e) =>
                        println(s"[$name] Failed to parse response: ${e.getMessage}")
                }
            case Failure(e) =>
                println(s"[$name] Error waiting for RebalanceStarted: ${e.getMessage}")
        }
    }

    private def waitForRebalanceCompletion(
        client: SimpleWebSocketClient,
        account: Account,
        clientId: ClientId,
        name: String,
        maxAttempts: Int
    ): Unit = {
        println(s"[$name] Waiting for RebalanceRequired...")
        var attempts = 0
        var rebalanceComplete = false

        while attempts < maxAttempts && !rebalanceComplete do {
            client.receiveMessage(timeoutSeconds = 2) match {
                case Success(msgJson) =>
                    Try(read[ClientResponse](msgJson)) match {
                        case Success(ClientResponse.RebalanceRequired(tx)) =>
                            signAndSendRebalance(client, account, clientId, tx, name)

                        case Success(ClientResponse.RebalanceComplete(snapshot, newChannelRef)) =>
                            println(
                              s"[$name] ✓ Rebalancing complete! Snapshot version: ${snapshot.signedSnapshot.snapshotVersion}"
                            )
                            println(
                              s"[$name] New channel ref: ${newChannelRef.transactionId.toHex.take(16)}...#${newChannelRef.index}"
                            )
                            rebalanceComplete = true

                        case Success(ClientResponse.RebalanceAborted(reason)) =>
                            println(s"[$name] Rebalancing aborted: $reason")
                            rebalanceComplete = true

                        case Success(ClientResponse.RebalanceStarted) =>
                            println(s"[$name] Received RebalanceStarted")

                        case Success(ClientResponse.Error(code, msg)) =>
                            println(s"[$name] Error during rebalancing [$code]: $msg")
                            rebalanceComplete = true

                        case Success(other) =>
                            println(s"[$name] Received: ${other.getClass.getSimpleName}")

                        case Failure(e) =>
                            println(s"[$name] Failed to parse message: ${e.getMessage}")
                    }
                case Failure(e) =>
                    e match {
                        case _: java.util.concurrent.TimeoutException =>
                            attempts += 1
                        case other =>
                            println(s"[$name] Error: ${other.getMessage}")
                            attempts += 1
                    }
            }
        }

        if !rebalanceComplete then {
            println(
              s"[$name] Rebalancing did not complete within timeout (this may be expected if no rebalancing needed)"
            )
        }
    }

    private def signAndSendRebalance(
        client: SimpleWebSocketClient,
        account: Account,
        clientId: ClientId,
        tx: Transaction,
        name: String
    ): Unit = {
        println(s"[$name] ✓ Received RebalanceRequired, signing transaction...")
        val signedTx = signTransaction(account, tx)
        println(s"[$name] Sending SignRebalance...")
        client.sendMessage(ClientRequest.SignRebalance(clientId, signedTx))
        println(s"[$name] ✓ SignRebalance sent")
    }
}

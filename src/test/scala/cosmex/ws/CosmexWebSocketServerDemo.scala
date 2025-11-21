package cosmex.ws

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import cosmex.*
import ox.*
import scalus.builtin.ByteString
import scalus.cardano.ledger.*
import scalus.cardano.ledger.rules.*
import scalus.ledger.api.v1.PubKeyHash
import scalus.testing.kit.MockLedgerApi

/** Demo main method that uses MockLedgerApi for testing */
object CosmexWebSocketServerDemo {

    def main(args: Array[String]): Unit = supervised {
        // Initialize server components
        val cardanoInfo = CardanoInfo.mainnet
        val network = Networks.preview()

        // Create exchange account (same seed as tests)
        val exchangeAccount = new Account(network, 1)
        val exchangePrivKey = ByteString.fromArray(
          exchangeAccount.privateKeyBytes().take(32)
        )
        val exchangePubKey = ByteString.fromArray(exchangeAccount.publicKeyBytes())
        val exchangePubKeyHash = ByteString.fromArray(
          exchangeAccount.hdKeyPair().getPublicKey.getKeyHash
        )

        val exchangeParams = ExchangeParams(
          exchangePkh = PubKeyHash(exchangePubKeyHash),
          contestationPeriodInMilliseconds = 5000,
          exchangePubKey = exchangePubKey
        )

        println(s"Exchange PubKeyHash: ${exchangePubKeyHash.toHex}")

        // Create mock ledger for testing
        val provider = MockLedgerApi(
          initialUtxos = Map.empty,
          context = Context.testMainnet(slot = 1000),
          validators = MockLedgerApi.defaultValidators -
              MissingKeyHashesValidator -
              ProtocolParamsViewHashesMatchValidator -
              MissingRequiredDatumsValidator,
          mutators = MockLedgerApi.defaultMutators -
              PlutusScriptsTransactionMutator
        )

        // Create server instance
        val server = Server(cardanoInfo, exchangeParams, provider, exchangePrivKey)

        // Run the WebSocket server
        CosmexWebSocketServer.run(server, port = 8080)
    }
}

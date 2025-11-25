package cosmex

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration
import scalus.builtin.{Builtins, ByteString}
import scalus.cardano.ledger.*
import scalus.ledger.api.v3.{TxOutRef, Value as V3Value}
import scalus.prelude.{AssocMap, Option as ScalusOption}

/** Helper utilities for demos and tests */
object DemoHelpers {

    /** Create a client-signed snapshot.
      *
      * Signs the (clientTxOutRef, snapshot) tuple with the client's Ed25519 private key. Exchange
      * signature is left empty - will be filled by server.
      *
      * Uses Cardano's extended Ed25519 signing (BIP32-Ed25519) which requires the full 64-byte
      * extended private key.
      */
    def mkClientSignedSnapshot(
        clientAccount: Account,
        clientTxOutRef: TxOutRef,
        snapshot: Snapshot
    ): SignedSnapshot = {
        val signedInfo = (clientTxOutRef, snapshot)
        import scalus.builtin.Data.toData
        val msg = Builtins.serialiseData(signedInfo.toData)

        // Use Bloxbean's signExtended for Cardano's BIP32-Ed25519 extended keys
        // Note: getKeyData returns 64-byte extended key, getBytes returns 96-byte (includes chain code)
        val signingProvider = CryptoConfiguration.INSTANCE.getSigningProvider
        val extendedPrivKey = clientAccount.hdKeyPair().getPrivateKey.getKeyData
        val clientPubKey = ByteString.fromArray(
          clientAccount.hdKeyPair().getPublicKey.getKeyData.take(32)
        )

        // Sign with extended private key (64 bytes)
        val clientSignature = ByteString.fromArray(
          signingProvider.signExtended(msg.bytes, extendedPrivKey)
        )

        SignedSnapshot(
          signedSnapshot = snapshot,
          snapshotClientSignature = clientSignature,
          snapshotExchangeSignature = ByteString.empty // Exchange hasn't signed yet
        )
    }

    /** Create an initial snapshot (version 0) with the given deposit amount.
      *
      *   - Client balance = depositAmount
      *   - Exchange balance = 0
      *   - No orders
      *   - No pending transactions
      */
    def mkInitialSnapshot(depositAmount: Value): Snapshot = {
        val tradingState = TradingState(
          tsClientBalance = LedgerToPlutusTranslation.getValue(depositAmount),
          tsExchangeBalance = V3Value.zero,
          tsOrders = AssocMap.empty
        )
        Snapshot(
          snapshotTradingState = tradingState,
          snapshotPendingTx = ScalusOption.None,
          snapshotVersion = 0
        )
    }

    /** Create a BUY limit order (positive amount) */
    def mkBuyOrder(pair: Pair, amount: BigInt, price: BigInt): LimitOrder = {
        LimitOrder(
          orderPair = pair,
          orderAmount = amount, // Positive for BUY
          orderPrice = price
        )
    }

    /** Create a SELL limit order (negative amount) */
    def mkSellOrder(pair: Pair, amount: BigInt, price: BigInt): LimitOrder = {
        LimitOrder(
          orderPair = pair,
          orderAmount = -amount, // Negative for SELL
          orderPrice = price
        )
    }

    /** Common asset classes for testing */
    type AssetClass = (ByteString, ByteString)
    val ADA: AssetClass = (ByteString.empty, ByteString.empty)

    /** Create an account from a seed and network */
    def createAccount(
        network: com.bloxbean.cardano.client.common.model.Network,
        seed: Int
    ): Account = {
        new Account(network, seed)
    }

    /** Extract public key hash from account */
    def getPubKeyHash(account: Account): ByteString = {
        ByteString.fromArray(account.hdKeyPair().getPublicKey.getKeyHash)
    }

    /** Extract public key (Ed25519 - first 32 bytes) from account */
    def getPubKey(account: Account): ByteString = {
        // Ed25519 public keys are 32 bytes - ensure consistency
        ByteString.fromArray(account.hdKeyPair().getPublicKey.getKeyData.take(32))
    }

    /** Extract private key (Ed25519 - first 32 bytes) from account */
    def getPrivKey(account: Account): ByteString = {
        ByteString.fromArray(account.privateKeyBytes().take(32))
    }
}

package cosmex.debug

import cosmex.{CosmexContract, ExchangeParams}
import cosmex.config.DemoConfig
import scalus.*
import scalus.builtin.ByteString
import scalus.builtin.Data.toData
import scalus.cardano.ledger.Script

/** Debug tool to diagnose script hash non-determinism when using config.
  *
  * This tool loads the actual config (same as the demo apps) and prints all the intermediate values
  * to help identify where differences occur.
  *
  * Run this from multiple terminals to see if the hashes differ:
  * {{{
  *   sbt "Test/runMain cosmex.debug.ConfigScriptHashDebug"
  * }}}
  */
object ConfigScriptHashDebug {
    def main(args: Array[String]): Unit = {
        println("=" * 80)
        println("COSMEX Config-Based Script Hash Debug Tool")
        println("=" * 80)
        println(s"Process ID: ${ProcessHandle.current().pid()}")
        println(s"Timestamp: ${java.time.Instant.now()}")
        println()

        // Load configuration (same as demo apps)
        println("Loading DemoConfig...")
        val config = DemoConfig.load()
        println(s"  Config loaded successfully")
        println()

        // Create exchange account (same as CosmexWebSocketServerFromConfig)
        println("Creating exchange account...")
        val exchangeAccount = config.exchange.createAccount()
        println(s"  Account created")
        println()

        // Get all the values that go into ExchangeParams
        println("Exchange account values:")
        val hdKeyPair = exchangeAccount.hdKeyPair()
        val pubKeyHash = ByteString.fromArray(hdKeyPair.getPublicKey.getKeyHash)
        val pubKeyData = hdKeyPair.getPublicKey.getKeyData
        val pubKey = ByteString.fromArray(exchangeAccount.publicKeyBytes())

        println(s"  hdKeyPair.getPublicKey.getKeyHash: ${pubKeyHash.toHex}")
        println(s"  hdKeyPair.getPublicKey.getKeyData: ${ByteString.fromArray(pubKeyData).toHex}")
        println(s"  publicKeyBytes():                  ${pubKey.toHex}")
        println(s"  publicKeyBytes length:             ${exchangeAccount.publicKeyBytes().length}")
        println()

        // Config values
        println("Config values:")
        println(s"  exchange.seed:                     ${config.exchange.seed}")
        println(s"  snapshotContestPeriod:             ${config.exchange.snapshotContestPeriod}")
        println(s"  network.slotLength:                ${config.network.slotLength}")
        val contestationMs = config.exchange.snapshotContestPeriod * config.network.slotLength
        println(s"  contestationPeriodInMs (computed): $contestationMs")
        println()

        // Create ExchangeParams via config method
        println("Creating ExchangeParams via config.exchange.createParams()...")
        val exchangeParams = config.exchange.createParams()
        println(s"  exchangePkh:        ${exchangeParams.exchangePkh.hash.toHex}")
        println(s"  exchangePubKey:     ${exchangeParams.exchangePubKey.toHex}")
        println(s"  contestationPeriod: ${exchangeParams.contestationPeriodInMilliseconds}")
        println()

        // Now generate the script hash
        println("Generating script hash...")
        import CosmexContract.given
        val program = CosmexContract.mkCosmexProgram(exchangeParams)
        val script = Script.PlutusV3(program.cborByteString)

        println(s"  Program CBOR size:  ${program.cborByteString.bytes.length}")
        println(s"  SCRIPT HASH:        ${script.scriptHash.toHex}")
        println()

        // Print CBOR bytes for comparison
        println("Program CBOR (first 100 bytes):")
        println(s"  ${program.cborByteString.toHex.take(200)}")
        println()

        println("=" * 80)
        println("COMPARE THESE VALUES ACROSS PROCESSES:")
        println(s"  Exchange pubKeyHash:  ${pubKeyHash.toHex}")
        println(s"  Exchange pubKey:      ${pubKey.toHex}")
        println(s"  Contestation period:  $contestationMs")
        println(s"  SCRIPT HASH:          ${script.scriptHash.toHex}")
        println("=" * 80)
    }
}

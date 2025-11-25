package cosmex.debug

import cosmex.{CosmexContract, CosmexValidator, ExchangeParams}
import scalus.*
import scalus.Compiler.*
import scalus.builtin.ByteString
import scalus.builtin.Data.toData
import scalus.cardano.ledger.Script
import scalus.ledger.api.v2.PubKeyHash

/** Debug tool to diagnose script hash non-determinism across processes.
  *
  * Run this from multiple terminals to see if the hashes differ:
  * {{{
  *   sbt "Test/runMain cosmex.debug.ScriptHashDebug"
  * }}}
  *
  * The tool prints:
  *   1. ExchangeParams values (to verify they're identical)
  *   2. Compiled validator hash (before param application)
  *   3. Final program hash (after param application)
  *   4. Script hash (the actual on-chain identifier)
  *   5. CBOR bytes (first 100 bytes for comparison)
  */
object ScriptHashDebug {
    def main(args: Array[String]): Unit = {
        println("=" * 80)
        println("COSMEX Script Hash Debug Tool")
        println("=" * 80)
        println(s"Process ID: ${ProcessHandle.current().pid()}")
        println(s"Timestamp: ${java.time.Instant.now()}")
        println()

        // Create deterministic ExchangeParams (hardcoded for reproducibility)
        val testPkh = PubKeyHash(ByteString.fromHex("a" * 56))
        val testPubKey = ByteString.fromHex("b" * 64)
        val contestationPeriod: BigInt = 180000

        val exchangeParams = ExchangeParams(
          exchangePkh = testPkh,
          exchangePubKey = testPubKey,
          contestationPeriodInMilliseconds = contestationPeriod
        )

        println("ExchangeParams:")
        println(s"  exchangePkh:        ${exchangeParams.exchangePkh.hash.toHex}")
        println(s"  exchangePubKey:     ${exchangeParams.exchangePubKey.toHex}")
        println(s"  contestationPeriod: ${exchangeParams.contestationPeriodInMilliseconds}")
        println()

        // Get the compiled validator using the same options as CosmexContract
        import CosmexContract.given

        println("Stage 1: Compiling validator...")
        val compiledValidator = Compiler.compile(CosmexValidator.validate)
        println(s"  SIR hashCode:       ${compiledValidator.hashCode()}")
        println(s"  SIR pretty (first 200 chars): ${compiledValidator.show.take(200)}...")
        println()

        println("Stage 2: Converting to UPLC optimized + PlutusV3...")
        val uplcProgram = compiledValidator.toUplcOptimized().plutusV3
        val programCbor = uplcProgram.cborByteString
        println(s"  Program CBOR size:  ${programCbor.bytes.length}")
        println(s"  Program CBOR hash:  ${hashBytes(programCbor.bytes)}")
        println()

        println("Stage 3: Applying ExchangeParams...")
        val appliedProgram = uplcProgram $ exchangeParams.toData
        val appliedCbor = appliedProgram.cborByteString
        println(s"  Applied CBOR size:  ${appliedCbor.bytes.length}")
        println(s"  Applied CBOR hash:  ${hashBytes(appliedCbor.bytes)}")
        println()

        println("Stage 4: Creating Script...")
        val script = Script.PlutusV3(appliedCbor)
        println(s"  SCRIPT HASH:        ${script.scriptHash.toHex}")
        println()

        // Also test via the existing mkCosmexProgram function
        println("Stage 5: Via CosmexContract.mkCosmexProgram...")
        val program = CosmexContract.mkCosmexProgram(exchangeParams)
        val programScript = Script.PlutusV3(program.cborByteString)
        println(s"  SCRIPT HASH:        ${programScript.scriptHash.toHex}")
        println()

        // Compare with no params (just the base validator)
        println("Stage 6: Base validator (no params applied)...")
        val baseScript = Script.PlutusV3(programCbor)
        println(s"  BASE SCRIPT HASH:   ${baseScript.scriptHash.toHex}")
        println()

        // Print first bytes of CBOR for manual comparison
        println("Applied CBOR bytes (first 100):")
        println(s"  ${appliedCbor.toHex.take(200)}")
        println()

        println("=" * 80)
        println("COMPARE THESE VALUES ACROSS PROCESSES:")
        println(s"  Base script hash:    ${baseScript.scriptHash.toHex}")
        println(s"  Applied script hash: ${script.scriptHash.toHex}")
        println("=" * 80)
    }

    private def hashBytes(bytes: Array[Byte]): String = {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        digest.map("%02x".format(_)).mkString.take(16) + "..."
    }
}

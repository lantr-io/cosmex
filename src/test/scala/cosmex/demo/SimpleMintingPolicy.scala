package cosmex.demo

import cosmex.demo.SimpleMintingPolicy.validate
import scalus.*
import scalus.builtin.Data
import scalus.builtin.Data.toData
import scalus.ledger.api.v3.*
import scalus.uplc.Program

/** Simple minting policy that allows minting only once using a specific UTxO
  *
  * This is a "one-time mint" policy where the UTxO reference ensures the policy can only be
  * executed once (since UTxOs can only be spent once).
  */
@Compile
object SimpleMintingPolicy {
    import scalus.prelude.*

    /** Minting policy validator that checks if a specific UTxO is being spent
      *
      * @param utxoRefData
      *   The UTxO reference that must be spent (as Data)
      * @return
      *   A minting policy function that takes ScriptContext
      */
    def validate(utxoRefData: Data) = (ctxData: Data) => {
        val utxoRef = utxoRefData.to[TxOutRef]
        val ctx = ctxData.to[ScriptContext]
        import ctx.txInfo

        // Check that the specified UTxO is being consumed in this transaction
        val hasUtxo = txInfo.inputs.exists { input =>
            (input.outRef.id.hash == utxoRef.id.hash) &&
            (input.outRef.idx == utxoRef.idx)
        }

        // The policy succeeds only if the required UTxO is consumed
        require(hasUtxo, "Required UTxO not found in transaction inputs")
    }
}

object SimpleMintingPolicyContract {

    private given Compiler.Options = Compiler.Options(
      targetLoweringBackend = Compiler.TargetLoweringBackend.SirToUplcV3Lowering
    )

    val compiledValidator = Compiler.compile(validate)

    /** Compile the minting policy and apply it to a specific UTxO reference
      *
      * @param utxoRef
      *   The UTxO that must be consumed to mint tokens
      * @return
      *   The compiled Plutus script program with the UTxO applied
      */
    def compileAndApply(utxoRef: TxOutRef): Program = {
        // Get the UPLC Term (NOT the optimized Program)
        // This matches the pattern from MintingPolicyExampleTest.scala
        val program =
            compiledValidator.toUplc(generateErrorTraces = true, optimizeUplc = false).plutusV3

        val utxoData = utxoRef.toData

        // Apply the parameter to the UPLC Program
        program $ utxoData
    }

    /** INCORRECT: Compile and apply using the wrong pattern that triggers IndexOutOfBoundsException
      *
      * This method demonstrates the INCORRECT approach that was causing DeBruijn index errors. DO
      * NOT USE this method - it's here only for documentation and regression testing.
      *
      * The bug: Applying parameters to a DeBruijnedProgram or after calling
      * toUplcOptimized().plutusV3 causes incorrect DeBruijn indices.
      *
      * @param utxoRef
      *   The UTxO that must be consumed to mint tokens
      * @return
      *   A program that will trigger IndexOutOfBoundsException during evaluation
      */
    def compileAndApplyInvalid(utxoRef: TxOutRef): Program = {

        println(
          s"[SimpleMintingPolicy - INVALID] Using incorrect pattern that triggers DeBruijn bug..."
        )

        // WRONG: Get optimized Program then apply parameter
        // This causes IndexOutOfBoundsException during script evaluation
        val program = compiledValidator.toUplcOptimized().plutusV3
        val utxoData = utxoRef.toData

        // WRONG: Applying to an already-optimized Program creates incorrect DeBruijn indices
        val appliedProgram = program $ utxoData

        println(
          s"[SimpleMintingPolicy - INVALID] Created invalid program (will fail during evaluation)"
        )

        appliedProgram
    }
}

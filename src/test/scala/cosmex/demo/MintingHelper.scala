package cosmex.demo

import scalus.builtin.ByteString
import scalus.cardano.ledger.*
import scalus.cardano.txbuilder.*
import scalus.ledger.api.v3.TxOutRef
import scalus.cardano.address.Address
import scalus.ledger.api.v3.TxId

object MintingHelper {

    /** Create a transaction that mints tokens using a one-time minting policy
      *
      * @param env
      *   The transaction builder environment
      * @param utxoToSpend
      *   The UTxO to spend (also used as minting policy parameter)
      * @param collateralUtxo
      *   The UTxO to use as collateral for script execution
      * @param recipientAddress
      *   The address to send the minted tokens to
      * @param tokenName
      *   The name of the token to mint
      * @param amount
      *   The amount of tokens to mint
      * @return
      *   An unsigned transaction that mints the tokens
      */
    def mintTokens(
        env: Environment,
        utxoToSpend: Utxo,
        collateralUtxo: Utxo,
        recipientAddress: Address,
        tokenName: ByteString,
        amount: Long
    ): Transaction = {
        // Create the UTxO reference for the minting policy
        val utxoRef = TxOutRef(
          scalus.ledger.api.v3.TxId(utxoToSpend.input.transactionId),
          utxoToSpend.input.index
        )

        // Compile the minting policy and apply the UTxO reference
        val compiledProgram = SimpleMintingPolicyContract.compileAndApply(utxoRef)
        val mintingScript = Script.PlutusV3(ByteString.fromArray(compiledProgram.cborEncoded))
        val policyId = mintingScript.scriptHash

        // Create the asset name
        val assetName = AssetName(tokenName)

        // Create the minted value (tokens only, no ADA)
        val mintedValue = Value.asset(policyId, assetName, amount)

        // Minimum ADA required for a multi-asset UTxO
        val minAdaForTokens = 2_000_000L

        // Reserve ADA for a separate collateral UTxO (for future script execution)
        val collateralAda = 5_000_000L

        // Build the transaction with collateral for Plutus script execution
        // Important: Do NOT use .changeTo() - it seems to merge change with token output
        // Instead, explicitly create all outputs with exact amounts
        TxBuilder(env)
            .spend(utxoToSpend)
            .collaterals(collateralUtxo)
            .mint(
              redeemer = scalus.builtin.Data.B(ByteString.empty),
              assets = Map(assetName -> amount),
              script = mintingScript
            )
            .payTo(recipientAddress, mintedValue + Value.lovelace(minAdaForTokens)) // Tokens + 2 ADA ONLY
            .payTo(recipientAddress, Value.lovelace(collateralAda)) // 5 ADA collateral
            .changeTo(recipientAddress) // All remaining ADA as change
            .build()
            .transaction
    }

    /** Get the policy ID for a given UTxO reference
      *
      * This is useful for calculating what the policy ID will be before minting
      *
      * @param utxoRef
      *   The UTxO reference used in the minting policy
      * @return
      *   The policy ID (script hash) as ByteString
      */
    def getPolicyId(utxoRef: TxOutRef): ByteString = {
        val compiledProgram = SimpleMintingPolicyContract.compileAndApply(utxoRef)
        val mintingScript = Script.PlutusV3(ByteString.fromArray(compiledProgram.cborEncoded))
        ByteString.fromArray(mintingScript.scriptHash.bytes)
    }
}

package cosmex
import scalus.builtin.Data
import scalus.cardano.address.*
import scalus.cardano.ledger.*
import scalus.cardano.txbuilder.*
import scalus.ledger.api.v2.PubKeyHash

class TxBuilder(val exchangeParams: ExchangeParams) {
    val protocolVersion = 9
    private val cosmexValidator = CosmexValidator.mkCosmexValidator(exchangeParams)

    // Convert PlutusScript to Script.PlutusV3
    private val script: Script.PlutusV3 = Script.PlutusV3(cosmexValidator.cborByteString)

    def mkTx(datum: Data, redeemer: Data, signatories: Seq[PubKeyHash]): Transaction = {
        val network = Network.Mainnet

        // Create the input (hardcoded as in original)
        val txId = TransactionHash.fromHex("1ab6879fc08345f51dc9571ac4f530bf8673e0d798758c470f9af6f98e2f3982")
        val input = TransactionInput(
          transactionId = txId,
          index = 0
        )

        // Create the output address (hardcoded as in original)
        val outputAddress = Address(network, Credential.ScriptHash(script.scriptHash))

        // Create the output with inline datum
        val output = TransactionOutput(
          address = outputAddress,
          value = Value.ada(20),
          datumOption = Some(DatumOption.Inline(datum)),
          scriptRef = None
        )

        // Create the UTxO to spend
        val utxo = TransactionUnspentOutput(input, output)

        // Create the witness with Scalus types
        val witness = ThreeArgumentPlutusScriptWitness(
          scriptSource = ScriptSource.PlutusScriptValue(script),
          redeemer = redeemer,
          datum = Datum.DatumInlined,
          additionalSigners = signatories.map { pkh =>
              ExpectedSigner(AddrKeyHash(pkh.hash))
          }.toSet
        )

        // Build transaction with steps
        val steps = Seq(
          TransactionBuilderStep.Spend(utxo, witness),
          TransactionBuilderStep.ValidityStartSlot(10),
          TransactionBuilderStep.ValidityEndSlot(1000),
          TransactionBuilderStep.Send(output),
          TransactionBuilderStep.Fee(Coin(200000)) // 0.2 ADA = 200,000 lovelace
        )

        // Build the transaction
        val result = TransactionBuilder.build(network, steps)

        result match {
            case Right(context) => context.transaction
            case Left(error)    => throw new RuntimeException(s"Transaction build failed: $error")
        }
    }
}

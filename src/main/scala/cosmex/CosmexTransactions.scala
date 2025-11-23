package cosmex
import scalus.builtin.Data.toData
import scalus.builtin.platform
import scalus.cardano.address.*
import scalus.cardano.ledger.*
import scalus.cardano.txbuilder.*
import scalus.ledger.api.v2.PubKeyHash
import scalus.ledger.api.v3.{TxId, TxOutRef}

class CosmexTransactions(val exchangeParams: ExchangeParams, env: Environment) {
    private val network = env.network
    val protocolVersion = 9
    private val cosmexValidator = CosmexContract.mkCosmexProgram(exchangeParams)
    private val script = Script.PlutusV3(cosmexValidator.cborByteString)

    /** Opens a new channel by depositing funds to the Cosmex script address.
      *
      * This creates an unsigned transaction that:
      *   - Spends the client's input UTxO
      *   - Creates an output to the Cosmex script with OpenState
      *   - Uses the client's input as the unique channel identifier (clientTxOutRef)
      *   - If input contains tokens, explicitly sends them back as change
      *
      * Protocol flow (from whitepaper):
      *   1. Client creates unsigned Tx with initial deposit and ClientSignedSnapshot v0 2. Exchange
      *      replies with BothSignedSnapshot v0 3. Client signs the Tx and publishes it on-chain
      *
      * @param clientInput
      *   The UTxO to spend (contains client's funds for deposit)
      * @param clientPubKey
      *   The client's public key (for signature verification)
      * @param depositAmount
      *   The amount to deposit into the channel
      * @param validityStartSlot
      *   The validity start slot for the transaction
      * @param validityEndSlot
      *   The validity end slot for the transaction
      * @return
      *   An unsigned Transaction that opens the channel
      */
    def openChannel(
        clientInput: Utxo,
        clientPubKey: Signature,
        depositAmount: Value
    ): Transaction = {
        // The script address where funds will be locked
        val scriptAddress = Address(network, Credential.ScriptHash(script.scriptHash))

        // Create the initial OnChainState with OpenState
        // The clientTxOutRef is the input being spent, which uniquely identifies this channel
        val initialState = OnChainState(
          clientPkh = PubKeyHash(platform.blake2b_224(clientPubKey)),
          clientPubKey = clientPubKey,
          clientTxOutRef = TxOutRef(TxId(clientInput.input.transactionId), clientInput.input.index),
          channelState = OnChainChannelState.OpenState
        )

        // Check if input contains tokens (multi-assets) by comparing to ADA-only value
        val inputValue = clientInput.output.value
        val hasTokens = inputValue != Value.lovelace(inputValue.coin.value)

        if (hasTokens) {
            // If input has tokens, we need to explicitly handle them
            // Calculate what remains after deposit (tokens should be preserved)
            val remainingValue = inputValue - depositAmount

            // If there's remaining value (tokens + ADA), send it back explicitly
            // Then use changeTo to set the diff handler for fee calculation
            if (remainingValue.coin.value > 0 || remainingValue != Value.lovelace(remainingValue.coin.value)) {
                TxBuilder(env)
                    .spend(clientInput)
                    .payTo(address = scriptAddress, value = depositAmount, datum = initialState.toData)
                    .payTo(address = clientInput.output.address, value = remainingValue)
                    .changeTo(clientInput.output.address)  // Sets diff handler for fee calculation
                    .build()
                    .transaction
            } else {
                // All value deposited, just need diff handler for fees
                TxBuilder(env)
                    .spend(clientInput)
                    .payTo(address = scriptAddress, value = depositAmount, datum = initialState.toData)
                    .changeTo(clientInput.output.address)
                    .build()
                    .transaction
            }
        } else {
            // No tokens - use standard changeTo
            TxBuilder(env)
                .spend(clientInput)
                .payTo(address = scriptAddress, value = depositAmount, datum = initialState.toData)
                .changeTo(clientInput.output.address)
                .build()
                .transaction
        }
    }

    def update(state: OnChainState, signatories: Seq[PubKeyHash]): Transaction = {
        // Create the input (hardcoded as in original)
        val txId = TransactionHash.fromHex(
          "1ab6879fc08345f51dc9571ac4f530bf8673e0d798758c470f9af6f98e2f3982"
        )
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
          datumOption = Some(DatumOption.Inline(state.toData)),
          scriptRef = None
        )

        // Create the UTxO to spend
        val utxo = Utxo(input, output)

        // Create the witness with Scalus types
        val witness = ThreeArgumentPlutusScriptWitness(
          scriptSource = ScriptSource.PlutusScriptValue(script),
          redeemer = Action.Update.toData,
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

        val diffHandler = ChangeOutputDiffHandler(env.protocolParams, 0).changeOutputDiffHandler

        // Build the transaction
        val result =
            for
                ctx <- TransactionBuilder.build(network, steps)
                r <- ctx.finalizeContext(
                  env.protocolParams,
                  diffHandler,
                  PlutusScriptEvaluator.noop,
                  Seq.empty
                )
            yield r

        result match
            case Right(context) => context.transaction
            case Left(error) =>
                throw new RuntimeException(s"Channel opening transaction build failed: $error")
    }
}

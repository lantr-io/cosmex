package cosmex
import scalus.builtin.{platform, ByteString}
import scalus.builtin.Data.toData
import scalus.cardano.address.*
import scalus.cardano.ledger.*
import scalus.cardano.txbuilder.*
import scalus.cardano.txbuilder.LowLevelTxBuilder.ChangeOutputDiffHandler
import scalus.ledger.api.v2.PubKeyHash
import scalus.ledger.api.v3.{TxId, TxOutRef}

class TxBuilder(val exchangeParams: ExchangeParams, env: Environment) {
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
        clientInput: TransactionUnspentOutput,
        clientPubKey: ByteString,
        depositAmount: Value,
        validityStartSlot: Long,
        validityEndSlot: Long
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

        // Create the output with the deposited funds and initial state
        val channelOutput = TransactionOutput(
          address = scriptAddress,
          value = depositAmount,
          datumOption = DatumOption.Inline(initialState.toData)
        )

        // Build transaction steps
        val steps = Seq(
          // Spend the client's input (no witness needed for unsigned tx)
          TransactionBuilderStep.Spend(clientInput),
          // Send funds to the script address with initial state
          TransactionBuilderStep.Send(channelOutput),
          // Set validity range
          TransactionBuilderStep.ValidityStartSlot(validityStartSlot),
          TransactionBuilderStep.ValidityEndSlot(validityEndSlot),
          TransactionBuilderStep.Fee(Coin.ada(1))
        )

        val diffHandler = ChangeOutputDiffHandler(env.protocolParams, 0).changeOutputDiffHandler

        // Build the transaction
        val result =
            for
                ctx <- TransactionBuilder.build(network, steps)
                r <- ctx.finalizeContext(env.protocolParams, diffHandler, env.evaluator, Seq.empty)
            yield r

        result match
            case Right(context) => context.transaction
            case Left(error) =>
                throw new RuntimeException(s"Channel opening transaction build failed: $error")
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
        val utxo = TransactionUnspentOutput(input, output)

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

        // Build the transaction
        val result = TransactionBuilder.build(network, steps)

        result match
            case Right(context) => context.transaction
            case Left(error)    => throw new RuntimeException(s"Transaction build failed: $error")
    }
}

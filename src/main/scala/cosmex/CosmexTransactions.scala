package cosmex
import scalus.builtin.Data.toData
import scalus.builtin.{platform, ByteString}
import scalus.cardano.address.*
import scalus.cardano.ledger.*
import scalus.cardano.node.Provider
import scalus.cardano.txbuilder.*
import scalus.cardano.wallet.Account
import scalus.utils.await
import scalus.ledger.api.v2.PubKeyHash
import scalus.ledger.api.v3.{TxId, TxOutRef}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class CosmexTransactions(
    val exchangeParams: ExchangeParams,
    env: CardanoInfo
) {
    private val network = env.network
    val protocolVersion = env.protocolParams.protocolVersion.major
    private val cosmexValidator = CosmexContract.mkCosmexProgram(exchangeParams)
    val script = Script.PlutusV3(cosmexValidator.cborByteString) // Public for debugging

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

        if hasTokens then {
            // If input has tokens, we need to explicitly handle them
            // Calculate what remains after deposit (tokens should be preserved)
            val remainingValue = inputValue - depositAmount

            // If there's remaining value (tokens + ADA), send it back explicitly
            // Then use changeTo to set the diff handler for fee calculation
            if remainingValue.coin.value > 0 || remainingValue != Value.lovelace(
                  remainingValue.coin.value
                )
            then {
                TxBuilder(env)
                    .spend(clientInput)
                    .payTo(
                      address = scriptAddress,
                      value = depositAmount,
                      datum = initialState.toData
                    )
                    .payTo(address = clientInput.output.address, value = remainingValue)
                    .build(changeTo = clientInput.output.address)
                    .transaction
            } else {
                // All value deposited, just need diff handler for fees
                TxBuilder(env)
                    .spend(clientInput)
                    .payTo(
                      address = scriptAddress,
                      value = depositAmount,
                      datum = initialState.toData
                    )
                    .build(changeTo = clientInput.output.address)
                    .transaction
            }
        } else {
            // No tokens - use standard changeTo
            TxBuilder(env)
                .spend(clientInput)
                .payTo(address = scriptAddress, value = depositAmount, datum = initialState.toData)
                .build(changeTo = clientInput.output.address)
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

    /** Closes the channel by creating a transaction that spends the client's UTxO at the script
      * address and pays out the locked value to the specified payout address.
      *
      * @param provider
      *   The Provider to use for building the transaction
      * @param clientInput
      *   The UTxO at the script address representing the channel
      * @param clientAccount
      *   The client's account for signing the transaction
      * @param payoutAddress
      *   The address to pay out the locked funds to
      * @param clientState
      *   The current ClientState containing the locked value
      * @return
      *   A signed Transaction that closes the channel and pays out the funds
      */
    def closeChannel(
        provider: Provider,
        clientAccount: Account,
        payoutAddress: Address,
        clientState: ClientState
    ) = {
        val clientUtxo = provider
            .findUtxo(clientState.channelRef)
            .await()
            .getOrElse(
              throw new RuntimeException(
                s"Client UTxO not found for channelRef: ${clientState.channelRef}"
              )
            )
        val publicKey = ByteString.fromArray(clientAccount.paymentKeyPair.publicKeyBytes.take(32))
        val pubKeyHash = platform.blake2b_224(publicKey)
        val addrKeyHash = AddrKeyHash(pubKeyHash)
        val cosmexAddrKeyHash = AddrKeyHash.fromByteString(exchangeParams.exchangePkh.hash)
        println(clientState.latestSnapshot)
        val txoutRef = LedgerToPlutusTranslation.getTxOutRefV3(clientState.channelRef)
        println(
          scalus.builtin.Builtins
              .serialiseData(
                (txoutRef, clientState.latestSnapshot.signedSnapshot).toData
              )
              .toHex
        )
        TxBuilder(env)
            .spend(
              clientUtxo,
              Action.Close(Party.Client, clientState.latestSnapshot),
              script,
              Set(addrKeyHash, cosmexAddrKeyHash)
            )
            .payTo(payoutAddress, clientState.lockedValue)
            .validFrom(Instant.now())
            .validTo(Instant.now().plusSeconds(600))
            .complete(provider, sponsor = payoutAddress)
            .await()
            .sign(new TransactionSigner(Set(clientAccount.paymentKeyPair)))
            .transaction
    }

    /** Build a rebalance transaction that updates multiple channels to match their snapshot states.
      *
      * This transaction:
      *   - Spends all affected channel UTxOs using the Update action
      *   - Creates new outputs with values matching each client's snapshot total
      *   - Requires signatures from all affected clients + exchange
      *
      * @param channelData
      *   Sequence of (UTxO, OnChainState, TradingState) for each channel to rebalance
      * @param exchangePkh
      *   Exchange public key hash for signature requirement
      * @return
      *   An unsigned Transaction for rebalancing
      */
    def rebalance(
        channelData: Seq[(Utxo, OnChainState, TradingState)],
        exchangePkh: PubKeyHash
    ): Transaction = {
        val scriptAddress = Address(network, Credential.ScriptHash(script.scriptHash))

        // Collect all signatories needed (all clients + exchange)
        val clientPkhs = channelData.map(_._2.clientPkh)
        val allSignatories = (clientPkhs :+ exchangePkh).distinct

        // Build spend steps for each channel
        val spendSteps = channelData.map { case (utxo, onChainState, _) =>
            val witness = ThreeArgumentPlutusScriptWitness(
              scriptSource = ScriptSource.PlutusScriptValue(script),
              redeemer = Action.Update.toData,
              datum = Datum.DatumInlined,
              additionalSigners = allSignatories.map { pkh =>
                  ExpectedSigner(AddrKeyHash(pkh.hash))
              }.toSet
            )
            TransactionBuilderStep.Spend(utxo, witness)
        }

        // Build output steps for each channel with updated values
        val outputSteps = channelData.map { case (utxo, onChainState, tradingState) =>
            // Calculate new locked value from snapshot: client + exchange + locked in orders
            val newLockedValue = tradingState.tsClientBalance +
                tradingState.tsExchangeBalance +
                CosmexValidator.lockedInOrders(tradingState.tsOrders)

            // Create output with same OnChainState but new value
            val output = TransactionOutput(
              address = scriptAddress,
              value = newLockedValue.toLedgerValue,
              datumOption = Some(DatumOption.Inline(onChainState.toData)),
              scriptRef = None
            )
            TransactionBuilderStep.Send(output)
        }

        // Combine all steps
        val steps = spendSteps ++ outputSteps ++ Seq(
          TransactionBuilderStep.ValidityStartSlot(0),
          TransactionBuilderStep.ValidityEndSlot(100000000), // Large validity window
          TransactionBuilderStep.Fee(Coin(500000)) // 0.5 ADA fee (higher for multi-input tx)
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
                throw new RuntimeException(s"Rebalance transaction build failed: $error")
    }

    /** Builds a timeout transaction that transitions a channel from SnapshotContestState to the next state.
      *
      * After the contestation period expires, anyone can submit this transaction to advance the state:
      *   - If no orders: SnapshotContestState → PayoutState
      *   - If orders exist: SnapshotContestState → TradesContestState
      *
      * This is a permissionless action - no signatures required after the timeout period.
      *
      * @param provider
      *   The Provider to use for fetching UTxOs
      * @param channelRef
      *   The TransactionInput pointing to the channel UTxO
      * @param currentState
      *   The current OnChainState (must be in SnapshotContestState)
      * @param validityStart
      *   The validity start time (must be after contestation deadline)
      * @return
      *   An unsigned Transaction that transitions the channel state
      */
    def timeout(
        provider: Provider,
        channelRef: TransactionInput,
        currentState: OnChainState,
        validityStart: Instant
    ): Transaction = {
        val channelUtxo = provider
            .findUtxo(channelRef)
            .await()
            .getOrElse(
              throw new RuntimeException(
                s"Channel UTxO not found for channelRef: $channelRef"
              )
            )

        val scriptAddress = Address(network, Credential.ScriptHash(script.scriptHash))

        // Compute the new state based on current state
        val newChannelState = currentState.channelState match {
            case OnChainChannelState.SnapshotContestState(contestSnapshot, _, _, _) =>
                val tradingState = contestSnapshot.snapshotTradingState
                if scalus.prelude.List.isEmpty(tradingState.tsOrders.toList) then
                    OnChainChannelState.PayoutState(
                      tradingState.tsClientBalance,
                      tradingState.tsExchangeBalance
                    )
                else
                    OnChainChannelState.TradesContestState(
                      tradingState,
                      validityStart.toEpochMilli
                    )
            case OnChainChannelState.TradesContestState(tradingState, _) =>
                OnChainChannelState.PayoutState(
                  tradingState.tsClientBalance,
                  tradingState.tsExchangeBalance
                )
            case _ =>
                throw new RuntimeException(
                  s"Cannot timeout from state: ${currentState.channelState}"
                )
        }

        val newState = OnChainState(
          currentState.clientPkh,
          currentState.clientPubKey,
          currentState.clientTxOutRef,
          newChannelState
        )

        TxBuilder(env)
            .spend(channelUtxo, Action.Timeout, script, Set.empty)
            .payTo(scriptAddress, channelUtxo.output.value, newState.toData)
            .validFrom(validityStart)
            .validTo(validityStart.plusSeconds(600))
            .complete(provider, sponsor = scriptAddress)
            .await()
            .transaction
    }

    /** Builds a payout transaction that allows a client to withdraw their funds from PayoutState.
      *
      * @param provider
      *   The Provider to use for fetching UTxOs
      * @param clientAccount
      *   The client's account for signing
      * @param payoutAddress
      *   The address to send the client's funds to
      * @param channelRef
      *   The TransactionInput pointing to the channel UTxO
      * @param currentState
      *   The current OnChainState (must be in PayoutState)
      * @return
      *   A signed Transaction that pays out the client's balance
      */
    def payout(
        provider: Provider,
        clientAccount: Account,
        payoutAddress: Address,
        channelRef: TransactionInput,
        currentState: OnChainState
    ): Transaction = {
        val channelUtxo = provider
            .findUtxo(channelRef)
            .await()
            .getOrElse(
              throw new RuntimeException(
                s"Channel UTxO not found for channelRef: $channelRef"
              )
            )

        val (clientBalance, exchangeBalance) = currentState.channelState match {
            case OnChainChannelState.PayoutState(cb, eb) => (cb, eb)
            case _ =>
                throw new RuntimeException(
                  s"Cannot payout from state: ${currentState.channelState}"
                )
        }

        val publicKey = ByteString.fromArray(clientAccount.paymentKeyPair.publicKeyBytes.take(32))
        val pubKeyHash = platform.blake2b_224(publicKey)
        val addrKeyHash = AddrKeyHash(pubKeyHash)

        val ownInputValue = LedgerToPlutusTranslation.getValue(channelUtxo.output.value)

        // Check if this is a full payout (client gets all, exchange balance is zero)
        val isFilled = clientBalance == ownInputValue &&
            exchangeBalance == scalus.ledger.api.v3.Value.zero

        if isFilled then
            // Full payout - all funds go to exchange address (per validator logic when filled)
            // This means exchange owns the remaining funds after client balance is 0
            TxBuilder(env)
                .spend(channelUtxo, Action.Payout, script, Set(addrKeyHash))
                .payTo(payoutAddress, channelUtxo.output.value)
                .validFrom(Instant.now())
                .validTo(Instant.now().plusSeconds(600))
                .complete(provider, sponsor = payoutAddress)
                .await()
                .sign(new TransactionSigner(Set(clientAccount.paymentKeyPair)))
                .transaction
        else
            // Partial payout - compute available for payment
            val availableForPayment = minValue(clientBalance, ownInputValue)
            val availableForPaymentLedger = availableForPayment.toLedgerValue
            val newOutputValue = channelUtxo.output.value - availableForPaymentLedger
            val newClientBalance = clientBalance - availableForPayment

            val scriptAddress = Address(network, Credential.ScriptHash(script.scriptHash))
            val newState = OnChainState(
              currentState.clientPkh,
              currentState.clientPubKey,
              currentState.clientTxOutRef,
              OnChainChannelState.PayoutState(newClientBalance, exchangeBalance)
            )

            TxBuilder(env)
                .spend(channelUtxo, Action.Payout, script, Set(addrKeyHash))
                .payTo(payoutAddress, availableForPaymentLedger)
                .payTo(scriptAddress, newOutputValue, newState.toData)
                .validFrom(Instant.now())
                .validTo(Instant.now().plusSeconds(600))
                .complete(provider, sponsor = payoutAddress)
                .await()
                .sign(new TransactionSigner(Set(clientAccount.paymentKeyPair)))
                .transaction
    }

    /** Compute element-wise minimum of two Values.
      * For each asset in `a`, takes the minimum of its amount and the corresponding amount in `b`.
      */
    private def minValue(
        a: scalus.ledger.api.v3.Value,
        b: scalus.ledger.api.v3.Value
    ): scalus.ledger.api.v3.Value = {
        import scalus.ledger.api.v3.Value
        import scalus.prelude.{List, Option}
        val minAssets = a.flatten.filterMap { case (policyId, tokenName, amountA) =>
            val amountB = b.quantityOf(policyId, tokenName)
            val minAmount = if amountA < amountB then amountA else amountB
            if minAmount != BigInt(0) then
                Option.Some((policyId, List.Cons((tokenName, minAmount), List.Nil)))
            else Option.None
        }
        Value.fromList(minAssets)
    }
}

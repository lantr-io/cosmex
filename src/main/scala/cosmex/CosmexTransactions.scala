package cosmex
import scalus.builtin.Data.toData
import scalus.builtin.{platform, ByteString}
import scalus.cardano.address.*
import scalus.cardano.ledger.*
import scalus.cardano.node.BlockchainProvider
import scalus.cardano.txbuilder.*
import scalus.cardano.wallet.Account
import scalus.utils.await
import scalus.ledger.api.v2.PubKeyHash
import scalus.ledger.api.v3.{TxId, TxOutRef}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

object CosmexTransactions {

    /** Default validity window for transactions (1 minute for demo) */
    val DefaultValiditySeconds: Int = 60
}

class CosmexTransactions(
    val exchangeParams: ExchangeParams,
    env: CardanoInfo
) {
    import CosmexTransactions.DefaultValiditySeconds

    private val network = env.network
    val protocolVersion = env.protocolParams.protocolVersion.major
    // Enable error traces for debugging script failures
    private val cosmexValidator =
        CosmexContract.mkCosmexProgram(exchangeParams, generateErrorTraces = true)
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
        clientInputs: Seq[Utxo],
        clientPubKey: Signature,
        depositAmount: Value
    ): Transaction = {
        require(clientInputs.nonEmpty, "At least one input required")

        // The first input is used as the channel identifier
        val primaryInput = clientInputs.head

        // The script address where funds will be locked
        val scriptAddress = Address(network, Credential.ScriptHash(script.scriptHash))

        // Create the initial OnChainState with OpenState
        // The clientTxOutRef is the first input being spent, which uniquely identifies this channel
        val initialState = OnChainState(
          clientPkh = PubKeyHash(platform.blake2b_224(clientPubKey)),
          clientPubKey = clientPubKey,
          clientTxOutRef =
              TxOutRef(TxId(primaryInput.input.transactionId), primaryInput.input.index),
          channelState = OnChainChannelState.OpenState
        )

        // Calculate total input value across all inputs
        val totalInputValue = clientInputs.map(_.output.value).reduce(_ + _)
        val hasTokens = totalInputValue != Value.lovelace(totalInputValue.coin.value)

        // Build transaction with all inputs
        val builder = clientInputs.foldLeft(TxBuilder(env)) { (b, utxo) =>
            b.spend(utxo)
        }

        if hasTokens then {
            // If inputs have tokens, we need to explicitly handle them
            // Calculate what remains after deposit (tokens should be preserved)
            val rawRemainingValue = totalInputValue - depositAmount
            // Filter out zero and negative token amounts (subtraction can leave these)
            val remainingValue =
                Value(rawRemainingValue.coin, rawRemainingValue.assets.onlyPositive)

            // If there's remaining value (tokens + ADA), send it back explicitly
            if remainingValue.coin.value > 0 || remainingValue != Value.lovelace(
                  remainingValue.coin.value
                )
            then {
                builder
                    .payTo(
                      address = scriptAddress,
                      value = depositAmount,
                      datum = initialState.toData
                    )
                    .payTo(address = primaryInput.output.address, value = remainingValue)
                    .build(changeTo = primaryInput.output.address)
                    .transaction
            } else {
                // All value deposited, just need diff handler for fees
                builder
                    .payTo(
                      address = scriptAddress,
                      value = depositAmount,
                      datum = initialState.toData
                    )
                    .build(changeTo = primaryInput.output.address)
                    .transaction
            }
        } else {
            // No tokens - use standard changeTo
            builder
                .payTo(address = scriptAddress, value = depositAmount, datum = initialState.toData)
                .build(changeTo = primaryInput.output.address)
                .transaction
        }
    }

    // Convenience method for single input (backwards compatibility)
    def openChannel(
        clientInput: Utxo,
        clientPubKey: Signature,
        depositAmount: Value
    ): Transaction = openChannel(Seq(clientInput), clientPubKey, depositAmount)

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
        provider: BlockchainProvider,
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
            .validTo(Instant.now().plusSeconds(DefaultValiditySeconds))
            .complete(provider, sponsor = payoutAddress)
            .await()
            .sign(new TransactionSigner(Set(clientAccount.paymentKeyPair)))
            .transaction
    }

    /** Builds a contested close transaction that initiates the contestation period.
      *
      * Unlike graceful close which requires both parties and distributes funds immediately,
      * contested close only requires the initiating party's signature and transitions to
      * SnapshotContestState for the contestation period.
      *
      * @param provider
      *   Provider for UTxO lookup
      * @param clientAccount
      *   Client account for signing
      * @param clientState
      *   Current client state
      * @param contestStartTime
      *   When the contestation starts (for validity window and state datum)
      * @return
      *   Transaction that transitions channel to SnapshotContestState
      */
    /** @param validitySeconds
      *   Time until validity expires. Use 1 for tests (instant), 60+ for preprod (block production
      *   delay)
      */
    def contestedClose(
        provider: BlockchainProvider,
        clientAccount: Account,
        clientState: ClientState,
        contestStartTime: Instant,
        validitySeconds: Int = 1
    ): Transaction = {
        val channelUtxo = provider
            .findUtxo(clientState.channelRef)
            .await()
            .getOrElse(
              throw new RuntimeException(
                s"Channel UTxO not found for channelRef: ${clientState.channelRef}"
              )
            )

        // Extract the on-chain state from datum to get the correct clientPkh
        val currentOnChainState = channelUtxo.output.datumOption match {
            case Some(DatumOption.Inline(data)) =>
                data.to[OnChainState]
            case _ =>
                throw new RuntimeException("Expected inline datum on channel UTxO")
        }

        // VALIDATION: Log on-chain state for debugging
        val onChainClientTxOutRef = currentOnChainState.clientTxOutRef
        println(
          s"[contestedClose] On-chain clientTxOutRef: ${onChainClientTxOutRef.id.hash.toHex.take(16)}...#${onChainClientTxOutRef.idx}"
        )
        println(
          s"[contestedClose] On-chain clientPkh: ${currentOnChainState.clientPkh.hash.toHex.take(16)}..."
        )
        println(
          s"[contestedClose] Snapshot version: ${clientState.latestSnapshot.signedSnapshot.snapshotVersion}"
        )

        // Use the client's key hash from the on-chain state for consistency
        val addrKeyHash = AddrKeyHash(currentOnChainState.clientPkh.hash)

        val scriptAddress = Address(network, Credential.ScriptHash(script.scriptHash))
        val spendingTxOutRef = LedgerToPlutusTranslation.getTxOutRefV3(clientState.channelRef)

        // Build the new state with SnapshotContestState
        // Note: contestSnapshotStart must match validRange(range)._2 (end of validity range)
        // validitySeconds: use 1 for tests (instant), 60+ for preprod (block production delay)
        // The 60-second clock skew buffer on validFrom gives (60 + validitySeconds) total window.
        val validityEndInstant = contestStartTime.plusSeconds(validitySeconds)
        val validityEndSlot = env.slotConfig.timeToSlot(validityEndInstant.toEpochMilli)
        val validityEnd = env.slotConfig.slotToTime(validityEndSlot)
        // Add clock skew buffer - start 60 seconds in the past for preprod compatibility
        val adjustedStart = contestStartTime.minusSeconds(60)
        println(
          s"[contestedClose] Round-trip: ${validityEndInstant.toEpochMilli} -> slot $validityEndSlot -> $validityEnd"
        )
        val newChannelState = OnChainChannelState.SnapshotContestState(
          contestSnapshot = clientState.latestSnapshot.signedSnapshot,
          contestSnapshotStart = validityEnd,
          contestInitiator = Party.Client,
          contestChannelTxOutRef = spendingTxOutRef
        )

        val newState = OnChainState(
          currentOnChainState.clientPkh,
          currentOnChainState.clientPubKey,
          currentOnChainState.clientTxOutRef,
          newChannelState
        )

        // Get client's address for fee sponsorship
        val clientAddress = Address(network, Credential.KeyHash(addrKeyHash))

        println(s"[contestedClose] On-chain clientPkh: ${currentOnChainState.clientPkh}")
        println(s"[contestedClose] addrKeyHash for signing: $addrKeyHash")
        println(s"[contestedClose] Spending UTxO ref: ${clientState.channelRef}")
        println(s"[contestedClose] Snapshot clientTxOutRef: ${currentOnChainState.clientTxOutRef}")
        println(
          s"[contestedClose] Snapshot version: ${clientState.latestSnapshot.signedSnapshot.snapshotVersion}"
        )
        println(s"[contestedClose] Validity: $adjustedStart (adjusted -60s) to $validityEndInstant")
        println(s"[contestedClose] contestSnapshotStart in datum: $validityEnd")
        println(s"[contestedClose] Script address: $scriptAddress")
        println(s"[contestedClose] newState channelState: ${newState.channelState}")

        TxBuilder(env)
            .spend(
              channelUtxo,
              Action.Close(Party.Client, clientState.latestSnapshot),
              script,
              Set(addrKeyHash) // Only client signs - NOT exchange
            )
            .payTo(scriptAddress, channelUtxo.output.value, newState.toData)
            .validFrom(adjustedStart)
            .validTo(validityEndInstant)
            .complete(provider, sponsor = clientAddress)
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
      * @param provider
      *   The Provider for fetching UTxOs and collateral
      * @param channelData
      *   Sequence of (UTxO, OnChainState, TradingState) for each channel to rebalance
      * @param exchangePkh
      *   Exchange public key hash for signature requirement
      * @param sponsor
      *   Address to use for collateral and fee sponsorship
      * @param validityStart
      *   The validity start time for the transaction
      * @return
      *   An unsigned Transaction for rebalancing
      */
    def rebalance(
        provider: BlockchainProvider,
        channelData: Seq[(Utxo, OnChainState, TradingState)],
        exchangePkh: PubKeyHash,
        sponsor: Address,
        validityStart: Instant = Instant.now()
    ): Transaction = {
        val scriptAddress = Address(network, Credential.ScriptHash(script.scriptHash))

        // Calculate validity times with buffer for blockchain time drift
        // Preprod blockchain time can be behind real UTC by 2+ minutes
        val BlockchainTimeDriftBufferSeconds = 180
        val adjustedStart = validityStart.minusSeconds(BlockchainTimeDriftBufferSeconds)
        val validityEnd =
            validityStart.plusSeconds(DefaultValiditySeconds + BlockchainTimeDriftBufferSeconds)
        println(
          s"[rebalance] validityStart: $adjustedStart (adjusted -${BlockchainTimeDriftBufferSeconds}s), validityEnd: $validityEnd"
        )

        // CRITICAL: Sort channelData by TxOutRef to match Cardano's input ordering.
        // The validator uses input index to find the corresponding output, expecting
        // output[i] to correspond to input[i]. Since Cardano sorts inputs by TxOutRef,
        // we must add outputs in the same sorted order.
        val sortedChannelData = channelData.sortBy { case (utxo, _, _) =>
            (utxo.input.transactionId.toHex, utxo.input.index)
        }

        // Collect all signatories needed (all clients + exchange)
        val clientPkhs = sortedChannelData.map(_._2.clientPkh)
        val allSignatories =
            (clientPkhs :+ exchangePkh).distinct.map(pkh => AddrKeyHash(pkh.hash)).toSet

        // Build the transaction using TxBuilder which handles collateral
        // First add all spends (order doesn't matter, they get sorted anyway)
        val withSpends = sortedChannelData.foldLeft(TxBuilder(env)) { case (b, (utxo, _, _)) =>
            b.spend(utxo, Action.Update, script, allSignatories)
        }

        // Then add all outputs IN SORTED ORDER to match input ordering
        val withOutputs = sortedChannelData.foldLeft(withSpends) {
            case (b, (_, onChainState, tradingState)) =>
                // Calculate new locked value from snapshot: client balance + locked in orders
                // NOTE: exchangeBalance is NOT included - it's internal accounting that nets to zero
                // across all channels. After rebalance, each channel should hold exactly what the
                // client is entitled to (their balance + their pending orders).
                val newLockedValue = tradingState.tsClientBalance +
                    CosmexValidator.lockedInOrders(tradingState.tsOrders)

                b.payTo(scriptAddress, newLockedValue.toLedgerValue, onChainState.toData)
        }

        // Set validity and complete the transaction
        withOutputs
            .validFrom(adjustedStart)
            .validTo(validityEnd)
            .complete(provider, sponsor = sponsor)
            .await()
            .transaction
    }

    /** Builds a timeout transaction that transitions a channel from SnapshotContestState to the
      * next state.
      *
      * After the contestation period expires, anyone can submit this transaction to advance the
      * state:
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
        provider: BlockchainProvider,
        clientAccount: Account,
        channelRef: TransactionInput,
        currentState: OnChainState,
        contestationPeriodMs: BigInt
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

        // Get client address for fee sponsorship (scripts can't sponsor transactions)
        val clientAddress =
            Address(network, Credential.KeyHash(AddrKeyHash(currentState.clientPkh.hash)))

        // Extract contest start time from on-chain state
        val contestStartMs = currentState.channelState match {
            case OnChainChannelState.SnapshotContestState(_, contestSnapshotStart, _, _) =>
                contestSnapshotStart
            case OnChainChannelState.TradesContestState(_, tradeContestStart) =>
                tradeContestStart
            case _ =>
                throw new RuntimeException(
                  s"Cannot get contest start from state: ${currentState.channelState}"
                )
        }

        // Calculate timeout deadline: contestStart + contestationPeriod
        // validFrom must be STRICTLY AFTER this deadline for validator to pass (uses < not <=)
        val timeoutDeadlineMs = (contestStartMs + contestationPeriodMs).toLong
        // Add 1 slot length to ensure strict inequality after slot rounding in validator
        // The slot rounding loses sub-slot precision, so we need to add a full slot
        val minValidityStartMs = timeoutDeadlineMs + env.slotConfig.slotLength
        // Use max of deadline and (current time - 180s buffer for blockchain time drift)
        // The buffer accounts for preprod blockchain time being behind real UTC
        val nowWithBufferMs = Instant.now().toEpochMilli - 180_000
        val validityStartInstant =
            Instant.ofEpochMilli(Math.max(minValidityStartMs, nowWithBufferMs))
        // Validity end must be after validity start (add default validity + buffer)
        val validityEndInstant = validityStartInstant.plusSeconds(DefaultValiditySeconds + 180)
        val validityEndSlot = env.slotConfig.timeToSlot(validityEndInstant.toEpochMilli)
        val validityEnd = env.slotConfig.slotToTime(validityEndSlot)

        println(s"[timeout] contestStartMs: $contestStartMs")
        println(s"[timeout] contestationPeriodMs: $contestationPeriodMs")
        println(
          s"[timeout] timeoutDeadlineMs: $timeoutDeadlineMs (${Instant.ofEpochMilli(timeoutDeadlineMs)})"
        )
        println(
          s"[timeout] minValidityStartMs: $minValidityStartMs, nowWithBufferMs: $nowWithBufferMs"
        )
        println(
          s"[timeout] validityStart: ${validityStartInstant.toEpochMilli} (${validityStartInstant})"
        )
        println(s"[timeout] validityEnd (round-tripped): $validityEnd")

        // Compute the new state based on current state
        val newChannelState = currentState.channelState match {
            case OnChainChannelState.SnapshotContestState(contestSnapshot, _, _, _) =>
                val tradingState = contestSnapshot.snapshotTradingState
                if scalus.prelude.List.isEmpty(tradingState.tsOrders.toList) then
                    println(s"[timeout] going to PayoutState")
                    OnChainChannelState.PayoutState(
                      tradingState.tsClientBalance,
                      tradingState.tsExchangeBalance
                    )
                else
                    println(
                      s"[timeout] going to TradesContestState with tradeContestStart=$validityEnd"
                    )
                    OnChainChannelState.TradesContestState(
                      tradingState,
                      validityEnd // Use round-tripped validity end to match validator
                    )
            case OnChainChannelState.TradesContestState(tradingState, _) =>
                println(s"[timeout] going from TradesContestState to PayoutState")
                // When orders expire, locked value returns to balances
                val lockedValue = CosmexValidator.lockedInOrders(tradingState.tsOrders)
                OnChainChannelState.PayoutState(
                  tradingState.tsClientBalance + lockedValue,
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

        println(s"[timeout] newState: $newState")

        TxBuilder(env)
            .spend(channelUtxo, Action.Timeout, script, Set.empty)
            .payTo(scriptAddress, channelUtxo.output.value, newState.toData)
            .validFrom(validityStartInstant)
            .validTo(validityEndInstant)
            .complete(provider, sponsor = clientAddress)
            .await()
            .sign(new TransactionSigner(Set(clientAccount.paymentKeyPair)))
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
        provider: BlockchainProvider,
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

        // Buffer for blockchain time drift - preprod blockchain time can be behind real UTC
        val BlockchainTimeDriftBufferSeconds = 180

        // Query fresh UTxOs from the payout address for collateral selection
        // This avoids using stale UTxOs that may have been spent in previous transactions
        val payoutUtxos = provider
            .findUtxos(payoutAddress, None, None, None, None)
            .await()
            .getOrElse(
              throw new RuntimeException(
                s"Failed to query UTxOs for collateral at address: $payoutAddress"
              )
            )

        // Find a suitable ADA-only UTxO for collateral (at least 5 ADA)
        // Must be ADA-only (no tokens) and not the channel UTxO
        val MinCollateralLovelace = 5_000_000L
        val collateralUtxo = payoutUtxos
            .find { case (_, output) =>
                // Must have at least 5 ADA
                output.value.coin.value >= MinCollateralLovelace &&
                // Must be ADA-only (no multi-assets)
                output.value.assets.isEmpty &&
                // Must not have a datum (script UTxOs have datums)
                output.datumOption.isEmpty
            }
            .map { case (input, output) => Utxo(input, output) }
            .getOrElse(
              throw new RuntimeException(
                s"No suitable collateral UTxO found at $payoutAddress. " +
                    s"Need ADA-only UTxO with at least ${MinCollateralLovelace / 1_000_000} ADA. " +
                    s"Found ${payoutUtxos.size} UTxOs total."
              )
            )

        println(
          s"[payout] Selected collateral UTxO: ${collateralUtxo.input.transactionId.toHex.take(16)}...#${collateralUtxo.input.index}"
        )
        println(
          s"[payout] Collateral value: ${collateralUtxo.output.value.coin.value / 1_000_000.0} ADA"
        )

        if isFilled then
            // Full payout - client takes all funds, channel closes completely
            println(s"[payout] Full payout - channelUtxo.output.value: ${channelUtxo.output.value}")
            println(s"[payout] payoutAddress: $payoutAddress")

            // Add buffer for blockchain time drift
            val validityStart = Instant.now().minusSeconds(BlockchainTimeDriftBufferSeconds)
            val tx = TxBuilder(env)
                .spend(channelUtxo, Action.Payout, script, Set(addrKeyHash))
                .collaterals(collateralUtxo)
                .payTo(payoutAddress, channelUtxo.output.value)
                .validFrom(validityStart)
                .validTo(
                  Instant
                      .now()
                      .plusSeconds(DefaultValiditySeconds + BlockchainTimeDriftBufferSeconds)
                )
                .complete(provider, sponsor = payoutAddress)
                .await()
                .sign(new TransactionSigner(Set(clientAccount.paymentKeyPair)))
                .transaction

            println(s"[payout] Transaction inputs: ${tx.body.value.inputs.toSeq}")
            println(s"[payout] Transaction outputs:")
            tx.body.value.outputs.zipWithIndex.foreach { case (out, idx) =>
                println(s"  [$idx] address: ${out.value.address}, value: ${out.value.value}")
            }

            tx
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
                .collaterals(collateralUtxo)
                .payTo(payoutAddress, availableForPaymentLedger)
                .payTo(scriptAddress, newOutputValue, newState.toData)
                .validFrom(Instant.now().minusSeconds(BlockchainTimeDriftBufferSeconds))
                .validTo(
                  Instant
                      .now()
                      .plusSeconds(DefaultValiditySeconds + BlockchainTimeDriftBufferSeconds)
                )
                .complete(provider, sponsor = payoutAddress)
                .await()
                .sign(new TransactionSigner(Set(clientAccount.paymentKeyPair)))
                .transaction
    }

    /** Compute element-wise minimum of two Values. For each asset in `a`, takes the minimum of its
      * amount and the corresponding amount in `b`.
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

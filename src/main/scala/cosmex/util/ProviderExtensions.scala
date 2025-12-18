package cosmex.util

import scalus.cardano.ledger.{Transaction, TransactionInput}
import scalus.cardano.node.Provider
import scalus.utils.await

import scala.concurrent.ExecutionContext.Implicits.global

/** Extension methods for Provider to add utility functionality */
extension (provider: Provider)
    /** Submit transaction and wait for confirmation
      *
      * For MockLedgerApi, this returns immediately as transactions are instant. For real
      * blockchains (yaci-devkit), this polls until the transaction is confirmed.
      *
      * @param tx
      *   The transaction to submit
      * @param maxAttempts
      *   Maximum number of polling attempts
      * @param delayMs
      *   Delay between polling attempts in milliseconds
      * @return
      *   Right(()) if successful, Left(error) if failed
      */
    def submitAndWait(
        tx: Transaction,
        maxAttempts: Int = 10,
        delayMs: Int = 500
    ): Either[RuntimeException, Unit] = {
        // Submit the transaction
        provider.submit(tx).await() match {
            case Left(error) => return Left(RuntimeException(error.toString))
            case Right(_)    => ()
        }

        // Wait for confirmation by polling for the first output
        val txOutRef = TransactionInput(tx.id, 0)
        var attempts = 0
        println(s"[Provider] Polling for UTxO confirmation: ${tx.id.toHex.take(16)}#0")
        while attempts < maxAttempts do {
            provider.findUtxo(txOutRef).await() match {
                case Right(_) =>
                    println(s"[Provider] UTxO confirmed after ${attempts + 1} attempt(s)")
                    return Right(())
                case Left(_) =>
                    attempts += 1
                    if attempts < maxAttempts then {
                        if attempts % 5 == 0 then {
                            println(
                              s"[Provider] Still waiting... (attempt ${attempts}/${maxAttempts})"
                            )
                        }
                        Thread.sleep(delayMs)
                    }
            }
        }
        val error = new RuntimeException(
          s"Transaction ${tx.id.toHex.take(16)}... not confirmed after ${maxAttempts} attempts"
        )
        println(s"[Provider] ${error.getMessage}")
        Left(error)
    }

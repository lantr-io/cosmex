package cosmex.util

/** Trait for providers that support transaction status checking */
trait TransactionStatusProvider {
  /** Check if a transaction has been confirmed on-chain
    *
    * @param txHash Transaction hash to check
    * @return Right(true) if confirmed, Right(false) if not found, Left(error) on failure
    */
  def isTransactionConfirmed(txHash: String): Either[RuntimeException, Boolean]
}

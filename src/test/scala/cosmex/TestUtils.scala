package cosmex

import scalus.cardano.ledger.CardanoInfo
import scalus.cardano.address.Network
import scalus.cardano.node.Provider
import cosmex.cardano.YaciTestcontainerProvider

object CardanoInfoTestNet {
    /** Create CardanoInfo with current protocol parameters from provider
      *
      * For yaci-devkit, this fetches the actual protocol parameters from the running node.
      * This is essential for Plutus script transactions (minting policies) which depend on
      * protocol parameters for scriptIntegrityHash calculation.
      *
      * @param provider The blockchain provider (e.g., YaciTestcontainerProvider)
      * @return CardanoInfo with current protocol parameters from the provider
      */
    def currentNetwork(provider: Provider): CardanoInfo = {
        provider match {
            case yaci: YaciTestcontainerProvider =>
                yaci.getProtocolParams() match {
                    case Right(params) =>
                        println(s"[CardanoInfoTestNet] Fetched protocol parameters from yaci-devkit")
                        CardanoInfo.mainnet.copy(network = Network.Testnet, protocolParams = params)
                    case Left(error) =>
                        println(s"[CardanoInfoTestNet] Warning: Failed to fetch protocol parameters: ${error.getMessage}")
                        println(s"[CardanoInfoTestNet] Using default mainnet parameters (Plutus scripts may fail)")
                        CardanoInfo.mainnet.copy(network = Network.Testnet)
                }
            case _ =>
                // For other providers (MockLedgerApi, etc.), use mainnet params with testnet network
                CardanoInfo.mainnet.copy(network = Network.Testnet)
        }
    }
}

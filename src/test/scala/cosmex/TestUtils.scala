package cosmex

import scalus.cardano.ledger.{CardanoInfo, SlotConfig}
import scalus.cardano.address.Network
import scalus.cardano.node.Provider
import cosmex.cardano.{BlockfrostProvider, YaciTestcontainerProvider}

object CardanoInfoTestNet {

    /** Create CardanoInfo with current protocol parameters from provider
      *
      * For yaci-devkit, this fetches the actual protocol parameters from the running node. This is
      * essential for Plutus script transactions (minting policies) which depend on protocol
      * parameters for scriptIntegrityHash calculation.
      *
      * For Blockfrost providers (preprod, preview, mainnet), this uses the correct SlotConfig
      * for the network, which is critical for time-to-slot conversions in transaction validity.
      *
      * @param provider
      *   The blockchain provider (e.g., YaciTestcontainerProvider, BlockfrostProvider)
      * @return
      *   CardanoInfo with current protocol parameters from the provider
      */
    def currentNetwork(provider: Provider): CardanoInfo = {
        provider match {
            case yaci: YaciTestcontainerProvider =>
                yaci.getProtocolParams() match {
                    case Right(params) =>
                        println(
                          s"[CardanoInfoTestNet] Fetched protocol parameters from yaci-devkit"
                        )
                        CardanoInfo.mainnet.copy(network = Network.Testnet, protocolParams = params)
                    case Left(error) =>
                        println(
                          s"[CardanoInfoTestNet] Warning: Failed to fetch protocol parameters: ${error.getMessage}"
                        )
                        println(
                          s"[CardanoInfoTestNet] Using default mainnet parameters (Plutus scripts may fail)"
                        )
                        CardanoInfo.mainnet.copy(network = Network.Testnet)
                }
            case bf: BlockfrostProvider =>
                // Use the correct SlotConfig based on the Blockfrost network type
                val (network, slotConfig) = bf.networkType match {
                    case BlockfrostProvider.NetworkType.Mainnet =>
                        println(s"[CardanoInfoTestNet] Using Blockfrost Mainnet with SlotConfig.Mainnet")
                        (Network.Mainnet, SlotConfig.Mainnet)
                    case BlockfrostProvider.NetworkType.Preprod =>
                        println(s"[CardanoInfoTestNet] Using Blockfrost Preprod with SlotConfig.Preprod")
                        (Network.Testnet, SlotConfig.Preprod)
                    case BlockfrostProvider.NetworkType.Preview =>
                        println(s"[CardanoInfoTestNet] Using Blockfrost Preview with SlotConfig.Preview")
                        (Network.Testnet, SlotConfig.Preview)
                    case BlockfrostProvider.NetworkType.Unknown =>
                        println(s"[CardanoInfoTestNet] Warning: Unknown Blockfrost network, defaulting to mainnet config")
                        (Network.Testnet, SlotConfig.Mainnet)
                }
                // Fetch protocol parameters from Blockfrost
                bf.getProtocolParams() match {
                    case Right(params) =>
                        println(s"[CardanoInfoTestNet] Fetched protocol parameters from Blockfrost")
                        CardanoInfo.mainnet.copy(network = network, slotConfig = slotConfig, protocolParams = params)
                    case Left(error) =>
                        println(s"[CardanoInfoTestNet] Warning: Failed to fetch protocol parameters: ${error.getMessage}")
                        println(s"[CardanoInfoTestNet] Using default mainnet parameters")
                        CardanoInfo.mainnet.copy(network = network, slotConfig = slotConfig)
                }
            case _ =>
                // For other providers (MockLedgerApi, etc.), use mainnet params with testnet network
                CardanoInfo.mainnet.copy(network = Network.Testnet)
        }
    }
}

package cosmex

import scalus.cardano.ledger.CardanoInfo
import scalus.cardano.node.BlockchainProvider

object CardanoInfoTestNet {

    /** Create CardanoInfo from provider.
      *
      * All BlockchainProvider implementations (BlockfrostProvider, Emulator, etc.) expose
      * `cardanoInfo` with correct network, slotConfig, and protocolParams.
      */
    def currentNetwork(provider: BlockchainProvider): CardanoInfo = {
        provider.cardanoInfo
    }
}

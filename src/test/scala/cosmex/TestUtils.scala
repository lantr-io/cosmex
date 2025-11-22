package cosmex

import scalus.cardano.ledger.CardanoInfo
import scalus.cardano.address.Network

object CardanoInfoTestNet {
    val testnet: CardanoInfo = CardanoInfo.mainnet.copy(network = Network.Testnet)
}

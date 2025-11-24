package cosmex.demo

import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.common.model.Networks
import com.typesafe.config.ConfigFactory

/** Utility to show wallet addresses for funding from faucet */
object ShowWalletAddresses extends App {
    println("""
        |================================================================
        |         COSMEX Wallet Addresses for Preprod Funding
        |================================================================
        |""".stripMargin)

    val config = ConfigFactory.load("application.conf")

    // Use preprod network
    val network = Networks.preprod()

    // Get configuration values
    val aliceSeed = config.getInt("alice.seed")
    val bobSeed = config.getInt("bob.seed")
    val exchangeSeed = config.getInt("exchange.seed")

    // Check if mnemonics are provided
    val aliceHasMnemonic =
        config.hasPath("alice.mnemonic") && !config.getString("alice.mnemonic").isEmpty
    val bobHasMnemonic = config.hasPath("bob.mnemonic") && !config.getString("bob.mnemonic").isEmpty

    // Create accounts
    val aliceAccount = if aliceHasMnemonic then {
        val mnemonic = config.getString("alice.mnemonic")
        new Account(network, mnemonic)
    } else {
        new Account(network, aliceSeed)
    }

    val bobAccount = if bobHasMnemonic then {
        val mnemonic = config.getString("bob.mnemonic")
        new Account(network, mnemonic)
    } else {
        new Account(network, bobSeed)
    }

    val exchangeAccount = new Account(network, exchangeSeed)

    // Show addresses
    println("Alice's Address:")
    println(s"  ${aliceAccount.baseAddress()}")
    println(s"  (Mnemonic: ${aliceHasMnemonic})")
    println()

    println("Bob's Address:")
    println(s"  ${bobAccount.baseAddress()}")
    println(s"  (Mnemonic: ${bobHasMnemonic})")
    println()

    println("Exchange's Address:")
    println(s"  ${exchangeAccount.baseAddress()}")
    println()

    println("""================================================================
        |Instructions:
        |1. Visit the Cardano Preprod Faucet:
        |   https://docs.cardano.org/cardano-testnet/tools/faucet/
        |
        |2. Request test ADA for each address:
        |   - Alice: At least 100 ADA (for channel deposit + trading)
        |   - Bob: At least 100 ADA (for channel deposit + trading)
        |   - Exchange: At least 50 ADA (for transaction fees)
        |
        |3. Verify balances using CardanoScan:
        |   https://preprod.cardanoscan.io
        |
        |4. Once funded, run the demo:
        |   sbt "testOnly cosmex.demo.MultiClientDemoTest"
        |================================================================
        |""".stripMargin)
}

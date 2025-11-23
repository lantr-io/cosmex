package cosmex.demo

/** Bob's Interactive Demo
  *
  * This demo launches the InteractiveDemo as Bob, providing:
  *   - Token minting capabilities
  *   - Channel opening
  *   - Order creation (buy/sell)
  *   - Real-time order matching
  *
  * For automated demo behavior, see MultiClientDemoTest
  */
object BobDemo extends App {
    println("""
        |================================================================
        |       COSMEX Demo - Bob's Interactive Trading Terminal
        |================================================================
        |
        |Starting interactive demo as Bob...
        |
        |Available commands:
        |  mint <tokenName> <amount>  - Mint custom tokens
        |  connect                     - Connect to the exchange
        |  buy <base> <quote> <amount> <price>   - Create buy order
        |  sell <base> <quote> <amount> <price>  - Create sell order
        |  help                        - Show this help
        |  quit                        - Exit the demo
        |
        |================================================================
        |""".stripMargin)

    // Run the interactive demo with Bob pre-selected
    System.setProperty("cosmex.demo.party", "bob")
    InteractiveDemo.main(args)
}

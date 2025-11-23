package cosmex.demo

/** Alice's Interactive Demo
  *
  * This demo launches the InteractiveDemo as Alice, providing:
  *   - Channel opening
  *   - Order creation (buy/sell)
  *   - Real-time order matching
  *
  * For automated demo behavior, see MultiClientDemoTest
  */
object AliceDemo extends App {
    println("""
        |================================================================
        |       COSMEX Demo - Alice's Interactive Trading Terminal
        |================================================================
        |
        |Starting interactive demo as Alice...
        |
        |Available commands:
        |  connect                     - Connect to the exchange
        |  buy <base> <quote> <amount> <price>   - Create buy order
        |  sell <base> <quote> <amount> <price>  - Create sell order
        |  help                        - Show this help
        |  quit                        - Exit the demo
        |
        |================================================================
        |""".stripMargin)

    // Run the interactive demo with Alice pre-selected
    System.setProperty("cosmex.demo.party", "alice")
    // Pass --external-server flag to connect to running server
    InteractiveDemo.main(Array("--external-server"))
}

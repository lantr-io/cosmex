# COSMEX Interactive Demo

An interactive command-line demo that allows you to experience the COSMEX decentralized exchange as either Alice or Bob.

## Overview

The interactive demo provides a hands-on way to:
- Choose which party to represent (Alice or Bob)
- Mint custom tokens
- Connect to the exchange
- Create buy/sell orders
- See real-time order matching

## Running the Demo

### Start the Demo

```bash
sbt "Test/runMain cosmex.demo.InteractiveDemo"
```

### Choose Your Party

When prompted, select who you want to be:
- **Alice**: Starts with ADA and can buy tokens
- **Bob**: Starts with ADA and can mint custom tokens to sell

## Available Commands

### `mint <tokenName> <amount>`
Mint custom tokens (useful for Bob to create tokens to sell).

**Example:**
```
Bob> mint MYTOKEN 1000000
```

This will:
- Create a minting transaction
- Submit it to the blockchain
- Wait for confirmation
- Display the policy ID of the minted token

### `connect`
Open a channel with the exchange and deposit funds.

**Example:**
```
Alice> connect
```

This will:
- Find available UTxOs
- Create an OpenChannel transaction
- Sign and submit it
- Wait for channel confirmation
- Connect via WebSocket

**Note:** If you minted tokens, the connect command will automatically use the UTxO containing the minted tokens.

### `buy <base> <quote> <amount> <price>`
Create a buy order for the specified trading pair.

**Parameters:**
- `base`: Base asset (e.g., "ADA", "MYTOKEN")
- `quote`: Quote asset (e.g., "ADA", "USDM")
- `amount`: Amount to buy
- `price`: Price per unit

**Example:**
```
Alice> buy ADA MYTOKEN 100000 1000000
```

This creates an order to buy 100,000 units of the base asset at price 1,000,000.

### `sell <base> <quote> <amount> <price>`
Create a sell order for the specified trading pair.

**Parameters:**
- `base`: Base asset (e.g., "ADA", "MYTOKEN")
- `quote`: Quote asset (e.g., "ADA", "USDM")
- `amount`: Amount to sell
- `price`: Price per unit

**Example:**
```
Bob> sell MYTOKEN ADA 50000 1000000
```

This creates an order to sell 50,000 units of the base asset at price 1,000,000.

### `help`
Display available commands.

### `quit` or `exit`
Exit the demo and shut down the exchange.

## Example Trading Session

Here's a complete example of a trading session as Bob:

```
================================================================
       COSMEX Interactive Demo - Decentralized Exchange
================================================================

Who would you like to be?
  1) Alice
  2) Bob

Enter choice (1 or 2): 2

✓ You are now playing as Bob
  Initial balance: ADA -> 50000000

[Server] Starting COSMEX exchange on port 18080...
[Server] ✓ Exchange is running at ws://localhost:18080

============================================================
Bob's Trading Terminal
============================================================

Available commands:
  mint <tokenName> <amount>  - Mint custom tokens
  connect                     - Connect to the exchange
  buy <base> <quote> <amount> <price>   - Create buy order
  sell <base> <quote> <amount> <price>  - Create sell order
  help                        - Show this help
  quit                        - Exit the demo

Bob> mint BOBTOKEN 1000000
[Mint] Minting 1000000 units of token 'BOBTOKEN'...
[Mint] ✓ Transaction submitted: 3f7a2b1c...
[Mint] Waiting for confirmation...
[Mint] ✓ Minted token policy ID: a3b4c5d6e7f8...

Bob> connect
[Connect] Opening channel with exchange...
[Connect] Depositing: 50 ADA
[Connect] ✓ Channel pending, txId: 8e9f0a1b...
[Connect] Waiting for channel confirmation...
[Connect] ✓ Channel opened successfully!

Bob> sell BOBTOKEN ADA 100000 1000000
[Order] Creating SELL order: 100000 BOBTOKEN/ADA @ 1000000
[Order] ✓ Order created! OrderID: order_12345
[Order] Waiting for potential matches...
[Order] No matching orders found

Bob> quit
Goodbye!

[Server] Exchange shut down.

================================================================
Thank you for using COSMEX!
================================================================
```

## Configuration

The demo uses the configuration from `src/test/resources/application.conf`. You can modify:

- **Network**: `mock`, `yaci-devkit`, `preprod`, `preview`
- **Initial balances**: Alice and Bob's starting funds
- **Exchange parameters**: Contest periods, minimum deposits, etc.

### Mock Provider (Default)

The mock provider is best for testing:
- Fast transactions (no waiting)
- No external dependencies
- Deterministic behavior

### Yaci DevKit Provider

For a more realistic blockchain experience:
```hocon
blockchain.provider = "yaci-devkit"
```

This will:
- Start a local Cardano node in a Docker container
- Create real transactions
- Have realistic confirmation times (~20 seconds)

### Network Providers (Preprod/Preview)

For real testnet interaction:
```hocon
blockchain.provider = "preprod"  # or "preview"
```

You'll need to:
1. Set `BLOCKFROST_PROJECT_ID` environment variable
2. Fund the addresses manually using a faucet

## Tips

1. **Start with mock**: Use the mock provider (`blockchain.provider = "mock"`) for fast testing
2. **Mint before connect**: If you want to trade custom tokens, mint them before connecting
3. **Watch for matches**: After creating orders, the demo will wait briefly for matching orders
4. **Two terminals**: Run two instances of the demo (as Alice and Bob) to see order matching in action

## Two-Party Trading Example

To see real order matching, open two terminal windows:

**Terminal 1 (Alice):**
```bash
sbt "Test/runMain cosmex.demo.InteractiveDemo"
# Choose Alice
Alice> connect
Alice> buy ADA BOBTOKEN 100000 1000000
```

**Terminal 2 (Bob):**
```bash
sbt "Test/runMain cosmex.demo.InteractiveDemo"
# Choose Bob
Bob> mint BOBTOKEN 1000000
Bob> connect
Bob> sell BOBTOKEN ADA 100000 1000000
```

When Bob's sell order matches Alice's buy order, both terminals will show:
```
[Order] ✓ Order executed! Trade amount: 100000, price: 1000000
```

## Troubleshooting

### "WebSocket connection failed to establish"
- Make sure the server started successfully
- Check that port 18080 is not already in use
- Try restarting the demo

### "Failed to find UTxO"
- Make sure you have sufficient initial balance in config
- For yaci-devkit, wait for funding to complete (~20 seconds)
- Check blockchain.provider setting

### "Order creation failed"
- Make sure you called `connect` first
- Verify you have sufficient balance
- Check that asset names are correct (ADA, USDM, or minted token name)

### "Unknown asset"
- Only ADA and USDM are pre-configured
- To trade other tokens, mint them first using the `mint` command
- Custom tokens are automatically recognized after minting

## Next Steps

After trying the interactive demo, explore:
- **MultiClientDemoTest**: Automated test showing concurrent trading
- **AliceDemo/BobDemo**: Programmatic demo clients
- **Server.scala**: The exchange implementation
- **CosmexValidator.scala**: The on-chain validation logic

## Architecture

The demo demonstrates the full COSMEX architecture:

```
┌─────────────┐     WebSocket      ┌──────────────┐
│   Client    │◄──────────────────►│    Server    │
│ (You/CLI)   │                    │  (Exchange)  │
└─────────────┘                    └──────┬───────┘
      │                                   │
      │         Blockchain                │
      │      (Mock/Yaci/Real)             │
      └───────────┬───────────────────────┘
                  │
          ┌───────▼────────┐
          │  CosmexValidator│
          │  (Plutus Smart  │
          │    Contract)    │
          └─────────────────┘
```

1. **Client (You)**: Issues commands via CLI
2. **Server (Exchange)**: Matches orders, manages channels
3. **Blockchain**: Validates transactions and enforces rules
4. **Smart Contract**: Ensures security and correctness

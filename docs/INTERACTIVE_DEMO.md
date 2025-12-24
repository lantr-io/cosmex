# COSMEX Interactive Demo

An interactive command-line demo that allows you to experience the COSMEX decentralized exchange as either Alice or Bob.

## Table of Contents

- [Overview](#overview)
- [Running the Demo](#running-the-demo)
- [Available Commands](#available-commands)
- [Example Trading Session](#example-trading-session)
- [Configuration](#configuration)
- [Tips](#tips)
- [Two-Party Trading Example](#two-party-trading-example)
- [Troubleshooting](#troubleshooting)

### Scenarios

| Scenario | Description |
|----------|-------------|
| [Example Trading Session](#example-trading-session) | Single-party demo: mint tokens, connect, and create sell order as Bob |
| [Two-Party Trading Example](#two-party-trading-example) | Basic two-party trading: Alice and Bob create matching orders |
| [Trading, Rebalance, and Payout](#scenario-trading-rebalance-and-payout) | Full lifecycle: trade, rebalance on-chain values, and exit via contested close with payout |

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

## Scenario: Trading, Rebalance, and Payout

This advanced scenario demonstrates the full lifecycle of a trading channel, including:
- Trading between parties
- Rebalancing on-chain values after trades
- Contested (unilateral) channel close with payout

### Prerequisites

Run three terminals:

**Terminal 1 (Server):**
```bash
sbt "Test/runMain cosmex.ws.CosmexWebSocketServerFromConfig"
```
Wait for the server to start:
```
============================================================
Starting WebSocket server on port 8080...
WebSocket endpoint: ws://localhost:8080/ws/{txId}/{txIdx}
============================================================
```

**Terminal 2 (Alice):**
```bash
sbt "Test/runMain cosmex.demo.AliceDemo"
```

**Terminal 3 (Bob):**
```bash
sbt "Test/runMain cosmex.demo.BobDemo"
```

Both `AliceDemo` and `BobDemo` connect to the server started in Terminal 1.

### Part 1: Setup and Trading

**Terminal 2 (Alice):**
```
Alice> connect ada 100
[Connect] Opening channel with exchange...
[Connect] Depositing: 100 ADA
[Connect] ✓ Channel pending, txId: 8e9f0a1b...
[Connect] ✓ Channel opened successfully!

Alice> get-state
============================================================
Client State
============================================================
Channel Status:    Open
Snapshot Version:  1

Balance:
  ADA:             100.000000
============================================================
```

**Terminal 3 (Bob):**
```
Bob> connect ada 100
[Connect] Opening channel with exchange...
[Connect] Depositing: 100 ADA
[Connect] ✓ Channel opened successfully!

Bob> get-state
============================================================
Client State
============================================================
Channel Status:    Open
Snapshot Version:  1

Balance:
  ADA:             100.000000
============================================================
```

Now both parties have channels. Let's trade using the built-in USDM stablecoin:

**Terminal 3 (Bob) - Creates a sell order:**
```
Bob> sell ada usdm 50 2
[Order] Creating SELL order: 50 ADA/USDM @ 2
[Order] ✓ Order request sent to exchange

[Notification] ✓ Order created! OrderID: 1
```

**Terminal 2 (Alice) - Creates a matching buy order:**
```
Alice> buy ada usdm 50 2
[Order] Creating BUY order: 50 ADA/USDM @ 2
[Order] ✓ Order request sent to exchange

[Notification] ✓ Order created! OrderID: 2
[Notification] ✓✓ Order executed! Trade amount: 50, price: 2
```

Both parties should now see the trade execution notification:
```
[Notification] ✓✓ Order executed! Trade amount: 50, price: 2
```

### Part 2: Verify Balances Changed

**Terminal 2 (Alice):**
```
Alice> get-state
============================================================
Client State
============================================================
Channel Status:    Open
Snapshot Version:  3

Balance:
  ADA:             150.000000    <- Alice now has 50 more ADA
============================================================
```

**Terminal 3 (Bob):**
```
Bob> get-state
============================================================
Client State
============================================================
Channel Status:    Open
Snapshot Version:  3

Balance:
  ADA:             50.000000     <- Bob now has 50 less ADA
============================================================
```

### Part 3: Rebalance

After trading, the on-chain locked values no longer match the off-chain balances:
- Alice's on-chain channel still holds 100 ADA (original deposit)
- But her off-chain balance is now 150 ADA

Rebalancing syncs the on-chain values with the current snapshot:

**Terminal 2 (Alice):**
```
Alice> rebalance
[Rebalance] Initiating rebalancing...
[Rebalance] This will sync on-chain locked values with snapshot balances.
[Rebalance] Sending FixBalance request...
[Rebalance] ✓ Rebalancing started!
[Rebalance] Waiting for RebalanceRequired...
[Rebalance] ✓ Received RebalanceRequired, signing transaction...
[Rebalance] Sending SignRebalance...
[Rebalance] ✓ SignRebalance sent
[Rebalance] ✓ Rebalancing complete! Snapshot version: 4
```

What happened:
1. Client requests rebalance via `FixBalance`
2. Server calculates the difference between on-chain and off-chain values
3. Server creates a multi-party swap transaction
4. Client signs their portion of the transaction
5. Server submits the transaction and updates the snapshot

After rebalance, Alice's on-chain channel now holds 150 ADA.

### Part 4: Contested Close with Payout

If Alice wants to exit without server cooperation (e.g., server is unresponsive), she can use the contested close path:

**Terminal 2 (Alice):**
```
Alice> contested-close
[ContestedClose] Initiating contested close...
[ContestedClose] Channel ref: 3f7a2b1c#0
[ContestedClose] This will start the contestation period.
[ContestedClose] Transaction built: a1b2c3d4...
[ContestedClose] ✓ Transaction submitted: a1b2c3d4...
[ContestedClose] Channel is now in SnapshotContestState.
[ContestedClose] After the contest period, use 'timeout' to advance.
```

Now Alice must wait for the contest period (configured in the exchange parameters, typically a few minutes on testnet).

After the contest period expires:
```
Alice> timeout
[Timeout] Advancing channel state after contest period...
[Timeout] Channel is in SnapshotContestState, advancing...
[Timeout] Transaction built: b2c3d4e5...
[Timeout] ✓ Transaction submitted: b2c3d4e5...
[Timeout] Channel is now in TradesContestState.
[Timeout] Run 'timeout' again after contest period.
```

After the second contest period:
```
Alice> timeout
[Timeout] Advancing channel state after contest period...
[Timeout] Channel is in TradesContestState, advancing...
[Timeout] Transaction built: c3d4e5f6...
[Timeout] ✓ Transaction submitted: c3d4e5f6...
[Timeout] Channel is now in PayoutState.
[Timeout] Client balance: 150 ADA
[Timeout] Use 'payout' to withdraw your funds.
```

Finally, withdraw funds:
```
Alice> payout
[Payout] Withdrawing funds from channel...
[Payout] Client balance to withdraw: 150 ADA
[Payout] Transaction built: d4e5f6g7...
[Payout] ✓ Transaction submitted: d4e5f6g7...
[Payout] ✓ Funds have been returned to your wallet!
```

### State Machine Summary

The contested close follows this state machine:

```
OpenState
    │
    │ contested-close (submits snapshot to chain)
    ▼
SnapshotContestState
    │
    │ timeout (after contest period)
    ▼
TradesContestState
    │
    │ timeout (after contest period)
    ▼
PayoutState
    │
    │ payout (withdraws funds)
    ▼
(Channel Closed)
```

### Why Use Rebalance Before Contested Close?

If you trade and then immediately use contested-close WITHOUT rebalancing:
- Your on-chain locked value is your original deposit (e.g., 100 ADA)
- But your off-chain balance is different (e.g., 150 ADA after winning trades)
- The payout will use the on-chain value, not your actual balance!

By rebalancing first:
- On-chain value is updated to match your current balance
- Contested close will use the correct (rebalanced) amount
- You receive your full balance after payout

### Graceful Close Alternative

If the exchange is responsive, use `close` instead of `contested-close`:

```
Alice> close
[Close] Closing channel with exchange...
[Close] ✓ Close transaction submitted: e5f6g7h8...
[Close] ✓ Channel closed successfully!
[Close] Final snapshot version: 4
[Close] Funds have been returned to your wallet.
```

Graceful close is instant and doesn't require waiting for contest periods.

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

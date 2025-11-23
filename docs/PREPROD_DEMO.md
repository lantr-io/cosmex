# COSMEX Preprod Demo Guide

This guide explains how to run the COSMEX demo on the Cardano Preprod testnet using the Blockfrost API.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Blockfrost Setup](#blockfrost-setup)
- [Wallet Setup](#wallet-setup)
- [Funding Wallets](#funding-wallets)
- [Configuration](#configuration)
- [Running the Demo](#running-the-demo)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

### Software Requirements

1. **Java JDK 11+**
   ```bash
   java -version
   # Should show version 11 or higher
   ```

2. **SBT** (Scala Build Tool)
   ```bash
   sbt --version
   ```

3. **Blockfrost Account**
   - Sign up at https://blockfrost.io
   - Create a Preprod project to get your API key

### Network Information

- **Network**: Preprod Testnet
- **Network Magic**: 1
- **Faucet**: https://docs.cardano.org/cardano-testnet/tools/faucet/
- **Blockfrost API**: https://cardano-preprod.blockfrost.io/api/v0

---

## Blockfrost Setup

### 1. Create Blockfrost Account

1. Visit https://blockfrost.io and sign up
2. Create a new project and select **Preprod** network
3. Copy your Project ID (API key)

### 2. Set Environment Variable

```bash
export BLOCKFROST_PROJECT_ID=your_preprod_project_id_here
```

Add this to your `~/.bashrc` or `~/.zshrc` to make it permanent:

```bash
echo 'export BLOCKFROST_PROJECT_ID=your_preprod_project_id_here' >> ~/.bashrc
source ~/.bashrc
```

---

## Wallet Setup

The demo uses seed-based or mnemonic-based wallet generation, so no manual wallet creation is needed. Wallets are automatically generated from configuration.

### Wallet Configuration

Wallets are configured in `src/test/resources/application.conf`:

- **Alice**: Uses seed or mnemonic phrase (configurable)
- **Bob**: Uses seed or mnemonic phrase (configurable)
- **Exchange**: Uses seed-based generation

If you want to use existing wallets with mnemonic phrases, you can set them in the configuration file (see Configuration section below).

---

## Funding Wallets

### Get Wallet Addresses

First, generate the wallet addresses from your configuration:

```bash
# Run the demo once to see wallet addresses (it will fail due to lack of funds)
sbt "testOnly cosmex.demo.MultiClientDemoTest"

# Or generate addresses using a simple script (see below)
```

The wallets will be generated from seeds/mnemonics configured in `application.conf`:
- Alice (seed: 2 or mnemonic if configured)
- Bob (seed: 3 or mnemonic if configured)
- Exchange (seed: 1)

### Using the Preprod Faucet

1. Visit the Cardano Preprod Faucet: https://docs.cardano.org/cardano-testnet/tools/faucet/

2. Fund each wallet with test ADA (you'll need the bech32 addresses generated above):
   - **Alice**: Request at least 100 ADA (for channel deposit + trading)
   - **Bob**: Request at least 100 ADA (for channel deposit + trading)
   - **Exchange**: Needs funds for transaction fees

3. Verify balances using Blockfrost or CardanoScan:
   - Visit https://preprod.cardanoscan.io
   - Enter wallet address to check balance

---

## Configuration

### Update application.conf for Preprod

Edit `src/test/resources/application.conf` to use Blockfrost provider:

```hocon
# COSMEX Preprod Demo Configuration

# Network Configuration
network {
  type = "testnet"      # For preprod testnet
  magic = 1             # Preprod magic number
  slotLength = 1000     # 1 second per slot
}

# Blockchain Provider Configuration
blockchain {
  provider = "preprod"  # Use Blockfrost for preprod network

  # Blockfrost settings (reads BLOCKFROST_PROJECT_ID from environment)
  networkProvider {
    type = "blockfrost"
    blockfrost {
      url = "https://cardano-preprod.blockfrost.io/api/v0"
      # Project ID is read from BLOCKFROST_PROJECT_ID environment variable
    }
  }
}

# Exchange Configuration
exchange {
  seed = 1              # Seed for exchange wallet generation
  params {
    minDeposit = 10000000           # 10 ADA minimum deposit
    snapshotContestPeriod = 100     # Slots
    tradesContestPeriod = 100       # Slots
  }
}

# Alice Configuration
alice {
  seed = 2              # Seed for Alice's wallet
  # Optional: Use mnemonic instead of seed for preprod
  mnemonic = "test test test test test test test test test test test test test test test test test test test test test test test sauce"

  initialBalance {
    ada = 1000000000    # 1000 ADA to deposit
  }

  trading {
    defaultOrder {
      side = "SELL"
      baseAsset = "ada"
      quoteAsset = "usdm"    # Trading ADA for USDM (or minted token)
      amount = 100000000      # 100 ADA
      price = 500000          # 0.50 USDM per ADA
    }
  }
}

# Bob Configuration
bob {
  seed = 3              # Seed for Bob's wallet
  # Optional: Use mnemonic instead of seed for preprod
  mnemonic = "test test test test test test test test test test test test test test test test test test test test test test test sauce"

  initialBalance {
    ada = 500000000     # 500 ADA to deposit
    usdm = 1000000000   # 1000 USDM (or minted token)
  }

  trading {
    defaultOrder {
      side = "BUY"
      baseAsset = "ada"
      quoteAsset = "usdm"
      amount = 100000000      # 100 ADA
      price = 550000          # 0.55 USDM per ADA
    }
  }

  # Bob will mint his own token for the demo
  minting {
    enabled = true
    tokenName = "BOBTOKEN"
    amount = 1000000000
  }
}

# Logging Configuration
logging {
  level = "INFO"
  verbose {
    server = false
    clients = false
    orderMatching = true
    blockchain = true
  }
}
```

### Configuration Checklist

Before running, ensure you have:

- [ ] Set `BLOCKFROST_PROJECT_ID` environment variable
- [ ] Set `blockchain.provider = "preprod"` in application.conf
- [ ] Funded Alice and Bob wallets with test ADA from faucet
- [ ] Network settings match Preprod (magic = 1)

---

## Running the Demo

COSMEX provides three ways to run the demo:

1. **Option A: Interactive Demo** (Recommended for beginners) - Interactive command-line interface where you control trading manually
2. **Option B: Automated Alice/Bob Demos** - Programmatic demos that automatically execute trades
3. **Option C: Automated Test** - Concurrent multi-client test

Choose the option that best fits your needs:

---

### Option A: Interactive Demo (Recommended)

The interactive demo provides a hands-on command-line interface where you can manually control trading operations.

**Step 1: Run the Interactive Demo**

```bash
# Navigate to project root
cd /path/to/cosmex

# Start the interactive demo
sbt "Test/runMain cosmex.demo.InteractiveDemo"
```

**Step 2: Choose Your Party**

When prompted, select who you want to be:
- **1) Alice**: Starts with ADA configured balance
- **2) Bob**: Starts with ADA, can mint custom tokens

**Step 3: Use Interactive Commands**

Available commands:
- `mint <tokenName> <amount>` - Mint custom tokens (Bob)
- `connect` - Connect to the exchange and open channel
- `buy <base> <quote> <amount> <price>` - Create buy order
- `sell <base> <quote> <amount> <price>` - Create sell order
- `help` - Show available commands
- `quit` - Exit the demo

**Example Session (as Bob):**

```
Bob> mint BOBTOKEN 1000000
[Mint] Minting 1000000 units of token 'BOBTOKEN'...
[Mint] ✓ Minted token policy ID: a3b4c5d6...

Bob> connect
[Connect] Opening channel with exchange...
[Connect] ✓ Channel opened successfully!

Bob> sell BOBTOKEN ADA 100000 1000000
[Order] Creating SELL order: 100000 BOBTOKEN/ADA @ 1000000
[Order] ✓ Order created! OrderID: order_1
[Order] Waiting for potential matches...

Bob> quit
```

For more details on the interactive demo, see [INTERACTIVE_DEMO.md](INTERACTIVE_DEMO.md).

---

### Option B: Alice/Bob Interactive Demos

This option runs interactive demos for Alice and Bob separately. Each demo provides an interactive terminal for manual trading.

#### Terminal 1: Start the Server

```bash
# Navigate to project root
cd /path/to/cosmex

# Start the WebSocket server (reads from application.conf)
sbt "Test/runMain cosmex.ws.CosmexWebSocketServerFromConfig"

# Or with custom port:
# sbt "Test/runMain cosmex.ws.CosmexWebSocketServerFromConfig --port 9090"

# Expected output:
# Loading configuration from: application.conf (classpath)
#
# COSMEX Demo Configuration
# ========================
# Server:
#   URL: ws://localhost:8080/ws
#   ...
# ============================================================
# COSMEX WebSocket Server Starting...
# ============================================================
# Exchange PubKeyHash: a1b2c3d4...
# Network: preprod
# Provider: preprod
# [Server] Using preprod network via Blockfrost
# [Server] WARNING: Ensure wallets are funded from the faucet:
# [Server]   - Alice: addr_test1vpep5knc7qqxa6w8s3m9rgg2zsqch40ff3tuq2f3e4xn5jg77d85e
# [Server]   - Bob: addr_test1vrmlwfnkk0l9pcp2ng7280acpkw5vgxt4lrmq78ltda4mngu8refr
# ============================================================
# Starting WebSocket server on port 8080...
# WebSocket endpoint: ws://localhost:8080/ws/{txId}/{txIdx}
# ============================================================
```

**Wait for the server to fully start** before proceeding to the next terminals.

#### Terminal 2: Run Alice

```bash
# In a new terminal
cd /path/to/cosmex

# Run Alice demo
sbt "Test/runMain cosmex.demo.AliceDemo"

# Expected output:
# ============================================================
# COSMEX Demo: Alice Opens Channel & Creates SELL Order
# ============================================================
#
# [1] Creating Alice's account from config...
#     Alice Seed: 2
#     Alice PubKeyHash: ...
#     Exchange PubKeyHash: ...
#
#     Alice's Initial Balance:
#       - 1000.0 ADA
#
# [2] Connecting to WebSocket server...
#     Server URL: ws://localhost:8080/ws
#
# [3] Opening channel with deposit...
#     Transaction built: ...
#     Snapshot v0 created and signed
#     ✓ Channel opened successfully!
#
# [4] Creating SELL order from config...
#     Order: SELL 100000000 ada @ 500000
#     ✓ Order created successfully!
#     Order ID: 1
#     Amount: 100.00 ADA
#     Price: 0.50 USDM/ADA
#
# [5] Waiting for order matching...
#     (If Bob creates a matching BUY order, trade will execute automatically)
#
# ============================================================
# Demo completed successfully!
# ============================================================
```

Alice's order is now in the order book, waiting for a match.

#### Terminal 3: Run Bob

```bash
# In a new terminal
cd /path/to/cosmex

# Run Bob demo
sbt "Test/runMain cosmex.demo.BobDemo"

# Expected output:
# ============================================================
# COSMEX Demo: Bob Opens Channel & Creates BUY Order
# ============================================================
#
# [1] Creating Bob's account from config...
#     Bob Seed: 3
#     Bob PubKeyHash: ...
#
#     Bob's Initial Balance:
#       - 500.0 ADA
#       - 1000.0 USDM
#
# [2] Connecting to WebSocket server...
#
# [3] Opening channel with deposit...
#     ✓ Channel opened successfully!
#
# [4] Creating BUY order from config...
#     Order: BUY 100000000 ada @ 550000
#     ✓ Order created successfully!
#     Order ID: 2
#     Amount: 100.00 ADA
#     Price: 0.55 USDM/ADA
#
# [5] Waiting for order matching...
#     ✓ TRADE EXECUTED!
#     Trade ID: 1
#     Amount: 100 ADA
#     Price: 0.50 USDM/ADA (Alice's price - maker)
#
# [6] Querying final balance...
#     Current balance:
#       ADA: 600.0
#       USDM: 950.0
#
# ============================================================
# Demo completed successfully!
# ============================================================
```

#### What Happened?

1. **Server** started and initialized the exchange
2. **Alice** opened a channel with 1000 ADA and created a SELL order (100 ADA @ 0.50)
3. **Bob** opened a channel with 500 ADA + 1000 USDM and created a BUY order (100 ADA @ 0.55)
4. **Automatic matching** occurred because Bob's BUY price (0.55) ≥ Alice's SELL price (0.50)
5. **Trade executed** at Alice's price (0.50) following price-time priority
6. **Final balances**:
   - Alice: 900 ADA + 50 USDM
   - Bob: 600 ADA + 950 USDM

---

### Option C: Automated Multi-Client Test

This option runs an automated test that simulates concurrent trading between Alice and Bob.

```bash
# Navigate to project root
cd /path/to/cosmex

# Run the multi-client test
sbt "testOnly cosmex.demo.MultiClientDemoTest"
```

**What This Test Does:**

1. **Starts the exchange server** automatically
2. **Funds Alice and Bob** from configuration
3. **Bob mints custom tokens** (if enabled in config)
4. **Both clients connect concurrently** and open channels
5. **Both create orders simultaneously** (Alice sells, Bob buys)
6. **Automatic order matching** occurs
7. **Verifies trade execution** and balances

**Expected Output:**

```
[Test] Starting Alice and Bob concurrently...
[Alice] Opening channel...
[Bob] Opening channel...
[Alice] ✓ Channel opened!
[Bob] ✓ Channel opened!
[Alice] Creating SELL order: 100 ADA @ 1000000
[Bob] Creating BUY order: 100 BOBTOKEN @ 1000000
[Alice] ✓ Order created! OrderID: order_1
[Bob] ✓ Order created! OrderID: order_2
[Bob] ✓ Order executed! Trade: order_2, amount: 100, price: 1000000
[Alice] ✓ Order executed! Trade: order_1, amount: 100, price: 1000000

✓ Multi-client test completed successfully!
```

This option is best for:
- **Automated testing** of the full COSMEX protocol
- **CI/CD integration** for continuous testing
- **Verifying concurrent client behavior**

---

## Troubleshooting

### Server Won't Start

**Problem**: Port 8080 already in use

**Solution**:
```bash
# Use a different port
sbt "Test/runMain cosmex.ws.CosmexWebSocketServerFromConfig --port 9090"

# Or find and kill the process using port 8080
lsof -ti:8080 | xargs kill
```

**Problem**: Configuration not working as expected

**Solution**:
```bash
# The server reads from application.conf by default
# Make sure src/test/resources/application.conf has the correct settings

# Or use a custom config file
sbt "Test/runMain cosmex.ws.CosmexWebSocketServerFromConfig --config /path/to/custom.conf"
```

### Alice Can't Connect

**Problem**: Connection refused

**Solution**:
- Ensure server is running in Terminal 1
- Check server output for errors
- Verify WebSocket URL in config matches server port
- Check firewall settings

**Problem**: Channel opening fails

**Solution**:
- Verify Alice's wallet has sufficient funds (check with cardano-cli)
- Ensure wallet signing key file path is correct
- Check server logs for validation errors

### Bob's Order Doesn't Match

**Problem**: No trade execution

**Solution**:
- Verify Bob's BUY price ≥ Alice's SELL price
- Check both orders are for the same trading pair
- Ensure sufficient balance for trade
- Check server order book state

### Transaction Fails On-Chain

**Problem**: Plutus script execution failed

**Solution**:
- Verify validator script is correctly deployed
- Check datum and redeemer are properly formatted
- Ensure all signatures are valid
- Review Plutus script errors in transaction logs

### Balance Mismatch

**Problem**: Final balances don't match expected values

**Solution**:
- Account for transaction fees (usually 0.17-0.3 ADA)
- Verify no other transactions occurred during demo
- Check for partial order fills
- Review trade execution logs

---

## Verifying On-Chain

### Check Transaction Status

```bash
# Get transaction ID from demo output
TX_ID="<transaction-id-from-demo>"

# Query transaction
cardano-cli query utxo \
    --tx-in "${TX_ID}#0" \
    --testnet-magic 1

# Or use Cardano Explorer
# https://preprod.cardanoscan.io/transaction/${TX_ID}
```

### Verify Channel State

```bash
# Query exchange script address
SCRIPT_ADDR="<exchange-script-address>"

cardano-cli query utxo \
    --address ${SCRIPT_ADDR} \
    --testnet-magic 1

# Should show channel UTxOs with datums
```

### Check Order Book State

The server maintains the order book off-chain. Query via WebSocket:

```scala
val balanceRequest = ClientRequest.GetOrders(clientId)
client.sendRequest(balanceRequest)
```

---

## Cleanup

### Stop the Demo

1. **Terminal 3 (Bob)**: Demo completes automatically or Ctrl+C
2. **Terminal 2 (Alice)**: Demo completes automatically or Ctrl+C
3. **Terminal 1 (Server)**: Ctrl+C to stop server

### Close Channels (Optional)

To properly close channels and withdraw funds:

```bash
# TODO: Implement channel closing in demos
# Currently channels remain open on server shutdown
```

### Return Test ADA (Optional)

Test ADA can be returned to the faucet or left for future testing. The Preprod network periodically resets, so funds are not permanent.

---

## Advanced Topics

### Running with Real Blockchain

To connect to a real Cardano Preprod node instead of mock ledger:

1. **Install and sync a Cardano node** for Preprod
2. **Update configuration** with node socket path
3. **Implement Provider** using cardano-node API
4. **Submit transactions** to the node
5. **Monitor chain events** for confirmations

### Custom Trading Pairs

To add custom token pairs:

1. **Mint tokens** using cardano-cli or custom minting script
2. **Update assets configuration** with policy ID and asset name
3. **Fund wallets** with tokens
4. **Update order configurations** to use new assets

### Multiple Clients

To run more than Alice and Bob:

1. **Create additional wallet** (Charlie, David, etc.)
2. **Add client configuration** to config file
3. **Create demo file** (e.g., `CharlieDemo.scala`)
4. **Run in new terminal**

---

## Next Steps

1. **Explore the API**: Try other requests (GetBalance, CancelOrder, etc.)
2. **Monitor trades**: Implement trade notification listeners
3. **Add error handling**: Handle network failures gracefully
4. **Implement persistence**: Save state to database
5. **Deploy to mainnet**: Requires security audit and real ADA

---

## Resources

- **Cardano Preprod Faucet**: https://docs.cardano.org/cardano-testnet/tools/faucet/
- **Cardano Explorer (Preprod)**: https://preprod.cardanoscan.io/
- **Cardano Documentation**: https://docs.cardano.org/
- **COSMEX Whitepaper**: See `docs/Whitepaper.md`
- **Interactive Demo Guide**: See `docs/INTERACTIVE_DEMO.md`
- **API Documentation**: See `docs/WEBSOCKET_DEMO.md`

---

## Support

For issues or questions:

- Check the [Troubleshooting](#troubleshooting) section
- Review server logs for detailed error messages
- Consult the COSMEX whitepaper for protocol details
- Check that all configuration values are correct

---

**Happy Trading on Cardano Preprod! 🚀**

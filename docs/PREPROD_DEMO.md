# COSMEX Preprod Demo Guide

This guide explains how to run the COSMEX demo (Alice and Bob trading) on the Cardano Preprod testnet using three separate terminal windows.

## Table of Contents

- [Prerequisites](#prerequisites)
- [Wallet Setup](#wallet-setup)
- [Funding Wallets](#funding-wallets)
- [Configuration](#configuration)
- [Running the Demo](#running-the-demo)
- [Troubleshooting](#troubleshooting)
- [Cleanup](#cleanup)

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
   sbtn --version
   ```

3. **cardano-cli** (for wallet creation and transaction signing)
   ```bash
   cardano-cli --version
   # Version 8.0.0 or higher recommended
   ```

4. **Access to Cardano Preprod Network**
   - Public Preprod RPC endpoints available
   - Or run your own Preprod node

### Network Information

- **Network**: Preprod Testnet
- **Network Magic**: 1
- **Faucet**: https://docs.cardano.org/cardano-testnet/tools/faucet/

---

## Wallet Setup

### 1. Create Wallet Directories

```bash
mkdir -p wallets/{alice,bob,exchange}
cd wallets
```

### 2. Generate Exchange Wallet

```bash
# Generate payment key pair
cardano-cli address key-gen \
    --verification-key-file exchange/payment.vkey \
    --signing-key-file exchange/payment.skey

# Generate stake key pair (optional for demo)
cardano-cli stake-address key-gen \
    --verification-key-file exchange/stake.vkey \
    --signing-key-file exchange/stake.skey

# Generate payment address
cardano-cli address build \
    --payment-verification-key-file exchange/payment.vkey \
    --stake-verification-key-file exchange/stake.vkey \
    --out-file exchange/payment.addr \
    --testnet-magic 1

# Display address
cat exchange/payment.addr
```

### 3. Generate Alice's Wallet

```bash
cardano-cli address key-gen \
    --verification-key-file alice/payment.vkey \
    --signing-key-file alice/payment.skey

cardano-cli stake-address key-gen \
    --verification-key-file alice/stake.vkey \
    --signing-key-file alice/stake.skey

cardano-cli address build \
    --payment-verification-key-file alice/payment.vkey \
    --stake-verification-key-file alice/stake.vkey \
    --out-file alice/payment.addr \
    --testnet-magic 1

cat alice/payment.addr
```

### 4. Generate Bob's Wallet

```bash
cardano-cli address key-gen \
    --verification-key-file bob/payment.vkey \
    --signing-key-file bob/payment.skey

cardano-cli stake-address key-gen \
    --verification-key-file bob/stake.vkey \
    --signing-key-file bob/stake.skey

cardano-cli address build \
    --payment-verification-key-file bob/payment.vkey \
    --stake-verification-key-file bob/stake.vkey \
    --out-file bob/payment.addr \
    --testnet-magic 1

cat bob/payment.addr
```

### 5. Extract Public Key Hashes

For configuration, you'll need the public key hashes:

```bash
# Exchange
cardano-cli address key-hash \
    --payment-verification-key-file exchange/payment.vkey

# Alice
cardano-cli address key-hash \
    --payment-verification-key-file alice/payment.vkey

# Bob
cardano-cli address key-hash \
    --payment-verification-key-file bob/payment.vkey
```

Save these hashes for later use in the configuration file.

---

## Funding Wallets

### Using the Preprod Faucet

1. Visit the Cardano Preprod Faucet: https://docs.cardano.org/cardano-testnet/tools/faucet/

2. Fund each wallet with test ADA:
   - **Exchange**: Request 1000 test ADA
   - **Alice**: Request 1100 test ADA (1000 for trading + 100 for fees)
   - **Bob**: Request 600 test ADA (500 for trading + 100 for fees)

3. Verify balances:

```bash
# Set environment variable for convenience
export CARDANO_NODE_SOCKET_PATH=/path/to/your/node.socket

# Check Exchange balance
cardano-cli query utxo \
    --address $(cat wallets/exchange/payment.addr) \
    --testnet-magic 1

# Check Alice balance
cardano-cli query utxo \
    --address $(cat wallets/alice/payment.addr) \
    --testnet-magic 1

# Check Bob balance
cardano-cli query utxo \
    --address $(cat wallets/bob/payment.addr) \
    --testnet-magic 1
```

### Minting Test USDM Token (Optional)

For Bob to have USDM tokens to trade:

1. Create a simple native token minting policy
2. Mint 1000 USDM tokens to Bob's address
3. Update configuration with actual USDM policy ID

**Note**: For the initial demo, you can skip USDM and just trade ADA/ADA by modifying the configuration.

---

## Configuration

### Update application.conf for Preprod

Create a preprod-specific configuration file:

```bash
cp src/main/resources/application.conf src/main/resources/preprod.conf
```

Edit `src/main/resources/preprod.conf`:

```hocon
# COSMEX Preprod Demo Configuration

server {
  host = "0.0.0.0"  # Listen on all interfaces
  port = 8080
}

network {
  type = "preprod"
  magic = 1

  # Preprod-specific settings
  slotLength = 1000

  # Cardano node connection (if using local node)
  nodeSocketPath = "/path/to/preprod/node.socket"

  # Or use public API endpoint
  apiEndpoint = "https://preprod.koios.rest/api/v1"
}

exchange {
  # IMPORTANT: Replace with actual values from wallet setup
  publicKeyHash = "YOUR_EXCHANGE_PKH_HERE"
  address = "YOUR_EXCHANGE_ADDRESS_HERE"

  # Path to signing key file
  signingKeyFile = "wallets/exchange/payment.skey"

  params {
    minDeposit = 2000000  # 2 ADA
    snapshotContestPeriod = 100
    tradesContestPeriod = 100
  }
}

alice {
  # IMPORTANT: Replace with actual values
  publicKeyHash = "YOUR_ALICE_PKH_HERE"
  address = "YOUR_ALICE_ADDRESS_HERE"
  signingKeyFile = "wallets/alice/payment.skey"

  initialBalance {
    ada = 1000000000  # 1000 ADA
  }

  trading {
    defaultOrder {
      side = "SELL"
      baseAsset = "ada"
      quoteAsset = "usdm"  # or "ada" for ADA/ADA trading
      amount = 100000000   # 100 ADA
      price = 500000       # 0.50 USDM per ADA
    }
  }
}

bob {
  # IMPORTANT: Replace with actual values
  publicKeyHash = "YOUR_BOB_PKH_HERE"
  address = "YOUR_BOB_ADDRESS_HERE"
  signingKeyFile = "wallets/bob/payment.skey"

  initialBalance {
    ada = 500000000   # 500 ADA
    usdm = 1000000000 # 1000 USDM (if you minted tokens)
  }

  trading {
    defaultOrder {
      side = "BUY"
      baseAsset = "ada"
      quoteAsset = "usdm"
      amount = 100000000  # 100 ADA
      price = 550000      # 0.55 USDM per ADA
    }
  }
}

# If trading ADA/ADA (no USDM tokens)
# Update both alice and bob orders:
# quoteAsset = "ada"
# And ensure both have sufficient ADA

assets {
  ada {
    policyId = ""
    assetName = ""
    decimals = 6
    symbol = "ADA"
  }

  usdm {
    # Replace with actual policy ID if you minted USDM
    policyId = "YOUR_USDM_POLICY_ID_HERE"
    assetName = "5553444d"  # "USDM" in hex
    decimals = 6
    symbol = "USDM"
  }
}

logging {
  level = "INFO"
  verbose {
    server = true
    clients = true
    orderMatching = true
    blockchain = true
  }
}
```

### Configuration Checklist

Before running, ensure you have:

- [ ] Replaced all `YOUR_*_HERE` placeholders with actual values
- [ ] Wallet key files exist at specified paths
- [ ] Wallets are funded with sufficient test ADA
- [ ] Network settings match Preprod (magic = 1)
- [ ] Server port (8080) is available

---

## Running the Demo

### Terminal 1: Start the Server

```bash
# Navigate to project root
cd /path/to/cosmex

# Start the WebSocket server with preprod config
sbtn "Test/runMain cosmex.ws.CosmexWebSocketServerDemo --config src/main/resources/preprod.conf"

# Expected output:
# Loading configuration from: src/main/resources/preprod.conf
#
# COSMEX Demo Configuration
# ========================
# Server:
#   URL: ws://0.0.0.0:8080/ws
#   ...
# ============================================================
# COSMEX WebSocket Server Starting...
# ============================================================
# Exchange PubKeyHash: a1b2c3d4...
# Network: preprod
# Starting WebSocket server on port 8080...
# WebSocket endpoint: ws://localhost:8080/ws/{txId}/{txIdx}
# ============================================================
```

**Wait for the server to fully start** before proceeding to the next terminals.

### Terminal 2: Run Alice

```bash
# In a new terminal
cd /path/to/cosmex

# Run Alice demo
sbtn "Test/runMain cosmex.demo.AliceDemo"

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

### Terminal 3: Run Bob

```bash
# In a new terminal
cd /path/to/cosmex

# Run Bob demo
sbtn "Test/runMain cosmex.demo.BobDemo"

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

### What Happened?

1. **Server** started and initialized the exchange
2. **Alice** opened a channel with 1000 ADA and created a SELL order (100 ADA @ 0.50)
3. **Bob** opened a channel with 500 ADA + 1000 USDM and created a BUY order (100 ADA @ 0.55)
4. **Automatic matching** occurred because Bob's BUY price (0.55) ≥ Alice's SELL price (0.50)
5. **Trade executed** at Alice's price (0.50) following price-time priority
6. **Final balances**:
   - Alice: 900 ADA + 50 USDM
   - Bob: 600 ADA + 950 USDM

---

## Troubleshooting

### Server Won't Start

**Problem**: Port 8080 already in use

**Solution**:
```bash
# Use a different port
sbtn "Test/runMain cosmex.ws.CosmexWebSocketServerDemo --port 9090"

# Or find and kill the process using port 8080
lsof -ti:8080 | xargs kill
```

**Problem**: Configuration file not found

**Solution**:
```bash
# Verify file exists
ls -la src/main/resources/preprod.conf

# Use absolute path
sbtn "Test/runMain cosmex.ws.CosmexWebSocketServerDemo --config /full/path/to/preprod.conf"
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

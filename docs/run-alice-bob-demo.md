# Alice and Bob Trading Demo

This document describes how to run the COSMEX WebSocket demo where Alice and Bob trade ADA/USDM.

## Architecture

**Current Status**: Virtual blockchain (MockLedgerApi)
**Next Steps**: 
1. Yaci DevKit integration
2. Preprod testnet deployment

## Components

1. **WebSocket Server** - COSMEX exchange server with order matching
2. **Alice Client** - Creates SELL order (100 ADA @ 0.50 USDM/ADA)
3. **Bob Client** - Creates BUY order (100 ADA @ 0.55 USDM/ADA)

## Running the Demo

### Step 1: Start the WebSocket Server

```bash
sbt "Test/runMain cosmex.ws.CosmexWebSocketServerDemo"
```

Expected output:
```
============================================================
COSMEX WebSocket Server
============================================================
Listening on: ws://localhost:8080/ws
============================================================

[Server] Ready to accept connections. Press ENTER to stop.
```

### Step 2: Run Alice (in a new terminal)

```bash
sbt "Test/runMain cosmex.demo.AliceDemo"
```

Alice will:
- Open a channel with 1000 ADA deposit
- Create a SELL order: 100 ADA @ 0.50 USDM/ADA
- Wait for a matching BUY order

### Step 3: Run Bob (in a new terminal)

```bash
sbt "Test/runMain cosmex.demo.BobDemo"
```

Bob will:
- Open a channel with 500 ADA + 1000 USDM deposit
- Create a BUY order: 100 ADA @ 0.55 USDM/ADA
- Trade executes automatically!

## Expected Trade Execution

Since Bob's BUY price (0.55) is higher than Alice's SELL price (0.50), the order should match at Alice's price (0.50).

**Result:**
- Alice gives 100 ADA, receives 50 USDM
- Bob gives 50 USDM, receives 100 ADA

## Configuration

All configuration is in `src/main/resources/application.conf`:

```hocon
alice {
  seed = 2
  initialBalance {
    ada = 1000000000  # 1000 ADA
  }
  trading.defaultOrder {
    side = "SELL"
    amount = 100000000  # 100 ADA
    price = 500000      # 0.50 USDM/ADA
  }
}

bob {
  seed = 3
  initialBalance {
    ada = 500000000   # 500 ADA
    usdm = 1000000000 # 1000 USDM
  }
  trading.defaultOrder {
    side = "BUY"
    amount = 100000000  # 100 ADA
    price = 550000      # 0.55 USDM/ADA
  }
}
```

## Troubleshooting

### "Connection refused"
- Make sure the server is running in Step 1
- Check that port 8080 is not in use

### "Timeout waiting for response"
- Server may be overloaded
- Try increasing timeout in client code
- Check server logs for errors

## Next: Yaci DevKit Integration

To test with a real (local) blockchain, we'll integrate Yaci DevKit:
- Local Cardano devnet
- Real transaction submission
- Real UTxO management
- Full validator execution

## Next: Preprod Deployment

Final step: Deploy to Cardano preprod testnet with real tADA.

# WebSocket Demo: Channel Lifecycle

This demo demonstrates the COSMEX WebSocket server and client implementation, showing:
1. Alice opening a channel with the exchange
2. Alice creating a limit order
3. Alice closing the channel and receiving payout

## Architecture

### Components Created

**1. DemoHelpers.scala** (`src/main/scala/cosmex/DemoHelpers.scala`)
- Reusable helper functions extracted from tests
- `mkClientSignedSnapshot()` - Sign snapshots with Ed25519 keys
- `mkInitialSnapshot()` - Create version 0 snapshot with deposits
- `mkBuyOrder()` / `mkSellOrder()` - Order creation utilities
- Account management helpers

**2. CosmexWebSocketServer** (`src/main/scala/cosmex/ws/CosmexWebSocketServer.scala`)
- **Core WebSocket server logic** (production-ready)
- Tapir + Netty-based WebSocket server
- Accepts any `Server` instance (Provider-agnostic)
- Handles `ClientRequest` messages (JSON)
- Returns `ClientResponse` messages (JSON)
- Request routing to `Server` class business logic
- `run(server, port)` method to start the server

**3. CosmexWebSocketServerDemo** (`src/test/scala/cosmex/ws/CosmexWebSocketServerDemo.scala`)
- **Demo entry point** using MockLedgerApi
- Creates exchange account and MockLedgerApi provider
- Initializes Server with test configuration
- Calls `CosmexWebSocketServer.run()` with demo server

**4. SimpleWebSocketClient** (`src/main/scala/cosmex/ws/SimpleWebSocketClient.scala`)
- Java 11 HttpClient WebSocket implementation
- Synchronous request/response for demo simplicity
- JSON serialization via upickle
- Production-ready client

**5. AliceDemo** (`src/test/scala/cosmex/demo/AliceDemo.scala`)
- Interactive demonstration application
- Hardcoded Alice account (seed=2)
- Supports full channel lifecycle:
  - `connect` - Open channel with deposit
  - `buy/sell` - Create limit orders
  - `get-state` - View balance and orders
  - `close` - Close channel and withdraw funds

**6. Server.scala Updates**
- Added `clientId` parameter to `ClientRequest` enum variants
- Added `Balance` and `Orders` response types
- Now supports GetBalance and GetOrders queries

## Running the Demo

### Terminal 1: Start the WebSocket Server

```bash
sbtn "Test/runMain cosmex.ws.CosmexWebSocketServerDemo"
```

Expected output:
```
============================================================
COSMEX WebSocket Server
============================================================
Exchange PubKeyHash: <hex string>
Listening on: ws://localhost:8080/ws
============================================================

[Server] Ready to accept connections. Press ENTER to stop.
```

### Terminal 2: Run Alice Demo

```bash
sbtn "Test/runMain cosmex.demo.AliceDemo"
```

Expected output:
```
================================================================
       COSMEX Demo - Alice's Interactive Trading Terminal
================================================================

Available commands:
  connect                     - Connect to the exchange
  buy <base> <quote> <amount> <price>   - Create buy order
  sell <base> <quote> <amount> <price>  - Create sell order
  get-state                   - Show current balance and orders
  close                       - Close channel and withdraw funds
  help                        - Show this help
  quit                        - Exit the demo

Alice> connect
[Connect] Opening channel with exchange...
[Connect] ✓ Channel opened successfully!

Alice> buy ada usdm 100 2
[Order] ✓ Order request sent to exchange
[Notification] ✓ Order created! OrderID: 0

Alice> get-state
============================================================
Client State
============================================================
Channel Status:    Open
Snapshot Version:  1

Balance:
  ADA:             500.000000

Orders:
  #0     BUY        100 @ 2

============================================================

Alice> close
[Close] Closing channel with exchange...
[Close] ✓ Close transaction submitted: abc123...
[Close] Waiting for confirmation...
[Close] ✓ Channel closed successfully!
[Close] Final snapshot version: 1
[Close] Funds have been returned to your wallet.

Alice> quit
Goodbye!
```

### Server Terminal Output

The server will show:
```
[Server] Received request: OpenChannel
[Server] Transaction submitted: abc123...
[Server] Channel pending for client: ClientId(...)
[Server] Channel confirmed after 3 attempt(s)

[Server] Received request: CreateOrder
[Server] Order created: 0, trades: 0
[Server] Sent response: OrderCreated

[Server] Received request: CloseChannel
[Server] Close pending for client: ClientId(...)
[Server] Close confirmed after 2 attempt(s)
```

## Message Protocol

### ClientRequest (Client → Server)

```scala
enum ClientRequest:
  case OpenChannel(tx: Transaction, snapshot: SignedSnapshot)
  case CloseChannel(clientId: ClientId, tx: Transaction, snapshot: SignedSnapshot)
  case CreateOrder(clientId: ClientId, order: LimitOrder)
  case CancelOrder(clientId: ClientId, orderId: OrderId)
  case Deposit(clientId: ClientId, amount: Value)
  case Withdraw(clientId: ClientId, amount: Value)
  case GetBalance(clientId: ClientId)
  case GetOrders(clientId: ClientId)
  case GetState(clientId: ClientId)
```

### ClientResponse (Server → Client)

```scala
enum ClientResponse:
  case ChannelPending(txId: String)
  case ChannelOpened(snapshot: SignedSnapshot)
  case ClosePending(txId: String)
  case ChannelClosed(snapshot: SignedSnapshot)
  case OrderCreated(orderId: OrderId)
  case OrderCancelled(orderId: OrderId)
  case OrderExecuted(trade: Trade)
  case Balance(balance: Value)
  case Orders(orders: AssocMap[OrderId, LimitOrder])
  case State(balance, orders, channelStatus, snapshotVersion)
  case Error(code: String, message: String)
```

## Technical Details

### Transaction Flow

1. **Channel Opening**:
   - Client builds opening transaction with deposit
   - Client creates snapshot v0: `{clientBalance: deposit, exchangeBalance: 0}`
   - Client signs snapshot with Ed25519 private key
   - Server validates transaction and snapshot
   - Server signs snapshot with exchange key
   - Server stores `ClientState` in memory

2. **Order Creation**:
   - Client sends `CreateOrder(clientId, order)`
   - Server validates channel is open
   - Server assigns `orderId` (auto-incrementing)
   - Server matches order against order book
   - Server applies trades to balances
   - Server creates snapshot v(n+1)
   - Server returns `OrderCreated(orderId)`

3. **Channel Closing (Graceful Close)**:
   - Client cancels all open orders first
   - Client builds close transaction with final snapshot
   - Client sends `CloseChannel(clientId, tx, snapshot)`
   - Server validates:
     - Channel is open
     - Snapshot version matches server's view
     - Exchange balance == 0
     - No open orders
     - Client balance == locked value
   - Server signs transaction with exchange key
   - Server submits transaction to blockchain
   - Server returns `ClosePending(txId)` immediately
   - Server updates channel status to `Closing`
   - Background polling waits for UTxO to be spent
   - When confirmed, server sends `ChannelClosed(snapshot)` via async channel
   - Client receives funds back to their wallet

### Key Design Decisions

- **MockLedgerApi**: Demo uses in-memory ledger (no real blockchain)
- **Hardcoded Keys**: Alice uses fixed seed for repeatability
- **Simple Synchronous Client**: Request/response pattern for clarity
- **Text-based WebSocket**: JSON over WebSocket text frames
- **OxStreams**: Using Tapir's Ox-based streaming for structured concurrency

### Dependencies Used

- **Tapir 1.12.3**: HTTP/WebSocket server framework
- **Netty**: Underlying server implementation
- **Ox**: Structured concurrency primitives
- **upickle**: JSON serialization
- **Scalus**: Cardano/Plutus integration
- **MockLedgerApi**: Test blockchain implementation

## Testing

All existing tests pass after implementation:
```bash
sbtn test
```

Output:
```
[info] Total number of tests run: 12
[info] Tests: succeeded 12, failed 0
[info] All tests passed.
```

## Next Steps

To extend the demo:

1. **Add more operations**: Implement Deposit, Withdraw, CancelOrder
2. **Multiple clients**: Run Bob's demo concurrently with Alice
3. **Persistence**: Add database to persist channel states
4. **Reconnection**: Handle WebSocket disconnects and reconnects
5. **Authentication**: Add signature verification for client requests
6. **Order book UI**: Create web frontend showing real-time order book updates
7. **Contested close**: Implement unilateral close with contestation period

## File Locations

```
cosmex/
├── src/main/scala/cosmex/
│   ├── DemoHelpers.scala                # Helper utilities
│   ├── Server.scala                     # Updated with WebSocket support
│   └── ws/
│       ├── CosmexWebSocketServer.scala # WebSocket server (core logic)
│       └── SimpleWebSocketClient.scala # WebSocket client
└── src/test/scala/cosmex/
    ├── demo/
    │   └── AliceDemo.scala                  # Demo application
    └── ws/
        └── CosmexWebSocketServerDemo.scala # Demo server with MockLedgerApi
```

## Notes

- Server runs on port 8080 by default
- All state is in-memory (lost on restart)
- Demo uses ADA/ADA pair for simplicity (real trading would use ADA/USDM, etc.)
- Order matching uses price-time priority
- Snapshots use version numbering for ordering

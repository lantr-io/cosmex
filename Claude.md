# COSMEX Project Guide for Claude

## Quick Reference

**Essential Commands:**

```bash
sbtn compile          # Compile the project
sbtn test             # Run all tests
sbtn cosmex/run       # Start the COSMEX server
```

**Key Files:**

- `src/main/scala/cosmex/CosmexValidator.scala` - Core Plutus validator (1033 lines)
- `src/main/scala/cosmex/Server.scala` - Off-chain server implementation
- `src/main/scala/cosmex/TxBuilder.scala` - Transaction construction utilities
- `src/test/scala/cosmex/` - Test suite
- `docs/Whitepaper.md` - Complete technical specification

**Important Patterns:**

- All state transitions must preserve balance invariants
- Signatures must be verified using `verifyEd25519Signature`
- State machine follows: Open → SnapshotContest → TradesContest → Payout
- Snapshots use version numbering for ordering

---

## Project Overview

**COSMEX** (Cardano Order-book Semi-centralized Multi-party EXchange) is a Layer-2 order book
exchange solution that extends the Hydra Head protocol for Cardano.

**Key Innovations:**

- **Star-shaped state channels**: Each user has a bilateral channel with the exchange (simpler than
  Hydra's multi-party channels)
- **Off-chain trading with on-chain guarantees**: Instant trades off-chain, secured by Plutus smart
  contracts
- **Provable solvency**: Users can always withdraw funds via on-chain contestation
- **Zero slippage & MEV protection**: Centralized order matching without front-running risks
- **Non-custodial**: Users maintain cryptographic control; exchange can only execute authorized
  orders

**Technology Stack:**

- **Scala 3.3.7** - Modern, type-safe language
- **Scalus 0.13.0** - Scala to Plutus Core compiler
- **Tapir 1.12.2** - Type-safe API definitions
- **Bloxbean Cardano Client Lib 0.7.0** - Blockchain interaction
- **ScalaTest 3.2.19** & **ScalaCheck 1.18.0** - Testing

---

## Key Principles

1. **Always run `sbtn test` before completing any task** - Tests must pass
2. **Maintain state machine invariants** - Every transition must follow the protocol
3. **Verify cryptographic signatures** - All snapshots must have valid signatures
4. **Preserve balance conservation** - Total funds in = total funds out
5. **Use Scala 3 idioms** - Prefer enums, given/using, extension methods
6. **Property-based testing** - Use ScalaCheck for complex validation logic

---

## Architecture Overview

### Channel Topology

COSMEX uses a **star-shaped network topology**:

- Each client has a bilateral channel with the exchange server
- Server maintains a global order book
- No direct channels between clients (unlike Hydra's full mesh)

### State Machine Phases

```
Open ──────────────────────────────────────────┐
  │                                             │
  ├─> SnapshotContest ──> TradesContest ─────> Payout
  │                            │                │
  └─────────────────────────────────────────────┘
```

1. **Open**: Normal trading, deposits, withdrawals
2. **SnapshotContest**: Contestation period for final snapshot (dispute wrong balances)
3. **TradesContest**: Contestation period for trades (dispute unauthorized trades)
4. **Payout**: Final settlement and fund distribution

### Core Components

**CosmexValidator.scala**:

- Main on-chain validator implementing the state machine
- Signature verification for snapshots
- Trade execution logic
- Balance verification
- State transition guards

**Server.scala**:

- Off-chain server managing channels
- Order book matching
- Snapshot signing and verification
- Client state tracking
- WebSocket/HTTP API

**TxBuilder.scala**:

- Transaction construction utilities
- UTXO management
- Fee calculation

### Key Data Structures

```scala
case class OnChainState(
                         channels: List[Channel],
                         snapshot: Snapshot,
                         tradingState: TradingState,
                         phase: Phase
                       )

case class Snapshot(
                     version: BigInt,
                     balances: List[(PubKeyHash, BalanceEntry)],
                     timestamp: POSIXTime
                   )

case class TradingState(
                         orders: List[Order],
                         trades: List[Trade],
                         nonces: List[(PubKeyHash, BigInt)]
                       )

case class Channel(
                    party: PubKeyHash,
                    balance: BigInt,
                    state: ChannelState
                  )
```

---

## Development Guidelines

### Smart Contract Development with Scalus

**Key Annotations:**

```scala
@Compile // Mark for Plutus compilation
given IsData[MyType] =
... // Define data encoding
```

**Common Patterns:**

```scala
// List operations (Plutus-compatible)
list.foldLeft(init) { (acc, elem) =>
...
}
list.find(predicate).get

// Signature verification
verifyEd25519Signature(pubKey, message, signature)

// Error handling
if (condition) () else error()
```

**Important Constraints:**

- No mutable state
- Limited recursion (stack depth)
- List operations must be Plutus-compatible

### Scala 3 Specific Practices

**Use Enums for Sum Types:**

```scala
enum Phase {
  case Open
  case SnapshotContest(deadline: POSIXTime)
  case TradesContest(deadline: POSIXTime)
  case Payout
}
```

**Extension Methods:**

```scala
extension (channel: Channel)
  def isActive: Boolean = channel.state == ChannelState.Active
```

**Given/Using for Context:**

```scala
given IsData[Order] =
...
def process(order: Order)(using IsData[Order]) =
...
```

### Testing Guidelines

**Always test:**

1. State machine transitions (valid and invalid)
2. Balance preservation properties
3. Signature verification
4. Edge cases (empty lists, zero balances, etc.)

**Property-Based Testing Example:**

```scala
property("balance sum is preserved") {
  forAll(genSnapshot) { snapshot =>
    val totalBefore = snapshot.balances.map(_._2.balance).sum
    val totalAfter = afterTrades.balances.map(_._2.balance).sum
    totalBefore == totalAfter
  }
}
```

**Unit Test Pattern:**

```scala
test("trade execution updates balances correctly") {
  val initialState =
...
  val trade =
...
  val result = executeTrade(initialState, trade)
  assert(result.balances(buyer) == expectedBuyerBalance)
  assert(result.balances(seller) == expectedSellerBalance)
}
```

---

## Security-Critical Code

### Signature Verification (CosmexValidator.scala:400-450)

**CRITICAL**: All snapshot signatures must be verified before accepting state changes.

```scala
// ✅ Correct
val isValid = verifyEd25519Signature(
  serverPubKey,
  snapshotMessage,
  snapshot.signature
)
if (!isValid) error()

// ❌ Wrong - missing verification
val snapshot = parseSnapshot(datum)
// ... use snapshot without verification
```

### Balance Validation (CosmexValidator.scala:600-650)

**CRITICAL**: Total funds must be conserved across all operations.

```scala
// ✅ Correct
val inputSum = channels.foldLeft(0) {
  _ + _.balance
}
val outputSum = newChannels.foldLeft(0) {
  _ + _.balance
}
if (inputSum != outputSum) error()

// ❌ Wrong - unchecked balance changes
val newBalance = oldBalance + amount // No verification
```

### Trade Execution (CosmexValidator.scala:700-800)

**CRITICAL**: Trades must be authorized by valid orders with matching prices.

```scala
// ✅ Correct
val buyOrder = orders.find(o => o.orderId == trade.buyOrderId).get
val sellOrder = orders.find(o => o.orderId == trade.sellOrderId).get
if (buyOrder.price < sellOrder.price) error() // Price check
if (trade.amount > buyOrder.amount) error() // Amount check

// ❌ Wrong - missing order validation
val trade = parseTrade(datum)
executeTrade(trade) // No checks
```

### State Transition Guards (CosmexValidator.scala:200-300)

**CRITICAL**: State transitions must follow the state machine rules.

```scala
// ✅ Correct
(oldPhase, newPhase) match {
  case (Open, SnapshotContest(_)) => checkSnapshotTransition()
  case (SnapshotContest(_), TradesContest(_)) => checkTradesTransition()
  case (TradesContest(_), Payout) => checkPayoutTransition()
  case _ => error() // Invalid transition
}

// ❌ Wrong - allowing arbitrary transitions
newPhase match {
  case Payout => distributeFunds()
  case _ => ()
}
```

---

## Common Tasks

### Running Tests

```bash
# Run all tests
sbtn test

# Run specific test
sbtn "testOnly cosmex.CosmexValidatorTest"
```

### Starting the Server

```bash
# Run server (listens on http://localhost:8080)
sbtn cosmex/run

# With custom parameters
sbtn "cosmex/run --port 9090"
```

### Adding New Order Types

1. **Define the order type** in data structures:

```scala
case class LimitOrder(
                       orderId: ByteString,
                       owner: PubKeyHash,
                       side: Side,
                       price: BigInt,
                       amount: BigInt,
                       timestamp: POSIXTime
                     ) derives IsData
```

2. **Add validation logic** in CosmexValidator.scala:

```scala
def validateOrder(order: Order, channel: Channel): Boolean =
  order match {
    case limit: LimitOrder =>
      limit.amount <= channel.balance && limit.price > 0
    // ... other cases
  }
```

3. **Update order matching** in Server.scala:

```scala
def matchOrders(orderBook: OrderBook, newOrder: Order): List[Trade] =
  newOrder match {
    case limit: LimitOrder => matchLimitOrder(orderBook, limit)
    // ... other cases
  }
```

4. **Add property-based tests**:

```scala
property("limit orders are matched correctly") {
  forAll(genLimitOrder, genOrderBook) { (order, book) =>
    val trades = matchOrders(book, order)
    trades.forall(t => t.price <= order.price)
  }
}
```

---

## Before Submitting Changes

**Checklist:**

- [ ] Run `sbtn test` - all tests pass
- [ ] Check for unused imports/variables (`-Wunused:all` enabled)
- [ ] Update Whitepaper.md if protocol changed
- [ ] Add tests for new functionality
- [ ] Property-based tests for complex logic
- [ ] Check balance preservation properties
- [ ] Verify signature checks are correct

**Commit Message Guidelines:**

- Use present tense: "add feature" not "added feature"
- Be specific: "fix signature verification in snapshot contest" not "fix bug"
- Keep it concise (50 chars for title, 72 for body)
- No "Co-authored by Claude" or similar

---

## Common Pitfalls to Avoid

### ❌ Using Scala Standard Library Lists Directly

```scala
// Wrong - not Plutus-compatible
val result = list.filter(predicate)

// ✅ Correct - use Scalus List

import scalus.builtin.List as PList

val result = list.filter(predicate) // Using PList
```

### ❌ Forgetting Snapshot Version Ordering

```scala
// Wrong - no version check
val newSnapshot = updateSnapshot(oldSnapshot)

// ✅ Correct - verify version increment
if (newSnapshot.version != oldSnapshot.version + 1) error()
```

### ❌ Modifying Balances Without Verification

```scala
// Wrong - unchecked balance change
channel.copy(balance = channel.balance + amount)

// ✅ Correct - verify total balance
val totalBefore = channels.map(_.balance).sum
val totalAfter = newChannels.map(_.balance).sum
if (totalBefore != totalAfter) error()
```

### ❌ Missing State Machine Guards

```scala
// Wrong - allowing invalid transitions
phase = Payout

// ✅ Correct - check valid transition
phase match {
  case TradesContest(deadline) if currentTime > deadline => Payout
  case _ => error()
}
```

---

## Resources

- **[Whitepaper.md](docs/Whitepaper.md)** - Complete technical specification
---

## Notes

- This project uses **snapshot resolvers** for Scalus (check `build.sbt` for versions)
- JDK 11+ required for running the server
- Nix flake available for reproducible development environment
- WebSocket API for real-time order updates (see Server.scala:200-250)
- Rebalancing mechanism allows moving funds between channels (see Whitepaper.md section 4.5)

# COSMEX – Cardano Layer 2 Order Book Solvent Centralized Crypto Exchange

## Abstract

COSMEX is a Cardano layer-2 (L2) solution.
It extends Hydra Head protocol to provide a centralized order book exchange (CEX), but guarantees the security of the funds and the privacy of the users.

Clients can deposit and withdraw funds to the exchange smart contract, and trade with other users using off-chain orders. They always has the option to withdraw funds from the exchange smart contract after a contestation period.

All the actual trades are guaranteed to follow client's orders, and are batched and settled on-chain in an efficient way.

## Desing overview

Clients connect to a centralized server, and use its REST/WS API to interact with the exchange. Most of the interactions are off-chain, where a client and the server exchange valid signed transaction, similarly to how Hydra Head or Bitcoin Lightning Network work.

### Deposit and withdraw

In order to trade a client must deposit funds to the Exchange Smart Contract (ESC) on-chain first.

To place or cancel orders, a client sends a signed transaction to the server that updates the state of the exchange smart contract with new list of orders.
The transaction is accepted and the client's order is added to the exchange order book (EOB).

When an order is matched, the client is notified and the server sends a signed transaction to the client that updates the state of the exchange smart contract with new list of orders. The exchange will update the state of the exchange smart contract with new list of orders off-chain. If needed the exchange will deposit/withdraw funds to/from the exchange smart contract on-chain to settle the trade.

A client can withdraw funds from the exchange smart contract by publishing its current state on-chain. This will put the ESC state machine into a contestation period. If there are matched trades on the exchange, the exchange will publish its current state on-chain. All funds that are not locked in orders will be withdrawn immediately.

### State machine

```haskell
data Action = Deposit | Withdraw | PlaceOrder | CancelOrder | MatchOrders
data Party = Client | Exchange
type OrderId = Integer
type Asset = (CurrencySymbol, TokenName)
type Pair = (Asset, Asset)
data Order = Order
  { orderId :: OrderId
  , orderPair :: Pair
  , orderAmount :: Integer
  , orderPrice :: Integer
  }

data State =
  Trading
    { escClientBalance :: Value
    , escExchangeBalance :: Value
    , escOrders :: Map OrderId Order
    , escVersion :: Integer
    }
  | Contestation
    { escBalances :: Map Party Value
    , escOrders :: Map OrderId Order
    , escVersion :: Integer
    }
```

### Settlement

## Related work

## References
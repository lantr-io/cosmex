package cosmex

import scala.annotation.tailrec

case class OrderBookEntry(orderId: OrderId, order: LimitOrder)

case class OrderBook(
    buyOrders: List[OrderBookEntry], // Sorted by price descending (highest bid first)
    sellOrders: List[OrderBookEntry] // Sorted by price ascending (lowest ask first)
)

object OrderBook {
    def empty: OrderBook = OrderBook(List.empty, List.empty)

    def addOrder(book: OrderBook, orderId: OrderId, order: LimitOrder): OrderBook = {
        if order.orderAmount > 0 then
            // BUY order - add to buy side, sorted by price descending
            val newEntry = OrderBookEntry(orderId, order)
            val updated = (newEntry :: book.buyOrders).sortBy(e => (-e.order.orderPrice, e.orderId))
            book.copy(buyOrders = updated)
        else if order.orderAmount < 0 then
            // SELL order - add to sell side, sorted by price ascending
            val newEntry = OrderBookEntry(orderId, order)
            val updated = (newEntry :: book.sellOrders).sortBy(e => (e.order.orderPrice, e.orderId))
            book.copy(sellOrders = updated)
        else
            // Zero amount - invalid order, don't add
            book
    }

    def removeOrder(book: OrderBook, orderId: OrderId): OrderBook = {
        book.copy(
          buyOrders = book.buyOrders.filterNot(_.orderId == orderId),
          sellOrders = book.sellOrders.filterNot(_.orderId == orderId)
        )
    }

    case class MatchResult(
        trades: List[Trade],
        remainingAmount: BigInt,
        updatedBook: OrderBook
    )

    def matchOrder(
        book: OrderBook,
        incomingOrderId: OrderId,
        incomingOrder: LimitOrder
    ): MatchResult = {
        if incomingOrder.orderAmount > 0 then
            // BUY order - match against sell orders
            matchBuyOrder(book, incomingOrderId, incomingOrder)
        else if incomingOrder.orderAmount < 0 then
            // SELL order - match against buy orders
            matchSellOrder(book, incomingOrderId, incomingOrder)
        else
            // Zero amount - no matches
            MatchResult(List.empty, 0, book)
    }

    private def matchBuyOrder(
        book: OrderBook,
        buyOrderId: OrderId,
        buyOrder: LimitOrder
    ): MatchResult = {
        @tailrec
        def loop(
            sellOrders: List[OrderBookEntry],
            remainingAmount: BigInt,
            trades: List[Trade]
        ): (List[OrderBookEntry], BigInt, List[Trade]) = {
            if remainingAmount == 0 || sellOrders.isEmpty then (sellOrders, remainingAmount, trades)
            else
                val sellEntry = sellOrders.head
                val sellOrder = sellEntry.order

                // Check if prices cross (buy price >= sell price)
                if buyOrder.orderPrice >= sellOrder.orderPrice then
                    val sellAmount = -sellOrder.orderAmount // Convert to positive
                    val tradeAmount = remainingAmount.min(sellAmount)
                    val tradePrice = sellOrder.orderPrice // Match at ask price

                    // Create trade for the buy order
                    val trade = Trade(
                      orderId = buyOrderId,
                      tradeAmount = tradeAmount,
                      tradePrice = tradePrice
                    )

                    val newRemaining = remainingAmount - tradeAmount
                    val newSellAmount = sellAmount - tradeAmount

                    // Update sell order or remove if fully filled
                    val updatedSellOrders =
                        if newSellAmount > 0 then
                            // Partially filled - update the order
                            val updatedOrder = sellOrder.copy(orderAmount = -newSellAmount)
                            sellEntry.copy(order = updatedOrder) :: sellOrders.tail
                        else
                            // Fully filled - remove from book
                            sellOrders.tail

                    loop(updatedSellOrders, newRemaining, trade :: trades)
                else
                    // No more matches
                    (sellOrders, remainingAmount, trades)
        }

        val (updatedSellOrders, remaining, trades) =
            loop(book.sellOrders, buyOrder.orderAmount, List.empty)

        MatchResult(
          trades = trades.reverse,
          remainingAmount = remaining,
          updatedBook = book.copy(sellOrders = updatedSellOrders)
        )
    }

    private def matchSellOrder(
        book: OrderBook,
        sellOrderId: OrderId,
        sellOrder: LimitOrder
    ): MatchResult = {
        val sellAmount = -sellOrder.orderAmount // Convert to positive for matching

        @tailrec
        def loop(
            buyOrders: List[OrderBookEntry],
            remainingAmount: BigInt,
            trades: List[Trade]
        ): (List[OrderBookEntry], BigInt, List[Trade]) = {
            if remainingAmount == 0 || buyOrders.isEmpty then (buyOrders, remainingAmount, trades)
            else
                val buyEntry = buyOrders.head
                val buyOrder = buyEntry.order

                // Check if prices cross (sell price <= buy price)
                if sellOrder.orderPrice <= buyOrder.orderPrice then
                    val buyAmount = buyOrder.orderAmount
                    val tradeAmount = remainingAmount.min(buyAmount)
                    val tradePrice = sellOrder.orderPrice // Match at bid price

                    // Create trade for the sell order (negative amount for SELL)
                    val trade = Trade(
                      orderId = sellOrderId,
                      tradeAmount = -tradeAmount,
                      tradePrice = tradePrice
                    )

                    val newRemaining = remainingAmount - tradeAmount
                    val newBuyAmount = buyAmount - tradeAmount

                    // Update buy order or remove if fully filled
                    val updatedBuyOrders =
                        if newBuyAmount > 0 then
                            // Partially filled - update the order
                            val updatedOrder = buyOrder.copy(orderAmount = newBuyAmount)
                            buyEntry.copy(order = updatedOrder) :: buyOrders.tail
                        else
                            // Fully filled - remove from book
                            buyOrders.tail

                    loop(updatedBuyOrders, newRemaining, trade :: trades)
                else
                    // No more matches
                    (buyOrders, remainingAmount, trades)
        }

        val (updatedBuyOrders, remaining, trades) =
            loop(book.buyOrders, sellAmount, List.empty)

        // Convert remaining back to negative for sell order
        val remainingSigned: BigInt = if remaining > 0 then -remaining else BigInt(0)

        MatchResult(
          trades = trades.reverse,
          remainingAmount = remainingSigned,
          updatedBook = book.copy(buyOrders = updatedBuyOrders)
        )
    }

    def getOrder(book: OrderBook, orderId: OrderId): Option[LimitOrder] = {
        book.buyOrders
            .find(_.orderId == orderId)
            .orElse(book.sellOrders.find(_.orderId == orderId))
            .map(_.order)
    }

    def getAllOrders(book: OrderBook): List[(OrderId, LimitOrder)] = {
        (book.buyOrders ++ book.sellOrders).map(e => (e.orderId, e.order))
    }
}

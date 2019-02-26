package data

import api.IStock
import database.*
import kotlinx.coroutines.CompletableDeferred
import api.OrderUpdate
import api.Transfer
import api.Update
import java.math.BigDecimal
import java.time.LocalDateTime

class FullState(val nonce: Long, val updatedStock: IStock, val books: Map<String, DepthBook>)

sealed class FullBookMsg(val stock: IStock)
@ExperimentalUnsignedTypes
class FullBook(updatedStock: IStock, val book: DepthBook, val nonce: ULong): FullBookMsg(updatedStock)
class DropBook(updatedStock: IStock): FullBookMsg(updatedStock)
class DropPair(updatedStock: IStock, val pair: String): FullBookMsg(updatedStock)

sealed class ControlMsg(var time: LocalDateTime = LocalDateTime.now())
class GetRates(val data: CompletableDeferred<Map<String, Map<String, Map<String, List<Map<String, String>>>>>> = CompletableDeferred()): ControlMsg()
class UpdateTradeFee(val name: String, val pairInfo: Map<String, TradeFee>, val done: CompletableDeferred<Boolean> = CompletableDeferred()): ControlMsg()
class UpdateCrossFee(val name: String, val info: Map<String, CrossFee>, val done: CompletableDeferred<Boolean> = CompletableDeferred()): ControlMsg()
class InitFeeVal(val done: CompletableDeferred<Boolean> = CompletableDeferred()): ControlMsg()
class UpdateLimits(val limit: BigDecimal, val full: BigDecimal, val limitLess: BigDecimal, val limitLessFull: BigDecimal): ControlMsg()
class UpdateFullWallet(val usdAmount: BigDecimal): ControlMsg()
class UpdateWallet(val name: String, val update: Map<String, BigDecimal>? = null, val plus: Pair<String, BigDecimal>? = null, val minus: Pair<String, BigDecimal>? = null): ControlMsg()
class GetWallet(val name: String, val wallet: CompletableDeferred<Map<String, BigDecimal>> = CompletableDeferred()): ControlMsg()
class DebugWallet(val name: String, val update: Map<String, BigDecimal>) : ControlMsg()
class GetActiveList(val name: String, val list: CompletableDeferred<List<Order>> = CompletableDeferred()): ControlMsg()
class LoadOrders(val name: String, val list: List<Order>): ControlMsg()
class ActiveUpdate(val name: String, val update: OrderUpdate, val decLock: Boolean): ControlMsg()
//class Arbs(val list: List<Arb>, val updatedStock: IStock): ControlMsg()

//sealed class DbMsg(val time: LocalDateTime = LocalDateTime.now())
//class OrderList(val playData: PlayData, time: LocalDateTime, val test: Boolean = false): DbMsg(time)
//class Netting(val operation: Operation, val amount: BigDecimal): DbMsg()
//class Progress(val stockFrom: String, val stockTo: String, val cur: String, val progress: BigDecimal, val limitLess: Boolean, val type: PlayType): DbMsg()
//class Withdraw(val stock_from: String, val stock_to: String, val cur: String, val amount: BigDecimal, val status: TransferStatus): DbMsg()
//class Book(val stockName: String, time: LocalDateTime, val update: List<Update>? = null, val fullState: DepthBook? = null): DbMsg(time)
//class Wallets(val data: Map<WalletType, Map<String, BigDecimal>>, val stockName: String): DbMsg()
//class TestWallets(val data: Map<String, Map<String, BigDecimal>>): DbMsg()
//class UpdateOrder(val order: Order): DbMsg()
//class FillProfitStatus(val order: Order): DbMsg()
//class UpdatePortfolio(val portfolio: Map<String, BigDecimal>): DbMsg()

sealed class TransferMsg(val time: LocalDateTime = LocalDateTime.now())
class TransferList(val list: List<Transfer>, val done: CompletableDeferred<Boolean>? = null): TransferMsg()

sealed class BookMsg(val time: LocalDateTime = LocalDateTime.now())
class InitPair(val pair: String?, val book: DepthBook): BookMsg()
class SingleUpdate(val update: Update): BookMsg()
class UpdateList(val list: List<Update>): BookMsg()
class PairError(val pair: String): BookMsg()
class ResetBook : BookMsg()
class BookError : BookMsg()
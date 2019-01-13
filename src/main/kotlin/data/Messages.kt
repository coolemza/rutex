package data

import database.CrossFee
import database.TradeFee
import kotlinx.coroutines.CompletableDeferred
import api.OrderUpdate
import api.Transfer
import api.Update
import java.math.BigDecimal
import java.time.LocalDateTime

sealed class ControlMsg(var time: LocalDateTime = LocalDateTime.now())
class BookUpdate(val name: String, val book: DepthBook): ControlMsg()
class DropBook(val name: String): ControlMsg()
class DropPair(val name: String, val pair: String): ControlMsg()
class GetRates(val data: CompletableDeferred<Map<String, Map<String, Map<String, List<Map<String, String>>>>>> = CompletableDeferred()): ControlMsg()
class UpdateTradeFee(val name: String, val pairInfo: Map<String, TradeFee>, val done: CompletableDeferred<Boolean> = CompletableDeferred()): ControlMsg()
class UpdateCrossFee(val name: String, val info: Map<String, CrossFee>, val done: CompletableDeferred<Boolean> = CompletableDeferred()): ControlMsg()
class InitFeeVal(val done: CompletableDeferred<Boolean> = CompletableDeferred()): ControlMsg()
class UpdateLimits(val limit: BigDecimal, val full: BigDecimal, val limitLess: BigDecimal, val limitLessFull: BigDecimal): ControlMsg()
class UpdateFullWallet(val usdAmount: BigDecimal): ControlMsg()
class UpdateWallet(val name: String, val update: Map<String, BigDecimal>? = null, val plus: Pair<String, BigDecimal>? = null, val minus: Pair<String, BigDecimal>? = null): ControlMsg()
class GetWallet(val name: String, val wallet: CompletableDeferred<Map<String, BigDecimal>> = CompletableDeferred()): ControlMsg()
//class UpdateProgress(val name: String, val o: Operation): ControlMsg()
class GetActiveList(val name: String, val list: CompletableDeferred<List<Order>> = CompletableDeferred()): ControlMsg()
class LoadOrder(val name: String, val list: List<Order>): ControlMsg()
class ActiveUpdate(val name: String, val update: OrderUpdate, val decLock: Boolean): ControlMsg()

sealed class BookMsg(val time: LocalDateTime = LocalDateTime.now())
class InitPair(val pair: String?, val book: DepthBook): BookMsg()
class SingleUpdate(val update: Update): BookMsg()
class UpdateList(val list: List<Update>): BookMsg()
class PairError(val pair: String): BookMsg()
class BookError : BookMsg()

sealed class TransferMsg(val time: LocalDateTime = LocalDateTime.now())
class TransferList(val list: List<Transfer>, val done: CompletableDeferred<Boolean>? = null): TransferMsg()
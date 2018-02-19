import com.github.salomonbrys.kodein.*
import database.Db
import database.IDb
import database.KeyType
import database.RutData
import org.amshove.kluent.shouldHaveKey
import org.junit.Test
import stock.Kraken

class KrakenTest {
    val kodein = Kodein {
        bind<IDb>() with singleton { Db() }
        constant("testKeys") with RutData.getTestKeys()
    }

    init {
        val db: IDb = kodein.instance()
        val wexId = db.getStockInfo(Kraken::class.simpleName!!).id

        val testKeys: Map<String, List<Triple<String, String, KeyType>>> = kodein.instance("testKeys")
        testKeys[Kraken::class.simpleName!!]!!.forEach { db.initKey(wexId, it.first, it.second, it.third) }
    }

    var stock = Kraken(kodein)

    @Test
    fun testWallet() {
        val wallet = stock.getBalance()!!
        wallet shouldHaveKey "usd"
        wallet shouldHaveKey "btc"
        wallet shouldHaveKey "ltc"
    }
}
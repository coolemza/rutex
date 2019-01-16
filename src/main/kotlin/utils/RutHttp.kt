package bot

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.content.ByteArrayContent
import io.ktor.http.ContentType
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance
import org.slf4j.Logger
import utils.IHttp
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDateTime

class RutHttp(override val kodein: Kodein) : IHttp, KodeinAware {
    private val client: HttpClient by instance()

    override suspend fun post(logger: Logger, url: String, hdrs: Map<String, String>?, postData: String, timeOut: Long): String? {
        try {
            return client.post<String>(url) {
                hdrs?.forEach { headers.append(it.key, it.value) }
                body = ByteArrayContent(postData.toByteArray(Charsets.UTF_8), contentType = ContentType.Application.FormUrlEncoded)
            }
        } catch (e: SocketTimeoutException) {
            logger.warn(e.message)
            logger.warn(" $url")
        } catch (e: IOException) {
            logger.error("IO exception: $url")
            logger.error(e.message)
        } catch (e: Exception) {
            logger.error("Full exception $url")
            logger.error(e.message)
        }
        return null
    }

    override suspend fun get(logger: Logger, url: String, timeOut: Long) = try {
        client.get<String>(url)
    } catch (e: SocketTimeoutException) {
        logger.warn(e.message)
        logger.warn(" $url")
        logger.trace("end ${LocalDateTime.now()}")
        null
    } catch (e: IOException) {
        logger.error("IO exception $url")
        logger.error(e.message)
        null
    } catch (e: Exception) {
        logger.error("Full exception $url")
        logger.error(e.message)
        null
    }
}

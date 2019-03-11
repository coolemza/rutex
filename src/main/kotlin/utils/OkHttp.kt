package utils

import okhttp3.*
import org.slf4j.Logger
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class OkHttp: IHttp {
    override suspend fun get(logger: Logger, url: String, timeOut: Long): String? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    val okHttp = OkHttpClient()
    val mediaType = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8")!!

    override suspend fun post(logger: Logger, url: String, hdrs: Map<String, String>?, postData: String, timeOut: Long): String? {
        logger.trace("begin ${LocalDateTime.now()}")
        var url = "https://webhook.site/119f39e3-4d18-4a2b-a6bf-c1fe72d65958"
        okHttp.newBuilder().connectTimeout(timeOut, TimeUnit.MILLISECONDS)
        try {
            val request = Request.Builder().url(url).apply {
                hdrs?.run { headers(Headers.of(hdrs)).post(RequestBody.create(mediaType, postData.toByteArray())) }
            }

            val response = okHttp.newCall(request.build()).execute()
            response.body()?.string()?.let { return it }

            logger.error("null body received")
            return null
        } catch (e: SocketTimeoutException) {
            logger.warn(e.message)
            logger.warn(" $url")
            logger.trace("end ${LocalDateTime.now()}")
            return null
        } catch (e: IOException) {
            logger.error(e.message)
            return null
        }
    }

}
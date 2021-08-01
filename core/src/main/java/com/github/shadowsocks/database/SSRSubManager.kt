package com.github.shadowsocks.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.util.Base64
import com.github.shadowsocks.Core
import com.github.shadowsocks.utils.printLog
import com.github.shadowsocks.utils.useCancellable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.sql.SQLException

object SSRSubManager {

    @Throws(SQLException::class)
    fun createSSRSub(ssrSub: SSRSub): SSRSub {
        ssrSub.id = 0
        ssrSub.id = PrivateDatabase.ssrSubDao.create(ssrSub)
        return ssrSub
    }

    @Throws(SQLException::class)
    fun updateSSRSub(ssrSub: SSRSub) = check(PrivateDatabase.ssrSubDao.update(ssrSub) == 1)

    @Throws(IOException::class)
    fun getSSRSub(id: Long): SSRSub? = try {
        PrivateDatabase.ssrSubDao[id]
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        null
    }

    @Throws(SQLException::class)
    fun delSSRSub(id: Long) {
        check(PrivateDatabase.ssrSubDao.delete(id) == 1)
    }

    @Throws(IOException::class)
    fun getAllSSRSub(): List<SSRSub> = try {
        PrivateDatabase.ssrSubDao.getAll()
    } catch (ex: SQLiteCantOpenDatabaseException) {
        throw IOException(ex)
    } catch (ex: SQLException) {
        printLog(ex)
        emptyList()
    }

    private suspend fun getResponse(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.addRequestProperty("User-Agent","ShadowsocksRb (https://github.com/ShadowsocksRb)")
        connection.addRequestProperty("X-Forwarded-For","127.0.0.1")
        val body = connection.useCancellable { inputStream.bufferedReader().use { it.readText() } }
        return String(Base64.decode(body, Base64.URL_SAFE))
    }

    fun deletProfiles(ssrSub: SSRSub) {
        val profiles = ProfileManager.getAllProfilesByGroup(ssrSub.url_group)
        ProfileManager.deletSSRSubProfiles(profiles)
    }

    suspend fun update(ssrSub: SSRSub, b: String = "") {
        val response = b.ifEmpty { getResponse(ssrSub.url) }
        var profiles = Profile.findAllSSRUrls(response, Core.currentProfile?.main).toList()
        when {
            profiles.isEmpty() -> {
                deletProfiles(ssrSub)
                ssrSub.status = SSRSub.EMPTY
                updateSSRSub(ssrSub)
                return
            }
            ssrSub.url_group != profiles[0].url_group -> {
                ssrSub.status = SSRSub.NAME_CHANGED
                updateSSRSub(ssrSub)
                return
            }
            else -> {
                ssrSub.status = SSRSub.NORMAL
                updateSSRSub(ssrSub)
            }
        }

        val count = profiles.count()
        var limit = -1
        if (response.indexOf("MAX=") == 0) {
            limit = response.split("\n")[0].split("MAX=")[1]
                    .replace("\\D+".toRegex(), "").toInt()
        }
        if (limit != -1 && limit < count) {
            profiles = profiles.shuffled().take(limit)
        }

        ProfileManager.createProfilesFromSub(profiles, ssrSub.url_group)
    }

    suspend fun create(url: String): SSRSub {
        val response = getResponse(url)
        val profiles = Profile.findAllSSRUrls(response, Core.currentProfile?.main).toList()
        if (profiles.isNullOrEmpty() || profiles[0].url_group.isEmpty()) throw IOException("Invalid Link")
        var new = SSRSub(url = url, url_group = profiles[0].url_group)
        getAllSSRSub().forEach { if (it.url_group == new.url_group) throw IOException("Group already exists") }
        new = createSSRSub(new)
        update(new, response)
        return new
    }
}

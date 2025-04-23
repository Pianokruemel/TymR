package com.example.tymr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class CalendarFetcher {
    /**
     * Fetches ICS calendar data from a URL
     * @param urlString The URL to fetch the ICS file from
     * @return The content of the ICS file as a string
     */
    suspend fun fetchCalendarData(urlString: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?

                while (reader.readLine().also { line = it } != null) {
                    response.append(line).append('\n')
                }
                reader.close()

                Result.success(response.toString())
            } else {
                Result.failure(IOException("HTTP error code: $responseCode"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
package com.smartstudent.planner.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import java.io.IOException

/** Adds "Authorization: Bearer <access token>" to every request. */
class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenStore.accessTokenNow() }
        val request = if (token != null) {
            chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

/**
 * On a 401, silently exchanges the refresh token for a new session and retries.
 * If the server says the refresh token is invalid, the session is cleared, which
 * sends the user back to the login screen.
 */
class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val plainApi: Lazy<PlannerApi>,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null

        synchronized(this) {
            val sentToken = response.request.header("Authorization")?.removePrefix("Bearer ")
            val currentToken = runBlocking { tokenStore.accessTokenNow() }

            // Another request already refreshed while we waited for the lock.
            if (currentToken != null && currentToken != sentToken) {
                return response.request.withToken(currentToken)
            }

            val refreshToken = runBlocking { tokenStore.refreshTokenNow() } ?: return null

            return try {
                val auth = runBlocking { plainApi.value.refresh(RefreshRequest(refreshToken)) }
                runBlocking { tokenStore.saveSession(auth) }
                response.request.withToken(auth.accessToken)
            } catch (e: HttpException) {
                if (e.code() == 401) runBlocking { tokenStore.clearSession() }
                null
            } catch (e: IOException) {
                null // offline: keep the session, try again later
            }
        }
    }

    private fun Request.withToken(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}

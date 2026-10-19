package com.smartstudent.planner.data.repo

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.smartstudent.planner.BuildConfig
import com.smartstudent.planner.data.local.AppDatabase
import com.smartstudent.planner.data.remote.GoogleLoginRequest
import com.smartstudent.planner.data.remote.PlannerApi
import com.smartstudent.planner.data.remote.RefreshRequest
import com.smartstudent.planner.data.remote.TokenStore
import com.smartstudent.planner.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class AuthRepository(
    private val plainApi: PlannerApi,
    private val tokenStore: TokenStore,
    private val database: AppDatabase,
    private val appContext: Context,
) {
    /** [activityContext] must be an Activity: Credential Manager shows UI on top of it. */
    suspend fun signInWithGoogle(activityContext: Context): Result<Unit> {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            return Result.failure(
                IllegalStateException("Set planner.googleWebClientId in local.properties")
            )
        }
        return try {
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val result = CredentialManager.create(activityContext).getCredential(activityContext, request)

            val credential = result.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return Result.failure(IllegalStateException("Unexpected credential type"))
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken

            val auth = plainApi.googleLogin(GoogleLoginRequest(idToken))

            // A different Google account than last time? Don't mix their data.
            if (tokenStore.dbOwner() != auth.user.id) {
                clearLocalData()
                tokenStore.setDbOwner(auth.user.id)
            }
            tokenStore.saveSession(auth)
            SyncScheduler.schedulePeriodic(appContext)
            SyncScheduler.syncSoon(appContext)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Result.failure(Exception("Sign-in cancelled"))
        } catch (e: GetCredentialException) {
            Result.failure(Exception(e.message ?: "Google sign-in failed"))
        } catch (e: IOException) {
            Result.failure(Exception("Can't reach the server. Check your connection and BASE_URL."))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Sign-in failed"))
        }
    }

    suspend fun signOut() {
        tokenStore.refreshTokenNow()?.let { token ->
            try {
                plainApi.logout(RefreshRequest(token))
            } catch (_: Exception) {
                // best effort; the token also expires on its own
            }
        }
        SyncScheduler.cancelAll(appContext)
        tokenStore.clearSession()
        clearLocalData()
        tokenStore.setDbOwner(null)
    }

    /** Wipes every local table and resets the sync cursors, so the next sign-in pulls everything. */
    private suspend fun clearLocalData() {
        withContext(Dispatchers.IO) { database.clearAllTables() }
        tokenStore.clearSyncCursors()
    }
}

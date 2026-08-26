package fr.enry.archivist.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * A dedicated Retrofit instance for [CognitoAuthApi] — deliberately not sharing
 * [NetworkModule]'s, because Cognito's endpoint requires the exact content type
 * `application/x-amz-json-1.1`, not `application/json`. Verified live: sending
 * `application/json` gets a 200 with `UnknownOperationException` in the body — the
 * endpoint silently ignores `X-Amz-Target` and never reaches the requested action —
 * so this can't be fixed with a per-call header override; it needs its own converter
 * bound to the right media type from the start.
 */
@Module
@InstallIn(SingletonComponent::class)
object CognitoNetworkModule {
    @Provides
    @Singleton
    fun provideCognitoAuthApi(
        okHttpClient: OkHttpClient,
        json: Json,
    ): CognitoAuthApi {
        val retrofit =
            Retrofit.Builder()
                .baseUrl(cognitoIdpUrl("us-east-1")) // never actually requested; every call supplies its own @Url
                .client(okHttpClient)
                .addConverterFactory(json.asConverterFactory("application/x-amz-json-1.1".toMediaType()))
                .build()
        return retrofit.create(CognitoAuthApi::class.java)
    }
}

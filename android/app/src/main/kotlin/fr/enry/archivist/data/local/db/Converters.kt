package fr.enry.archivist.data.local.db

import androidx.room.TypeConverter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Room instantiates [TypeConverters][androidx.room.TypeConverters] with a bare no-arg
 * constructor, so this can't take the app's shared [Json] via Hilt the way the
 * DataStore-backed stores do — a private instance is fine, since the only job here is
 * a stable JSON encoding for one column, not wire compatibility with the API.
 */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun thumbsToJson(thumbs: Map<Int, ThumbEntry>): String = json.encodeToString(thumbs)

    @TypeConverter
    fun thumbsFromJson(raw: String): Map<Int, ThumbEntry> = json.decodeFromString(raw)

    @TypeConverter
    fun assetStatusToString(status: AssetStatus): String = status.name

    @TypeConverter
    fun assetStatusFromString(raw: String): AssetStatus = AssetStatus.valueOf(raw)

    @TypeConverter
    fun renditionRoleToString(role: RenditionRole): String = role.name

    @TypeConverter
    fun renditionRoleFromString(raw: String): RenditionRole = RenditionRole.valueOf(raw)

    @TypeConverter
    fun uploadStateToString(state: UploadState): String = state.name

    @TypeConverter
    fun uploadStateFromString(raw: String): UploadState = UploadState.valueOf(raw)
}

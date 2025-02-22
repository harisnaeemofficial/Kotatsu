package org.koitharu.kotatsu.sync.domain

import android.accounts.Account
import android.content.*
import android.database.Cursor
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.core.content.contentValuesOf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.*
import org.koitharu.kotatsu.parsers.util.json.mapJSONTo
import org.koitharu.kotatsu.sync.data.SyncAuthApi
import org.koitharu.kotatsu.sync.data.SyncAuthenticator
import org.koitharu.kotatsu.sync.data.SyncInterceptor
import org.koitharu.kotatsu.utils.GZipInterceptor
import org.koitharu.kotatsu.utils.ext.parseJsonOrNull
import org.koitharu.kotatsu.utils.ext.toContentValues
import org.koitharu.kotatsu.utils.ext.toJson
import org.koitharu.kotatsu.utils.ext.toRequestBody
import java.util.concurrent.TimeUnit

const val AUTHORITY_HISTORY = "org.koitharu.kotatsu.history"
const val AUTHORITY_FAVOURITES = "org.koitharu.kotatsu.favourites"

private const val FIELD_TIMESTAMP = "timestamp"

/**
 * Warning! This class may be used in another process
 */
@WorkerThread
class SyncHelper(
	context: Context,
	account: Account,
	private val provider: ContentProviderClient,
) {

	private val httpClient = OkHttpClient.Builder()
		.authenticator(SyncAuthenticator(context, account, SyncAuthApi(context, OkHttpClient())))
		.addInterceptor(SyncInterceptor(context, account))
		.addInterceptor(GZipInterceptor())
		.build()
	private val baseUrl = context.getString(R.string.url_sync_server)
	private val defaultGcPeriod: Long // gc period if sync enabled
		get() = TimeUnit.DAYS.toMillis(4)

	fun syncFavourites(syncResult: SyncResult) {
		val data = JSONObject()
		data.put(TABLE_FAVOURITE_CATEGORIES, getFavouriteCategories())
		data.put(TABLE_FAVOURITES, getFavourites())
		data.put(FIELD_TIMESTAMP, System.currentTimeMillis())
		val request = Request.Builder()
			.url("$baseUrl/resource/$TABLE_FAVOURITES")
			.post(data.toRequestBody())
			.build()
		val response = httpClient.newCall(request).execute().parseJsonOrNull() ?: return
		val timestamp = response.getLong(FIELD_TIMESTAMP)
		val categoriesResult = upsertFavouriteCategories(response.getJSONArray(TABLE_FAVOURITE_CATEGORIES), timestamp)
		syncResult.stats.numDeletes += categoriesResult.first().count?.toLong() ?: 0L
		syncResult.stats.numInserts += categoriesResult.drop(1).sumOf { it.count?.toLong() ?: 0L }
		val favouritesResult = upsertFavourites(response.getJSONArray(TABLE_FAVOURITES), timestamp)
		syncResult.stats.numDeletes += favouritesResult.first().count?.toLong() ?: 0L
		syncResult.stats.numInserts += favouritesResult.drop(1).sumOf { it.count?.toLong() ?: 0L }
		gcFavourites()
	}

	fun syncHistory(syncResult: SyncResult) {
		val data = JSONObject()
		data.put(TABLE_HISTORY, getHistory())
		data.put(FIELD_TIMESTAMP, System.currentTimeMillis())
		val request = Request.Builder()
			.url("$baseUrl/resource/$TABLE_HISTORY")
			.post(data.toRequestBody())
			.build()
		val response = httpClient.newCall(request).execute().parseJsonOrNull() ?: return
		val result = upsertHistory(
			json = response.getJSONArray(TABLE_HISTORY),
			timestamp = response.getLong(FIELD_TIMESTAMP),
		)
		syncResult.stats.numDeletes += result.first().count?.toLong() ?: 0L
		syncResult.stats.numInserts += result.drop(1).sumOf { it.count?.toLong() ?: 0L }
		gcHistory()
	}

	private fun upsertHistory(json: JSONArray, timestamp: Long): Array<ContentProviderResult> {
		val uri = uri(AUTHORITY_HISTORY, TABLE_HISTORY)
		val operations = ArrayList<ContentProviderOperation>()
		operations += ContentProviderOperation.newDelete(uri)
			.withSelection("updated_at < ?", arrayOf(timestamp.toString()))
			.build()
		json.mapJSONTo(operations) { jo ->
			operations.addAll(upsertManga(jo.removeJSONObject("manga"), AUTHORITY_HISTORY))
			ContentProviderOperation.newInsert(uri)
				.withValues(jo.toContentValues())
				.build()
		}
		return provider.applyBatch(operations)
	}

	private fun upsertFavouriteCategories(json: JSONArray, timestamp: Long): Array<ContentProviderResult> {
		val uri = uri(AUTHORITY_FAVOURITES, TABLE_FAVOURITE_CATEGORIES)
		val operations = ArrayList<ContentProviderOperation>()
		operations += ContentProviderOperation.newDelete(uri)
			.withSelection("created_at < ?", arrayOf(timestamp.toString()))
			.build()
		json.mapJSONTo(operations) { jo ->
			ContentProviderOperation.newInsert(uri)
				.withValues(jo.toContentValues())
				.build()
		}
		return provider.applyBatch(operations)
	}

	private fun upsertFavourites(json: JSONArray, timestamp: Long): Array<ContentProviderResult> {
		val uri = uri(AUTHORITY_FAVOURITES, TABLE_FAVOURITES)
		val operations = ArrayList<ContentProviderOperation>()
		operations += ContentProviderOperation.newDelete(uri)
			.withSelection("created_at < ?", arrayOf(timestamp.toString()))
			.build()
		json.mapJSONTo(operations) { jo ->
			operations.addAll(upsertManga(jo.removeJSONObject("manga"), AUTHORITY_FAVOURITES))
			ContentProviderOperation.newInsert(uri)
				.withValues(jo.toContentValues())
				.build()
		}
		return provider.applyBatch(operations)
	}

	private fun upsertManga(json: JSONObject, authority: String): List<ContentProviderOperation> {
		val tags = json.removeJSONArray(TABLE_TAGS)
		val result = ArrayList<ContentProviderOperation>(tags.length() * 2 + 1)
		for (i in 0 until tags.length()) {
			val tag = tags.getJSONObject(i)
			result += ContentProviderOperation.newInsert(uri(authority, TABLE_TAGS))
				.withValues(tag.toContentValues())
				.build()
			result += ContentProviderOperation.newInsert(uri(authority, TABLE_MANGA_TAGS))
				.withValues(
					contentValuesOf(
						"manga_id" to json.getLong("manga_id"),
						"tag_id" to tag.getLong("tag_id"),
					)
				).build()
		}
		result.add(
			0,
			ContentProviderOperation.newInsert(uri(authority, TABLE_MANGA))
				.withValues(json.toContentValues())
				.build()
		)
		return result
	}

	private fun getHistory(): JSONArray {
		return provider.query(AUTHORITY_HISTORY, TABLE_HISTORY).use { cursor ->
			val json = JSONArray()
			if (cursor.moveToFirst()) {
				do {
					val jo = cursor.toJson()
					jo.put("manga", getManga(AUTHORITY_HISTORY, jo.getLong("manga_id")))
					json.put(jo)
				} while (cursor.moveToNext())
			}
			json
		}
	}

	private fun getFavourites(): JSONArray {
		return provider.query(AUTHORITY_FAVOURITES, TABLE_FAVOURITES).use { cursor ->
			val json = JSONArray()
			if (cursor.moveToFirst()) {
				do {
					val jo = cursor.toJson()
					jo.put("manga", getManga(AUTHORITY_FAVOURITES, jo.getLong("manga_id")))
					json.put(jo)
				} while (cursor.moveToNext())
			}
			json
		}
	}

	private fun getFavouriteCategories(): JSONArray {
		return provider.query(AUTHORITY_FAVOURITES, TABLE_FAVOURITE_CATEGORIES).use { cursor ->
			val json = JSONArray()
			if (cursor.moveToFirst()) {
				do {
					json.put(cursor.toJson())
				} while (cursor.moveToNext())
			}
			json
		}
	}

	private fun getManga(authority: String, id: Long): JSONObject {
		val manga = provider.query(
			uri(authority, TABLE_MANGA),
			null,
			"manga_id = ?",
			arrayOf(id.toString()),
			null,
		)?.use { cursor ->
			cursor.moveToFirst()
			cursor.toJson()
		}
		requireNotNull(manga)
		val tags = provider.query(
			uri(authority, TABLE_MANGA_TAGS),
			arrayOf("tag_id"),
			"manga_id = ?",
			arrayOf(id.toString()),
			null,
		)?.use { cursor ->
			val json = JSONArray()
			if (cursor.moveToFirst()) {
				do {
					val tagId = cursor.getLong(0)
					json.put(getTag(authority, tagId))
				} while (cursor.moveToNext())
			}
			json
		}
		manga.put("tags", requireNotNull(tags))
		return manga
	}

	private fun getTag(authority: String, tagId: Long): JSONObject {
		val tag = provider.query(
			uri(authority, TABLE_TAGS),
			null,
			"tag_id = ?",
			arrayOf(tagId.toString()),
			null,
		)?.use { cursor ->
			if (cursor.moveToFirst()) {
				cursor.toJson()
			} else {
				null
			}
		}
		return requireNotNull(tag)
	}

	private fun gcFavourites() {
		val deletedAt = System.currentTimeMillis() - defaultGcPeriod
		val selection = "deleted_at != 0 AND deleted_at < ?"
		val args = arrayOf(deletedAt.toString())
		provider.delete(uri(AUTHORITY_FAVOURITES, TABLE_FAVOURITES), selection, args)
		provider.delete(uri(AUTHORITY_FAVOURITES, TABLE_FAVOURITE_CATEGORIES), selection, args)
	}

	private fun gcHistory() {
		val deletedAt = System.currentTimeMillis() - defaultGcPeriod
		val selection = "deleted_at != 0 AND deleted_at < ?"
		val args = arrayOf(deletedAt.toString())
		provider.delete(uri(AUTHORITY_HISTORY, TABLE_HISTORY), selection, args)
	}

	private fun ContentProviderClient.query(authority: String, table: String): Cursor {
		val uri = uri(authority, table)
		return query(uri, null, null, null, null)
			?: throw OperationApplicationException("Query failed: $uri")
	}

	private fun uri(authority: String, table: String) = Uri.parse("content://$authority/$table")

	private fun JSONObject.removeJSONObject(name: String) = remove(name) as JSONObject

	private fun JSONObject.removeJSONArray(name: String) = remove(name) as JSONArray
}
package org.koitharu.kotatsu.search.ui.multi

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.ui.BaseViewModel
import org.koitharu.kotatsu.core.exceptions.CompositeException
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.list.ui.model.*
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.utils.ext.asLiveDataDistinct
import org.koitharu.kotatsu.utils.ext.printStackTraceDebug
import org.koitharu.kotatsu.utils.ext.runCatchingCancellable

private const val MAX_PARALLELISM = 4
private const val MIN_HAS_MORE_ITEMS = 8

class MultiSearchViewModel @AssistedInject constructor(
	@Assisted initialQuery: String,
	private val settings: AppSettings,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) : BaseViewModel() {

	private var searchJob: Job? = null
	private val listData = MutableStateFlow<List<MultiSearchListModel>>(emptyList())
	private val loadingData = MutableStateFlow(false)
	private var listError = MutableStateFlow<Throwable?>(null)

	val query = MutableLiveData(initialQuery)
	val list: LiveData<List<ListModel>> = combine(
		listData,
		loadingData,
		listError,
	) { list, loading, error ->
		when {
			list.isEmpty() -> listOf(
				when {
					loading -> LoadingState
					error != null -> error.toErrorState(canRetry = true)
					else -> EmptyState(
						icon = R.drawable.ic_empty_common,
						textPrimary = R.string.nothing_found,
						textSecondary = R.string.text_search_holder_secondary,
						actionStringRes = 0,
					)
				},
			)

			loading -> list + LoadingFooter
			else -> list
		}
	}.asLiveDataDistinct(viewModelScope.coroutineContext + Dispatchers.Default, listOf(LoadingState))

	init {
		doSearch(initialQuery)
	}

	fun getItems(ids: Set<Long>): Set<Manga> {
		val result = HashSet<Manga>(ids.size)
		listData.value.forEach { x ->
			for (item in x.list) {
				if (item.id in ids) {
					result.add(item.manga)
				}
			}
		}
		return result
	}

	fun doSearch(q: String) {
		val prevJob = searchJob
		searchJob = launchJob(Dispatchers.Default) {
			prevJob?.cancelAndJoin()
			try {
				listError.value = null
				listData.value = emptyList()
				loadingData.value = true
				query.postValue(q)
				searchImpl(q)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				listError.value = e
			} finally {
				loadingData.value = false
			}
		}
	}

	private suspend fun searchImpl(q: String) = coroutineScope {
		val sources = settings.getMangaSources(includeHidden = false)
		val dispatcher = Dispatchers.Default.limitedParallelism(MAX_PARALLELISM)
		val deferredList = sources.map { source ->
			async(dispatcher) {
				runCatchingCancellable {
					val list = mangaRepositoryFactory.create(source).getList(offset = 0, query = q)
						.toUi(ListMode.GRID)
					if (list.isNotEmpty()) {
						MultiSearchListModel(source, list.size > MIN_HAS_MORE_ITEMS, list)
					} else {
						null
					}
				}.onFailure {
					it.printStackTraceDebug()
				}
			}
		}

		val errors = ArrayList<Throwable>()
		for (deferred in deferredList) {
			deferred.await()
				.onSuccess { item ->
					if (item != null) {
						listData.update { x -> x + item }
					}
				}.onFailure {
					errors.add(it)
				}
		}
		if (listData.value.isEmpty()) {
			when (errors.size) {
				0 -> Unit
				1 -> throw errors[0]
				else -> throw CompositeException(errors)
			}
		}
	}

	@AssistedFactory
	interface Factory {

		fun create(initialQuery: String): MultiSearchViewModel
	}
}

package org.koitharu.kotatsu.base.ui

import android.app.Service
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.utils.ext.printStackTraceDebug

abstract class CoroutineIntentService : BaseService() {

	private val mutex = Mutex()
	protected open val dispatcher: CoroutineDispatcher = Dispatchers.Default

	final override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		super.onStartCommand(intent, flags, startId)
		launchCoroutine(intent, startId)
		return Service.START_REDELIVER_INTENT
	}

	private fun launchCoroutine(intent: Intent?, startId: Int) = lifecycleScope.launch(errorHandler(startId)) {
		mutex.withLock {
			try {
				if (intent != null) {
					withContext(dispatcher) {
						processIntent(startId, intent)
					}
				}
			} finally {
				stopSelf(startId)
			}
		}
	}

	protected abstract suspend fun processIntent(startId: Int, intent: Intent)

	protected abstract fun onError(startId: Int, error: Throwable)

	private fun errorHandler(startId: Int) = CoroutineExceptionHandler { _, throwable ->
		throwable.printStackTraceDebug()
		onError(startId, throwable)
	}
}

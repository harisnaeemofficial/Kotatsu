package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.View
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.RemoteMangaRepository
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.settings.sources.auth.SourceAuthActivity
import org.koitharu.kotatsu.utils.ext.awaitViewLifecycle
import org.koitharu.kotatsu.utils.ext.getDisplayMessage
import org.koitharu.kotatsu.utils.ext.printStackTraceDebug
import org.koitharu.kotatsu.utils.ext.runCatchingCancellable
import org.koitharu.kotatsu.utils.ext.serializableArgument
import org.koitharu.kotatsu.utils.ext.viewLifecycleScope
import org.koitharu.kotatsu.utils.ext.withArgs
import javax.inject.Inject

@AndroidEntryPoint
class SourceSettingsFragment : BasePreferenceFragment(0) {

	@Inject
	lateinit var mangaRepositoryFactory: MangaRepository.Factory

	private val source by serializableArgument<MangaSource>(EXTRA_SOURCE)
	private var repository: RemoteMangaRepository? = null
	private val exceptionResolver = ExceptionResolver(this)

	override fun onResume() {
		super.onResume()
		setTitle(source.title)
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		preferenceManager.sharedPreferencesName = source.name
		val repo = mangaRepositoryFactory.create(source) as? RemoteMangaRepository ?: return
		repository = repo
		addPreferencesFromResource(R.xml.pref_source)
		addPreferencesFromRepository(repo)

		findPreference<Preference>(KEY_AUTH)?.run {
			val authProvider = repo.getAuthProvider()
			isVisible = authProvider != null
			isEnabled = authProvider?.isAuthorized == false
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		findPreference<Preference>(KEY_AUTH)?.run {
			if (isVisible) {
				loadUsername(viewLifecycleOwner, this)
			}
		}
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			KEY_AUTH -> {
				startActivity(SourceAuthActivity.newIntent(preference.context, source))
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun loadUsername(owner: LifecycleOwner, preference: Preference) = owner.lifecycleScope.launch {
		runCatchingCancellable {
			preference.summary = null
			withContext(Dispatchers.Default) {
				requireNotNull(repository?.getAuthProvider()?.getUsername())
			}
		}.onSuccess { username ->
			preference.title = getString(R.string.logged_in_as, username)
		}.onFailure { error ->
			preference.isEnabled = error is AuthRequiredException
			when {
				error is AuthRequiredException -> Unit
				ExceptionResolver.canResolve(error) -> {
					ensureActive()
					Snackbar.make(
						listView ?: return@onFailure,
						error.getDisplayMessage(preference.context.resources),
						Snackbar.LENGTH_INDEFINITE,
					).setAction(ExceptionResolver.getResolveStringId(error)) { resolveError(error) }
						.show()
				}

				else -> preference.summary = error.getDisplayMessage(preference.context.resources)
			}
			error.printStackTraceDebug()
		}
	}

	private fun resolveError(error: Throwable) {
		viewLifecycleScope.launch {
			if (exceptionResolver.resolve(error)) {
				val pref = findPreference<Preference>(KEY_AUTH) ?: return@launch
				val lifecycleOwner = awaitViewLifecycle()
				loadUsername(lifecycleOwner, pref)
			}
		}
	}

	companion object {

		private const val KEY_AUTH = "auth"

		private const val EXTRA_SOURCE = "source"

		fun newInstance(source: MangaSource) = SourceSettingsFragment().withArgs(1) {
			putSerializable(EXTRA_SOURCE, source)
		}
	}
}

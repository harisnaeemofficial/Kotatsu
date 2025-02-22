package org.koitharu.kotatsu.search.ui.suggestion

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ItemTouchHelper
import coil.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.ui.BaseFragment
import org.koitharu.kotatsu.databinding.FragmentSearchSuggestionBinding
import org.koitharu.kotatsu.search.ui.suggestion.adapter.SearchSuggestionAdapter
import org.koitharu.kotatsu.utils.ext.addMenuProvider

@AndroidEntryPoint
class SearchSuggestionFragment :
	BaseFragment<FragmentSearchSuggestionBinding>(),
	SearchSuggestionItemCallback.SuggestionItemListener {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by activityViewModels<SearchSuggestionViewModel>()

	override fun onInflateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
	) = FragmentSearchSuggestionBinding.inflate(inflater, container, false)

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		val adapter = SearchSuggestionAdapter(
			coil = coil,
			lifecycleOwner = viewLifecycleOwner,
			listener = requireActivity() as SearchSuggestionListener,
		)
		addMenuProvider(SearchSuggestionMenuProvider(view.context, viewModel))
		binding.root.adapter = adapter
		binding.root.setHasFixedSize(true)
		viewModel.suggestion.observe(viewLifecycleOwner) {
			adapter.items = it
		}
		ItemTouchHelper(SearchSuggestionItemCallback(this))
			.attachToRecyclerView(binding.root)
	}

	override fun onWindowInsetsChanged(insets: Insets) {
		val extraPadding = resources.getDimensionPixelOffset(R.dimen.list_spacing)
		binding.root.updatePadding(
			top = extraPadding,
			right = insets.right,
			left = insets.left,
			bottom = insets.bottom,
		)
	}

	override fun onRemoveQuery(query: String) {
		viewModel.deleteQuery(query)
	}

	companion object {

		fun newInstance() = SearchSuggestionFragment()
	}
}

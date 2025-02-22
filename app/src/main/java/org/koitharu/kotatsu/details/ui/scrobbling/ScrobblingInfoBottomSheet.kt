package org.koitharu.kotatsu.details.ui.scrobbling

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.RatingBar
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.net.toUri
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import coil.ImageLoader
import coil.request.ImageRequest
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.ui.BaseBottomSheet
import org.koitharu.kotatsu.databinding.SheetScrobblingBinding
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.image.ui.ImageActivity
import org.koitharu.kotatsu.scrobbling.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.domain.model.ScrobblingStatus
import org.koitharu.kotatsu.scrobbling.ui.selector.ScrobblingSelectorBottomSheet
import org.koitharu.kotatsu.utils.ext.*

@AndroidEntryPoint
class ScrobblingInfoBottomSheet :
	BaseBottomSheet<SheetScrobblingBinding>(),
	AdapterView.OnItemSelectedListener,
	RatingBar.OnRatingBarChangeListener,
	View.OnClickListener,
	PopupMenu.OnMenuItemClickListener {

	private val viewModel by activityViewModels<DetailsViewModel>()
	private var scrobblerIndex: Int = -1

	@Inject
	lateinit var coil: ImageLoader
	private var menu: PopupMenu? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		scrobblerIndex = requireArguments().getInt(ARG_INDEX, scrobblerIndex)
	}

	override fun onInflateView(inflater: LayoutInflater, container: ViewGroup?): SheetScrobblingBinding {
		return SheetScrobblingBinding.inflate(inflater, container, false)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.scrobblingInfo.observe(viewLifecycleOwner, ::onScrobblingInfoChanged)
		viewModel.onError.observe(viewLifecycleOwner) {
			Toast.makeText(view.context, it.getDisplayMessage(view.resources), Toast.LENGTH_SHORT).show()
		}

		binding.spinnerStatus.onItemSelectedListener = this
		binding.ratingBar.onRatingBarChangeListener = this
		binding.buttonMenu.setOnClickListener(this)
		binding.imageViewCover.setOnClickListener(this)
		binding.textViewDescription.movementMethod = LinkMovementMethod.getInstance()

		menu = PopupMenu(view.context, binding.buttonMenu).apply {
			inflate(R.menu.opt_scrobbling)
			setOnMenuItemClickListener(this@ScrobblingInfoBottomSheet)
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
		menu = null
	}

	override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
		viewModel.updateScrobbling(
			rating = binding.ratingBar.rating / binding.ratingBar.numStars,
			status = enumValues<ScrobblingStatus>().getOrNull(position),
		)
	}

	override fun onNothingSelected(parent: AdapterView<*>?) = Unit

	override fun onRatingChanged(ratingBar: RatingBar, rating: Float, fromUser: Boolean) {
		if (fromUser) {
			viewModel.updateScrobbling(
				rating = rating / ratingBar.numStars,
				status = enumValues<ScrobblingStatus>().getOrNull(binding.spinnerStatus.selectedItemPosition),
			)
		}
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_menu -> menu?.show()
			R.id.imageView_cover -> {
				val coverUrl = viewModel.scrobblingInfo.value?.getOrNull(scrobblerIndex)?.coverUrl ?: return
				val options = scaleUpActivityOptionsOf(v)
				startActivity(ImageActivity.newIntent(v.context, coverUrl), options.toBundle())
			}
		}
	}

	private fun onScrobblingInfoChanged(scrobblings: List<ScrobblingInfo>) {
		val scrobbling = scrobblings.getOrNull(scrobblerIndex)
		if (scrobbling == null) {
			dismissAllowingStateLoss()
			return
		}
		binding.textViewTitle.text = scrobbling.title
		binding.ratingBar.rating = scrobbling.rating * binding.ratingBar.numStars
		binding.textViewDescription.text = scrobbling.description
		binding.spinnerStatus.setSelection(scrobbling.status?.ordinal ?: -1)
		ImageRequest.Builder(context ?: return)
			.target(binding.imageViewCover)
			.data(scrobbling.coverUrl)
			.crossfade(context)
			.lifecycle(viewLifecycleOwner)
			.placeholder(R.drawable.ic_placeholder)
			.fallback(R.drawable.ic_placeholder)
			.error(R.drawable.ic_error_placeholder)
			.enqueueWith(coil)
	}

	override fun onMenuItemClick(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.action_browser -> {
				val url = viewModel.scrobblingInfo.value?.getOrNull(scrobblerIndex)?.externalUrl ?: return false
				val intent = Intent(Intent.ACTION_VIEW, url.toUri())
				startActivity(
					Intent.createChooser(intent, getString(R.string.open_in_browser)),
				)
			}
			R.id.action_unregister -> {
				viewModel.unregisterScrobbling()
				dismiss()
			}
			R.id.action_edit -> {
				val manga = viewModel.manga.value ?: return false
				ScrobblingSelectorBottomSheet.show(parentFragmentManager, manga)
				dismiss()
			}
		}
		return true
	}

	companion object {

		private const val TAG = "ScrobblingInfoBottomSheet"
		private const val ARG_INDEX = "index"

		fun show(fm: FragmentManager, index: Int) = ScrobblingInfoBottomSheet().withArgs(1) {
			putInt(ARG_INDEX, index)
		}.show(fm, TAG)
	}
}

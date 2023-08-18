package live.hms.roomkit.ui.meeting.videogrid

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import live.hms.roomkit.databinding.FragmentVideoGridPageBinding
import live.hms.roomkit.ui.meeting.MeetingTrack
import live.hms.roomkit.ui.meeting.commons.VideoGridBaseFragment
import live.hms.roomkit.util.viewLifecycle
import kotlin.math.min

class VideoGridPageFragment : VideoGridBaseFragment() {

  companion object {
    private const val TAG = "VideoGridPageFragment"

    private const val BUNDLE_PAGE_INDEX = "bundle-page-index"

    fun newInstance(pageIndex: Int): VideoGridPageFragment {
      return VideoGridPageFragment().apply {
        arguments = bundleOf(BUNDLE_PAGE_INDEX to pageIndex)
      }
    }
  }


  private var binding by viewLifecycle<FragmentVideoGridPageBinding>()
  private val pageIndex by lazy { requireArguments()[BUNDLE_PAGE_INDEX] as Int }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    binding = FragmentVideoGridPageBinding.inflate(inflater, container, false)

    initViewModels()
    return binding.root
  }

  override fun onResume() {
    super.onResume()
    // Turn of sorting when we leave the first page
    meetingViewModel.speakerUpdateLiveData.enableSorting(pageIndex == 0)
  }
  private fun getCurrentPageVideos(tracks: List<MeetingTrack>): List<MeetingTrack?> {
    val pageVideos = ArrayList<MeetingTrack?>()

    // Range is [fromIndex, toIndex] -- Notice the bounds
    val itemsCount = maxItems
    val fromIndex = pageIndex * itemsCount
    val toIndex = min(tracks.size, (pageIndex + 1) * itemsCount) - 1

    for (idx in fromIndex..toIndex step 1) {
      pageVideos.add(tracks[idx])
    }

    return pageVideos
  }

  private fun refreshGridRowsAndColumns(rowCount: Int, columnCount: Int) {
      //update row and columns span useful when remote screen share
      // remote screen share [enabled] =  3 * 3 --> 1 * 2
      // remote screen share [disabled] = 1 * 2 --> 3 * 3
      val shouldUpdate = shouldUpdateRowOrGrid(rowCount, columnCount)
      //don't update if row and column are same
      if (shouldUpdate.not()) return
      setVideoGridRowsAndColumns(rowCount, columnCount)
      meetingViewModel.speakerUpdateLiveData.refresh()
  }

  override fun initViewModels() {
    super.initViewModels()
    meetingViewModel.speakerUpdateLiveData.observe(viewLifecycleOwner) { tracks ->
      val videos = getCurrentPageVideos(tracks)
      updateVideos(binding.container, videos, false)
    }

    meetingViewModel.updateRowAndColumnForGrid.observe(viewLifecycleOwner) { (rowCount, columnCount) ->
      refreshGridRowsAndColumns(rowCount, columnCount)
    }

    //meetingViewModel.speakers.observe(viewLifecycleOwner) { applySpeakerUpdates(it) }
  }
}
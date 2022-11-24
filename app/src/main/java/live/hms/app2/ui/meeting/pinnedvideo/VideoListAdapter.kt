package live.hms.app2.ui.meeting.pinnedvideo

import android.graphics.PorterDuff
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.Flow
import live.hms.app2.R
import live.hms.app2.databinding.ListItemVideoBinding
import live.hms.app2.helpers.NetworkQualityHelper
import live.hms.app2.ui.meeting.CustomPeerMetadata
import live.hms.app2.ui.meeting.MeetingTrack
import live.hms.app2.util.NameUtils
import live.hms.app2.util.crashlyticsLog
import live.hms.app2.util.visibility
import live.hms.video.connection.stats.HMSStats
import live.hms.video.error.HMSException
import live.hms.video.sdk.HMSActionResultListener
import live.hms.video.sdk.models.HMSPeer
import live.hms.video.sdk.models.enums.HMSPeerUpdate

class VideoListAdapter(
  private val onVideoItemClick: (item: MeetingTrack) -> Unit,
  private val itemStats: Flow<Map<String, HMSStats>>,
  private val statsActive: Boolean
) : RecyclerView.Adapter<VideoListAdapter.VideoItemViewHolder>() {

  companion object {
    private const val TAG = "VideoListAdapter"
  }

  var pinnedTrack : MeetingTrack? = null

  override fun getItemId(position: Int) = currentTrackItem[position].id

  override fun onViewAttachedToWindow(holder: VideoItemViewHolder) {
    super.onViewAttachedToWindow(holder)
    Log.d(TAG, "onViewAttachedToWindow($holder)")
    holder.bindSurfaceView()
  }

  override fun onViewDetachedFromWindow(holder: VideoItemViewHolder) {
    super.onViewDetachedFromWindow(holder)
    Log.d(TAG, "onViewDetachedFromWindow($holder)")
    holder.unbindSurfaceView()
  }

  inner class VideoItemViewHolder(
    val binding: ListItemVideoBinding,
    private val itemStats: Flow<Map<String, HMSStats>>
  ) : RecyclerView.ViewHolder(binding.root) {

    private var itemRef: VideoListItem? = null
    private val statsInterpreter = StatsInterpreter(statsActive)


    private var isSurfaceViewBinded = false

    fun bind(item: VideoListItem) {
      binding.nameInitials.text = NameUtils.getInitials(item.track.peer.name)
      binding.name.text = item.track.peer.name
      binding.iconScreenShare.visibility = if (item.track.isScreen) View.VISIBLE else View.GONE

      binding.hmsVideoView.apply {

        // Meanwhile until the video is not binded, hide the view.
        visibility = View.GONE

        // Update the reference such that when view is attached to window
        // surface view is initialized with correct [VideoTrack]
        binding.hmsVideoView.addTrack(item.track.video)
        itemRef = item
        isSurfaceViewBinded = false
//        itemRef?.let { item ->
//          SurfaceViewRendererUtil.bind(
//            binding.surfaceView,
//            item.track,
//            "VideoItemViewHolder::bindSurfaceView"
//          ).let { success ->
//            if (success) {
//              binding.surfaceView.visibility =
//                if (item.track.video?.isDegraded == true) View.INVISIBLE else View.VISIBLE
//              isSurfaceViewBinded = true
//            }
//          }
//        }
      }

      val isHandRaised: Boolean =
        CustomPeerMetadata.fromJson(item.track.peer.metadata)?.isHandRaised == true
      binding.raisedHand.visibility = visibility(isHandRaised)

      binding.root.setOnClickListener { onVideoItemClick(item.track) }
    }


    fun bindSurfaceView() {
      if (isSurfaceViewBinded) {
        Log.d(TAG, "bindSurfaceView: Surface view already initialized")
        return
      }

      binding.root.findViewTreeLifecycleOwner()?.let {
        statsInterpreter.initiateStats(
          it,
          itemStats, itemRef?.track?.video,
          itemRef?.track?.audio, itemRef?.track?.peer?.isLocal == true
        ) { binding.stats.text = it }
      }
      binding.hmsVideoView.addTrack(itemRef?.track?.video, object :HMSActionResultListener {
        override fun onError(error: HMSException) {
          // errors
        }

        override fun onSuccess() {
          binding.hmsVideoView.visibility = if (itemRef?.track?.video?.isDegraded == true) View.INVISIBLE else View.VISIBLE
          isSurfaceViewBinded = true
        }

      })
    }

    fun unbindSurfaceView() {
      if (!isSurfaceViewBinded) return
//      statsInterpreter.close()
      binding.hmsVideoView.removeTrack(object :HMSActionResultListener {
        override fun onError(error: HMSException) {

        }

        override fun onSuccess() {
          isSurfaceViewBinded = false
          binding.hmsVideoView.visibility = View.GONE
        }

      })
    }
  }

  private val currentTrackItem = ArrayList<VideoListItem>()
  private val currentTrackSource = mutableListOf<MeetingTrack>()

  fun updateTotalSource(newItems: List<MeetingTrack>) {
    currentTrackSource.clear()
    currentTrackSource+= newItems
  }

  /**
   * @param newItems: Complete list of video items which needs
   *  to be updated in the VideoGrid
   */
  @MainThread
  fun setItems(excludeTrack: MeetingTrack? = null) {

    val newVideoItems = currentTrackSource
      .filter { (it == excludeTrack).not() }
      .sortedBy { it.peer.name }
      .mapIndexed { index, track -> VideoListItem(index.toLong(), track, isTrackMute = track.peer.audioTrack?.isMute?:false) }

    val callback = VideoListItemDiffUtil(currentTrackItem, newVideoItems)
    val diff = DiffUtil.calculateDiff(callback)

    diff.dispatchUpdatesTo(this)

    currentTrackItem.clear()
    currentTrackItem.addAll(newVideoItems)

    crashlyticsLog(TAG, "Updated video list: size=${currentTrackItem.size}")
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoItemViewHolder {
    val binding = ListItemVideoBinding.inflate(
      LayoutInflater.from(parent.context),
      parent,
      false
    )
    return VideoItemViewHolder(binding, itemStats)
  }

  override fun onBindViewHolder(holder: VideoItemViewHolder, position: Int) {
    crashlyticsLog(TAG, "onBindViewHolder: ${currentTrackItem[position]}")
    holder.bind(currentTrackItem[position])
  }

  override fun onBindViewHolder(
    holder: VideoItemViewHolder,
    position: Int,
    payloads: MutableList<Any>
  ) {
    if (payloads.isEmpty()) {
      return super.onBindViewHolder(holder, position, payloads)
    } else if (payloads.all { it is PeerUpdatePayloads }) {
      // Only if all the payloads are of type peer udpate it makes sense to individually update.
      // If they weren't the non-payload type will result in a full redraw anyway so we let
      // it go to the full redraw in the else clause.
      payloads.forEach { payload ->
        if (payload is PeerUpdatePayloads) {
          when (payload) {
            is PeerUpdatePayloads.MetadataChanged -> {
              holder.binding.raisedHand.visibility =
                visibility(payload.metadata?.isHandRaised == true)
            }
            is PeerUpdatePayloads.NameChanged -> {
              holder.binding.nameInitials.text = NameUtils.getInitials(payload.name)
              holder.binding.name.text = payload.name
            }
            is PeerUpdatePayloads.NetworkQualityChanged -> {
              holder.binding.root.context?.let { context ->
                holder.binding.networkQuality.visibility = View.VISIBLE
                NetworkQualityHelper.getNetworkResource(payload.downlinkSpeed, context = context)?.let {
                  if (payload.downlinkSpeed == 0) {
                    holder.binding.networkQuality.setColorFilter(ContextCompat.getColor(context, R.color.red), PorterDuff.Mode.SRC_IN);
                  } else {
                    holder.binding.networkQuality.colorFilter = null
                  }
                  holder.binding.networkQuality.setImageDrawable(it)
                } ?: {
                  holder.binding.networkQuality.visibility = View.GONE
                }
              }
            }
           is PeerUpdatePayloads.SpeakerMuteUnmute -> holder.binding.iconAudioOff.visibility = visibility(payload.isMute)
          }
        }
      }
    } else {

      crashlyticsLog(
        TAG,
        "onBindViewHolder: Manually updating $holder with ${currentTrackItem[position]} " +
                "[payloads=$payloads]"
      )
      holder.unbindSurfaceView() // Free the context initialized for the previous item
      holder.bind(currentTrackItem[position])
      holder.bindSurfaceView()
    }
  }

  override fun getItemCount() = currentTrackItem.size

  fun itemChanged(changedPeer: Pair<HMSPeer, HMSPeerUpdate>) {

    val updatedItemId = currentTrackItem.find { it.track.peer.peerID == changedPeer.first.peerID }?.id

    val payload = when (changedPeer.second) {
      HMSPeerUpdate.METADATA_CHANGED -> PeerUpdatePayloads.MetadataChanged(
        CustomPeerMetadata.fromJson(
          changedPeer.first.metadata
        )
      )
      HMSPeerUpdate.NETWORK_QUALITY_UPDATED -> {
        PeerUpdatePayloads.NetworkQualityChanged(changedPeer.first.networkQuality?.downlinkQuality)
      }
      HMSPeerUpdate.NAME_CHANGED -> PeerUpdatePayloads.NameChanged(changedPeer.first.name)
      else -> null
    }

    updatedItemId?.toInt()
      ?.let { notifyItemChanged(it, payload) }
  }


  fun updatePinnedVideo(track: MeetingTrack) {
    setItems(excludeTrack = track)
  }

  sealed class PeerUpdatePayloads {
    data class NameChanged(val name: String) : PeerUpdatePayloads()
    data class NetworkQualityChanged(val downlinkSpeed: Int?) : PeerUpdatePayloads()
    data class MetadataChanged(val metadata: CustomPeerMetadata?) : PeerUpdatePayloads()
    data class SpeakerMuteUnmute(val isMute: Boolean) : PeerUpdatePayloads()
  }

}
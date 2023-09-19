package live.hms.roomkit.ui.meeting.participants

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import live.hms.roomkit.R
import live.hms.roomkit.databinding.FragmentParticipantsBinding
import live.hms.roomkit.setOnSingleClickListener
import live.hms.roomkit.ui.meeting.MeetingState
import live.hms.roomkit.ui.meeting.MeetingViewModel
import live.hms.roomkit.ui.meeting.MeetingViewModelFactory
import live.hms.roomkit.ui.theme.applyTheme
import live.hms.roomkit.util.viewLifecycle
import live.hms.video.error.HMSException
import live.hms.video.sdk.listeners.PeerListResultListener
import live.hms.video.sdk.models.HMSPeer
import live.hms.video.sdk.models.PeerListIterator

class ParticipantsFragment : Fragment() {

    private val TAG = "ParticipantsFragment"
    private var binding by viewLifecycle<FragmentParticipantsBinding>()
    private var alertDialog: AlertDialog? = null
    private val meetingViewModel: MeetingViewModel by activityViewModels {
        MeetingViewModelFactory(
            requireActivity().application
        )
    }
    private val participantsUseCase by lazy { ParticipantsUseCase(meetingViewModel, {
        if (isLargeRoom) {
            paginatedPeerList
        } else {
            meetingViewModel.peers
        }
    }, { role ->
        getNextPage(role)
    })
    }


    private val iteratorMap = hashMapOf<String, PeerListIterator>()
    private val paginatedPeerList = arrayListOf<HMSPeer>()
    private var isLargeRoom = false
    private var isPaginatedPeerlistInitialized = false

    private fun getNextPage(role: String) {
        iteratorMap[role]?.next(object : PeerListResultListener{
            override fun onError(error: HMSException) {
                meetingViewModel.triggerErrorNotification(message = error.message)
            }

            override fun onSuccess(result: ArrayList<HMSPeer>) {
                // add the next page of peers into final list
                paginatedPeerList.addAll(result)
               lifecycleScope.launch {
                   participantsUseCase.updateParticipantsAdapter(getPeerList())
               }
            }

        })
    }

    private suspend fun getPeerList(): List<HMSPeer> {
        return if (isLargeRoom) {
            if (!isPaginatedPeerlistInitialized) {
                val deferred = CompletableDeferred<Boolean>()
                initPaginatedPeerlist(deferred)
                deferred.await()
            }
            // Return  the combined list of real time and non real time peers
            meetingViewModel.peers.plus(paginatedPeerList)
        } else {
            // Return only Real time peers
            meetingViewModel.peers
        }
    }
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentParticipantsBinding.inflate(inflater, container, false)
        isLargeRoom = meetingViewModel.hmsSDK.getRoom()?.isLargeRoom == true
        initViewModels()
        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.applyTheme()
        initOnBackPress()
        initViews()
    }
    private fun updateParticipantCount(count : Int) {
        binding.participantsNum.text = resources.getString(R.string.participants_heading, count)
    }

    private fun initViews() {
        participantsUseCase.initRecyclerView(binding.recyclerView)
        binding.closeButton.setOnSingleClickListener {
            closeButton()
        }
        // Search disables conventional updates.
        participantsUseCase.initSearchView(binding.textInputSearch, lifecycleScope)
    }

    private fun closeButton() {
        parentFragmentManager
            .beginTransaction()
            .remove(this)
            .commitAllowingStateLoss()
    }

    private suspend fun initPaginatedPeerlist(initPaginationDeffered: CompletableDeferred<Boolean>) {
        isPaginatedPeerlistInitialized =  true
        lifecycleScope.launch {
            // Now fetch the first set of peers for all off-stage roles
            val offStageRoleNames = meetingViewModel.prebuiltInfoContainer.offStageRoles(meetingViewModel.hmsSDK.getLocalPeer()?.hmsRole?.name!!)
            offStageRoleNames?.let {
                for (role in it) {
                    if (role != null) {
                        val iterator = meetingViewModel.getPeerlistIterator(role)
                        iteratorMap[role] = iterator
                        val deferred = CompletableDeferred<Boolean>()
                        // Get the first page
                        iterator.next(object : PeerListResultListener{
                            override fun onError(error: HMSException) {
                                deferred.complete(false)
                            }

                            override fun onSuccess(result: ArrayList<HMSPeer>) {
                                paginatedPeerList.addAll(result)
                                deferred.complete(true)
                            }
                        })
                        deferred.await()
                    }
                }
            }
            initPaginationDeffered.complete(true)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initViewModels() {
        binding.recyclerView.adapter = participantsUseCase.adapter
        // Initial updating of views
        // Using HMSCoroutine scope here since we want the next call to get queued
//        initPaginatedPeerlist()
        meetingViewModel.participantPeerUpdate.observe(viewLifecycleOwner) {
            lifecycleScope.launch {
                participantsUseCase.updateParticipantsAdapter(getPeerList())
            }
        }
        meetingViewModel.peerCount.observe(viewLifecycleOwner,::updateParticipantCount)

        meetingViewModel.state.observe(viewLifecycleOwner) { state ->
            if (state is MeetingState.NonFatalFailure) {

                alertDialog?.dismiss()
                alertDialog = null

                val message = state.exception.message

                val builder = AlertDialog.Builder(requireContext())
                    .setMessage(message)
                    .setTitle(live.hms.roomkit.R.string.non_fatal_error_dialog_title)
                    .setCancelable(true)

                builder.setPositiveButton(live.hms.roomkit.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                    alertDialog = null
                    meetingViewModel.setStatetoOngoing() // hack, so that the liveData represents the correct state. Use SingleLiveEvent instead
                }


                alertDialog = builder.create().apply { show() }
            }


        }
    }

    override fun onDestroy() {
        super.onDestroy()
        paginatedPeerList.clear()
        isPaginatedPeerlistInitialized = false
        iteratorMap.clear()
    }

    private fun initOnBackPress() {
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeButton()
                }
            })
    }

}
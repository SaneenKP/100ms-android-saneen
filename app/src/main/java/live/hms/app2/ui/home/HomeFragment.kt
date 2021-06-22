package live.hms.app2.ui.home

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import live.hms.app2.R
import live.hms.app2.api.Status
import live.hms.app2.databinding.FragmentHomeBinding
import live.hms.app2.model.RoomDetails
import live.hms.app2.ui.meeting.MeetingActivity
import live.hms.app2.ui.settings.SettingsMode
import live.hms.app2.ui.settings.SettingsStore
import live.hms.app2.util.*
import java.util.*

class HomeFragment : Fragment() {

  companion object {
    private const val TAG = "HomeFragment"
  }

  private var binding by viewLifecycle<FragmentHomeBinding>()
  private val homeViewModel: HomeViewModel by viewModels()
  private lateinit var settings: SettingsStore

  override fun onResume() {
    super.onResume()
    val data = requireActivity().intent.data
    Log.v(TAG, "onResume: Trying to update $data into EditTextMeetingUrl")

    data?.let {
      if (it.toString().isNotEmpty()) {
        saveTokenEndpointUrlIfValid(data.toString())
        requireActivity().intent.data = null
      }
    }
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
      R.id.action_settings -> {
        findNavController().navigate(
          HomeFragmentDirections.actionHomeFragmentToSettingsFragment(SettingsMode.HOME)
        )
      }
      R.id.action_email_logs -> {
        requireContext().startActivity(
          EmailUtils.getNonFatalLogIntent(requireContext())
        )
      }
    }
    return false
  }

  override fun onCreateView(
    inflater: LayoutInflater, container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    binding = FragmentHomeBinding.inflate(inflater, container, false)
    settings = SettingsStore(requireContext())

    setHasOptionsMenu(true)

    observeLiveData()
    initEditTextViews()
    initConnectButton()
    initOnBackPress()
    hideProgressBar()

    return binding.root
  }

  private fun initOnBackPress() {
    requireActivity().apply {
      onBackPressedDispatcher.addCallback(
        this@HomeFragment.viewLifecycleOwner,
        object : OnBackPressedCallback(true) {
          override fun handleOnBackPressed() {
            Log.v(TAG, "initOnBackPress -> handleOnBackPressed")
            finish()
          }
        })
    }
  }

  @SuppressLint("SetTextI18n")
  private fun updateProgressBarUI(isRoomCreator: Boolean) {
    val headingPrefix = if (isRoomCreator) "Creating room for" else "Joining as"
    binding.progressBar.heading.text = "$headingPrefix ${getUsername()}..."

    val descriptionDefaults = if (settings.publishVideo && settings.publishAudio) {
      "Video and microphone will be turned on by default.\n"
    } else if (settings.publishVideo && !settings.publishVideo) {
      "Only audio will be turned on by default\n"
    } else if (!settings.publishVideo && settings.publishVideo) {
      "Only video will be turned on by default\n"
    } else {
      "Video and microphone will be turned off by default.\n"
    }

    val descriptionSetting = "You can change the defaults in the app settings."
    binding.progressBar.description.text = descriptionDefaults + descriptionSetting
  }

  private fun showProgressBar() {
    binding.buttonJoinMeeting.visibility = View.GONE

    binding.containerCardJoin.visibility = View.GONE
    binding.containerCardName.visibility = View.GONE
    binding.progressBar.root.visibility = View.VISIBLE

    binding.containerMeetingUrl.isEnabled = false
  }

  private fun hideProgressBar() {
    binding.buttonJoinMeeting.visibility = View.VISIBLE

    binding.containerCardJoin.visibility = View.VISIBLE
    binding.containerCardName.visibility = View.VISIBLE
    binding.progressBar.root.visibility = View.GONE

    binding.containerMeetingUrl.isEnabled = true
  }

  private fun getUsername() = binding.editTextName.text.toString()

  private fun joinRoom() {
    homeViewModel.sendAuthTokenRequest(settings.lastUsedMeetingUrl)
  }

  private fun observeLiveData() {
    homeViewModel.authTokenResponse.observe(viewLifecycleOwner) { response ->
      when (response.status) {
        Status.LOADING -> {
          updateProgressBarUI(false)
          showProgressBar()
        }
        Status.SUCCESS -> {
          // No need to hide progress bar here, as we directly move to
          // the next page

          val data = response.data!!
          val roomDetails = RoomDetails(
            env = settings.environment,
            url = settings.lastUsedMeetingUrl,
            username = getUsername(),
            authToken = data.token
          )
          Log.i(TAG, "Auth Token: ${roomDetails.authToken}")

          // Start the meeting activity
          Intent(requireContext(), MeetingActivity::class.java).apply {
            LogUtils.staticFileWriterStart(
              requireContext(),
              roomDetails.url.toUniqueRoomSpecifier()
            )
            putExtra(ROOM_DETAILS, roomDetails)
            startActivity(this)
          }
          requireActivity().finish()
        }
        Status.ERROR -> {
          hideProgressBar()
          Toast.makeText(
            requireContext(),
            response.message,
            Toast.LENGTH_SHORT
          ).show()
        }
      }
    }
  }

  private fun saveTokenEndpointUrlIfValid(url: String): Boolean {
    if (url.isValidMeetingUrl()) {
      settings.lastUsedMeetingUrl = url
      binding.editTextMeetingUrl.setText(url.toUniqueRoomSpecifier())
      settings.environment = url.getInitEndpointEnvironment()
      return true
    }

    return false
  }

  private fun initEditTextViews() {
    // Load the data if saved earlier (easy debugging)
    binding.editTextName.setText(settings.username)
    val url = settings.lastUsedMeetingUrl
    if (url.isNotBlank()) {
      binding.editTextMeetingUrl.setText(url.toUniqueRoomSpecifier())
    }

    mapOf(
      binding.editTextName to binding.containerName,
      binding.editTextMeetingUrl to binding.containerMeetingUrl
    ).forEach {
      it.key.addTextChangedListener { text ->
        if (text.toString().isNotEmpty()) it.value.error = null
      }
    }
  }

  private fun isValidUserName(): Boolean {
    val username = binding.editTextName.text.toString()
    if (username.isEmpty()) {
      binding.containerName.error = "Username cannot be empty"
      return false
    }
    return true
  }

  private fun initConnectButton() {
    binding.buttonJoinMeeting.setOnClickListener {
      try {
        val url = binding
          .editTextMeetingUrl
          .text
          .toString()
          .trim()
          .toValidMeetingUrl(settings.environment, settings.role)

        if (saveTokenEndpointUrlIfValid(url) && isValidUserName()) {
          joinRoom()
        }
      } catch (e: Exception) {
        binding.containerMeetingUrl.error = e.message
      }
    }
  }
}
package live.hms.roomkit.ui.meeting.chat

import live.hms.video.sdk.models.HMSMessage
import live.hms.video.sdk.models.enums.HMSMessageRecipientType
import live.hms.video.sdk.models.role.HMSRole
import java.util.*
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

const val DEFAULT_SENDER_NAME = "Participant"


@Parcelize
data class ChatMessage constructor(
    val senderName: String,
    val localSenderRealNameForPinMessage : String,
    val time: Long? = null,
    val message: String,
    val isSentByMe: Boolean,
    var messageId : String? = null,
    val sentTo : String?,
    val toGroup : String?,
    val senderPeerId : String?,
    val senderRoleName : String?,
    val userIdForBlockList : String?
)  : Parcelable {
    companion object {
        fun sendTo(message: HMSMessage) : String? = sendTo(message.recipient.recipientType,
            message.recipient.recipientRoles)

        fun sendTo(recipient: HMSMessageRecipientType,
                           roles : List<HMSRole>?) : String? = when(recipient) {
            HMSMessageRecipientType.BROADCAST -> null
            HMSMessageRecipientType.PEER -> "Direct Message"
            HMSMessageRecipientType.ROLES -> roles?.firstOrNull()?.name ?: "Role"
        }

        fun toGroup(recipient: HMSMessageRecipientType) = when(recipient) {
            HMSMessageRecipientType.PEER, HMSMessageRecipientType.BROADCAST -> null
            HMSMessageRecipientType.ROLES -> "To Group"
        }
    }
    constructor(message: HMSMessage, sentByMe: Boolean) : this(
        if(sentByMe) "You" else message.sender?.name ?: DEFAULT_SENDER_NAME,
        message.sender?.name ?: DEFAULT_SENDER_NAME,
        message.serverReceiveTime,
        message.message,
        sentByMe,
        messageId = message.messageId,
        sentTo = sendTo(message),
        toGroup = toGroup(message.recipient.recipientType),
        message.sender?.peerID,
        message.sender?.hmsRole?.name,
        message.sender?.customerUserID
    )

}

package com.lenid.zoh.aap.protocol.messages

import com.lenid.zoh.aap.AapMessage
import com.lenid.zoh.aap.protocol.Channel
import com.lenid.zoh.aap.protocol.proto.Media
import com.google.protobuf.Message

/**
 * Message sent on the Video channel (Channel 3) to update the headunit's UI config
 * (margins, insets, theme) without a full session reconnect.
 */
class UpdateUiConfigRequest(
    left: Int, top: Int, right: Int, bottom: Int
) : AapMessage(
    Channel.ID_VID,
    Media.MsgType.MEDIA_MESSAGE_UPDATE_UI_CONFIG_REQUEST_VALUE,
    makeProto(left, top, right, bottom)
) {

    companion object {
        private fun makeProto(left: Int, top: Int, right: Int, bottom: Int): Message {
            val insets = Media.Insets.newBuilder()
                .setLeft(left)
                .setTop(top)
                .setRight(right)
                .setBottom(bottom)
                .build()

            // The entire visible canvas is available to AA. Reassert this when its taskbar
            // moves between bottom and side so embedded apps do not retain stale safe areas.
            val contentInsets = Media.Insets.newBuilder()
                .setLeft(0).setTop(0).setRight(0).setBottom(0).build()
            val uiConfig = Media.UiConfig.newBuilder()
                .setMargins(insets)
                .setContentInsets(contentInsets)
                .setStableContentInsets(contentInsets)
                .build()

            return Media.UpdateUiConfigRequest.newBuilder()
                .setUiConfig(uiConfig)
                .build()
        }
    }
}


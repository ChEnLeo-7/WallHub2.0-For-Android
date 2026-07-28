package com.wallhub.android.data.steam

import `in`.dragonbra.javasteam.base.PacketClientMsgProtobuf
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.callback.ServiceMethodResponse
import `in`.dragonbra.javasteam.types.AsyncJobSingle
import com.wallhub.android.data.steam.protobuf.CommunityMessages

internal class CommunityUnifiedService(
    unifiedMessages: SteamUnifiedMessages,
) : UnifiedService(unifiedMessages) {
    override val serviceName: String = "Community"

    override fun handleResponseMsg(
        methodName: String,
        packetMsg: PacketClientMsgProtobuf,
    ) {
        when (methodName) {
            GET_COMMENT_THREAD -> postResponseMsg<CommunityMessages.GetCommentThreadResponse.Builder>(
                CommunityMessages.GetCommentThreadResponse::class.java,
                packetMsg,
            )

            POST_COMMENT_TO_THREAD -> postResponseMsg<CommunityMessages.PostCommentToThreadResponse.Builder>(
                CommunityMessages.PostCommentToThreadResponse::class.java,
                packetMsg,
            )
        }
    }

    override fun handleNotificationMsg(
        methodName: String,
        packetMsg: PacketClientMsgProtobuf,
    ) = Unit

    fun getCommentThread(
        request: CommunityMessages.GetCommentThreadRequest,
    ): AsyncJobSingle<ServiceMethodResponse<CommunityMessages.GetCommentThreadResponse.Builder>> =
        checkNotNull(unifiedMessages).sendMessage(
            CommunityMessages.GetCommentThreadResponse.Builder::class.java,
            "$serviceName.$GET_COMMENT_THREAD#1",
            request,
        )

    fun postCommentToThread(
        request: CommunityMessages.PostCommentToThreadRequest,
    ): AsyncJobSingle<ServiceMethodResponse<CommunityMessages.PostCommentToThreadResponse.Builder>> =
        checkNotNull(unifiedMessages).sendMessage(
            CommunityMessages.PostCommentToThreadResponse.Builder::class.java,
            "$serviceName.$POST_COMMENT_TO_THREAD#1",
            request,
        )

    private companion object {
        const val GET_COMMENT_THREAD = "GetCommentThread"
        const val POST_COMMENT_TO_THREAD = "PostCommentToThread"
    }
}

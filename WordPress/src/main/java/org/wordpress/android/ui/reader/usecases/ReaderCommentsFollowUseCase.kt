package org.wordpress.android.ui.reader.usecases

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.wordpress.android.R
import org.wordpress.android.analytics.AnalyticsTracker.Stat
import org.wordpress.android.datasets.wrappers.ReaderPostTableWrapper
import org.wordpress.android.fluxc.store.AccountStore
import org.wordpress.android.ui.reader.usecases.ReaderCommentsFollowUseCase.AnalyticsFollowCommentsAction.FOLLOW_COMMENTS
import org.wordpress.android.ui.reader.usecases.ReaderCommentsFollowUseCase.AnalyticsFollowCommentsAction.UNFOLLOW_COMMENTS
import org.wordpress.android.ui.reader.usecases.ReaderCommentsFollowUseCase.AnalyticsFollowCommentsActionResult.ERROR
import org.wordpress.android.ui.reader.usecases.ReaderCommentsFollowUseCase.AnalyticsFollowCommentsActionResult.SUCCEEDED
import org.wordpress.android.ui.reader.usecases.ReaderCommentsFollowUseCase.AnalyticsFollowCommentsGenericError.NO_NETWORK
import org.wordpress.android.ui.reader.usecases.ReaderCommentsFollowUseCase.FollowCommentsState.UserNotAuthenticated
import org.wordpress.android.ui.reader.utils.PostSubscribersApiCallsProvider
import org.wordpress.android.ui.reader.utils.PostSubscribersApiCallsProvider.PostSubscribersCallResult
import org.wordpress.android.ui.reader.utils.PostSubscribersApiCallsProvider.PostSubscribersCallResult.Failure
import org.wordpress.android.ui.reader.utils.PostSubscribersApiCallsProvider.PostSubscribersCallResult.Success
import org.wordpress.android.ui.utils.UiString
import org.wordpress.android.ui.utils.UiString.UiStringRes
import org.wordpress.android.ui.utils.UiString.UiStringText
import org.wordpress.android.util.NetworkUtilsWrapper
import org.wordpress.android.util.analytics.AnalyticsUtilsWrapper
import javax.inject.Inject
import kotlin.coroutines.suspendCoroutine

class ReaderCommentsFollowUseCase @Inject constructor(
    private val networkUtilsWrapper: NetworkUtilsWrapper,
    private val postSubscribersApiCallsProvider: PostSubscribersApiCallsProvider,
    private val accountStore: AccountStore,
    private val analyticsUtilsWrapper: AnalyticsUtilsWrapper,
    private val readerPostTableWrapper: ReaderPostTableWrapper
) {
    private val FOLLOW_COMMENT_ACTION = "follow_action"
    private val FOLLOW_COMMENT_ACTION_RESULT = "follow_action_result"
    private val FOLLOW_COMMENT_ACTION_ERROR = "follow_action_error"

    suspend fun getMySubscriptionToPost(blogId: Long, postId: Long, isInit: Boolean) = flow {
        if (!accountStore.hasAccessToken()) {
            emit(UserNotAuthenticated)
        } else {
            emit(FollowCommentsState.Loading)

            if (!networkUtilsWrapper.isNetworkAvailable()) {
                emit(FollowCommentsState.Failure(blogId, postId, UiStringRes(R.string.error_network_connection)))
            } else {
                val canFollowComments: Boolean = suspendCoroutine { continuation ->
                    postSubscribersApiCallsProvider.getCanFollowComments(blogId, continuation)
                }

                if (!canFollowComments) {
                    emit(FollowCommentsState.FollowCommentsNotAllowed)
                } else {
                    val status: PostSubscribersCallResult = suspendCoroutine { continuation ->
                        postSubscribersApiCallsProvider.getMySubscriptionToPost(blogId, postId, continuation)
                    }

                    when (status) {
                        is Success -> {
                            emit(
                                    FollowCommentsState.FollowStateChanged(
                                            blogId,
                                            postId,
                                            status.isFollowing,
                                            isInit
                                    )
                            )
                        }
                        is Failure -> {
                            emit(FollowCommentsState.Failure(blogId, postId, UiStringText(status.error)))
                        }
                    }
                }
            }
        }
    }

    suspend fun setMySubscriptionToPost(
        blogId: Long,
        postId: Long,
        subscribe: Boolean
    ): Flow<FollowCommentsState> = flow {
        val properties = mutableMapOf<String, Any?>()

        properties.addFollowAction(subscribe)

        emit(FollowCommentsState.Loading)

        if (!networkUtilsWrapper.isNetworkAvailable()) {
            emit(FollowCommentsState.Failure(blogId, postId, UiStringRes(R.string.error_network_connection)))
            properties.addFollowActionResult(ERROR, NO_NETWORK.errorMessage)
        } else {
            val status: PostSubscribersCallResult = suspendCoroutine { continuation ->
                if (subscribe) {
                    postSubscribersApiCallsProvider.subscribeMeToPost(blogId, postId, continuation)
                } else {
                    postSubscribersApiCallsProvider.unsubscribeMeFromPost(blogId, postId, continuation)
                }
            }

            when (status) {
                is Success -> {
                    emit(
                            FollowCommentsState.FollowStateChanged(
                                    blogId,
                                    postId,
                                    status.isFollowing,
                                    false,
                                    UiStringRes(
                                        if (status.isFollowing)
                                            R.string.reader_follow_comments_subscribe_success
                                        else
                                            R.string.reader_follow_comments_unsubscribe_success
                                    )
                            )
                    )
                    properties.addFollowActionResult(SUCCEEDED)
                }
                is Failure -> {
                    emit(FollowCommentsState.Failure(blogId, postId, UiStringText(status.error)))
                    properties.addFollowActionResult(ERROR, status.error)
                }
            }
        }

        val post = readerPostTableWrapper.getBlogPost(blogId, postId, true)

        analyticsUtilsWrapper.trackFollowCommentsWithReaderPostDetails(
                Stat.COMMENT_FOLLOW_CONVERSATION,
                blogId,
                postId,
                post,
                properties
        )
    }

    sealed class FollowCommentsState {
        object Loading : FollowCommentsState()

        data class FollowStateChanged(
            val blogId: Long,
            val postId: Long,
            val isFollowing: Boolean,
            val isInit: Boolean = false,
            val userMessage: UiString? = null
        ) : FollowCommentsState()

        data class Failure(
            val blogId: Long,
            val postId: Long,
            val error: UiString
        ) : FollowCommentsState()

        object FollowCommentsNotAllowed : FollowCommentsState()

        object UserNotAuthenticated : FollowCommentsState()
    }

    private enum class AnalyticsFollowCommentsAction(val action: String) {
        FOLLOW_COMMENTS("followed"),
        UNFOLLOW_COMMENTS("unfollowed")
    }

    private enum class AnalyticsFollowCommentsActionResult(val actionResult: String) {
        SUCCEEDED("succeeded"),
        ERROR("error")
    }

    private enum class AnalyticsFollowCommentsGenericError(val errorMessage: String) {
        NO_NETWORK("no_network")
    }

    private fun MutableMap<String, Any?>.addFollowAction(subscribe: Boolean): MutableMap<String, Any?> {
        this[FOLLOW_COMMENT_ACTION] = if (subscribe) {
            FOLLOW_COMMENTS.action
        } else {
            UNFOLLOW_COMMENTS.action
        }
        return this
    }

    private fun MutableMap<String, Any?>.addFollowActionResult(
        result: AnalyticsFollowCommentsActionResult,
        errorMessage: String? = null
    ): MutableMap<String, Any?> {
        this[FOLLOW_COMMENT_ACTION_RESULT] = result.actionResult
        errorMessage?.also {
            this[FOLLOW_COMMENT_ACTION_ERROR] = errorMessage
        }
        return this
    }
}

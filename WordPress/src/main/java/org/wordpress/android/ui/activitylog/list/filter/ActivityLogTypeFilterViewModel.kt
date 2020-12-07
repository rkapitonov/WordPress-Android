package org.wordpress.android.ui.activitylog.list.filter

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wordpress.android.R
import org.wordpress.android.fluxc.model.LocalOrRemoteId.RemoteId
import org.wordpress.android.modules.BG_THREAD
import org.wordpress.android.modules.UI_THREAD
import org.wordpress.android.ui.activitylog.list.filter.ActivityLogTypeFilterViewModel.ListItemUiState.ActivityType
import org.wordpress.android.ui.activitylog.list.filter.ActivityLogTypeFilterViewModel.UiState.Content
import org.wordpress.android.ui.activitylog.list.filter.ActivityLogTypeFilterViewModel.UiState.FullscreenLoading
import org.wordpress.android.ui.activitylog.list.filter.DummyActivityTypesProvider.DummyActivityType
import org.wordpress.android.ui.utils.UiString
import org.wordpress.android.ui.utils.UiString.UiStringRes
import org.wordpress.android.ui.utils.UiString.UiStringText
import org.wordpress.android.viewmodel.Event
import org.wordpress.android.viewmodel.ScopedViewModel
import org.wordpress.android.viewmodel.activitylog.ActivityLogViewModel
import javax.inject.Inject
import javax.inject.Named

class ActivityLogTypeFilterViewModel @Inject constructor(
    private val dummyActivityTypesProvider: DummyActivityTypesProvider,
    @Named(BG_THREAD) private val bgDispatcher: CoroutineDispatcher,
    @Named(UI_THREAD) private val mainDispatcher: CoroutineDispatcher
) : ScopedViewModel(mainDispatcher) {
    private var isStarted = false
    private lateinit var remoteSiteId: RemoteId
    private lateinit var parentViewModel: ActivityLogViewModel
    private lateinit var initialSelection: List<Int>

    private val _uiState = MutableLiveData<UiState>()
    val uiState: LiveData<UiState> = _uiState

    private val _dismissDialog = MutableLiveData<Event<Unit>>()
    val dismissDialog: LiveData<Event<Unit>> = _dismissDialog

    fun start(
        remoteSiteId: RemoteId,
        parentViewModel: ActivityLogViewModel,
        initialSelection: List<Int>
    ) {
        if (isStarted) return
        isStarted = true
        this.remoteSiteId = remoteSiteId
        this.parentViewModel = parentViewModel
        this.initialSelection = initialSelection

        fetchAvailableActivityTypes()
    }

    private fun fetchAvailableActivityTypes() {
        launch {
            _uiState.value = FullscreenLoading
            val response = dummyActivityTypesProvider.fetchAvailableActivityTypes(remoteSiteId.value)
            if (response.isError) {
                _uiState.value = buildErrorUiState()
            } else {
                _uiState.value = buildContentUiState(response.activityTypes)
            }
        }
    }

    private fun buildErrorUiState() =
            UiState.Error(Action(UiStringRes(R.string.retry)).apply { action = ::onRetryClicked })

    private suspend fun buildContentUiState(activityTypes: List<DummyActivityType>): Content {
        return withContext(bgDispatcher) {
            // TODO malinjir replace the hardcoded header title
            val headerListItem = ListItemUiState.SectionHeader(UiStringText("Test"))
            // TODO malinjir replace "it.toString()" with activity type name
            val activityTypeListItems: List<ListItemUiState.ActivityType> = activityTypes
                    .map {
                        ListItemUiState.ActivityType(
                                id = it.id,
                                title = UiStringText(it.toString()),
                                onClick = { onItemClicked(it.id) },
                                checked = initialSelection.contains(it.id)
                        )
                    }
            Content(
                    listOf(headerListItem) + activityTypeListItems,
                    primaryAction = Action(label = UiStringRes(R.string.activity_log_activity_type_filter_apply))
                            .apply { action = ::onApplyClicked },
                    secondaryAction = Action(label = UiStringRes(R.string.activity_log_activity_type_filter_clear))
                            .apply { action = ::onClearClicked }
            )
        }
    }

    private fun onItemClicked(itemId: Int) {
        (_uiState.value as? Content)?.let { content ->
            val updatedList = content.items.map { itemUiState ->
                if (itemUiState is ListItemUiState.ActivityType && itemUiState.id == itemId) {
                    itemUiState.copy(checked = !itemUiState.checked)
                } else {
                    itemUiState
                }
            }
            _uiState.postValue(content.copy(items = updatedList))
        }
    }

    private fun onApplyClicked() {
        parentViewModel.onActivityTypesSelected(getSelectedActivityTypeIds())
        _dismissDialog.value = Event(Unit)
    }

    private fun onRetryClicked() {
        fetchAvailableActivityTypes()
    }

    private fun onClearClicked() {
        (_uiState.value as? Content)?.let { it ->
            _uiState.value = it.copy(items = getAllActivityTypeItemsUnchecked(it.items))
        }
    }

    private fun getAllActivityTypeItemsUnchecked(listItemUiStates: List<ListItemUiState>): List<ListItemUiState> =
            listItemUiStates.map { item ->
                if (item is ListItemUiState.ActivityType) {
                    item.copy(checked = false)
                } else {
                    item
                }
            }

    private fun getSelectedActivityTypeIds(): List<Int> =
            (_uiState.value as Content).items
                    .filterIsInstance(ActivityType::class.java)
                    .filter { it.checked }
                    .map { it.id }

    sealed class UiState {
        open val contentVisibility = false
        open val loadingVisibility = false
        open val errorVisibility = false

        object FullscreenLoading : UiState() {
            override val loadingVisibility: Boolean = true
            val loadingText: UiString = UiStringRes(R.string.loading)
        }

        data class Error(val retryAction: Action) : UiState() {
            override val errorVisibility = true

            // TODO malinjir replace strings according to design
            val errorTitle: UiString = UiStringRes(R.string.error)
            val errorSubtitle: UiString = UiStringRes(R.string.hpp_retry_error)
            val errorButtonText: UiString = UiStringRes(R.string.retry)
        }

        data class Content(
            val items: List<ListItemUiState>,
            val primaryAction: Action,
            val secondaryAction: Action
        ) : UiState() {
            override val contentVisibility = true
        }
    }

    sealed class ListItemUiState {
        data class SectionHeader(
            val title: UiString
        ) : ListItemUiState()

        data class ActivityType(
            val id: Int,
            val title: UiString,
            val checked: Boolean = false,
            val onClick: (() -> Unit)
        ) : ListItemUiState()
    }

    data class Action(val label: UiString) {
        lateinit var action: (() -> Unit)
    }
}

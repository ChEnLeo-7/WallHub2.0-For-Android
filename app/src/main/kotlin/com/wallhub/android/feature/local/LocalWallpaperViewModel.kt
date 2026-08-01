package com.wallhub.android.feature.local

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wallhub.android.R
import com.wallhub.android.core.model.LocalWallpaperImportState
import com.wallhub.android.core.model.LocalWallpaperRepository
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperScanSnapshot
import com.wallhub.android.core.model.LocalWallpaperViewMode
import com.wallhub.android.core.model.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

enum class LocalWallpaperFormatFilter {
    ALL,
    MPKG,
    PKG,
    VIDEO,
    HTML,
    UNKNOWN,
}

enum class LocalWallpaperImportFilter {
    ALL,
    NOT_IMPORTED,
    IMPORT_REQUESTED,
}

enum class LocalWallpaperSort {
    RECENT,
    NAME,
    SIZE,
    TYPE,
}

data class LocalWallpaperUiState(
    val scan: LocalWallpaperScanSnapshot = LocalWallpaperScanSnapshot(),
    val searchQuery: String = "",
    val formatFilter: LocalWallpaperFormatFilter = LocalWallpaperFormatFilter.ALL,
    val importFilter: LocalWallpaperImportFilter = LocalWallpaperImportFilter.ALL,
    val sourceId: String? = null,
    val favoriteOnly: Boolean = false,
    val selectedTag: String? = null,
    val sort: LocalWallpaperSort = LocalWallpaperSort.RECENT,
    val viewMode: LocalWallpaperViewMode = LocalWallpaperViewMode.LIST,
    val selectedResourceId: String? = null,
    val selectionMode: Boolean = false,
    val selectedResourceIds: Set<String> = emptySet(),
) {
    val resources: List<LocalWallpaperResource>
        get() {
            val query = searchQuery.trim().lowercase(Locale.ROOT)
            return scan.resources
                .asSequence()
                .filter { resource ->
                    formatFilter == LocalWallpaperFormatFilter.ALL ||
                        resource.format.name == formatFilter.name
                }.filter { resource ->
                    when (importFilter) {
                        LocalWallpaperImportFilter.ALL -> true
                        LocalWallpaperImportFilter.NOT_IMPORTED ->
                            resource.importState == LocalWallpaperImportState.NOT_IMPORTED
                        LocalWallpaperImportFilter.IMPORT_REQUESTED ->
                            resource.importState == LocalWallpaperImportState.IMPORT_REQUESTED
                    }
                }.filter { resource -> sourceId == null || resource.sourceId == sourceId }
                .filter { resource -> !favoriteOnly || resource.isFavorite }
                .filter { resource -> selectedTag == null || selectedTag in resource.tags }
                .filter { resource ->
                    query.isBlank() ||
                        listOf(
                            resource.title,
                            resource.displayName,
                            resource.relativePath,
                            resource.format.name,
                            resource.tags.joinToString(" "),
                        ).any { value -> value.lowercase(Locale.ROOT).contains(query) }
                }.let { filtered ->
                    when (sort) {
                        LocalWallpaperSort.RECENT ->
                            filtered.sortedWith(
                                compareByDescending<LocalWallpaperResource> { it.modifiedAt }
                                    .thenBy { it.title.lowercase(Locale.ROOT) },
                            )

                        LocalWallpaperSort.NAME ->
                            filtered.sortedBy {
                                it.title.lowercase(Locale.ROOT)
                            }

                        LocalWallpaperSort.SIZE -> filtered.sortedByDescending(LocalWallpaperResource::sizeBytes)
                        LocalWallpaperSort.TYPE ->
                            filtered.sortedWith(
                                compareBy<LocalWallpaperResource> { it.format.name }
                                    .thenBy { it.title.lowercase(Locale.ROOT) },
                            )
                    }
                }.toList()
        }

    val allTags: List<String>
        get() =
            scan.resources
                .flatMap(LocalWallpaperResource::tags)
                .distinct()
                .sorted()

    val activeFilterCount: Int
        get() =
            listOf(
                formatFilter != LocalWallpaperFormatFilter.ALL,
                importFilter != LocalWallpaperImportFilter.ALL,
                sourceId != null,
                favoriteOnly,
                selectedTag != null,
                sort != LocalWallpaperSort.RECENT,
            ).count { it }

    val summary: LocalUiText
        get() =
            when {
                scan.isScanning ->
                    LocalUiText.Plural(
                        R.plurals.local_summary_scanning,
                        scan.discoveredCount,
                        listOf(scan.discoveredCount),
                    )
                resources.isEmpty() -> LocalUiText.Resource(R.string.local_summary_empty)
                activeFilterCount > 0 ->
                    LocalUiText.Plural(
                        R.plurals.local_summary_filtered,
                        resources.size,
                        listOf(resources.size),
                    )
                else ->
                    LocalUiText.Plural(
                        R.plurals.local_summary_total,
                        resources.size,
                        listOf(resources.size),
                    )
            }
}

sealed interface LocalUiText {
    data class Resource(
        @StringRes val id: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : LocalUiText

    data class Plural(
        @PluralsRes val id: Int,
        val quantity: Int,
        val formatArgs: List<Any>,
    ) : LocalUiText

    data class Raw(
        val value: String,
    ) : LocalUiText
}

sealed interface LocalWallpaperAction {
    data object EnterPage : LocalWallpaperAction

    data object Refresh : LocalWallpaperAction

    data object CancelScan : LocalWallpaperAction

    data object ChooseDirectory : LocalWallpaperAction

    data object ResetDirectory : LocalWallpaperAction

    data class DirectorySelected(
        val treeUri: String,
        val label: String,
    ) : LocalWallpaperAction

    data class SearchQueryChanged(
        val query: String,
    ) : LocalWallpaperAction

    data class SelectViewMode(
        val mode: LocalWallpaperViewMode,
    ) : LocalWallpaperAction

    data class SelectFormatFilter(
        val filter: LocalWallpaperFormatFilter,
    ) : LocalWallpaperAction

    data class SelectImportFilter(
        val filter: LocalWallpaperImportFilter,
    ) : LocalWallpaperAction

    data class SelectSource(
        val sourceId: String?,
    ) : LocalWallpaperAction

    data class SetFavoriteOnly(
        val enabled: Boolean,
    ) : LocalWallpaperAction

    data class SelectTag(
        val tag: String?,
    ) : LocalWallpaperAction

    data class SelectSort(
        val sort: LocalWallpaperSort,
    ) : LocalWallpaperAction

    data object ResetFilters : LocalWallpaperAction

    data class SelectResource(
        val resourceId: String?,
    ) : LocalWallpaperAction

    data class StartSelection(
        val resourceId: String,
    ) : LocalWallpaperAction

    data class ToggleSelection(
        val resourceId: String,
    ) : LocalWallpaperAction

    data object ClearSelection : LocalWallpaperAction

    data class ToggleFavorite(
        val resourceId: String,
    ) : LocalWallpaperAction

    data class AddTagToSelection(
        val tag: String,
    ) : LocalWallpaperAction

    data class ReplaceResourceTags(
        val resourceId: String,
        val tags: Set<String>,
    ) : LocalWallpaperAction

    data class RenameTag(
        val oldTag: String,
        val newTag: String,
    ) : LocalWallpaperAction

    data class DeleteTag(
        val tag: String,
    ) : LocalWallpaperAction

    data class DeleteResources(
        val resourceIds: Set<String>,
    ) : LocalWallpaperAction

    data class OpenResource(
        val resource: LocalWallpaperResource,
    ) : LocalWallpaperAction

    data class ShareResources(
        val resources: List<LocalWallpaperResource>,
    ) : LocalWallpaperAction

    data class CopyLocation(
        val resource: LocalWallpaperResource,
    ) : LocalWallpaperAction

    data class ImportToWallpaperEngine(
        val resource: LocalWallpaperResource,
    ) : LocalWallpaperAction

    data class ImportLaunched(
        val resourceId: String,
    ) : LocalWallpaperAction

    data class SystemActionFailed(
        val message: String,
    ) : LocalWallpaperAction
}

sealed interface LocalWallpaperEffect {
    data object ChooseDirectory : LocalWallpaperEffect

    data class OpenResource(
        val resource: LocalWallpaperResource,
    ) : LocalWallpaperEffect

    data class ShareResources(
        val resources: List<LocalWallpaperResource>,
    ) : LocalWallpaperEffect

    data class CopyLocation(
        val resource: LocalWallpaperResource,
    ) : LocalWallpaperEffect

    data class ImportToWallpaperEngine(
        val resource: LocalWallpaperResource,
    ) : LocalWallpaperEffect

    data class ShowMessage(
        val message: String? = null,
        @StringRes val messageRes: Int? = null,
    ) : LocalWallpaperEffect
}

@HiltViewModel
class LocalWallpaperViewModel
    @Inject
    constructor(
        private val repository: LocalWallpaperRepository,
        private val settingsRepository: SettingsRepository,
        private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(savedStateHandle.localWallpaperState())
        private val effectChannel = Channel<LocalWallpaperEffect>(capacity = Channel.BUFFERED)
        private var scanJob: Job? = null
        private var browseViewMode = LocalWallpaperViewMode.LIST

        val uiState: StateFlow<LocalWallpaperUiState> = mutableState.asStateFlow()
        val effects: Flow<LocalWallpaperEffect> = effectChannel.receiveAsFlow()

        init {
            viewModelScope.launch {
                mutableState.collect { state -> savedStateHandle.saveLocalWallpaperState(state) }
            }
            viewModelScope.launch {
                val preferences = settingsRepository.preferences.first()
                browseViewMode = preferences.localWallpaperViewMode
                    .takeUnless { mode -> mode == LocalWallpaperViewMode.DETAIL }
                    ?: LocalWallpaperViewMode.LIST
                mutableState.value = mutableState.value.copy(viewMode = browseViewMode)
            }
        }

        private fun enterPage() {
            scan()
        }

        private fun scan() {
            scanJob?.cancel()
            scanJob =
                viewModelScope.launch {
                    repository.scan().collect { snapshot ->
                        val state = mutableState.value
                        val existingResources = state.scan.resources.associateBy(LocalWallpaperResource::id)
                        val scannedResources = snapshot.resources
                        val displayedSnapshot =
                            if (snapshot.isScanning && existingResources.isNotEmpty()) {
                                snapshot.copy(
                                    resources =
                                        (state.scan.resources + scannedResources)
                                            .associateBy(LocalWallpaperResource::id)
                                            .values
                                            .toList(),
                                )
                            } else {
                                snapshot.copy(resources = scannedResources)
                            }
                        val selectedResourceId =
                            state.selectedResourceId
                                ?.takeIf { id -> displayedSnapshot.resources.any { it.id == id } }
                        val selectedResourceIds =
                            state.selectedResourceIds
                                .filterTo(linkedSetOf()) { id ->
                                    displayedSnapshot.resources.any { it.id == id }
                                }
                        mutableState.value =
                            state.copy(
                                scan = displayedSnapshot,
                                viewMode =
                                    if (
                                        state.viewMode == LocalWallpaperViewMode.DETAIL &&
                                        selectedResourceId == null
                                    ) {
                                        browseViewMode
                                    } else {
                                        state.viewMode
                                    },
                                selectedResourceId = selectedResourceId,
                                selectionMode = state.selectionMode && selectedResourceIds.isNotEmpty(),
                                selectedResourceIds = selectedResourceIds,
                            )
                    }
                }
        }

        private fun cancelScan() {
            scanJob?.cancel()
            scanJob = null
            mutableState.value =
                mutableState.value.copy(
                    scan =
                        mutableState.value.scan.copy(
                            isScanning = false,
                            currentSourceLabel = null,
                        ),
                )
            showMessage(R.string.local_scan_cancelled)
        }

        private fun setViewMode(mode: LocalWallpaperViewMode) {
            if (mode == LocalWallpaperViewMode.DETAIL) return
            browseViewMode = mode
            mutableState.value =
                mutableState.value.copy(
                    viewMode = mode,
                    selectedResourceId = null,
                )
            viewModelScope.launch { settingsRepository.setLocalWallpaperViewMode(mode) }
        }

        private fun setSearchQuery(query: String) {
            mutableState.value = mutableState.value.copy(searchQuery = query.take(MAX_SEARCH_LENGTH))
        }

        private fun setFormatFilter(filter: LocalWallpaperFormatFilter) {
            mutableState.value = mutableState.value.copy(formatFilter = filter)
        }

        private fun setImportFilter(filter: LocalWallpaperImportFilter) {
            mutableState.value = mutableState.value.copy(importFilter = filter)
        }

        private fun setSource(sourceId: String?) {
            mutableState.value = mutableState.value.copy(sourceId = sourceId)
        }

        private fun setFavoriteOnly(enabled: Boolean) {
            mutableState.value = mutableState.value.copy(favoriteOnly = enabled)
        }

        private fun setSelectedTag(tag: String?) {
            mutableState.value = mutableState.value.copy(selectedTag = tag)
        }

        private fun setSort(sort: LocalWallpaperSort) {
            mutableState.value = mutableState.value.copy(sort = sort)
        }

        private fun resetFilters() {
            mutableState.value =
                mutableState.value.copy(
                    formatFilter = LocalWallpaperFormatFilter.ALL,
                    importFilter = LocalWallpaperImportFilter.ALL,
                    sourceId = null,
                    favoriteOnly = false,
                    selectedTag = null,
                    sort = LocalWallpaperSort.RECENT,
                )
        }

        private fun selectResource(resourceId: String?) {
            mutableState.value =
                if (resourceId == null) {
                    mutableState.value.copy(
                        viewMode = browseViewMode,
                        selectedResourceId = null,
                    )
                } else {
                    mutableState.value.copy(
                        viewMode = LocalWallpaperViewMode.DETAIL,
                        selectedResourceId = resourceId,
                    )
                }
        }

        private fun startSelection(resourceId: String) {
            mutableState.value =
                mutableState.value.copy(
                    viewMode = browseViewMode,
                    selectionMode = true,
                    selectedResourceIds = setOf(resourceId),
                    selectedResourceId = resourceId,
                )
        }

        private fun toggleSelection(resourceId: String) {
            val selected = mutableState.value.selectedResourceIds.toMutableSet()
            if (!selected.add(resourceId)) selected.remove(resourceId)
            mutableState.value =
                mutableState.value.copy(
                    viewMode = if (selected.isEmpty()) browseViewMode else mutableState.value.viewMode,
                    selectionMode = selected.isNotEmpty(),
                    selectedResourceIds = selected,
                    selectedResourceId = selected.lastOrNull(),
                )
        }

        private fun clearSelection() {
            mutableState.value =
                mutableState.value.copy(
                    viewMode = browseViewMode,
                    selectionMode = false,
                    selectedResourceIds = emptySet(),
                    selectedResourceId = null,
                )
        }

        private fun setCustomDirectory(
            treeUri: String,
            label: String,
        ) {
            viewModelScope.launch {
                try {
                    settingsRepository.setLocalManagementDirectory(treeUri, label)
                    scan()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    showMessage(R.string.local_save_directory_failed)
                }
            }
        }

        private fun clearCustomDirectory() {
            viewModelScope.launch {
                settingsRepository.clearLocalManagementDirectory()
                scan()
            }
        }

        private fun toggleFavorite(resourceId: String) {
            val resource =
                mutableState.value.scan.resources
                    .firstOrNull { it.id == resourceId } ?: return
            val value = !resource.isFavorite
            viewModelScope.launch {
                try {
                    repository.setFavorite(resourceId, value)
                    updateResource(resourceId) { it.copy(isFavorite = value) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    showMessage(R.string.local_save_favorite_failed)
                    scan()
                }
            }
        }

        private fun addTagToSelection(tag: String) {
            val normalized = tag.trim().take(MAX_TAG_LENGTH)
            if (normalized.isBlank()) return
            val ids = targetResourceIds()
            val updatedTags =
                ids.mapNotNull { id ->
                    mutableState.value.scan.resources
                        .firstOrNull { it.id == id }
                        ?.let { resource -> id to (resource.tags + normalized) }
                }
            viewModelScope.launch {
                try {
                    updatedTags.forEach { (id, tags) -> repository.replaceTags(id, tags) }
                    updatedTags.forEach { (id, tags) ->
                        updateResource(id) { resource -> resource.copy(tags = tags) }
                    }
                    clearSelection()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    showMessage(R.string.local_save_tags_failed)
                    scan()
                }
            }
        }

        private fun replaceResourceTags(
            resourceId: String,
            tags: Set<String>,
        ) {
            val normalized =
                tags
                    .map { it.trim().take(MAX_TAG_LENGTH) }
                    .filter(String::isNotBlank)
                    .toSet()
            viewModelScope.launch {
                try {
                    repository.replaceTags(resourceId, normalized)
                    updateResource(resourceId) { it.copy(tags = normalized) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    showMessage(R.string.local_save_tags_failed)
                    scan()
                }
            }
        }

        private fun renameTag(
            oldTag: String,
            newTag: String,
        ) {
            viewModelScope.launch {
                try {
                    repository.renameTag(oldTag, newTag)
                    val normalizedNewTag = newTag.trim().take(MAX_TAG_LENGTH)
                    mutableState.value =
                        mutableState.value.copy(
                            scan =
                                mutableState.value.scan.copy(
                                    resources =
                                        mutableState.value.scan.resources.map { resource ->
                                            if (oldTag in resource.tags) {
                                                resource.copy(tags = (resource.tags - oldTag) + normalizedNewTag)
                                            } else {
                                                resource
                                            }
                                        },
                                ),
                            selectedTag =
                                if (mutableState.value.selectedTag == oldTag) {
                                    normalizedNewTag
                                } else {
                                    mutableState.value.selectedTag
                                },
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    showMessage(R.string.local_rename_tag_failed)
                    scan()
                }
            }
        }

        private fun deleteTag(tag: String) {
            viewModelScope.launch {
                try {
                    repository.deleteTag(tag)
                    mutableState.value =
                        mutableState.value.copy(
                            scan =
                                mutableState.value.scan.copy(
                                    resources =
                                        mutableState.value.scan.resources.map { resource ->
                                            resource.copy(tags = resource.tags - tag)
                                        },
                                ),
                            selectedTag = mutableState.value.selectedTag.takeUnless { it == tag },
                        )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    showMessage(R.string.local_delete_tag_failed)
                    scan()
                }
            }
        }

        private fun markImportRequested(resourceId: String) {
            val now = System.currentTimeMillis()
            viewModelScope.launch {
                try {
                    repository.markImportRequested(resourceId, now)
                    updateResource(resourceId) { it.copy(importRequestedAt = now) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    showMessage(R.string.local_save_import_state_failed)
                    scan()
                }
            }
        }

        private fun deleteResources(resourceIds: Set<String>) {
            val resources =
                mutableState.value.scan.resources
                    .filter { it.id in resourceIds }
            viewModelScope.launch {
                val results = resources.map { resource -> repository.delete(resource) }
                val failures = results.filterNot { it.deleted }
                if (failures.isNotEmpty()) {
                    showMessage(R.string.backend_local_delete_failed)
                }
                clearSelection()
                scan()
            }
        }

        fun onAction(action: LocalWallpaperAction) {
            if (handleScanAndDirectoryAction(action)) return
            if (handleFilterAction(action)) return
            if (handleSelectionAndTagAction(action)) return
            handleSystemAction(action)
        }

        private fun handleScanAndDirectoryAction(action: LocalWallpaperAction): Boolean {
            when (action) {
                LocalWallpaperAction.EnterPage -> enterPage()
                LocalWallpaperAction.Refresh -> scan()
                LocalWallpaperAction.CancelScan -> cancelScan()
                LocalWallpaperAction.ChooseDirectory -> emitEffect(LocalWallpaperEffect.ChooseDirectory)
                LocalWallpaperAction.ResetDirectory -> clearCustomDirectory()
                is LocalWallpaperAction.DirectorySelected ->
                    setCustomDirectory(
                        action.treeUri,
                        action.label,
                    )
                else -> return false
            }
            return true
        }

        private fun handleFilterAction(action: LocalWallpaperAction): Boolean {
            when (action) {
                is LocalWallpaperAction.SearchQueryChanged -> setSearchQuery(action.query)
                is LocalWallpaperAction.SelectViewMode -> setViewMode(action.mode)
                is LocalWallpaperAction.SelectFormatFilter -> setFormatFilter(action.filter)
                is LocalWallpaperAction.SelectImportFilter -> setImportFilter(action.filter)
                is LocalWallpaperAction.SelectSource -> setSource(action.sourceId)
                is LocalWallpaperAction.SetFavoriteOnly -> setFavoriteOnly(action.enabled)
                is LocalWallpaperAction.SelectTag -> setSelectedTag(action.tag)
                is LocalWallpaperAction.SelectSort -> setSort(action.sort)
                LocalWallpaperAction.ResetFilters -> resetFilters()
                else -> return false
            }
            return true
        }

        private fun handleSelectionAndTagAction(action: LocalWallpaperAction): Boolean {
            when (action) {
                is LocalWallpaperAction.SelectResource -> selectResource(action.resourceId)
                is LocalWallpaperAction.StartSelection -> startSelection(action.resourceId)
                is LocalWallpaperAction.ToggleSelection -> toggleSelection(action.resourceId)
                LocalWallpaperAction.ClearSelection -> clearSelection()
                is LocalWallpaperAction.ToggleFavorite -> toggleFavorite(action.resourceId)
                is LocalWallpaperAction.AddTagToSelection -> addTagToSelection(action.tag)
                is LocalWallpaperAction.ReplaceResourceTags ->
                    replaceResourceTags(
                        action.resourceId,
                        action.tags,
                    )
                is LocalWallpaperAction.RenameTag -> renameTag(action.oldTag, action.newTag)
                is LocalWallpaperAction.DeleteTag -> deleteTag(action.tag)
                is LocalWallpaperAction.DeleteResources -> deleteResources(action.resourceIds)
                else -> return false
            }
            return true
        }

        private fun handleSystemAction(action: LocalWallpaperAction) {
            when (action) {
                is LocalWallpaperAction.OpenResource ->
                    emitEffect(
                        LocalWallpaperEffect.OpenResource(action.resource),
                    )
                is LocalWallpaperAction.ShareResources ->
                    emitEffect(
                        LocalWallpaperEffect.ShareResources(action.resources),
                    )
                is LocalWallpaperAction.CopyLocation ->
                    emitEffect(
                        LocalWallpaperEffect.CopyLocation(action.resource),
                    )
                is LocalWallpaperAction.ImportToWallpaperEngine ->
                    emitEffect(
                        LocalWallpaperEffect.ImportToWallpaperEngine(action.resource),
                    )
                is LocalWallpaperAction.ImportLaunched -> markImportRequested(action.resourceId)
                is LocalWallpaperAction.SystemActionFailed -> showMessage(action.message)
                else -> Unit
            }
        }

        private fun showMessage(
            @StringRes messageRes: Int,
        ) {
            emitEffect(LocalWallpaperEffect.ShowMessage(messageRes = messageRes))
        }

        private fun showMessage(message: String) {
            emitEffect(LocalWallpaperEffect.ShowMessage(message = message))
        }

        private fun emitEffect(effect: LocalWallpaperEffect) {
            effectChannel.trySend(effect)
        }

        private fun targetResourceIds(): Set<String> =
            mutableState.value.selectedResourceIds.ifEmpty {
                mutableState.value.selectedResourceId
                    ?.let(::setOf)
                    .orEmpty()
            }

        private fun updateResource(
            resourceId: String,
            transform: (LocalWallpaperResource) -> LocalWallpaperResource,
        ) {
            mutableState.value =
                mutableState.value.copy(
                    scan =
                        mutableState.value.scan.copy(
                            resources =
                                mutableState.value.scan.resources.map { resource ->
                                    if (resource.id == resourceId) transform(resource) else resource
                                },
                        ),
                )
        }

        private companion object {
            const val MAX_SEARCH_LENGTH = 120
            const val MAX_TAG_LENGTH = 40
        }
    }

private fun SavedStateHandle.localWallpaperState(): LocalWallpaperUiState =
    LocalWallpaperUiState(
        searchQuery = get<String>(LOCAL_SEARCH_QUERY_KEY).orEmpty(),
        formatFilter = enumValueOrDefault(get(LOCAL_FORMAT_FILTER_KEY), LocalWallpaperFormatFilter.ALL),
        importFilter = enumValueOrDefault(get(LOCAL_IMPORT_FILTER_KEY), LocalWallpaperImportFilter.ALL),
        sourceId = get(LOCAL_SOURCE_ID_KEY),
        favoriteOnly = get<Boolean>(LOCAL_FAVORITE_ONLY_KEY) ?: false,
        selectedTag = get(LOCAL_SELECTED_TAG_KEY),
        sort = enumValueOrDefault(get(LOCAL_SORT_KEY), LocalWallpaperSort.RECENT),
        viewMode = enumValueOrDefault(get(LOCAL_VIEW_MODE_KEY), LocalWallpaperViewMode.LIST),
        selectedResourceId = get(LOCAL_SELECTED_RESOURCE_ID_KEY),
        selectionMode = get<Boolean>(LOCAL_SELECTION_MODE_KEY) ?: false,
        selectedResourceIds = get<ArrayList<String>>(LOCAL_SELECTED_RESOURCE_IDS_KEY)?.toSet().orEmpty(),
    )

private fun SavedStateHandle.saveLocalWallpaperState(state: LocalWallpaperUiState) {
    this[LOCAL_SEARCH_QUERY_KEY] = state.searchQuery
    this[LOCAL_FORMAT_FILTER_KEY] = state.formatFilter.name
    this[LOCAL_IMPORT_FILTER_KEY] = state.importFilter.name
    this[LOCAL_SOURCE_ID_KEY] = state.sourceId
    this[LOCAL_FAVORITE_ONLY_KEY] = state.favoriteOnly
    this[LOCAL_SELECTED_TAG_KEY] = state.selectedTag
    this[LOCAL_SORT_KEY] = state.sort.name
    this[LOCAL_VIEW_MODE_KEY] = state.viewMode.name
    this[LOCAL_SELECTED_RESOURCE_ID_KEY] = state.selectedResourceId
    this[LOCAL_SELECTION_MODE_KEY] = state.selectionMode
    this[LOCAL_SELECTED_RESOURCE_IDS_KEY] = ArrayList(state.selectedResourceIds)
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(
    value: String?,
    default: T,
): T = value?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

private const val LOCAL_SEARCH_QUERY_KEY = "local.searchQuery"
private const val LOCAL_FORMAT_FILTER_KEY = "local.formatFilter"
private const val LOCAL_IMPORT_FILTER_KEY = "local.importFilter"
private const val LOCAL_SOURCE_ID_KEY = "local.sourceId"
private const val LOCAL_FAVORITE_ONLY_KEY = "local.favoriteOnly"
private const val LOCAL_SELECTED_TAG_KEY = "local.selectedTag"
private const val LOCAL_SORT_KEY = "local.sort"
private const val LOCAL_VIEW_MODE_KEY = "local.viewMode"
private const val LOCAL_SELECTED_RESOURCE_ID_KEY = "local.selectedResourceId"
private const val LOCAL_SELECTION_MODE_KEY = "local.selectionMode"
private const val LOCAL_SELECTED_RESOURCE_IDS_KEY = "local.selectedResourceIds"

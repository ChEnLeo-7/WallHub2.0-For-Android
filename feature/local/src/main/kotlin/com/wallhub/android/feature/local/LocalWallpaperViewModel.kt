package com.wallhub.android.feature.local

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wallhub.android.core.model.LocalWallpaperFormat
import com.wallhub.android.core.model.LocalWallpaperImportState
import com.wallhub.android.core.model.LocalWallpaperRepository
import com.wallhub.android.core.model.LocalWallpaperResource
import com.wallhub.android.core.model.LocalWallpaperScanSnapshot
import com.wallhub.android.core.model.LocalWallpaperViewMode
import com.wallhub.android.core.model.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    val actionMessage: String? = null,
) {
    val resources: List<LocalWallpaperResource>
        get() {
            val query = searchQuery.trim().lowercase(Locale.ROOT)
            return scan.resources
                .asSequence()
                .filter { resource ->
                    formatFilter == LocalWallpaperFormatFilter.ALL ||
                        resource.format.name == formatFilter.name
                }
                .filter { resource ->
                    when (importFilter) {
                        LocalWallpaperImportFilter.ALL -> true
                        LocalWallpaperImportFilter.NOT_IMPORTED ->
                            resource.importState == LocalWallpaperImportState.NOT_IMPORTED
                        LocalWallpaperImportFilter.IMPORT_REQUESTED ->
                            resource.importState == LocalWallpaperImportState.IMPORT_REQUESTED
                    }
                }
                .filter { resource -> sourceId == null || resource.sourceId == sourceId }
                .filter { resource -> !favoriteOnly || resource.isFavorite }
                .filter { resource -> selectedTag == null || selectedTag in resource.tags }
                .filter { resource ->
                    query.isBlank() || listOf(
                        resource.title,
                        resource.displayName,
                        resource.relativePath,
                        resource.format.name,
                        resource.tags.joinToString(" "),
                    ).any { value -> value.lowercase(Locale.ROOT).contains(query) }
                }
                .let { filtered ->
                    when (sort) {
                        LocalWallpaperSort.RECENT -> filtered.sortedWith(
                            compareByDescending<LocalWallpaperResource> { it.modifiedAt }
                                .thenBy { it.title.lowercase(Locale.ROOT) },
                        )

                        LocalWallpaperSort.NAME -> filtered.sortedBy {
                            it.title.lowercase(Locale.ROOT)
                        }

                        LocalWallpaperSort.SIZE -> filtered.sortedByDescending(LocalWallpaperResource::sizeBytes)
                        LocalWallpaperSort.TYPE -> filtered.sortedWith(
                            compareBy<LocalWallpaperResource> { it.format.name }
                                .thenBy { it.title.lowercase(Locale.ROOT) },
                        )
                    }
                }
                .toList()
        }

    val allTags: List<String>
        get() = scan.resources.flatMap(LocalWallpaperResource::tags).distinct().sorted()

    val activeFilterCount: Int
        get() = listOf(
            formatFilter != LocalWallpaperFormatFilter.ALL,
            importFilter != LocalWallpaperImportFilter.ALL,
            sourceId != null,
            favoriteOnly,
            selectedTag != null,
            sort != LocalWallpaperSort.RECENT,
        ).count { it }

    val summary: String
        get() = when {
            scan.isScanning -> "正在扫描 · 已发现 ${scan.discoveredCount} 个文件"
            resources.isEmpty() -> "没有符合条件的本地资源"
            activeFilterCount > 0 -> "显示 ${resources.size} 个匹配资源"
            else -> "共 ${resources.size} 个本地资源"
        }
}

@HiltViewModel
class LocalWallpaperViewModel @Inject constructor(
    private val repository: LocalWallpaperRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LocalWallpaperUiState())
    private var scanJob: Job? = null
    private var browseViewMode = LocalWallpaperViewMode.LIST

    val uiState: StateFlow<LocalWallpaperUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val preferences = settingsRepository.preferences.first()
            browseViewMode = preferences.localWallpaperViewMode
                .takeUnless { mode -> mode == LocalWallpaperViewMode.DETAIL }
                ?: LocalWallpaperViewMode.LIST
            mutableState.value = mutableState.value.copy(viewMode = browseViewMode)
        }
    }

    fun enterPage() {
        scan()
    }

    fun scan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            repository.scan().collect { snapshot ->
                val state = mutableState.value
                val existingResources = state.scan.resources.associateBy(LocalWallpaperResource::id)
                val scannedResources = snapshot.resources.map { resource ->
                    existingResources[resource.id]?.let { existing ->
                        resource.copy(
                            isFavorite = existing.isFavorite,
                            tags = existing.tags,
                            importRequestedAt = existing.importRequestedAt,
                        )
                    } ?: resource
                }
                val displayedSnapshot = if (snapshot.isScanning && existingResources.isNotEmpty()) {
                    snapshot.copy(
                        resources = (state.scan.resources + scannedResources)
                            .associateBy(LocalWallpaperResource::id)
                            .values
                            .toList(),
                    )
                } else {
                    snapshot.copy(resources = scannedResources)
                }
                val selectedResourceId = state.selectedResourceId
                    ?.takeIf { id -> displayedSnapshot.resources.any { it.id == id } }
                val selectedResourceIds = state.selectedResourceIds
                    .filterTo(linkedSetOf()) { id ->
                        displayedSnapshot.resources.any { it.id == id }
                    }
                mutableState.value = state.copy(
                    scan = displayedSnapshot,
                    viewMode = if (
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
                    actionMessage = null,
                )
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        mutableState.value = mutableState.value.copy(
            scan = mutableState.value.scan.copy(
                isScanning = false,
                currentSourceLabel = null,
            ),
            actionMessage = "已取消扫描",
        )
    }

    fun setViewMode(mode: LocalWallpaperViewMode) {
        if (mode == LocalWallpaperViewMode.DETAIL) return
        browseViewMode = mode
        mutableState.value = mutableState.value.copy(
            viewMode = mode,
            selectedResourceId = null,
        )
        viewModelScope.launch { settingsRepository.setLocalWallpaperViewMode(mode) }
    }

    fun setSearchQuery(query: String) {
        mutableState.value = mutableState.value.copy(searchQuery = query.take(MAX_SEARCH_LENGTH))
    }

    fun setFormatFilter(filter: LocalWallpaperFormatFilter) {
        mutableState.value = mutableState.value.copy(formatFilter = filter)
    }

    fun setImportFilter(filter: LocalWallpaperImportFilter) {
        mutableState.value = mutableState.value.copy(importFilter = filter)
    }

    fun setSource(sourceId: String?) {
        mutableState.value = mutableState.value.copy(sourceId = sourceId)
    }

    fun setFavoriteOnly(enabled: Boolean) {
        mutableState.value = mutableState.value.copy(favoriteOnly = enabled)
    }

    fun setSelectedTag(tag: String?) {
        mutableState.value = mutableState.value.copy(selectedTag = tag)
    }

    fun setSort(sort: LocalWallpaperSort) {
        mutableState.value = mutableState.value.copy(sort = sort)
    }

    fun resetFilters() {
        mutableState.value = mutableState.value.copy(
            formatFilter = LocalWallpaperFormatFilter.ALL,
            importFilter = LocalWallpaperImportFilter.ALL,
            sourceId = null,
            favoriteOnly = false,
            selectedTag = null,
            sort = LocalWallpaperSort.RECENT,
        )
    }

    fun selectResource(resourceId: String?) {
        mutableState.value = if (resourceId == null) {
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

    fun startSelection(resourceId: String) {
        mutableState.value = mutableState.value.copy(
            viewMode = browseViewMode,
            selectionMode = true,
            selectedResourceIds = setOf(resourceId),
            selectedResourceId = resourceId,
        )
    }

    fun toggleSelection(resourceId: String) {
        val selected = mutableState.value.selectedResourceIds.toMutableSet()
        if (!selected.add(resourceId)) selected.remove(resourceId)
        mutableState.value = mutableState.value.copy(
            viewMode = if (selected.isEmpty()) browseViewMode else mutableState.value.viewMode,
            selectionMode = selected.isNotEmpty(),
            selectedResourceIds = selected,
            selectedResourceId = selected.lastOrNull(),
        )
    }

    fun clearSelection() {
        mutableState.value = mutableState.value.copy(
            viewMode = browseViewMode,
            selectionMode = false,
            selectedResourceIds = emptySet(),
            selectedResourceId = null,
        )
    }

    fun setCustomDirectory(treeUri: String, label: String) {
        viewModelScope.launch {
            runCatching { settingsRepository.setLocalManagementDirectory(treeUri, label) }
                .onSuccess { scan() }
                .onFailure { error -> setActionMessage(error.message ?: "无法保存目录") }
        }
    }

    fun clearCustomDirectory() {
        viewModelScope.launch {
            settingsRepository.clearLocalManagementDirectory()
            scan()
        }
    }

    fun toggleFavorite(resourceId: String) {
        val resource = mutableState.value.scan.resources.firstOrNull { it.id == resourceId } ?: return
        val value = !resource.isFavorite
        updateResource(resourceId) { it.copy(isFavorite = value) }
        viewModelScope.launch {
            runCatching { repository.setFavorite(resourceId, value) }
                .onFailure { error -> setActionMessage(error.message ?: "收藏状态保存失败") }
        }
    }

    fun addTagToSelection(tag: String) {
        val normalized = tag.trim().take(MAX_TAG_LENGTH)
        if (normalized.isBlank()) return
        val ids = targetResourceIds()
        ids.forEach { id ->
            updateResource(id) { resource -> resource.copy(tags = resource.tags + normalized) }
        }
        viewModelScope.launch {
            ids.forEach { id ->
                val resource = mutableState.value.scan.resources.firstOrNull { it.id == id } ?: return@forEach
                repository.replaceTags(id, resource.tags)
            }
            clearSelection()
        }
    }

    fun replaceResourceTags(resourceId: String, tags: Set<String>) {
        val normalized = tags.map { it.trim().take(MAX_TAG_LENGTH) }
            .filter(String::isNotBlank)
            .toSet()
        updateResource(resourceId) { it.copy(tags = normalized) }
        viewModelScope.launch {
            runCatching { repository.replaceTags(resourceId, normalized) }
                .onFailure { error -> setActionMessage(error.message ?: "标签保存失败") }
        }
    }

    fun renameTag(oldTag: String, newTag: String) {
        viewModelScope.launch {
            runCatching { repository.renameTag(oldTag, newTag) }
                .onSuccess {
                    val normalizedNewTag = newTag.trim().take(MAX_TAG_LENGTH)
                    mutableState.value = mutableState.value.copy(
                        scan = mutableState.value.scan.copy(
                            resources = mutableState.value.scan.resources.map { resource ->
                                if (oldTag in resource.tags) {
                                    resource.copy(tags = (resource.tags - oldTag) + normalizedNewTag)
                                } else {
                                    resource
                                }
                            },
                        ),
                        selectedTag = if (mutableState.value.selectedTag == oldTag) {
                            normalizedNewTag
                        } else {
                            mutableState.value.selectedTag
                        },
                    )
                }
                .onFailure { error -> setActionMessage(error.message ?: "标签重命名失败") }
        }
    }

    fun deleteTag(tag: String) {
        viewModelScope.launch {
            runCatching { repository.deleteTag(tag) }
                .onSuccess {
                    mutableState.value = mutableState.value.copy(
                        scan = mutableState.value.scan.copy(
                            resources = mutableState.value.scan.resources.map { resource ->
                                resource.copy(tags = resource.tags - tag)
                            },
                        ),
                        selectedTag = mutableState.value.selectedTag.takeUnless { it == tag },
                    )
                }
                .onFailure { error -> setActionMessage(error.message ?: "标签删除失败") }
        }
    }

    fun markImportRequested(resourceId: String) {
        val now = System.currentTimeMillis()
        updateResource(resourceId) { it.copy(importRequestedAt = now) }
        viewModelScope.launch {
            runCatching { repository.markImportRequested(resourceId, now) }
                .onFailure { error -> setActionMessage(error.message ?: "导入状态保存失败") }
        }
    }

    fun deleteResources(resourceIds: Set<String>) {
        val resources = mutableState.value.scan.resources.filter { it.id in resourceIds }
        viewModelScope.launch {
            val results = resources.map { resource -> repository.delete(resource) }
            val failures = results.filterNot { it.deleted }
            if (failures.isNotEmpty()) {
                setActionMessage(failures.first().message)
            }
            clearSelection()
            scan()
        }
    }

    fun setActionMessage(message: String) {
        mutableState.value = mutableState.value.copy(actionMessage = message)
    }

    private fun targetResourceIds(): Set<String> = mutableState.value.selectedResourceIds.ifEmpty {
        mutableState.value.selectedResourceId?.let(::setOf).orEmpty()
    }

    private fun updateResource(
        resourceId: String,
        transform: (LocalWallpaperResource) -> LocalWallpaperResource,
    ) {
        mutableState.value = mutableState.value.copy(
            scan = mutableState.value.scan.copy(
                resources = mutableState.value.scan.resources.map { resource ->
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

package com.wallhub.android.feature.votes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.wallhub.android.R
import com.wallhub.android.core.model.AccountWorkshopRepository
import com.wallhub.android.core.model.CmPublicDetailResult
import com.wallhub.android.core.model.SteamSessionRepository
import com.wallhub.android.core.model.SteamUnifiedWorkshopRepository
import com.wallhub.android.core.model.WorkshopSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private class VoteAccountMismatch : IllegalStateException()

enum class PersonalVotesProblem { READ, ACCOUNT_UNVERIFIED, ACCOUNT_MISMATCH }

data class PersonalVoteItem(val summary: WorkshopSummary, val vote: Int)

data class PersonalVotesState(
    val accountName: String? = null,
    val steamId: String? = null,
    val generation: Long = 0,
    val requestCounter: Int = 0,
    val readerMounted: Boolean = false,
    val readerVisible: Boolean = true,
    val busy: Boolean = false,
    val started: Boolean = false,
    val hasNext: Boolean = false,
    val exhausted: Boolean = false,
    val unresolved: Set<Long> = emptySet(),
    val items: List<PersonalVoteItem> = emptyList(),
    val selectedVote: Int = 1,
    val problem: PersonalVotesProblem? = null,
) {
    val complete: Boolean get() = exhausted && unresolved.isEmpty() && problem == null
}

/** No init-time RPCs, scans, automatic next pages, or alternate account owner. */
@HiltViewModel
class PersonalVotesViewModel @Inject constructor(
    private val sessions: SteamSessionRepository,
    private val accountWorkshop: AccountWorkshopRepository,
    private val publicWorkshop: SteamUnifiedWorkshopRepository,
    private val countsStore: VoteCountsStore,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PersonalVotesState())
    val state = mutableState.asStateFlow()
    private var epoch = 0L
    private var work: Job? = null
    private val resolved = linkedMapOf<Long, PersonalVoteItem>()

    init {
        viewModelScope.launch {
            sessions.session.map { it.accountName?.takeIf(String::isNotBlank) }
                .distinctUntilChanged().collect { name ->
                    if (mutableState.value.accountName != name) resetAccount(name)
                    countsStore.bindAccount(name)
                }
        }
    }

    private fun resetAccount(name: String?) {
        work?.cancel()
        epoch++
        resolved.clear()
        countsStore.clear()
        mutableState.value = PersonalVotesState(accountName = name, generation = epoch)
    }

    private fun current(generation: Long, name: String?): Boolean =
        epoch == generation && mutableState.value.accountName == name &&
            sessions.session.value.accountName == name

    private suspend fun verifyAccount(generation: Long, name: String?, steamId: String): Boolean {
        if (!current(generation, name)) return false
        val actual = accountWorkshop.currentAccountSteamId()
        if (!current(generation, name)) return false
        if (actual != steamId) throw VoteAccountMismatch()
        return true
    }

    fun sync() {
        val name = sessions.session.value.accountName?.takeIf(String::isNotBlank) ?: return
        resetAccount(name)
        val generation = epoch
        mutableState.value = mutableState.value.copy(busy = true, started = true)
        work = viewModelScope.launch {
            try {
                val steamId = accountWorkshop.currentAccountSteamId()
                if (!current(generation, name)) return@launch
                if (steamId.isNullOrBlank()) {
                    mutableState.value = mutableState.value.copy(busy = false, problem = PersonalVotesProblem.READ)
                } else {
                    mutableState.value = mutableState.value.copy(
                        steamId = steamId, readerMounted = true, readerVisible = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current(generation, name)) {
                    mutableState.value = mutableState.value.copy(busy = false, problem = PersonalVotesProblem.READ)
                }
            }
        }
    }

    fun selectVote(vote: Int) {
        if (vote == 1 || vote == 2) mutableState.value = mutableState.value.copy(selectedVote = vote)
    }

    fun nextPage() {
        val state = mutableState.value
        if (state.busy || !state.hasNext || !state.readerMounted) return
        mutableState.value = state.copy(busy = true, requestCounter = state.requestCounter + 1)
    }

    fun onReaderError(generation: Long, error: VotesReaderError) {
        if (!current(generation, mutableState.value.accountName)) return
        work?.cancel()
        if (error == VotesReaderError.ACCOUNT_MISMATCH) {
            failAccountMatch()
            return
        }
        mutableState.value = mutableState.value.copy(
            busy = false,
            problem = if (error == VotesReaderError.ACCOUNT_UNVERIFIED) {
                PersonalVotesProblem.ACCOUNT_UNVERIFIED
            } else {
                PersonalVotesProblem.READ
            },
        )
        publishCounts()
    }

    fun onPage(generation: Long, page: VotesReadPage) {
        val initial = mutableState.value
        val name = initial.accountName
        if (!current(generation, name) || !initial.readerMounted || (!initial.busy && !initial.readerVisible)) return
        if (page.accountId != initial.steamId) {
            failAccountMatch()
            return
        }
        work?.cancel()
        mutableState.value = initial.copy(busy = true)
        work = viewModelScope.launch {
            val unresolved = initial.unresolved.toMutableSet()
            var problem = initial.problem
            try {
                val steamId = requireNotNull(initial.steamId)
                if (!verifyAccount(generation, name, steamId)) return@launch
                // HTML flags identify candidates only. CM is authoritative for the current direction.
                val ids = page.rows.map { it.fileId }.distinct()
                for (chunk in ids.chunked(16)) {
                    if (!verifyAccount(generation, name, steamId)) return@launch
                    val outcomes = try {
                        publicWorkshop.getPublicDetailsResult(chunk).outcomes.groupBy({ it.first }, { it.second })
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyMap()
                    }
                    if (!current(generation, name)) return@launch
                    for (id in chunk) {
                        if (!current(generation, name)) return@launch
                        val result = outcomes[id]?.singleOrNull()
                        val detail = (result as? CmPublicDetailResult.Available)?.detail
                        val appId = detail?.contentIdentity?.consumerAppId
                        if (detail == null || detail.summary.id != id || appId == null || appId <= 0L) {
                            // Missing/not-found/unavailable/contradictory details never mean wrong app.
                            unresolved += id
                            continue
                        }
                        if (appId != 431960L) {
                            unresolved -= id
                            resolved.remove(id)
                            continue
                        }
                        try {
                            if (!verifyAccount(generation, name, steamId)) return@launch
                            val vote = accountWorkshop.getUserVote(id)
                            if (!verifyAccount(generation, name, steamId)) return@launch
                            check(vote in 0..2) { "Unknown vote result" }
                            if (vote == 0) resolved.remove(id) else resolved[id] = PersonalVoteItem(detail.summary, vote)
                            unresolved -= id
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (mismatch: VoteAccountMismatch) {
                            throw mismatch
                        } catch (_: Exception) {
                            if (!current(generation, name)) return@launch
                            unresolved += id
                        }
                        if (!current(generation, name)) return@launch
                        mutableState.value = mutableState.value.copy(items = resolved.values.toList())
                    }
                }
                if (!verifyAccount(generation, name, steamId)) return@launch
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!current(generation, name)) return@launch
                if (error is VoteAccountMismatch) {
                    failAccountMatch()
                    return@launch
                }
                unresolved += page.rows.map { it.fileId }
                problem = PersonalVotesProblem.READ
            }
            if (!current(generation, name)) return@launch
            mutableState.value = mutableState.value.copy(
                busy = false, readerVisible = false, hasNext = page.hasNext,
                exhausted = !page.hasNext, unresolved = unresolved,
                items = resolved.values.toList(), problem = problem,
            )
            publishCounts()
        }
    }

    private fun failAccountMatch() {
        resolved.clear()
        countsStore.clear()
        epoch++
        mutableState.value = PersonalVotesState(
            accountName = sessions.session.value.accountName,
            generation = epoch,
            started = true,
            problem = PersonalVotesProblem.ACCOUNT_MISMATCH,
        )
    }

    fun dismissReader() {
        work?.cancel()
        mutableState.value = mutableState.value.copy(
            readerMounted = false, busy = false, problem = PersonalVotesProblem.READ,
        )
        publishCounts()
    }

    private fun publishCounts() {
        val state = mutableState.value
        val steamId = state.steamId ?: return
        countsStore.publish(steamId, VoteCounts(
            up = state.items.count { it.vote == 1 },
            down = state.items.count { it.vote == 2 },
            complete = state.complete,
        ))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalVotesRoute(
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    onOpenLogin: () -> Unit,
    viewModel: PersonalVotesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.votes_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.votes_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.accountName == null) {
                Button(onClick = onOpenLogin) { Text(stringResource(R.string.votes_sign_in)) }
                return@Column
            }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth()) {
                listOf(1, 2).forEachIndexed { index, vote ->
                    SegmentedButton(
                        selected = state.selectedVote == vote,
                        onClick = { viewModel.selectVote(vote) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) {
                        Text(stringResource(if (vote == 1) R.string.votes_up else R.string.votes_down))
                    }
                }
            }
            Row(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val count = state.items.count { it.vote == state.selectedVote }
                Text(stringResource(
                    if (state.complete) R.string.votes_complete_count else R.string.votes_loaded_count,
                    count,
                ))
                TextButton(onClick = viewModel::sync, enabled = !state.busy) {
                    Text(stringResource(R.string.votes_sync))
                }
            }
            if (!state.started) Text(stringResource(R.string.votes_sync_hint))
            val problemLabel = when {
                state.problem == PersonalVotesProblem.ACCOUNT_MISMATCH -> R.string.votes_account_mismatch
                state.problem == PersonalVotesProblem.ACCOUNT_UNVERIFIED -> R.string.votes_account_unverified
                state.problem == PersonalVotesProblem.READ -> R.string.votes_read_failed
                state.unresolved.isNotEmpty() -> R.string.votes_incomplete
                else -> null
            }
            problemLabel?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error)
            }
            if (state.busy && !state.readerVisible) CircularProgressIndicator(Modifier.size(24.dp))
            if (state.readerMounted && state.steamId != null) {
                val readerGeneration = state.generation
                key(readerGeneration, state.steamId) {
                    VotesAuthorizationReader(
                        expectedSteamId = requireNotNull(state.steamId),
                        requestCounter = state.requestCounter,
                        labels = VotesReaderLabels(
                            authorization = stringResource(R.string.votes_authorization),
                            sync = stringResource(R.string.votes_sync),
                            dismiss = stringResource(R.string.votes_dismiss),
                        ),
                        onPage = { viewModel.onPage(readerGeneration, it) },
                        onError = { viewModel.onReaderError(readerGeneration, it) },
                        onDismiss = viewModel::dismissReader,
                        visible = state.readerVisible,
                        modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth()
                            .height(if (state.readerVisible) 320.dp else 0.dp),
                    )
                }
            }
            val visibleItems = state.items.filter { it.vote == state.selectedVote }
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.started && !state.readerVisible && !state.busy &&
                    state.problem == null && state.unresolved.isEmpty() && visibleItems.isEmpty()
                ) {
                    item { Text(stringResource(R.string.votes_empty)) }
                }
                items(visibleItems, key = { it.summary.id }) { item ->
                    Card(onClick = { onOpenDetail(item.summary.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            AsyncImage(
                                model = item.summary.previewUrl,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                item.summary.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (state.hasNext) {
                    item {
                        Button(onClick = viewModel::nextPage, enabled = !state.busy) {
                            Text(stringResource(R.string.votes_next_page))
                        }
                    }
                }
            }
        }
    }
}

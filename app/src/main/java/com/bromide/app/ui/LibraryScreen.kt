package com.bromide.app.ui

import androidx.compose.ui.platform.LocalResources
import com.bromide.app.categoryRes
import com.bromide.app.blurbRes
import com.bromide.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bromide.engine.Stock

/** Filter keys: "All", "Favorites", or a [Stock.category]. */
private val FILTERS = listOf("All", "Favorites", "Color", "B&W", "Slide", "Cinema")

/** All films: search, filter by type, favorite. */
@Composable
fun LibraryScreen(app: AppState, nav: (Route) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val looksVersion = app.looksVersion
    val res = LocalResources.current
    // English words always work ("portra", "b&w"), and so do the phone's language's.
    val searchText = remember(res) {
        Stock.ALL.associate { s ->
            s.id to (s.searchText() + " " + res.getString(s.blurbRes()) + " " +
                res.getString(categoryRes(s.category()))).lowercase()
        }
    }
    val shown = remember(query, filter, app.favorites) {
        val q = query.trim().lowercase()
        Stock.ALL.filter { s ->
            (q.isEmpty() || q.split(' ').all { it in searchText.getValue(s.id) }) &&
                when (filter) {
                    "All" -> true
                    "Favorites" -> s.id in app.favorites
                    else -> s.category() == filter
                }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
            Text(stringResource(R.string.films), style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.films_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.clear)) }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(FILTERS) { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(when (f) {
                        "All" -> stringResource(R.string.filter_all)
                        "Favorites" -> "♥ " + stringResource(R.string.filter_favorites)
                        else -> stringResource(categoryRes(f))
                    }) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = FB.SurfaceHigh, selectedLabelColor = FB.Accent)
                )
            }
        }
        if (shown.isEmpty()) {
            Text(
                stringResource(if (filter == "Favorites" && query.isEmpty()) R.string.films_no_favorites
                               else R.string.films_none),
                color = FB.Muted, modifier = Modifier.padding(24.dp)
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(shown, key = { it.id }) { stock ->
                FilmCard(
                    stock = stock,
                    look = remember(stock.id, looksVersion) { app.look(stock) },
                    source = app.previewSource,
                    selected = stock.id == app.stockId,
                    favorite = stock.id in app.favorites,
                    width = 200.dp,
                    onClick = { nav(Route.Film(stock.id)) },
                    onFavorite = { app.toggleFavorite(stock) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

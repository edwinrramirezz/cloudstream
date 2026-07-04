package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.charset.StandardCharsets

class ExampleProvider : MainAPI() { // All providers must be an instance of MainAPI
    override var mainUrl = "https://doramasflix.co"
    override var name = "Doramasflix"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override var lang = "es"

    override val hasMainPage = true

    companion object {
        private const val GRAPHQL_URL = "https://user-api.seriesapi.co/graphql"
        private val HEADERS = mapOf(
            "Origin" to "https://doramasflix.co",
            "Referer" to "https://doramasflix.co/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )

        fun decryptLink(jwtToken: String): String? {
            return try {
                val parts = jwtToken.split(".")
                if (parts.size < 2) return null
                val payloadB64 = parts[1]
                val payloadBytes = Base64.decode(payloadB64, Base64.DEFAULT)
                val payloadJson = String(payloadBytes, StandardCharsets.UTF_8)
                
                val linkRegex = """"link"\s*:\s*"([^"]+)"""".toRegex()
                val match = linkRegex.find(payloadJson) ?: return null
                val encLink = match.groupValues[1]
                val decBytes = Base64.decode(encLink, Base64.DEFAULT)
                String(decBytes, StandardCharsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val itemsList = mutableListOf<HomePageList>()

        if (page == 1) {
            // 1. Premiere Episodes
            try {
                val epQuery = """
                    query premiereEpisodes(${'$'}limit: Int!) {
                        premiereEpisodes(limit: ${'$'}limit) {
                            _id
                            name
                            slug
                            name_es
                            still_path
                            serie_backdrop_path
                        }
                    }
                """.trimIndent()
                
                val epRes = app.post(
                    GRAPHQL_URL,
                    headers = HEADERS,
                    json = GraphQLRequest(epQuery, mapOf("limit" to 15))
                ).parsedSafe<GraphQLResponse<PremiereEpisodesData>>()
                
                val epItems = epRes?.data?.premiereEpisodes?.map { ep ->
                    val poster = (ep.still_path ?: ep.serie_backdrop_path)?.let { "https://image.tmdb.org/t/p/w500$it" }
                    val doramaSlug = ep.slug.substringBeforeLast("-")
                    newTvSeriesSearchResponse(
                        ep.name_es ?: ep.name,
                        "$mainUrl/doramas/$doramaSlug",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                    }
                }
                if (!epItems.isNullOrEmpty()) {
                    itemsList.add(HomePageList("Últimos Episodios", epItems))
                }
            } catch (e: Exception) {
                // Ignore row failure
            }

            // 2. Trends Doramas
            try {
                val doramaQuery = """
                    query TrendsDoramas(${'$'}limit: Int) {
                        trendsDoramas(limit: ${'$'}limit) {
                            _id
                            name
                            slug
                            name_es
                            poster_path
                        }
                    }
                """.trimIndent()
                
                val doramaRes = app.post(
                    GRAPHQL_URL,
                    headers = HEADERS,
                    json = GraphQLRequest(doramaQuery, mapOf("limit" to 15))
                ).parsedSafe<GraphQLResponse<TrendsDoramasData>>()
                
                val doramaItems = doramaRes?.data?.trendsDoramas?.map { dorama ->
                    val poster = dorama.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                    newTvSeriesSearchResponse(
                        dorama.name_es ?: dorama.name,
                        "$mainUrl/doramas/${dorama.slug}",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                    }
                }
                if (!doramaItems.isNullOrEmpty()) {
                    itemsList.add(HomePageList("Doramas en Tendencia", doramaItems))
                }
            } catch (e: Exception) {
                // Ignore
            }

            // 3. Trends Movies
            try {
                val movieQuery = """
                    query TrendsMovies(${'$'}limit: Int) {
                        trendsMovies(limit: ${'$'}limit) {
                            _id
                            slug
                            name
                            name_es
                            poster_path
                        }
                    }
                """.trimIndent()
                
                val movieRes = app.post(
                    GRAPHQL_URL,
                    headers = HEADERS,
                    json = GraphQLRequest(movieQuery, mapOf("limit" to 15))
                ).parsedSafe<GraphQLResponse<TrendsMoviesData>>()
                
                val movieItems = movieRes?.data?.trendsMovies?.map { movie ->
                    val poster = movie.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                    newMovieSearchResponse(
                        movie.name_es ?: movie.name,
                        "$mainUrl/peliculas/${movie.slug}",
                        TvType.Movie
                    ) {
                        this.posterUrl = poster
                    }
                }
                if (!movieItems.isNullOrEmpty()) {
                    itemsList.add(HomePageList("Películas en Tendencia", movieItems))
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        if (itemsList.isEmpty()) return null
        return newHomePageResponse(itemsList, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        try {
            val doramaResponse = app.post(
                GRAPHQL_URL,
                headers = HEADERS,
                json = GraphQLRequest(
                    query = """
                        query SearchFullDoramas(${'$'}input: String!, ${'$'}limit: Int, ${'$'}page: Int) {
                            searchFullDoramas(input: ${'$'}input, perPage: ${'$'}limit, page: ${'$'}page) {
                                items {
                                    _id
                                    name
                                    name_es
                                    slug
                                    poster_path
                                }
                            }
                        }
                    """.trimIndent(),
                    variables = mapOf("input" to query, "limit" to 20, "page" to 1)
                )
            ).parsedSafe<GraphQLResponse<SearchDoramasData>>()

            doramaResponse?.data?.searchFullDoramas?.items?.forEach { dorama ->
                val poster = dorama.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                results.add(
                    newTvSeriesSearchResponse(
                        dorama.name_es ?: dorama.name,
                        "$mainUrl/doramas/${dorama.slug}",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster
                    }
                )
            }
        } catch (e: Exception) {
            // Ignore
        }

        try {
            val movieResponse = app.post(
                GRAPHQL_URL,
                headers = HEADERS,
                json = GraphQLRequest(
                    query = """
                        query SearchFullMovies(${'$'}input: String!, ${'$'}limit: Int, ${'$'}page: Int) {
                            searchFullMovies(input: ${'$'}input, perPage: ${'$'}limit, page: ${'$'}page) {
                                items {
                                    _id
                                    name
                                    name_es
                                    slug
                                    poster_path
                                }
                            }
                        }
                    """.trimIndent(),
                    variables = mapOf("input" to query, "limit" to 20, "page" to 1)
                )
            ).parsedSafe<GraphQLResponse<SearchMoviesData>>()

            movieResponse?.data?.searchFullMovies?.items?.forEach { movie ->
                val poster = movie.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
                results.add(
                    newMovieSearchResponse(
                        movie.name_es ?: movie.name,
                        "$mainUrl/peliculas/${movie.slug}",
                        TvType.Movie
                    ) {
                        this.posterUrl = poster
                    }
                )
            }
        } catch (e: Exception) {
            // Ignore
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val isMovie = url.contains("/peliculas/")
        val slug = url.substringAfterLast("/")

        if (isMovie) {
            val movieQuery = """
                query DetailMovieSlug(${'$'}slug: String!) {
                    detailMovie(filter: { slug: ${'$'}slug }) {
                        _id
                        name
                        slug
                        name_es
                        overview
                        poster_path
                    }
                }
            """.trimIndent()

            val response = app.post(
                GRAPHQL_URL,
                headers = HEADERS,
                json = GraphQLRequest(movieQuery, mapOf("slug" to slug))
            ).parsedSafe<GraphQLResponse<DetailMovieData>>() ?: return null

            val movie = response.data?.detailMovie ?: return null
            val poster = movie.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            val title = movie.name_es ?: movie.name

            return newMovieLoadResponse(title, url, TvType.Movie, "movie:${movie._id}") {
                this.posterUrl = poster
                this.plot = movie.overview
            }
        } else {
            val doramaQuery = """
                query DetailDoramaSlug(${'$'}slug: String!) {
                    detailDorama(filter: { slug: ${'$'}slug }) {
                        _id
                        name
                        slug
                        name_es
                        overview
                        poster_path
                        seasons {
                            _id
                            season_number
                            number_of_episodes
                        }
                    }
                }
            """.trimIndent()

            val response = app.post(
                GRAPHQL_URL,
                headers = HEADERS,
                json = GraphQLRequest(doramaQuery, mapOf("slug" to slug))
            ).parsedSafe<GraphQLResponse<DetailDoramaData>>() ?: return null

            val dorama = response.data?.detailDorama ?: return null
            val poster = dorama.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            val title = dorama.name_es ?: dorama.name

            val episodesList = mutableListOf<Episode>()
            dorama.seasons?.forEach { season ->
                val episodesQuery = """
                    query EpisodesPagination(${'$'}page: Int!, ${'$'}serie_id: ID!, ${'$'}season_number: Int!, ${'$'}limit: Int!) {
                        paginationEpisode(page: ${'$'}page, limit: ${'$'}limit, filter: { serie_id: ${'$'}serie_id, season_number: ${'$'}season_number }) {
                            items {
                                _id
                                slug
                                name
                                name_es
                                episode_number
                                season_number
                            }
                        }
                    }
                """.trimIndent()

                val epResponse = app.post(
                    GRAPHQL_URL,
                    headers = HEADERS,
                    json = GraphQLRequest(
                        episodesQuery,
                        mapOf(
                            "page" to 1,
                            "limit" to 100,
                            "serie_id" to dorama._id,
                            "season_number" to season.season_number
                        )
                    )
                ).parsedSafe<GraphQLResponse<EpisodesPaginationData>>()

                epResponse?.data?.paginationEpisode?.items?.forEach { ep ->
                    episodesList.add(
                        Episode(
                            data = "episode:${ep._id}",
                            name = ep.name_es ?: ep.name,
                            episode = ep.episode_number,
                            season = ep.season_number
                        )
                    )
                }
            }

            episodesList.sortWith(compareBy<Episode> { it.season }.thenBy { it.episode })

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.plot = dorama.overview
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isMovie = data.startsWith("movie:")
        val id = data.substringAfter(":")

        val query = if (isMovie) {
            """
                query MovieLinks(${'$'}movie_id: ID!) {
                    getMovieLinks(id: ${'$'}movie_id, app: "com.playgo.doramasgo") {
                        links_online {
                            server
                            lang
                            link
                        }
                    }
                }
            """.trimIndent()
        } else {
            """
                query EpisodeLinksOnline(${'$'}episode_id: ID!) {
                    getEpisodeLinks(id: ${'$'}episode_id, app: "com.playgo.doramasgo") {
                        links_online {
                            server
                            lang
                            link
                        }
                    }
                }
            """.trimIndent()
        }

        val variables = if (isMovie) mapOf("movie_id" to id) else mapOf("episode_id" to id)

        val response = if (isMovie) {
            app.post(
                GRAPHQL_URL,
                headers = HEADERS,
                json = GraphQLRequest(query, variables)
            ).parsedSafe<GraphQLResponse<MovieLinksData>>()?.data?.getMovieLinks?.links_online
        } else {
            app.post(
                GRAPHQL_URL,
                headers = HEADERS,
                json = GraphQLRequest(query, variables)
            ).parsedSafe<GraphQLResponse<EpisodeLinksData>>()?.data?.getEpisodeLinks?.links_online
        }

        response?.forEach { onlineLink ->
            if (onlineLink.link.startsWith("https://embedshortener.co/e/")) {
                val jwtToken = onlineLink.link.substringAfter("/e/")
                val decUrl = decryptLink(jwtToken)
                if (decUrl != null) {
                    loadExtractor(decUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }

        return true
    }
}

// ==========================================
// GraphQL Data Classes
// ==========================================

data class GraphQLRequest(
    @JsonProperty("query") val query: String,
    @JsonProperty("variables") val variables: Map<String, Any?> = emptyMap()
)

data class GraphQLResponse<T>(
    @JsonProperty("data") val data: T?
)

data class DetailDoramaData(
    @JsonProperty("detailDorama") val detailDorama: DoramaDetail?
)

data class DetailMovieData(
    @JsonProperty("detailMovie") val detailMovie: MovieDetail?
)

data class EpisodesPaginationData(
    @JsonProperty("paginationEpisode") val paginationEpisode: EpisodePaginationItems?
)

data class EpisodePaginationItems(
    @JsonProperty("items") val items: List<EpisodeItem>?
)

data class SearchDoramasData(
    @JsonProperty("searchFullDoramas") val searchFullDoramas: DoramaSearchItems?
)

data class DoramaSearchItems(
    @JsonProperty("items") val items: List<DoramaSearchItem>?
)

data class SearchMoviesData(
    @JsonProperty("searchFullMovies") val searchFullMovies: MovieSearchItems?
)

data class MovieSearchItems(
    @JsonProperty("items") val items: List<MovieSearchItem>?
)

data class EpisodeLinksData(
    @JsonProperty("getEpisodeLinks") val getEpisodeLinks: LinksContainer?
)

data class MovieLinksData(
    @JsonProperty("getMovieLinks") val getMovieLinks: LinksContainer?
)

data class PremiereEpisodesData(
    @JsonProperty("premiereEpisodes") val premiereEpisodes: List<PremiereEpisode>?
)

data class TrendsDoramasData(
    @JsonProperty("trendsDoramas") val trendsDoramas: List<TrendDorama>?
)

data class TrendsMoviesData(
    @JsonProperty("trendsMovies") val trendsMovies: List<TrendMovie>?
)
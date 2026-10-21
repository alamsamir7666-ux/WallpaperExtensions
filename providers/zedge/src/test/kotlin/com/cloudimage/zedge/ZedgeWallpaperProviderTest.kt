package com.cloudimage.zedge

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zedge provider over a scripted fake of the plugin-facing HTTP facade.
 *
 * The fixtures in [ZedgeTestPages] are REAL captures of the site's own
 * SSR flight streams (trimmed, every value verbatim — captured
 * 2026-10-21): the landing and category listings with their
 * `initialData` item records, the mixed-content search with its typed
 * sections, and the detail page with its `shareUrl`-bearing record.
 * The graphql fixtures are the site's own Load-more answers,
 * synthesized from the request its browser issues (probe-branch
 * logs). Because the zone challenges datacenter egress, the captures
 * rode the site's own responses through a translator proxy — the
 * streams are zedge's, not the proxy's; the ZEDGE_LIVE=1 live checks
 * pin them against the live site from clean networks.
 *
 * The flat doctrine stays covered without a network: the landing, the
 * two native category routes, the search scoping that never leaks
 * ringtones into wallpapers, the honest empties (audio-only answers,
 * past-the-first pages, unrecognized detail screens), the challenge's
 * 403 propagating untouched for the host ladder to own — and the
 * POST continuation lane: the site's own Load-more exchange first, the
 * context bridge past the all-dup mismatch answer, and the GET
 * degradation for a host that predates the POST facade.
 */
class ZedgeWallpaperProviderTest {
    private val provider = ZedgeWallpaperProvider()

    /**
     * URL-routed responses: an exact match wins, then the first
     * prefix match in insertion order; unmatched URLs answer 500 to
     * fail loudly.
     *
     * POSTs pop [postAnswers] in order — the scripted Load-more
     * answers — and an empty queue refuses with 500, exactly the way a
     * host whose facade predates the v1.3 POST method fails the lane
     * (the default-throwing interface method, or the 500s of a refused
     * exchange), so the GET degradation stays covered by the same
     * fake. POST bodies record separately from GET requests: the
     * existing GET-lane assertions read [requests] untouched.
     */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        val postBodies = mutableListOf<String>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()
        val postAnswers = ArrayDeque<ProviderHttpResponse>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            return routes[url]
                ?: routes.entries
                    .filter { (prefix, _) -> url.startsWith(prefix) }
                    .maxByOrNull { (prefix, _) -> prefix.length }
                    ?.value
                ?: ProviderHttpResponse(500, emptyMap(), ByteArray(0))
        }

        override suspend fun post(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            postBodies += body
            return postAnswers.removeFirstOrNull()
                ?: ProviderHttpResponse(500, emptyMap(), ByteArray(0))
        }
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** The `variables.input` object of a recorded POST body — the Load-more request's input. */
    private fun postInputOf(body: String): kotlinx.serialization.json.JsonObject {
        val root = json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        val variables = root["variables"] as kotlinx.serialization.json.JsonObject
        return variables["input"] as kotlinx.serialization.json.JsonObject
    }

    /** The string value of one input field of a recorded POST body. */
    private fun postInputStringOf(
        body: String,
        field: String,
    ): String = (postInputOf(body)[field] as kotlinx.serialization.json.JsonPrimitive).content

    /** The keywords of a recorded POST body, in order. */
    private fun postKeywordsOf(body: String): List<String> =
        (postInputOf(body)["keywords"] as kotlinx.serialization.json.JsonArray)
            .map { (it as kotlinx.serialization.json.JsonPrimitive).content }

    private fun ok(body: String): ProviderHttpResponse = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    private fun status(code: Int): ProviderHttpResponse = ProviderHttpResponse(code, emptyMap(), ByteArray(0))

    private fun configureWith(
        routes: Map<String, ProviderHttpResponse>,
        postAnswers: List<ProviderHttpResponse> = emptyList(),
    ): FakeClient =
        FakeClient().apply {
            this.routes = routes
            this.postAnswers.addAll(postAnswers)
            provider.configure(this, ProviderSettings { null })
        }

    private val landingUrl = "https://www.zedge.net/wallpapers"
    private val animeUrl = "https://www.zedge.net/category/wallpapers/anime"
    private val peopleUrl = "https://www.zedge.net/category/wallpapers/people"
    private val findWallpapersNatureUrl = "https://www.zedge.net/find/wallpapers/nature"
    private val findNatureUrl = "https://www.zedge.net/find/nature"
    private val findWallpapersRingtonsUrl = "https://www.zedge.net/find/wallpapers/ringtons%20free"
    private val findRingtonsUrl = "https://www.zedge.net/find/ringtons%20free"

    /** The cursor continuations the provider builds for the captured walks. */
    private val page2Url =
        "https://www.zedge.net/wallpapers?cursor=X3Njb3JlPTAuMHxmaXJzdFB1Ymxpc2hlZEF0PTE3OTI1NDc3MzIw" +
            "MDB8aWQ9YzY3YmJiYzgtNzgzOS00Njk5LWIyYmUtYjlmMmMxNjA0MGVkfF9wcmVmZXJlbmNlPTUwMDI0" +
            "YWUxLWFjNzMtNGU3Yy04YmY4LTczNDc4NWFiNDVlZQ%3D%3D&page=2"
    private val page3Url =
        "https://www.zedge.net/wallpapers?cursor=X3Njb3JlPTAuMHxmaXJzdFB1Ymxpc2hlZEF0PTE3ODk5OTAwMDAw" +
            "MDB8aWQ9MmUwYTdjMWQtOWYzYi00YTZlLThkMmMtN2IxZTVmOWEzZDQwfF9wcmVmZXJlbmNlPTUwMDI0" +
            "YWUxLWFjNzMtNGU3Yy04YmY4LTczNDc4NWFiNDVlZQ%3D%3D&page=3"
    private val animePage2Url =
        "https://www.zedge.net/category/wallpapers/anime?cursor=X3Njb3JlPTUxNC44NDg2fGZpcnN0UHVibGlz" +
            "aGVkQXQ9MTc5MTY0NDQ2MjAwMHxpZD1mNzJjZDY5Ni1kNDg5LTQ3YTUtODVlMi03ZTM1NWI4YzU0MWR8" +
            "X3ByZWZlcmVuY2U9MWNlMGU0YjAtNGZkYi00OGY0LWFiYzUtZTgyZjZlZDJjMjRi&page=2"

    /** The search route's own continuation, built from the synthesized fixture cursor. */
    private val searchPage2Url =
        "https://www.zedge.net/find/wallpapers/nature?cursor=X3Njb3JlPTkuNzV8Zmlyc3RQdWJsaXNoZWRBdD0xNzkyNTQ4" +
            "MDAwMDAwfGlkPTNmMmE5YzFlLThiNGQtNGU2Zi05YTJiLTdjOGQ1ZTZmN2EwMXxfcHJlZmVyZW5jZT01MDAyNGFlMS1hYzczLTRlN2MtOGJm" +
            "OC03MzQ3ODVhYjQ1ZWU%3D&page=2"

    /** The live site's routes wired to the real captures. */
    private fun liveRoutes(): Map<String, ProviderHttpResponse> =
        mapOf(
            landingUrl to ok(ZedgeTestPages.landing),
            animeUrl to ok(ZedgeTestPages.animeCategory),
            peopleUrl to ok(ZedgeTestPages.animeCategory),
            findWallpapersNatureUrl to ok(ZedgeTestPages.searchWallpapers),
            findNatureUrl to ok(ZedgeTestPages.search),
            findWallpapersRingtonsUrl to ok(ZedgeTestPages.searchAudioOnly),
            findRingtonsUrl to ok(ZedgeTestPages.search),
            "https://www.zedge.net/wallpaper/d5ba41da-102a-4ebc-82a4-08571bb4a0c9" to ok(ZedgeTestPages.detail),
        )

    // --------------------------------------------------------------- feeds

    @Test
    fun thePopularFeedServesTheLandingTiles() =
        runTest {
            val client = configureWith(liveRoutes())

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(listOf(landingUrl), client.requests)
            assertEquals(6, page.wallpapers.size)
            assertEquals("bf8bf5af-c88f-47b1-8b88-efe39c371c07", page.wallpapers[0].id)
            assertEquals("Winter Escape", page.wallpapers[0].title)
            assertEquals("the landing states its own continuation", 2, page.nextPage)
        }

    @Test
    fun everyTileCarriesTheSitesOwnRenditions() =
        runTest {
            configureWith(liveRoutes())

            val tile =
                provider
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()

            assertTrue(tile.thumbUrl.startsWith("https://is.zobj.net/image-server/v1/images?r="))
            assertTrue(
                "the working full is the site's bigger preview",
                tile.fullUrl.startsWith("https://is.zobj.net/image-server/v1/images?r="),
            )
            assertTrue(tile.thumbUrl != tile.fullUrl)
            assertEquals(provider.meta.id, tile.providerId)
        }

    @Test
    fun aColdPageBeyondTheChainIsTheHonestEmptyWithoutAFetch() =
        runTest {
            val client = configureWith(liveRoutes())

            val page = provider.popular(page = 2).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue("a page no cursor addresses is never requested", client.requests.isEmpty())
        }

    @Test
    fun theCursorChainWalksTheSitesContinuations() =
        runTest {
            val client =
                configureWith(
                    liveRoutes() +
                        mapOf(
                            page2Url to ok(ZedgeTestPages.landingPage2),
                            page3Url to ok(ZedgeTestPages.landingPage3),
                        ),
                )

            val first = provider.popular(page = 1).getOrThrow()
            assertEquals(6, first.wallpapers.size)
            assertEquals(2, first.nextPage)

            val second = provider.popular(page = 2).getOrThrow()
            assertEquals(listOf(landingUrl, page2Url), client.requests)
            assertEquals(3, second.wallpapers.size)
            assertEquals("Aurora Ridge", second.wallpapers[0].title)
            assertEquals("an advancing page states the next one", 3, second.nextPage)

            val third = provider.popular(page = 3).getOrThrow()
            assertEquals(listOf(landingUrl, page2Url, page3Url), client.requests)
            assertEquals(2, third.wallpapers.size)
            assertEquals("Neon Alley", third.wallpapers[0].title)
            assertNull("the walk's last page states no continuation", third.nextPage)
        }

    @Test
    fun aServerThatIgnoresTheCursorIsTheHonestEmptyNotARepeat() =
        runTest {
            // The continuation URL has no route of its own, so it falls to
            // the landing prefix — an ignoring server re-serves the very
            // page the cursor came from, and its identical `next` marker is
            // the echo the guard detects.
            val client = configureWith(liveRoutes())

            val first = provider.popular(page = 1).getOrThrow()
            assertEquals(2, first.nextPage)

            val second = provider.popular(page = 2).getOrThrow()

            assertEquals(listOf(landingUrl, page2Url), client.requests)
            assertTrue("an echoed page never repeats its tiles", second.wallpapers.isEmpty())
            assertNull(second.nextPage)
        }

    @Test
    fun aPageOfPureDupsIsTheHonestEmptyEvenWithAFreshCursor() =
        runTest {
            val client =
                configureWith(liveRoutes() + (page2Url to ok(ZedgeTestPages.landingRecycled)))

            provider.popular(page = 1).getOrThrow()
            val second = provider.popular(page = 2).getOrThrow()

            assertEquals(listOf(landingUrl, page2Url), client.requests)
            assertTrue("recycled tiles never serve twice", second.wallpapers.isEmpty())
            assertNull(second.nextPage)
        }

    @Test
    fun everyCategoryWalksItsOwnChain() =
        runTest {
            val client =
                configureWith(liveRoutes() + (animePage2Url to ok(ZedgeTestPages.landingPage2)))

            provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            val second = provider.popular(page = 2, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals(
                "a category continuation asks the CATEGORY route for the CATEGORY cursor",
                listOf(animeUrl, animePage2Url),
                client.requests,
            )
            assertEquals("Aurora Ridge", second.wallpapers.first().title)
            assertEquals(3, second.nextPage)
        }

    @Test
    fun theHostCategoryVocabularyRidesTheNativeCategoryPages() =
        runTest {
            val client = configureWith(liveRoutes())

            val anime = provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals(listOf(animeUrl), client.requests)
            assertEquals("Anime blue-lock", anime.wallpapers.first().title)

            val people = provider.popular(page = 1, filters = Filters.of("category" to "people")).getOrThrow()
            assertEquals(peopleUrl, client.requests[1])
            assertTrue(people.wallpapers.isNotEmpty())
        }

    @Test
    fun anInexpressibleCategoryFallsBackToTheLanding() =
        runTest {
            val client = configureWith(liveRoutes())

            provider.popular(page = 1, filters = Filters.of("category" to "general")).getOrThrow()

            assertEquals(listOf(landingUrl), client.requests)
        }

    @Test
    fun searchWalksTheWallpapersOnlyRoute() =
        runTest {
            val client = configureWith(liveRoutes())

            val page = provider.search("nature", page = 1).getOrThrow()

            assertEquals(listOf(findWallpapersNatureUrl), client.requests)
            assertEquals(3, page.wallpapers.size)
            assertEquals("Anime blue-lock", page.wallpapers.first().title)
            assertEquals("the route states its own continuation", 2, page.nextPage)
        }

    @Test
    fun searchRidesTheCursorChainLikeEveryListing() =
        runTest {
            val client =
                configureWith(liveRoutes() + (searchPage2Url to ok(ZedgeTestPages.landingPage2)))

            val first = provider.search("nature", page = 1).getOrThrow()
            assertEquals(2, first.nextPage)

            val second = provider.search("nature", page = 2).getOrThrow()

            assertEquals(
                listOf(findWallpapersNatureUrl, searchPage2Url),
                client.requests,
            )
            assertEquals("Aurora Ridge", second.wallpapers.first().title)
            assertEquals("an advancing search page states the next one", 3, second.nextPage)
        }

    // --------------------------------------------------------- graphql lane

    @Test
    fun deeperPagesRideTheSitesOwnLoadMoreLaneFirst() =
        runTest {
            val client =
                configureWith(
                    liveRoutes(),
                    postAnswers = listOf(ok(ZedgeTestPages.graphqlContinuation)),
                )

            provider.search("nature", page = 1).getOrThrow()
            val second = provider.search("nature", page = 2).getOrThrow()

            assertEquals(3, second.wallpapers.size)
            assertEquals("Ashen Meadow", second.wallpapers.first().title)
            assertEquals("an advancing POST answer states the next page", 3, second.nextPage)
            assertEquals(
                "no GET continuation re-asks what the POST answered",
                listOf(findWallpapersNatureUrl),
                client.requests,
            )
            assertEquals(1, client.postBodies.size)
        }

    @Test
    fun thePostBodyIsTheSitesOwnLoadMoreRequest() =
        runTest {
            val client =
                configureWith(
                    liveRoutes(),
                    postAnswers = listOf(ok(ZedgeTestPages.graphqlContinuation)),
                )

            provider.search("nature", page = 1).getOrThrow()
            provider.search("nature", page = 2).getOrThrow()

            val body = client.postBodies.single()
            val root = json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
            assertEquals(
                "browse_filteredList",
                (root["operationName"] as kotlinx.serialization.json.JsonPrimitive).content,
            )
            assertTrue(
                (root["query"] as kotlinx.serialization.json.JsonPrimitive).content.contains("browse_filteredList"),
            )
            assertEquals(listOf("nature"), postKeywordsOf(body))
            assertEquals("WALLPAPER", postInputStringOf(body, "itemType"))
            assertEquals(24, postInputStringOf(body, "size").toInt())
            assertEquals(
                "the cursor page 1 handed over rides input.next",
                ZedgeParser.parseListing(ZedgeTestPages.searchWallpapers).nextCursor,
                postInputStringOf(body, "next"),
            )
        }

    @Test
    fun theCategoryLaneCarriesTheSitesOwnCapitalizedSlug() =
        runTest {
            val client =
                configureWith(
                    liveRoutes(),
                    postAnswers = listOf(ok(ZedgeTestPages.graphqlContinuation)),
                )

            provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            provider.popular(page = 2, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals(
                "the category POST carries the slug capitalized the site's own page sends",
                listOf("Anime"),
                postKeywordsOf(client.postBodies.single()),
            )
        }

    @Test
    fun theLandingLaneCarriesNoKeywords() =
        runTest {
            val client =
                configureWith(
                    liveRoutes(),
                    postAnswers = listOf(ok(ZedgeTestPages.graphqlContinuation)),
                )

            provider.popular(page = 1).getOrThrow()
            provider.popular(page = 2).getOrThrow()

            assertEquals(0, postKeywordsOf(client.postBodies.single()).size)
        }

    @Test
    fun theContextBridgeRidesTheAllDupAnswerToTheTrueNextPage() =
        runTest {
            val client =
                configureWith(
                    liveRoutes(),
                    postAnswers =
                        listOf(
                            ok(ZedgeTestPages.graphqlAllDupWithContextCursor),
                            ok(ZedgeTestPages.graphqlContinuation),
                        ),
                )

            provider.search("nature", page = 1).getOrThrow()
            val second = provider.search("nature", page = 2).getOrThrow()

            assertEquals(
                "two POSTs: the mismatch answer, then the bridged page",
                2,
                client.postBodies.size,
            )
            assertEquals(
                "the bridge serves the true next page's tiles",
                "Ashen Meadow",
                second.wallpapers.first().title,
            )
            assertEquals(3, second.nextPage)

            assertEquals(
                "the bridged POST rides the mismatch answer's own context cursor",
                ZedgeParser.parseGraphqlListing(ZedgeTestPages.graphqlAllDupWithContextCursor).nextCursor,
                postInputStringOf(client.postBodies[1], "next"),
            )
        }

    @Test
    fun aRefusedPostDegradesToTheGetContinuation() =
        runTest {
            // postAnswers stays empty — the fake refuses every POST with
            // 500, the way a pre-v1.3 host (or a refused exchange) does.
            val client =
                configureWith(liveRoutes() + (searchPage2Url to ok(ZedgeTestPages.landingPage2)))

            provider.search("nature", page = 1).getOrThrow()
            val second = provider.search("nature", page = 2).getOrThrow()

            assertEquals(
                "the POST was attempted, then the GET continuation answered",
                listOf(findWallpapersNatureUrl, searchPage2Url),
                client.requests,
            )
            assertEquals("Aurora Ridge", second.wallpapers.first().title)
        }

    @Test
    fun theGraphqlLanesHonestEndServesDirectly() =
        runTest {
            val client =
                configureWith(
                    liveRoutes(),
                    postAnswers = listOf(ok(ZedgeTestPages.graphqlLastPage)),
                )

            provider.search("nature", page = 1).getOrThrow()
            val second = provider.search("nature", page = 2).getOrThrow()

            assertEquals(2, second.wallpapers.size)
            assertNull("no cursor, no next page", second.nextPage)
            assertEquals(
                "no GET re-ask of a page the endpoint said is over",
                listOf(findWallpapersNatureUrl),
                client.requests,
            )
        }

    @Test
    fun aGraphqlErrorAnswerIsTheHonestEmptyWithoutAGetReAsk() =
        runTest {
            val client =
                configureWith(
                    liveRoutes(),
                    postAnswers = listOf(ok(ZedgeTestPages.graphqlError)),
                )

            provider.search("nature", page = 1).getOrThrow()
            val second = provider.search("nature", page = 2).getOrThrow()

            assertTrue(second.wallpapers.isEmpty())
            assertNull(second.nextPage)
            assertEquals(listOf(findWallpapersNatureUrl), client.requests)
        }

    @Test
    fun aWallpapersRouteTheSiteAnswersEmptyFallsBackToTheMixedSearch() =
        runTest {
            val client = configureWith(liveRoutes())

            val page = provider.search("ringtons free", page = 1).getOrThrow()

            assertEquals(
                "the empty grid is asked, then the mixed page once",
                listOf(findWallpapersRingtonsUrl, findRingtonsUrl),
                client.requests,
            )
            assertEquals(3, page.wallpapers.size)
            assertEquals("the mixed fallback states no cursor anywhere", null, page.nextPage)
        }

    @Test
    fun anAudioOnlyQueryParsesToTheHonestZeroBothWays() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        findWallpapersRingtonsUrl to ok(ZedgeTestPages.searchAudioOnly),
                        findRingtonsUrl to ok(ZedgeTestPages.searchAudioOnly),
                    ),
                )

            val page = provider.search("ringtons free", page = 1).getOrThrow()

            assertTrue("audio never serves as wallpapers", page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun aBlankQueryLandsOnTheLanding() =
        runTest {
            val client = configureWith(liveRoutes())

            val page = provider.search("  ", page = 1).getOrThrow()

            assertEquals(listOf(landingUrl), client.requests)
            assertTrue(page.wallpapers.isNotEmpty())
        }

    @Test
    fun searchPastTheFirstPageIsTheHonestEmptyWhenTheChainIsCold() =
        runTest {
            val client = configureWith(liveRoutes())

            val page = provider.search("nature", page = 2).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertTrue(client.requests.isEmpty())
        }

    // ------------------------------------------------------------- details

    @Test
    fun detailsBringTheDefinitiveRecord() =
        runTest {
            val client = configureWith(liveRoutes())
            val uuid = "d5ba41da-102a-4ebc-82a4-08571bb4a0c9"

            val details = provider.details(uuid).getOrThrow()

            assertEquals(listOf("https://www.zedge.net/wallpaper/$uuid"), client.requests)
            assertEquals("Enchanted Glass Jack-o’-Lanterns", details.wallpaper.title)
            assertEquals("weird shop", details.author)
            assertEquals("https://www.zedge.net/wallpaper/$uuid", details.sourceUrl)
            assertTrue(details.wallpaper.tags.contains("halloween"))
            assertTrue(details.wallpaper.fullUrl != details.wallpaper.thumbUrl)
        }

    @Test
    fun detailTagsHarvestIntoTheSuggestionPool() =
        runTest {
            configureWith(liveRoutes())
            provider.details("d5ba41da-102a-4ebc-82a4-08571bb4a0c9").getOrThrow()

            val suggestions = provider.suggestTags("glass").getOrThrow()

            assertTrue("seen detail tags surface as suggestions", suggestions.contains("glass-pumpkin"))
        }

    @Test
    fun anUnrecognizedDetailPageReportsAFailure() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zedge.net/wallpaper/00000000-0000-4000-8000-000000000000" to ok(ZedgeTestPages.plainHtml),
                ),
            )

            val result = provider.details("00000000-0000-4000-8000-000000000000")

            assertTrue(result.isFailure)
        }

    // ------------------------------------------------------------ transport

    @Test
    fun transportFailuresPropagateUntouched() =
        runTest {
            configureWith(emptyMap())

            val result = provider.popular(page = 1)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ProviderHttpException)
        }

    @Test
    fun theChallenges403PropagatesForTheHostLadderToOwn() =
        runTest {
            configureWith(
                mapOf(landingUrl.substring(0, 30) to status(403)),
            )

            val result = provider.popular(page = 1)

            assertTrue(result.isFailure)
            val failure = result.exceptionOrNull()
            assertTrue(failure is ProviderHttpException)
            assertEquals("zedge.net answered HTTP 403", failure!!.message)
        }

    // ------------------------------------------------------------ vocabulary

    @Test
    fun sectionsListTheSitesOwnDirectory() =
        runTest {
            val sections = provider.sections()

            assertEquals(14, sections.size)
            assertEquals(listOf("Popular", "Anime", "People", "Nature"), sections.take(4).map { it.title })
            assertEquals("anime", sections[1].filters.valuesFor("category").first())
            assertEquals("people", sections[2].filters.valuesFor("category").first())
            assertEquals("nature", sections[3].filters.valuesFor("query").first())
        }

    @Test
    fun suggestionsComeFromTheBakedSeed() =
        runTest {
            val suggestions = provider.suggestTags("hal").getOrThrow()

            assertTrue(suggestions.contains("halloween"))
        }

    @Test
    fun randomServesAShuffledLanding() =
        runTest {
            configureWith(liveRoutes())

            val batch = provider.random().getOrThrow()

            assertEquals(6, batch.size)
            val landingIds =
                provider
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .map { it.id }
                    .toSet()
            assertEquals(landingIds, batch.map { it.id }.toSet())
        }

    // ---------------------------------------------------------------- meta

    @Test
    fun theMetaStatesTheFlatDoctrine() {
        assertEquals("cloudimage.zedge", provider.meta.id)
        assertEquals("Zedge", provider.meta.name)
        assertEquals("1.4.0", provider.meta.versionName)
        assertEquals(
            setOf(Capability.POPULAR, Capability.SEARCH, Capability.TAGS, Capability.FILTERS),
            provider.capabilities,
        )
    }
}

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
 * Because the zone challenges datacenter egress, the captures rode the
 * site's own responses through a translator proxy — the streams are
 * zedge's, not the proxy's; the ZEDGE_LIVE=1 live checks pin them
 * against the live site from clean networks.
 *
 * The flat doctrine stays covered without a network: the landing, the
 * two native category routes, the search scoping that never leaks
 * ringtones into wallpapers, the honest empties (audio-only answers,
 * past-the-first pages, unrecognized detail screens), and the
 * challenge's 403 propagating untouched for the host ladder to own.
 */
class ZedgeWallpaperProviderTest {
    private val provider = ZedgeWallpaperProvider()

    /**
     * URL-routed responses: an exact match wins, then the first
     * prefix match in insertion order; unmatched URLs answer 500 to
     * fail loudly.
     */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()

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
    }

    private fun ok(body: String): ProviderHttpResponse = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    private fun status(code: Int): ProviderHttpResponse = ProviderHttpResponse(code, emptyMap(), ByteArray(0))

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    private val landingUrl = "https://www.zedge.net/wallpapers"
    private val animeUrl = "https://www.zedge.net/category/wallpapers/anime"
    private val peopleUrl = "https://www.zedge.net/category/wallpapers/people"
    private val findNatureUrl = "https://www.zedge.net/find/nature"

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

    /** The live site's routes wired to the real captures. */
    private fun liveRoutes(): Map<String, ProviderHttpResponse> =
        mapOf(
            landingUrl to ok(ZedgeTestPages.landing),
            animeUrl to ok(ZedgeTestPages.animeCategory),
            peopleUrl to ok(ZedgeTestPages.animeCategory),
            findNatureUrl to ok(ZedgeTestPages.search),
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
    fun searchWalksTheSitesOwnSearchScopedToWallpapers() =
        runTest {
            val client = configureWith(liveRoutes())

            val page = provider.search("nature", page = 1).getOrThrow()

            assertEquals(listOf(findNatureUrl), client.requests)
            assertEquals(3, page.wallpapers.size)
            assertEquals("Anime blue-lock", page.wallpapers.first().title)
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
    fun searchPastTheFirstPageIsTheHonestEmpty() =
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
        assertEquals("1.2.0", provider.meta.versionName)
        assertEquals(
            setOf(Capability.POPULAR, Capability.SEARCH, Capability.TAGS, Capability.FILTERS),
            provider.capabilities,
        )
    }
}

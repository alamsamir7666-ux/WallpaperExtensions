package com.cloudimage.behance

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Category
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behance provider over a scripted fake of the plugin-facing HTTP facade,
 * with fixtures cut from the live site's pages: the search state's own
 * application/json block (three real project cards with their real cover
 * rendition URLs, the tag rail, the cursor arithmetic), the project page's
 * own state (four real image modules with their source renditions, one
 * TextModule that must filter out, the project's tags and owners), the
 * Varnish edge's 403 challenge page, and the empty-search wall — plus the
 * challenge dance itself: the fake serves 403 hand-offs exactly the way
 * the edge does, a fresh value per refusal, and the provider must present
 * the latest one until the page clears.
 */
class BehanceWallpaperProviderTest {
    private val provider = BehanceWallpaperProvider()

    /**
     * URL-routed responses with the edge's challenge semantics: every
     * route's first GET answers the 403 cookie hand-off; a retry presents
     * the value of the immediately preceding refusal — the reload a browser
     * performs — and clears once the route's stubborn round count is met.
     * Unmatched URLs answer 500 to fail loudly.
     */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var routes: Map<String, String> = emptyMap()
        var stubbornRounds: Int = 1
        var recognizeChallenge: Boolean = true
        var neverClear: String? = null
        private val served = mutableMapOf<String, Int>()
        private val latest = mutableMapOf<String, String>()
        private var counter = 0

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url to headers
            val never = neverClear
            if (never != null && url.startsWith(never)) return challenge()
            val route =
                routes.entries
                    .filter { (prefix, _) -> url.startsWith(prefix) }
                    .maxByOrNull { (prefix, _) -> prefix.length }
                    ?: return ProviderHttpResponse(500, emptyMap(), ByteArray(0))
            val expected = latest[route.key]?.let { "js_challenge_value=$it" }
            if (presentedEnough(route.key) && headers["Cookie"] == expected && expected != null) {
                return ProviderHttpResponse(200, emptyMap(), route.value.toByteArray())
            }
            return challenge(route.key)
        }

        private fun presentedEnough(prefix: String): Boolean = (served[prefix] ?: 0) >= stubbornRounds

        private fun challenge(prefix: String? = null): ProviderHttpResponse {
            if (prefix != null) served[prefix] = (served[prefix] ?: 0) + 1
            if (!recognizeChallenge) {
                return ProviderHttpResponse(403, emptyMap(), "no cookies here".toByteArray())
            }
            val value = counter++.toString(16).padStart(40, '0')
            if (prefix != null) latest[prefix] = value
            return ProviderHttpResponse(403, emptyMap(), challengeHtml(value).toByteArray())
        }

        /** The edge's 403 hand-off, verbatim shape, with the fresh value injected. */
        private fun challengeHtml(value: String): String =
            """
            <!doctype html>
            <html>
              <head>
                <meta name="robots" content="noindex,nofollow">
              </head>
              <body>
                <script>
                  document.cookie = "js_challenge_value=$value; Path=/";
                  window.location.reload();
                </script>
              </body>
            </html>
            """.trimIndent()
    }

    private fun ok(body: String) = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    private fun configureWith(
        routes: Map<String, String>,
        setup: (FakeClient) -> Unit = { },
    ): FakeClient =
        FakeClient().apply {
            this.routes = routes
            setup(this)
            provider.configure(this, ProviderSettings { null })
        }

    // Fixtures: shapes captured from behance.net, trimmed to the parts the
    // parser keys on, values verbatim from the live site.

    /** The tiny assets block the page ships ahead of the big state — the largest-block picker must skip it. */
    private val assetsBlock =
        """
        {"CBSTR":"cb=264615658","ASSETSURL":"https://a5.behance.net/9d75f239cc502e4cdaa5bc44c0acc4fec827173f/"}
        """.trimIndent()

    /** One real search card: the Gradient Mesh project, covers verbatim. */
    private val nodeGradient =
        """
        {
          "__typename": "Project",
          "id": 162276423,
          "oid": "_3WCcHUzSDfURYyFKToqmw",
          "url": "https://www.behance.net/gallery/162276423/Gradient-Mesh-Minimal-Creative-Desktop-Iphone-Wallpaper",
          "slug": "Gradient-Mesh-Minimal-Creative-Desktop-Iphone-Wallpaper",
          "name": "Gradient Mesh Minimal Creative Desktop Iphone Wallpaper",
          "publishedOn": 1674758494,
          "premium": 0,
          "isBoosted": false,
          "isFlagged": false,
          "isPrivate": false,
          "owners": [
            {
              "id": 503369499,
              "username": "qawsenefekt",
              "displayName": "Trey C.",
              "url": "https://www.behance.net/qawsenefekt"
            }
          ],
          "stats": {"appreciations": {"all": 49}, "views": {"all": 3640}},
          "covers": {
            "allAvailable": [
              {"url": "https://mir-s3-cdn-cf.behance.net/projects/original_webp/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png", "width": null, "type": "WEBP"},
              {"url": "https://mir-s3-cdn-cf.behance.net/projects/404_webp/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png", "width": 404, "type": "WEBP"},
              {"url": "https://mir-s3-cdn-cf.behance.net/projects/404/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png", "width": 404, "type": "JPG"},
              {"url": "https://mir-s3-cdn-cf.behance.net/projects/original/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png", "width": null, "type": "JPG"}
            ]
          }
        }
        """.trimIndent()

    /** A second real card, whose covers list the plain jpg original FIRST — order must not matter. */
    private val nodeDell =
        """
        {
          "__typename": "Project",
          "id": 243119743,
          "url": "https://www.behance.net/gallery/243119743/DELL-2026-XPS-Wallpaper",
          "name": "DELL 2026 XPS Wallpaper",
          "publishedOn": 1769665684,
          "owners": [
            {"id": 123, "username": "wootcreative", "displayName": "WOOT Creative"}
          ],
          "stats": {"appreciations": {"all": 232}, "views": {"all": 7081}},
          "covers": {
            "allAvailable": [
              {"url": "https://mir-s3-cdn-cf.behance.net/projects/original/9bc71b243119743.Y3JvcCwxMjE4LDk1MiwwLDM.jpg", "width": null, "type": "JPG"},
              {"url": "https://mir-s3-cdn-cf.behance.net/projects/original_webp/9bc71b243119743.Y3JvcCwxMjE4LDk1MiwwLDM.jpg", "width": null, "type": "WEBP"},
              {"url": "https://mir-s3-cdn-cf.behance.net/projects/404_webp/9bc71b243119743.Y3JvcCwxMjE4LDk1MiwwLDM.jpg", "width": 404, "type": "WEBP"}
            ]
          }
        }
        """.trimIndent()

    private fun searchHtml(nodes: String): String =
        """
        <html><head><title>Search</title></head><body>
        <script type="application/json">$assetsBlock</script>
        <script type="application/json">{
          "gates": {"feature_stock_rail": 0},
          "search": {
            "content": [],
            "filters": {"search": "wallpaper", "sort": null, "time": null},
            "hasMore": false,
            "itemsPerPage": 48,
            "isSafeBrowsing": true,
            "showMature": false,
            "projects": {
              "viewer": null,
              "search": {
                "pageInfo": {"hasNextPage": true, "endCursor": "MjQ="},
                "metaContent": {"csam": {"isCSAMViolation": false}},
                "nodes": [$nodes]
              },
              "tagSuggestions": [
                {"text": "design", "concatenatedText": "wallpaper design", "concatenatedTagId": null},
                {"text": "background", "concatenatedText": "wallpaper background", "concatenatedTagId": 5452713},
                {"text": "illustration", "concatenatedText": "wallpaper illustration", "concatenatedTagId": 4324639}
              ],
              "stockSearch": null,
              "isLoadingMore": false,
              "seenBoostedProjectIds": []
            },
            "typeaheadResults": {"searchTermSuggestions": [], "searchShortcuts": []},
            "lastSearchTerm": "wallpaper"
          }
        }</script>
        </body></html>
        """.trimIndent()

    private val emptySearchHtml =
        """
        <html><body>
        <script type="application/json">{
          "gates": {},
          "search": {
            "projects": {
              "search": {"pageInfo": {"hasNextPage": false, "endCursor": ""}, "nodes": []},
              "tagSuggestions": []
            }
          }
        }</script>
        </body></html>
        """.trimIndent()

    /**
     * The real project page state: the Gradient Mesh project with four real
     * image modules (the source rendition first in the list, the webp
     * renditions interleaved — pickers must key by segment, not order),
     * one real TextModule that must filter out of the walls, tags with the
     * site's own trailing-space sloppiness, and both cover families.
     */
    private val galleryHtml =
        """
        <html><body>
        <script type="application/json">$assetsBlock</script>
        <script type="application/json">{
          "appreciationCount": 49,
          "commentCount": 1,
          "project": {
            "id": 162276423,
            "oid": "ACDgosOkMAGjljEyaDstog",
            "isDoneFetching": true,
            "isFullPage": true,
            "project": {
              "id": 162276423,
              "name": "Gradient Mesh Minimal Creative Desktop Iphone Wallpaper",
              "url": "https://www.behance.net/gallery/162276423/Gradient-Mesh-Minimal-Creative-Desktop-Iphone-Wallpaper",
              "slug": "Gradient-Mesh-Minimal-Creative-Desktop-Iphone-Wallpaper",
              "privacyLevel": "PUBLIC",
              "owners": [
                {"id": 503369499, "username": "qawsenefekt", "displayName": "Trey C.", "firstName": "Trey"}
              ],
              "creator": {"displayName": "Trey C."},
              "tags": [
                {"id": 982571, "title": "gradient"},
                {"id": 1081719, "title": "gradient mesh"},
                {"id": 109048781, "title": "mobile wallpaper"},
                {"id": 5617837, "title": "typography  "}
              ],
              "covers": {
                "size_original_webp": {"url": "https://mir-s3-cdn-cf.behance.net/projects/original_webp/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png"},
                "allAvailable": [
                  {"url": "https://mir-s3-cdn-cf.behance.net/projects/original/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png", "width": null, "type": "JPG"},
                  {"url": "https://mir-s3-cdn-cf.behance.net/projects/original_webp/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png", "width": null, "type": "WEBP"},
                  {"url": "https://mir-s3-cdn-cf.behance.net/projects/404_webp/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png", "width": 404, "type": "WEBP"},
                  {"url": "https://mir-s3-cdn-cf.behance.net/projects/404/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png", "width": 404, "type": "JPG"}
                ]
              },
              "modules": [
                {
                  "__typename": "ImageModule",
                  "id": 915380531,
                  "src": "https://mir-s3-cdn-cf.behance.net/project_modules/disp/6a409a162276423.63d2c8a9c7b29.png",
                  "width": 600,
                  "height": 600,
                  "altText": "Image may contain: indoor, wall and sitting",
                  "caption": "",
                  "imageSizes": {
                    "size_disp": {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/disp/6a409a162276423.63d2c8a9c7b29.png", "width": 600, "height": 600},
                    "allAvailable": [
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/source/6a409a162276423.63d2c8a9c7b29.png", "width": 2160, "type": "JPG"},
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/max_632_webp/6a409a162276423.63d2c8a9c7b29.png", "width": 632, "type": "WEBP"},
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/disp_webp/6a409a162276423.63d2c8a9c7b29.png", "width": 600, "type": "WEBP"},
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/disp/6a409a162276423.63d2c8a9c7b29.png", "width": 600, "type": "JPG"},
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/hd/6a409a162276423.63d2c8a9c7b29.png", "width": 1240, "type": "JPG"}
                    ]
                  }
                },
                {
                  "__typename": "ImageModule",
                  "id": 915380529,
                  "src": "https://mir-s3-cdn-cf.behance.net/project_modules/disp/9852df162276423.63d2c8a9c6aee.png",
                  "width": 600,
                  "height": 600,
                  "altText": "Image may contain: laptop, computer and indoor",
                  "imageSizes": {
                    "allAvailable": [
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/source/9852df162276423.63d2c8a9c6aee.png", "width": 2160, "type": "JPG"},
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/disp_webp/9852df162276423.63d2c8a9c6aee.png", "width": 600, "type": "WEBP"},
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/fs/9852df162276423.63d2c8a9c6aee.png", "width": 1920, "type": "JPG"}
                    ]
                  }
                },
                {
                  "__typename": "ImageModule",
                  "id": 915380535,
                  "src": "https://mir-s3-cdn-cf.behance.net/project_modules/disp/8a6736162276423.63d2c8a9c8bf4.jpg",
                  "width": 600,
                  "height": 400,
                  "altText": "Image may contain: screenshot, electronics and mobile phone",
                  "imageSizes": {
                    "allAvailable": [
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/source/8a6736162276423.63d2c8a9c8bf4.jpg", "width": 4500, "type": "JPG"},
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/disp/8a6736162276423.63d2c8a9c8bf4.jpg", "width": 600, "type": "JPG"},
                      {"url": "https://mir-s3-cdn-cf.behance.net/project_modules/max_3840/8a6736162276423.63d2c8a9c8bf4.jpg", "width": 3840, "type": "JPG"}
                    ]
                  }
                },
                {
                  "__typename": "TextModule",
                  "id": 1007246737,
                  "alignment": "text-align: center;",
                  "fullBleed": 0,
                  "text": "<div class=\"main-text\"><p>About this project</p></div>"
                }
              ],
              "stats": {"appreciations": {"all": 49}, "views": {"all": 3640}, "comments": {"all": 1}}
            }
          }
        }</script>
        </body></html>
        """.trimIndent()

    /** A prose-only project: no image modules at all — the cover must serve the walls alone. */
    private val coverOnlyHtml =
        """
        <html><body>
        <script type="application/json">{
          "project": {
            "project": {
              "id": 256312775,
              "name": "PRISMATIC BLOOM",
              "url": "https://www.behance.net/gallery/256312775/PRISMATIC-BLOOM",
              "owners": [{"id": 9, "username": "JavierPardina", "displayName": "Javier Pardina"}],
              "tags": [{"id": 1, "title": "aesthetic"}],
              "covers": {
                "allAvailable": [
                  {"url": "https://mir-s3-cdn-cf.behance.net/projects/original_webp/3fed4b256312775.Y3JvcCwxMDA3LDc4OCwxOTksMA.jpg", "width": null, "type": "WEBP"},
                  {"url": "https://mir-s3-cdn-cf.behance.net/projects/404_webp/3fed4b256312775.Y3JvcCwxMDA3LDc4OCwxOTksMA.jpg", "width": 404, "type": "WEBP"}
                ]
              },
              "modules": []
            }
          }
        }</script>
        </body></html>
        """.trimIndent()

    private fun searchRoutes(): Map<String, String> =
        mapOf(
            "https://www.behance.net/search?search=wallpaper" to searchHtml("$nodeGradient,$nodeDell"),
            "https://www.behance.net/search?search=space%20wallpaper" to searchHtml(nodeDell),
            "https://www.behance.net/gallery/162276423/" to galleryHtml,
            "https://www.behance.net/gallery/256312775/" to coverOnlyHtml,
            "https://www.behance.net/search?search=aint%20nothing" to emptySearchHtml,
        )

    // ---------------------------------------------------------------- feeds

    @Test
    fun theFeaturedWallServesItsCards() =
        runTest {
            val client = configureWith(searchRoutes())

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals("162276423", page.wallpapers[0].id)
            assertEquals("243119743", page.wallpapers[1].id)
            assertEquals(2, page.nextPage)
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/projects/404_webp/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png",
                page.wallpapers[0].thumbUrl,
            )
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/projects/original_webp/90e4b0162276423.Y3JvcCwyMTYwLDE2ODksMCwyMzU.png",
                page.wallpapers[0].fullUrl,
            )
            // The cover original of the second card sits FIRST in its list —
            // pickers must key by segment, never by position.
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/projects/original_webp/9bc71b243119743.Y3JvcCwxMjE4LDk1MiwwLDM.jpg",
                page.wallpapers[1].fullUrl,
            )
            page.wallpapers.forEach { wallpaper ->
                assertTrue(wallpaper.id.isNotBlank())
                assertTrue(wallpaper.thumbUrl.startsWith("https://mir-s3-cdn-cf.behance.net/projects/404_webp/"))
                assertTrue(wallpaper.fullUrl.startsWith("https://mir-s3-cdn-cf.behance.net/projects/original"))
                assertEquals("cloudimage.behance", wallpaper.providerId)
            }
            // The dance: the first GET was the 403 hand-off, and the
            // successful reload presented the cookie it handed.
            assertTrue("dance too short: ${client.requests.size}", client.requests.size >= 2)
            val first = client.requests.first()
            val cleared = client.requests[1]
            assertNull(first.second["Cookie"])
            assertEquals("js_challenge_value=", cleared.second["Cookie"]?.substring(0, 19))
            assertTrue(
                "unexpected browser identity: ${first.second["User-Agent"]}",
                first.second["User-Agent"]?.startsWith("Mozilla/5.0 (Windows NT 10.0; Win64; x64)") == true,
            )
        }

    @Test
    fun theDanceClearsStubbornEdges() =
        runTest {
            val client = configureWith(searchRoutes()) { it.stubbornRounds = 3 }

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            // One anonymous GET plus the three hand-offs the edge demanded.
            assertEquals(4, client.requests.size)
        }

    @Test
    fun theDanceGivesUpInsideItsBudget() =
        runTest {
            val client = configureWith(searchRoutes()) { it.neverClear = "https://www.behance.net/search" }

            val result = provider.popular(page = 1)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is ProviderHttpException)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("did not clear"),
            )
            assertEquals(12, client.requests.size)
        }

    @Test
    fun anUnrecognized403IsASourceFailure() =
        runTest {
            configureWith(searchRoutes()) { it.recognizeChallenge = false }

            val result = provider.popular(page = 1)

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("unrecognized HTTP 403"),
            )
        }

    @Test
    fun aNon403RefusalIsASourceFailure() =
        runTest {
            val client = configureWith(searchRoutes())

            val result = provider.search("an unrouted term", page = 1)

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("answered HTTP 500"),
            )
            // Only the challenge status is ever retried; a 500 is terminal.
            assertEquals(1, client.requests.size)
        }

    @Test
    fun aQueryTheSiteCannotAnswerIsHonestlyEmpty() =
        runTest {
            configureWith(searchRoutes())

            val page = provider.search("aint nothing", page = 1).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun aBlankQueryLandsOnTheFeaturedWall() =
        runTest {
            val client = configureWith(searchRoutes())

            val page = provider.search("   ", page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertTrue(
                client.requests
                    .first()
                    .first
                    .contains("search=wallpaper"),
            )
        }

    @Test
    fun beyondTheCapNoRequestIsFired() =
        runTest {
            val client = configureWith(searchRoutes())

            val page = provider.popular(page = 41).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertEquals(0, client.requests.size)
        }

    // ------------------------------------------------------------- grammar

    @Test
    fun theCursorArithmeticIsTheSitesOwn() {
        val url1 = provider.searchUrl("wallpaper", Filters.None, 1)
        val url2 = provider.searchUrl("wallpaper", Filters.None, 2)
        val url3 = provider.searchUrl("wallpaper", Filters.None, 3)

        assertFalse(url1.contains("after="))
        assertTrue("the live-verified page-2 cursor: $url2", url2.contains("after=MjQ%3D"))
        assertTrue("the live-verified page-3 cursor: $url3", url3.contains("after=NDg%3D"))
    }

    @Test
    fun theSortedWatersCarryTheirTimeCompanions() {
        assertEquals(
            listOf("sort" to "appreciations", "time" to "all"),
            provider.sortParams(Filters.of("sorting" to "toplist")),
        )
        assertEquals(
            listOf("sort" to "published_date", "time" to "all"),
            provider.sortParams(Filters.of("sorting" to "date")),
        )
        assertEquals(emptyList<Pair<String, String>>(), provider.sortParams(Filters.None))
        val url = provider.searchUrl("wallpaper", Filters.of("sorting" to "toplist"), 2)
        assertTrue(url.contains("sort=appreciations"))
        assertTrue(url.contains("time=all"))
    }

    @Test
    fun theHostCategoryRidesTheTerm() {
        val url = provider.searchUrl("wallpaper", Filters.of("category" to "anime"), 1)
        assertTrue(url.contains("search=anime%20wallpaper"))
    }

    // ------------------------------------------------------------ tag rail

    @Test
    fun theTagRailFeedsSuggestions() =
        runTest {
            val client = configureWith(searchRoutes())

            val suggestions = provider.suggestTags("wallpaper").getOrThrow()

            assertEquals(
                listOf("wallpaper design", "wallpaper background", "wallpaper illustration"),
                suggestions,
            )
            // The rail is remembered per term: the second ask stays free.
            val requestsAfterFirst = client.requests.size
            val again = provider.suggestTags("wallpaper").getOrThrow()
            assertEquals(suggestions, again)
            assertEquals(requestsAfterFirst, client.requests.size)
        }

    @Test
    fun feedFetchesHarvestTheRailAsABonus() =
        runTest {
            val client = configureWith(searchRoutes())

            provider.popular(page = 1).getOrThrow()

            // The featured wall's own fetch populated the cache; suggestTags
            // answers from it without touching the network again.
            val requestsAfterFeed = client.requests.size
            val suggestions = provider.suggestTags("wallpaper").getOrThrow()
            assertTrue(suggestions.isNotEmpty())
            assertEquals(requestsAfterFeed, client.requests.size)
        }

    // ------------------------------------------------------------- details

    @Test
    fun detailsReadTheProjectPage() =
        runTest {
            configureWith(searchRoutes())

            val details = provider.details("162276423").getOrThrow()

            assertEquals("162276423", details.wallpaper.id)
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/project_modules/source/6a409a162276423.63d2c8a9c7b29.png",
                details.wallpaper.fullUrl,
            )
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/project_modules/disp_webp/6a409a162276423.63d2c8a9c7b29.png",
                details.wallpaper.thumbUrl,
            )
            // The source rendition's own width composed with the display
            // aspect into the true resolution — the site's two numbers.
            assertEquals(2160, details.wallpaper.width)
            assertEquals(2160, details.wallpaper.height)
            assertEquals("2160x2160", details.resolution)
            assertEquals("Trey C.", details.author)
            assertEquals(
                "https://www.behance.net/gallery/162276423/Gradient-Mesh-Minimal-Creative-Desktop-Iphone-Wallpaper",
                details.sourceUrl,
            )
            // The site's own trailing-space sloppiness is trimmed away.
            assertEquals(
                listOf("gradient", "gradient mesh", "mobile wallpaper", "typography"),
                details.wallpaper.tags,
            )
        }

    @Test
    fun detailsHonorTheModuleTheIdNames() =
        runTest {
            configureWith(searchRoutes())

            val details = provider.details("162276423-915380535").getOrThrow()

            assertEquals("162276423-915380535", details.wallpaper.id)
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/project_modules/source/8a6736162276423.63d2c8a9c8bf4.jpg",
                details.wallpaper.fullUrl,
            )
            // 4500-wide source, 600x400 display pair: 4500x3000.
            assertEquals(4500, details.wallpaper.width)
            assertEquals(3000, details.wallpaper.height)
            assertEquals("4500x3000", details.resolution)
        }

    @Test
    fun aModulelessProjectServesItsCover() =
        runTest {
            configureWith(searchRoutes())

            val details = provider.details("256312775").getOrThrow()

            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/projects/original_webp/3fed4b256312775.Y3JvcCwxMDA3LDc4OCwxOTksMA.jpg",
                details.wallpaper.fullUrl,
            )
            assertNull(details.wallpaper.width)
            assertNull(details.wallpaper.height)
            assertNull(details.resolution)
        }

    @Test
    fun aProjectAnsweringForAnotherIdFails() =
        runTest {
            val routes =
                mapOf(
                    "https://www.behance.net/gallery/256312776/" to coverOnlyHtml,
                )
            configureWith(routes)

            val result = provider.details("256312776")

            assertTrue(result.isFailure)
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("answered for"),
            )
        }

    @Test
    fun aGarbageIdFailsWithoutFetching() =
        runTest {
            val client = configureWith(searchRoutes())

            val result = provider.details("not-a-project")

            assertTrue(result.isFailure)
            assertEquals(0, client.requests.size)
        }

    // --------------------------------------------------------------- albums

    @Test
    fun theAlbumWallsServeOnlyTheImageModules() =
        runTest {
            configureWith(searchRoutes())

            val walls = provider.albumWallpapers("162276423").getOrThrow()

            assertEquals(3, walls.size)
            assertEquals("162276423-915380531", walls[0].id)
            assertEquals("162276423-915380529", walls[1].id)
            assertEquals("162276423-915380535", walls[2].id)
            walls.forEach { wall ->
                assertTrue(wall.fullUrl.contains("/project_modules/source/"))
                assertTrue(wall.thumbUrl.contains("/project_modules/"))
            }
            assertEquals(4500, walls[2].width)
            assertEquals(listOf("gradient", "gradient mesh", "mobile wallpaper", "typography"), walls[0].tags)
        }

    @Test
    fun aModulelessAlbumServesItsCoverAlone() =
        runTest {
            configureWith(searchRoutes())

            val walls = provider.albumWallpapers("256312775").getOrThrow()

            assertEquals(1, walls.size)
            assertTrue(walls[0].fullUrl.contains("/projects/original_webp/"))
        }

    @Test
    fun theAlbumParadigmWalksTheSite() =
        runTest {
            val client = configureWith(searchRoutes())

            val categories = provider.categories()
            assertEquals(12, categories.size)
            assertEquals(Category("abstract", "Abstract", "🔷"), categories.first())
            assertTrue(categories.all { it.iconEmoji != null && it.id.isNotBlank() })

            val home = provider.homeAlbums().getOrThrow()
            assertEquals(2, home.size)
            assertEquals("cloudimage.behance", home[0].providerId)
            assertEquals("Gradient Mesh Minimal Creative Desktop Iphone Wallpaper", home[0].title)
            assertTrue(home[0].coverUrl.contains("/projects/404_webp/"))

            val ofCategory = provider.albums("space").getOrThrow()
            assertEquals(1, ofCategory.size)
            assertTrue(
                client.requests
                    .last()
                    .first
                    .contains("search=space%20wallpaper"),
            )

            val searched = provider.searchAlbums("wallpaper").getOrThrow()
            assertTrue(searched.isNotEmpty())

            assertTrue(provider.searchAlbums("   ").getOrThrow().isEmpty())
            assertTrue(provider.albums("  ").getOrThrow().isEmpty())
        }

    @Test
    fun anUnroutedAlbumQueryFailsHonestly() =
        runTest {
            configureWith(searchRoutes())

            val result = provider.albums("deep space nine")

            assertTrue(result.isFailure)
        }

    // -------------------------------------------------------------- surface

    @Test
    fun theShelvesCoverTheSitesOwnWaters() =
        runTest {
            val sections = provider.sections()

            assertEquals(13, sections.size)
            assertEquals(sections.map { it.id }.distinct().size, sections.size)
            assertEquals("featured", sections[0].id)
            assertEquals("Top Rated", sections[1].title)
            assertTrue(sections[1].filters.isSelected("query", "wallpaper"))
            assertTrue(sections[1].filters.isSelected("sorting", "toplist"))
            assertTrue(sections[2].filters.isSelected("sorting", "date"))
            assertTrue(sections[3].filters.valuesFor("query").contains("abstract wallpaper"))
        }

    @Test
    fun randomKeepsTheContractTotal() =
        runTest {
            configureWith(searchRoutes())

            val batch = provider.random().getOrThrow()

            assertTrue(batch.isNotEmpty())
            assertTrue(batch.all { it.fullUrl.contains("/projects/original") })
        }

    @Test
    fun theContractSurface() {
        assertEquals("cloudimage.behance", provider.meta.id)
        assertEquals("Behance", provider.meta.name)
        assertEquals(
            setOf(
                Capability.POPULAR,
                Capability.LATEST,
                Capability.SEARCH,
                Capability.TAGS,
                Capability.FILTERS,
                Capability.ALBUMS,
            ),
            provider.capabilities,
        )
    }
}

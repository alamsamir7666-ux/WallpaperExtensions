package com.cloudimage.behance

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live check against behance.net, for maintenance: the fixture tests pin
 * the state shapes, this answers whether the SITE still speaks them —
 * including the Varnish edge's 403 challenge dance, the search grammar's
 * sorted waters and cursors, and the project pages' source renditions.
 *
 * Run on demand with:
 *
 * `BEHANCE_LIVE=1 ./gradlew :providers:behance:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 */
class BehanceLiveCheckTest {
    private val provider = BehanceWallpaperProvider()

    /**
     * The plugin facade over HttpURLConnection, sending the app's own
     * User-Agent — the same identity [com.cloudimage.core.network] sends;
     * the provider's browser identity rides the facade's header swap on
     * top of it, exactly the way it does in the app.
     */
    private class LiveClient : ProviderHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)",
            )
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val body =
                (if (status in 200..399) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() }
                    ?: ByteArray(0)
            connection.disconnect()
            return ProviderHttpResponse(status, emptyMap(), body)
        }
    }

    private fun live(): Boolean = System.getenv("BEHANCE_LIVE") == "1"

    private suspend fun configureLive() {
        provider.configure(LiveClient(), ProviderSettings { null })
    }

    @Test
    fun theFeaturedWallStillServesItsCards() =
        runTest {
            assumeTrue(live())
            configureLive()

            val page = provider.popular(page = 1).getOrThrow()

            assertTrue("wall too shallow: ${page.wallpapers.size}", page.wallpapers.size >= 5)
            assertEquals(2, page.nextPage)
            page.wallpapers.take(5).forEach { wallpaper ->
                assertTrue(wallpaper.id.isNotBlank())
                assertTrue(
                    "unexpected thumb host: ${wallpaper.thumbUrl}",
                    wallpaper.thumbUrl.startsWith("https://mir-s3-cdn-cf.behance.net/projects/"),
                )
                assertTrue(
                    "unexpected full host: ${wallpaper.fullUrl}",
                    wallpaper.fullUrl.startsWith("https://mir-s3-cdn-cf.behance.net/projects/original"),
                )
            }

            // The cursor's own arithmetic: the second page serves different
            // projects than the first.
            val second = provider.popular(page = 2).getOrThrow()
            assertTrue("page two empty", second.wallpapers.isNotEmpty())
            val firstIds = page.wallpapers.map { it.id }.toSet()
            assertTrue(
                "page two repeated page one",
                second.wallpapers.any { it.id !in firstIds },
            )
        }

    @Test
    fun theSortedWatersStillFlow() =
        runTest {
            assumeTrue(live())
            configureLive()

            val top = provider.popular(page = 1, filters = Filters.of("sorting" to "toplist")).getOrThrow()
            assertTrue("toplist empty", top.wallpapers.isNotEmpty())

            val latest = provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()
            assertTrue("latest empty", latest.wallpapers.isNotEmpty())
            assertTrue(
                "latest served the same water as toplist",
                top.wallpapers.map { it.id }.toSet() != latest.wallpapers.map { it.id }.toSet(),
            )

            // The toplist's first node should tower over the default
            // featured water's — the sort earns its name.
            val featured = provider.popular(page = 1).getOrThrow()
            assertTrue(
                "toplist ids should differ from featured",
                top.wallpapers.map { it.id }.toSet() != featured.wallpapers.map { it.id }.toSet(),
            )
        }

    @Test
    fun searchStillAnswersAndSuggests() =
        runTest {
            assumeTrue(live())
            configureLive()

            val hit = provider.search("abstract wallpaper", page = 1).getOrThrow()
            assertTrue("search empty", hit.wallpapers.isNotEmpty())
            hit.wallpapers.forEach { wallpaper ->
                assertTrue(
                    "unexpected image host: ${wallpaper.thumbUrl}",
                    wallpaper.thumbUrl.startsWith("https://mir-s3-cdn-cf.behance.net/projects/"),
                )
            }

            // A miss the site truly cannot answer embeds an empty nodes
            // array, and the fixture test pins that shape; the live site's
            // fuzzy search answers almost everything, so this check only
            // verifies the provider passes WHATEVER the site answered
            // through, without inventing items of its own.
            val miss = provider.search("zzqxjwvk 897123", page = 1).getOrThrow()
            assertTrue("a miss paged beyond its answer", miss.wallpapers.size <= 24)

            val suggestions = provider.suggestTags("wallpaper").getOrThrow()
            assertTrue("tag rail empty", suggestions.isNotEmpty())
        }

    @Test
    fun projectPagesStillDiscloseTheirSources() =
        runTest {
            assumeTrue(live())
            configureLive()

            val first =
                provider
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()
            val details = provider.details(first.id).getOrThrow()

            assertEquals(first.id, details.wallpaper.id)
            assertTrue(
                "full is not a module source: ${details.wallpaper.fullUrl}",
                details.wallpaper.fullUrl.startsWith("https://mir-s3-cdn-cf.behance.net/"),
            )
            assertTrue(
                "full is neither a source nor an original: ${details.wallpaper.fullUrl}",
                "/source/" in details.wallpaper.fullUrl || "/original" in details.wallpaper.fullUrl,
            )
            assertTrue(details.wallpaper.thumbUrl.startsWith("https://mir-s3-cdn-cf.behance.net/"))
            assertTrue("source URL missing", !details.sourceUrl.isNullOrEmpty())
        }

    @Test
    fun theAlbumParadigmStillWalks() =
        runTest {
            assumeTrue(live())
            configureLive()

            val categories = provider.categories()
            assertTrue("categories empty", categories.isNotEmpty())

            val home = provider.homeAlbums().getOrThrow()
            assertTrue("home albums empty", home.size >= 5)

            val album = home.first()
            val walls = provider.albumWallpapers(album.id).getOrThrow()
            assertTrue("album walls empty for '${album.title}'", walls.isNotEmpty())
            walls.forEach { wall ->
                assertTrue(
                    "album wall is not a source image: ${wall.fullUrl}",
                    wall.fullUrl.startsWith("https://mir-s3-cdn-cf.behance.net/"),
                )
            }

            val searched = provider.searchAlbums("gradient").getOrThrow()
            assertTrue("album search empty", searched.isNotEmpty())
        }
}

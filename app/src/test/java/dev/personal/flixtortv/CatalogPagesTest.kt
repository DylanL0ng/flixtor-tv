package dev.personal.flixtortv

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CatalogPagesTest {
    @Test fun pagesStayStablePastOldLimitAndReloadAfterEviction() {
        val root = Files.createTempDirectory("catalog-test").toFile()
        val pages = CatalogPages(root)
        try {
            val first = pages.append(listOf(Title("0", "/watch/movie/0/test", "First")))
            var all = first
            repeat(12) { page -> all = pages.append((1..30).map { i ->
                val id = (page * 30 + i).toString()
                Title(id, "/watch/movie/$id/test", "Title $id")
            }) }
            assertEquals(361, all.size)
            assertEquals(1, first.size)
            assertEquals("First", all.first().name)
            assertEquals("m:0", (all as CatalogPages.Snapshot).key(0))
            assertEquals(361, pages.append(listOf(all.first())).size)
            pages.warm(0)
            assertEquals("Title 360", all.last().name)
        } finally { pages.close(); root.deleteRecursively() }
    }
}

package apk.harness.cells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CellCheckTest {

    @Test
    fun `the Montmartre pair checks`() {
        val map = mapCell(rows = listOf(sourcedRow("Le Bateau-Lavoir", 48.886056, 2.337721)))
        val table = Cell(
            kind = CellKind.Table,
            from = "montmartre",
            attributes = mapOf("columns" to names("name")),
        )
        val ids = rowsById(listOf(map, table))
        assertEquals(emptyList<CellProblem>(), checkCell(map, ids))
        assertEquals(emptyList<CellProblem>(), checkCell(table, ids))
    }

    @Test
    fun `a map with no title fails`() {
        val cell = mapCell(title = null, rows = listOf(sourcedRow("a", 0.0, 0.0)))
        assertTrue(checkCell(cell, rowsById(listOf(cell))).any { "title" in it.reason })
    }

    @Test
    fun `coordinates are two numbers`() {
        val one = mapCell(rows = listOf(row("a", mapOf("at" to at(1.0)))))
        assertTrue(checkCell(one, emptyMap()).any { "at" in it.reason })

        val text = mapCell(rows = listOf(row("a", mapOf("at" to CellValue.Text("48, 2")))))
        assertTrue(checkCell(text, emptyMap()).any { "at" in it.reason })
    }

    @Test
    fun `a row with no coordinates at all fails`() {
        val cell = mapCell(rows = listOf(row("a", emptyMap())))
        assertTrue(checkCell(cell, emptyMap()).any { "at" in it.reason })
    }

    @Test
    fun `a latitude outside ninety degrees fails`() {
        val cell = mapCell(rows = listOf(sourcedRow("a", 91.0, 0.0)))
        assertTrue(checkCell(cell, emptyMap()).any { "latitude" in it.reason })
    }

    @Test
    fun `a longitude outside a hundred and eighty degrees fails`() {
        val cell = mapCell(rows = listOf(sourcedRow("a", 0.0, -181.0)))
        assertTrue(checkCell(cell, emptyMap()).any { "longitude" in it.reason })
    }

    @Test
    fun `a zoom the map view will not take fails`() {
        val cell = mapCell(zoom = 40.0, rows = listOf(sourcedRow("a", 0.0, 0.0)))
        assertTrue(checkCell(cell, emptyMap()).any { "zoom" in it.reason })
    }

    @Test
    fun `a from that names nothing fails, and says what it named`() {
        val cell = Cell(CellKind.Table, from = "plaecs", attributes = mapOf("columns" to names("name")))
        assertTrue(checkCell(cell, mapOf("places" to emptyList())).any { it.reason == "unknown id: plaecs" })
    }

    @Test
    fun `a from that names a view fails, because a view carries no rows`() {
        val view = Cell(CellKind.Table, id = "sorted", from = "montmartre",
            attributes = mapOf("columns" to names("name")))
        val second = Cell(CellKind.Table, from = "sorted",
            attributes = mapOf("columns" to names("name")))
        assertTrue(checkCell(second, rowsById(listOf(view, second))).isNotEmpty())
    }

    @Test
    fun `a column that was never stored fails`() {
        val map = mapCell(rows = listOf(sourcedRow("a", 0.0, 0.0)))
        val table = Cell(CellKind.Table, from = "montmartre",
            attributes = mapOf("columns" to names("name", "vintage")))
        assertTrue(checkCell(table, rowsById(listOf(map))).any { "vintage" in it.reason })
    }

    @Test
    fun `a sort that was never stored fails`() {
        val map = mapCell(rows = listOf(sourcedRow("a", 0.0, 0.0)))
        val table = Cell(CellKind.Table, from = "montmartre",
            attributes = mapOf("columns" to names("name"), "sort" to CellValue.Text("vintage")))
        assertTrue(checkCell(table, rowsById(listOf(map))).any { "vintage" in it.reason })
    }

    @Test
    fun `an extra survives for a referrer to name`() {
        val stored = sourcedRow("a", 0.0, 0.0).let {
            it.copy(fields = it.fields + ("who" to CellValue.Text("Picasso")))
        }
        val map = mapCell(rows = listOf(stored))
        val table = Cell(CellKind.Table, from = "montmartre",
            attributes = mapOf("columns" to names("who")))
        assertEquals(emptyList<CellProblem>(), checkCell(table, rowsById(listOf(map))))
    }

    @Test
    fun `a factual field with neither a source nor a mark fails`() {
        val cell = mapCell(rows = listOf(row("a", mapOf("at" to at(0.0, 0.0)))))
        assertTrue(checkCell(cell, emptyMap()).any { "at" in it.reason && "provenance" in it.reason })
    }

    @Test
    fun `a mark is provenance, and so is a reference`() {
        val marked = mapCell(rows = listOf(
            row("a", mapOf("at" to at(0.0, 0.0))).copy(blanket = Provenance.Reasoned),
        ))
        assertEquals(emptyList<CellProblem>(), checkCell(marked, emptyMap()))
    }

    @Test
    fun `notes needs nothing, because narrative is presumed reasoned`() {
        val cell = mapCell(rows = listOf(
            sourcedRow("a", 0.0, 0.0).let {
                it.copy(fields = it.fields + ("notes" to CellValue.Text("a walk")))
            },
        ))
        assertEquals(emptyList<CellProblem>(), checkCell(cell, emptyMap()))
    }

    @Test
    fun `an hour that nobody vouched for fails`() {
        val cell = mapCell(rows = listOf(
            sourcedRow("a", 0.0, 0.0).let {
                it.copy(fields = it.fields + ("hours" to CellValue.Text("18:00-01:00")))
            },
        ))
        assertTrue(checkCell(cell, emptyMap()).any { "hours" in it.reason })
    }

    @Test
    fun `a table that carries its own rows checks against them`() {
        val table = Cell(
            kind = CellKind.Table,
            id = "prices",
            attributes = mapOf("columns" to names("name")),
            rows = listOf(row("a", emptyMap())),
        )
        assertEquals(emptyList<CellProblem>(), checkCell(table, rowsById(listOf(table))))
    }

    @Test
    fun `a table that both refers and carries rows fails`() {
        val table = Cell(
            kind = CellKind.Table,
            from = "montmartre",
            attributes = mapOf("columns" to names("name")),
            rows = listOf(row("a", emptyMap())),
        )
        assertTrue(checkCell(table, rowsById(listOf(table))).isNotEmpty())
    }

    @Test
    fun `a table with neither fails`() {
        val table = Cell(CellKind.Table, attributes = mapOf("columns" to names("name")))
        assertTrue(checkCell(table, emptyMap()).isNotEmpty())
    }

    @Test
    fun `two cells claiming one id leave the first holding it`() {
        val first = mapCell(rows = listOf(sourcedRow("a", 0.0, 0.0)))
        val second = mapCell(rows = listOf(sourcedRow("b", 1.0, 1.0)))
        val ids = rowsById(listOf(first, second))
        assertEquals(1, ids.size)
        assertEquals("a", (ids.getValue("montmartre")[0].fields["name"] as CellValue.Text).value)
    }

    @Test
    fun `everything wrong with a cell comes back at once`() {
        val cell = mapCell(title = null, zoom = 99.0, rows = listOf(row("a", mapOf("at" to at(0.0, 0.0)))))
        assertTrue(checkCell(cell, emptyMap()).size >= 3)
    }

    private fun mapCell(
        title: String? = "Famous residents of Montmartre",
        zoom: Double? = 15.0,
        rows: List<CellRow>,
    ): Cell = Cell(
        kind = CellKind.Map,
        id = "montmartre",
        attributes = buildMap {
            if (title != null) put("title", CellValue.Text(title))
            if (zoom != null) put("zoom", CellValue.Number(zoom))
        },
        rows = rows,
    )

    private fun row(name: String, fields: Map<String, CellValue>) =
        CellRow(fields = mapOf("name" to CellValue.Text(name)) + fields)

    private fun sourcedRow(name: String, latitude: Double, longitude: Double) = CellRow(
        fields = mapOf("name" to CellValue.Text(name), "at" to at(latitude, longitude)),
        provenance = mapOf("at" to Provenance.Source("https://maps.google.com/?cid=1")),
    )

    private fun at(vararg values: Double) = CellValue.Series(values.map { CellValue.Number(it) })

    private fun names(vararg values: String) = CellValue.Series(values.map { CellValue.Text(it) })
}

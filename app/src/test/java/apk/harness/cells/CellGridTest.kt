package apk.harness.cells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellGridTest {

    @Test
    fun `text is itself`() {
        assertEquals("Le Bateau-Lavoir", textOf(CellValue.Text("Le Bateau-Lavoir")))
    }

    @Test
    fun `a whole number drops the point zero a double carries`() {
        assertEquals("12", textOf(CellValue.Number(12.0)))
        assertEquals("0", textOf(CellValue.Number(0.0)))
    }

    @Test
    fun `a fractional number keeps its digits`() {
        assertEquals("48.886056", textOf(CellValue.Number(48.886056)))
    }

    @Test
    fun `a flag reads as an answer`() {
        assertEquals("yes", textOf(CellValue.Flag(true)))
        assertEquals("no", textOf(CellValue.Flag(false)))
    }

    @Test
    fun `a series is joined with commas`() {
        val at = CellValue.Series(listOf(CellValue.Number(48.5), CellValue.Number(2.0)))
        assertEquals("48.5, 2", textOf(at))
    }

    @Test
    fun `an absent field is an empty cell`() {
        assertEquals("", textOf(null))
    }

    @Test
    fun `columns decide the order and which fields are drawn`() {
        val cell = table(columns = listOf("years", "name"))
        val rows = listOf(row("name" to "Picasso", "years" to "1904", "who" to "painter"))

        val grid = gridOf(cell, rows)

        assertEquals(listOf("years", "name"), grid.header)
        assertEquals(listOf(listOf("1904", "Picasso")), grid.rows)
    }

    @Test
    fun `sort orders the rows by that column's text`() {
        val cell = table(columns = listOf("name"), sort = "name")
        val rows = listOf(row("name" to "Utrillo"), row("name" to "Picasso"))

        assertEquals(listOf(listOf("Picasso"), listOf("Utrillo")), gridOf(cell, rows).rows)
    }

    @Test
    fun `a row missing the sort column comes last`() {
        val cell = table(columns = listOf("name", "years"), sort = "years")
        val rows = listOf(row("name" to "Nobody"), row("name" to "Picasso", "years" to "1904"))

        assertEquals(listOf("Picasso", "Nobody"), gridOf(cell, rows).rows.map { it.first() })
    }

    @Test
    fun `a mark stands beside the value it vouches for`() {
        val cell = table(columns = listOf("name", "at"))
        val rows = listOf(
            CellRow(
                fields = mapOf(
                    "name" to CellValue.Text("Picasso"),
                    "at" to CellValue.Text("Montmartre"),
                ),
                provenance = mapOf("at" to Provenance.Source("https://example.test")),
            ),
        )

        val marks = gridOf(cell, rows).marks.single()

        assertNull(marks[0])
        assertEquals(Provenance.Source("https://example.test"), marks[1])
    }

    @Test
    fun `the grid copies as tab-separated text with its header`() {
        val cell = table(columns = listOf("name", "years"))
        val rows = listOf(row("name" to "Picasso", "years" to "1904"))

        assertEquals("name\tyears\nPicasso\t1904", tabSeparated(gridOf(cell, rows)))
    }

    @Test
    fun `a blanket source vouches for a factual field`() {
        val row = CellRow(fields = mapOf("at" to CellValue.Text("0, 0")), blanket = Provenance.Reasoned)

        assertEquals(Provenance.Reasoned, markFor(CellKind.Map, "at", row))
    }

    @Test
    fun `a blanket source does not reach an extra the schema calls nobody's claim`() {
        val row = CellRow(fields = mapOf("who" to CellValue.Text("Picasso")), blanket = Provenance.Reasoned)

        assertNull(markFor(CellKind.Map, "who", row))
    }

    @Test
    fun `an extra the row vouched for itself keeps its mark`() {
        val row = CellRow(
            fields = mapOf("who" to CellValue.Text("Picasso")),
            provenance = mapOf("who" to Provenance.Source("https://example.test")),
            blanket = Provenance.Reasoned,
        )

        assertEquals(Provenance.Source("https://example.test"), markFor(CellKind.Map, "who", row))
    }

    @Test
    fun `notes is marked only when the row marked it`() {
        val presumed = CellRow(
            fields = mapOf("notes" to CellValue.Text("a walk")),
            blanket = Provenance.Operator,
        )
        val stated = presumed.copy(provenance = mapOf("notes" to Provenance.Reasoned))

        assertNull(markFor(CellKind.Map, "notes", presumed))
        assertEquals(Provenance.Reasoned, markFor(CellKind.Map, "notes", stated))
    }

    @Test
    fun `a card leaves a blanket source to its footer`() {
        val row = CellRow(
            fields = mapOf("at" to CellValue.Text("0, 0"), "price" to CellValue.Text("free")),
            blanket = Provenance.Source("https://example.test"),
        )

        assertNull(cardMark(CellKind.Map, "at", row))
        assertNull(cardMark(CellKind.Map, "price", row))
    }

    @Test
    fun `a field the row vouched for itself keeps its own mark`() {
        val row = CellRow(
            fields = mapOf("at" to CellValue.Text("0, 0")),
            provenance = mapOf("at" to Provenance.Source("https://field.test")),
            blanket = Provenance.Source("https://blanket.test"),
        )

        assertEquals(Provenance.Source("https://field.test"), cardMark(CellKind.Map, "at", row))
    }

    @Test
    fun `a per-field claim identical to the blanket is the blanket`() {
        val row = CellRow(
            fields = mapOf("at" to CellValue.Text("0, 0")),
            provenance = mapOf("at" to Provenance.Source("https://example.test")),
            blanket = Provenance.Source("https://example.test"),
        )

        assertNull(cardMark(CellKind.Map, "at", row))
    }

    @Test
    fun `a field marked where the row carries no blanket keeps its mark`() {
        val row = CellRow(
            fields = mapOf("hours" to CellValue.Text("daily")),
            provenance = mapOf("hours" to Provenance.Reasoned),
        )

        assertEquals(Provenance.Reasoned, cardMark(CellKind.Map, "hours", row))
    }

    private fun table(columns: List<String>, sort: String? = null) = Cell(
        kind = CellKind.Table,
        attributes = buildMap {
            put("columns", CellValue.Series(columns.map { CellValue.Text(it) }))
            if (sort != null) put("sort", CellValue.Text(sort))
        },
    )

    private fun row(vararg fields: Pair<String, String>) =
        CellRow(fields = fields.associate { (name, value) -> name to CellValue.Text(value) })
}

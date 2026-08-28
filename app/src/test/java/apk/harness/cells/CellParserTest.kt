package apk.harness.cells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CellParserTest {

    @Test
    fun `a map cell carries its attributes and its rows`() {
        val cell = parseCell(CellKind.Map, MONTMARTRE).getOrThrow()
        assertEquals("montmartre", cell.id)
        assertEquals("Famous residents of Montmartre", cell.text("title"))
        assertEquals(15.0, cell.number("zoom")!!, 0.0)
        assertEquals(2, cell.rows.size)
        assertEquals("Le Bateau-Lavoir", (cell.rows[0].fields["name"] as CellValue.Text).value)
    }

    @Test
    fun `coordinates parse as two numbers`() {
        val at = parseCell(CellKind.Map, MONTMARTRE).getOrThrow().rows[0].fields["at"]
        val values = (at as CellValue.Series).values.map { (it as CellValue.Number).value }
        assertEquals(listOf(48.886056, 2.337721), values)
    }

    @Test
    fun `a source table names the field it covers`() {
        val row = parseCell(CellKind.Map, MONTMARTRE).getOrThrow().rows[0]
        assertEquals(Provenance.Source("https://maps.google.com/?cid=4568"), row.provenanceOf("at"))
        assertEquals(Provenance.Reasoned, row.provenanceOf("notes"))
    }

    @Test
    fun `a bare source covers every factual field the row does not name`() {
        val cell = parseCell(
            CellKind.Map,
            """
            title = "one place"
            [[rows]]
            name = "FRIA SOPHIA"
            at = [39.570609, 2.653292]
            source = "operator"
            """.trimIndent(),
        ).getOrThrow()
        assertEquals(Provenance.Operator, cell.rows[0].provenanceOf("at"))
        assertEquals(Provenance.Operator, cell.rows[0].provenanceOf("hours"))
    }

    @Test
    fun `the two marks are read as themselves and anything else is a reference`() {
        val cell = parseCell(
            CellKind.Map,
            """
            title = "marks"
            [[rows]]
            name = "a"
            at = [0.0, 0.0]
            source = { at = "reasoned", hours = "operator", price = "https://example.test/menu" }
            """.trimIndent(),
        ).getOrThrow()
        val row = cell.rows[0]
        assertEquals(Provenance.Reasoned, row.provenanceOf("at"))
        assertEquals(Provenance.Operator, row.provenanceOf("hours"))
        assertEquals(Provenance.Source("https://example.test/menu"), row.provenanceOf("price"))
    }

    @Test
    fun `a table cell carries from and its columns`() {
        val cell = parseCell(
            CellKind.Table,
            """
            from = "montmartre"
            columns = ["who", "name", "years"]
            sort = "years"
            """.trimIndent(),
        ).getOrThrow()
        assertEquals("montmartre", cell.from)
        assertEquals(listOf("who", "name", "years"), cell.names("columns"))
        assertEquals("years", cell.text("sort"))
        assertTrue(cell.rows.isEmpty())
    }

    @Test
    fun `a triple-quoted note carries punctuation with no escaping`() {
        val notes = parseCell(
            CellKind.Map,
            "title = \"quotes\"\n[[rows]]\nname = \"a\"\nat = [0.0, 0.0]\n" +
                "notes = '''He said \"four minutes\" -- it's a walk, not a trek.'''\n",
        ).getOrThrow().rows[0].fields["notes"]
        assertEquals(
            "He said \"four minutes\" -- it's a walk, not a trek.",
            (notes as CellValue.Text).value,
        )
    }

    @Test
    fun `a body that is not TOML fails with a reason and a position`() {
        val problem = (parseCell(CellKind.Map, "title = \"unclosed\nzoom = 15\n")
            .exceptionOrNull() as CellProblemException).problem
        assertTrue(problem.reason.isNotBlank())
        assertNotNull(problem.line)
    }

    @Test
    fun `a dotted source is refused rather than silently mislaid`() {
        // The parser hoists a dotted key out of its `[[rows]]` element into one
        // file-level table shared by every row, where nothing but a line number
        // says which row it came from. Attaching it to the wrong row would pass
        // the check and state a falsehood, so the spelling is refused.
        val result = parseCell(
            CellKind.Map,
            """
            title = "dotted"
            [[rows]]
            name = "a"
            at = [0.0, 0.0]
            source.at = "https://example.test"
            """.trimIndent(),
        )
        assertTrue(result.isFailure)
        val problem = (result.exceptionOrNull() as CellProblemException).problem
        assertTrue("source" in problem.reason)
    }

    @Test
    fun `an empty body is a cell with nothing in it rather than a crash`() {
        val cell = parseCell(CellKind.Table, "").getOrThrow()
        assertTrue(cell.attributes.isEmpty())
        assertTrue(cell.rows.isEmpty())
    }

    @Test
    fun `a row that nests further is refused`() {
        // Attributes and rows, nothing deeper. A table inside a row would parse
        // as TOML and mean nothing here.
        val result = parseCell(
            CellKind.Map,
            """
            title = "nested"
            [[rows]]
            name = "a"
            at = [0.0, 0.0]
            [rows.detail]
            depth = 1
            """.trimIndent(),
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `a source that is not a reference or a mark is refused`() {
        // An empty reference draws as no link but satisfies the check, so a cell
        // would claim a source it does not have. Silence is the error the
        // provenance rules exist to catch, and this is silence in a costume.
        for (body in listOf(
            "title = \"a\"\n[[rows]]\nname = \"a\"\nat = [0.0, 0.0]\nsource = 5\n",
            "title = \"a\"\n[[rows]]\nname = \"a\"\nat = [0.0, 0.0]\nsource = { at = 5 }\n",
        )) {
            val result = parseCell(CellKind.Map, body)
            assertTrue("accepted: $body", result.isFailure)
            val problem = (result.exceptionOrNull() as CellProblemException).problem
            assertTrue("source" in problem.reason)
        }
    }

    private companion object {
        val MONTMARTRE = """
            id = "montmartre"
            title = "Famous residents of Montmartre"
            zoom = 15

            [[rows]]
            name = "Le Bateau-Lavoir"
            at = [48.886056, 2.337721]
            who = "Picasso"
            years = "1904-1909"
            notes = "Painted Les Demoiselles d'Avignon here in 1907."
            source = { at = "https://maps.google.com/?cid=4568", notes = "reasoned" }

            [[rows]]
            name = "54 rue Lepic"
            at = [48.886550, 2.333971]
            who = "Van Gogh"
            years = "1886-1888"
            source = { at = "https://maps.google.com/?cid=8452" }
        """.trimIndent()
    }
}

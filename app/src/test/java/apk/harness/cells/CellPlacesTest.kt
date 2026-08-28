package apk.harness.cells

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellPlacesTest {

    @Test
    fun `a row's coordinates are its pin`() {
        assertEquals(CellPoint(48.886056, 2.337721), pointOf(row(48.886056, 2.337721)))
    }

    @Test
    fun `a row with no coordinates has no pin`() {
        assertNull(pointOf(CellRow(fields = mapOf("name" to CellValue.Text("a")))))
    }

    @Test
    fun `coordinates that are not a pair of numbers have no pin`() {
        assertNull(pointOf(CellRow(fields = mapOf("at" to CellValue.Text("48, 2")))))
        assertNull(pointOf(CellRow(fields = mapOf("at" to series(CellValue.Number(48.0))))))
        assertNull(
            pointOf(
                CellRow(
                    fields = mapOf("at" to series(CellValue.Number(48.0), CellValue.Text("2"))),
                ),
            ),
        )
    }

    @Test
    fun `coordinates off the globe have no pin`() {
        assertNull(pointOf(row(91.0, 0.0)))
        assertNull(pointOf(row(0.0, -181.0)))
    }

    @Test
    fun `a box holds every pin`() {
        val points = listOf(
            CellPoint(48.886056, 2.337721),
            CellPoint(48.886550, 2.333971),
            CellPoint(48.884000, 2.340000),
        )

        assertEquals(CellBounds(48.884000, 2.333971, 48.886550, 2.340000), boundsOf(points))
    }

    @Test
    fun `one pin has no box`() {
        assertNull(boundsOf(listOf(CellPoint(48.886056, 2.337721))))
    }

    @Test
    fun `pins on one spot have no box`() {
        val here = CellPoint(48.886056, 2.337721)

        assertNull(boundsOf(listOf(here, here, here)))
    }

    @Test
    fun `no pins have no box`() {
        assertNull(boundsOf(emptyList()))
    }

    @Test
    fun `pins along one line still have a box`() {
        val points = listOf(CellPoint(48.0, 2.0), CellPoint(48.0, 3.0))

        assertEquals(CellBounds(48.0, 2.0, 48.0, 3.0), boundsOf(points))
    }

    @Test
    fun `the card nearest the snap edge is the selection`() {
        val cards = listOf(CardSpan(2, -40), CardSpan(3, 310), CardSpan(4, 660))

        assertEquals(2, snappedIndex(cards))
    }

    @Test
    fun `a card most of the way past the edge hands the selection on`() {
        val cards = listOf(CardSpan(2, -280), CardSpan(3, 70))

        assertEquals(3, snappedIndex(cards))
    }

    @Test
    fun `a carousel showing nothing has no selection`() {
        assertNull(snappedIndex(emptyList()))
    }

    @Test
    fun `a place hands off as a pin with its name on it`() {
        assertEquals(
            "geo:48.886056,2.337721?q=48.886056,2.337721(Le%20Bateau-Lavoir)",
            geoUri(CellPoint(48.886056, 2.337721), "Le Bateau-Lavoir"),
        )
    }

    @Test
    fun `an ampersand in a name does not end the query`() {
        assertEquals(
            "geo:0.0,0.0?q=0.0,0.0(Chez%20Jean%20%26%20Fils)",
            geoUri(CellPoint(0.0, 0.0), "Chez Jean & Fils"),
        )
    }

    @Test
    fun `a place with no name still hands off its coordinates`() {
        assertEquals("geo:1.5,2.5?q=1.5,2.5", geoUri(CellPoint(1.5, 2.5), " "))
    }

    private fun row(latitude: Double, longitude: Double) = CellRow(
        fields = mapOf(
            "at" to series(CellValue.Number(latitude), CellValue.Number(longitude)),
        ),
    )

    private fun series(vararg values: CellValue) = CellValue.Series(values.toList())
}

package app.kairn

import app.kairn.data.Book
import app.kairn.data.Clipping
import app.kairn.data.Clippings
import org.junit.Assert.assertTrue
import org.junit.Test

/** Third probe: does a Kindle clipping land on the RIGHT book? */
class ClippingMatchTest {

    private fun book(title: String, author: String = "") =
        Book(id = "b", title = title, author = author, uri = "file:///b.epub")

    private fun clip(title: String, author: String = "") =
        Clipping(title, author, quote = "some quoted passage", note = "", kind = "highlight")

    @Test fun a_two_letter_title_does_not_claim_every_clipping_under_the_sun() {
        // "It" normalises to "it", which is a substring of a great many normalised titles.
        val it = book("It", "Stephen King")
        val falseHits = listOf("Little Women", "The Hobbit", "Pride and Prejudice", "White Teeth")
            .filter { title -> Clippings.matches(clip(title), it) }
        assertTrue("a 2-letter title matched ${falseHits.size} unrelated clippings: $falseHits", falseHits.isEmpty())
    }

    @Test fun the_same_author_does_not_make_a_short_title_claim_a_sibling_book() {
        val king = "Stephen King"
        val falseHits = listOf("The Shining", "Carrie", "Misery").filter { title ->
            Clippings.matches(clip(title, king), book("It", king))
        }
        assertTrue("same author, wrong book: $falseHits", falseHits.isEmpty())
    }

    @Test fun a_real_title_still_matches_its_own_clipping() {
        val b = book("The Left Hand of Darkness", "Ursula K. Le Guin")
        assertTrue(Clippings.matches(clip("The Left Hand of Darkness", "Le Guin, Ursula K."), b))
        // The short title matches itself, and its longer form still matches it.
        assertTrue(Clippings.matches(clip("It", "Stephen King"), book("It", "Stephen King")))
        assertTrue(Clippings.matches(clip("Mistborn: The Final Empire", "Brandon Sanderson"), book("Mistborn", "Brandon Sanderson")))
    }
}

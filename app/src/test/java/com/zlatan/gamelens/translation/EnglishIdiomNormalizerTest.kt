package com.zlatan.gamelens.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishIdiomNormalizerTest {

    @Test
    fun testIdiomReplacements() {
        assertEquals("stop the nonsense", EnglishIdiomNormalizer.normalize("cut the crap"))
        assertEquals("come down here right now", EnglishIdiomNormalizer.normalize("get your ass down here"))
        assertEquals("come out here right now", EnglishIdiomNormalizer.normalize("get your ass out here"))
        assertEquals("See you, buddy.", EnglishIdiomNormalizer.normalize("See you, homes."))
        assertEquals("no parties allowed.", EnglishIdiomNormalizer.normalize("no parties."))
        assertEquals("damn!", EnglishIdiomNormalizer.normalize("shit!"))
        assertEquals("damn,", EnglishIdiomNormalizer.normalize("shit,"))
        assertEquals("easy, understood", EnglishIdiomNormalizer.normalize("whoa, got you"))
        assertEquals("be back in five minutes", EnglishIdiomNormalizer.normalize("be back in five"))
        assertEquals("back in ten minutes", EnglishIdiomNormalizer.normalize("back in ten"))
        assertEquals("local newcomer", EnglishIdiomNormalizer.normalize("local upstart"))
        assertEquals("stay hidden off the streets", EnglishIdiomNormalizer.normalize("get off the streets"))
        assertEquals("disguise is compromised", EnglishIdiomNormalizer.normalize("disguise is blown"))
        assertEquals("easy targets", EnglishIdiomNormalizer.normalize("sitting ducks"))
        assertEquals("approaching the target", EnglishIdiomNormalizer.normalize("closing in on target"))
        assertEquals("stay seated and don't move", EnglishIdiomNormalizer.normalize("sit on your asses"))
        assertEquals("stay seated and don't move", EnglishIdiomNormalizer.normalize("sit on your ass"))
        assertEquals("make trouble in he's own backyard", EnglishIdiomNormalizer.normalize("shit where he eats"))
        assertEquals("make trouble in they's own backyard", EnglishIdiomNormalizer.normalize("shit where they eat"))
    }
}

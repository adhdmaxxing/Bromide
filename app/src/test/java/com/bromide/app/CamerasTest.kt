package com.bromide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamerasTest {
    private val sony = Camera(id = "sony", name = "Sony", brand = Brand.Sony, link = Link.App)
    private val fuji = Camera(id = "fuji", name = "Fujifilm", brand = Brand.Fujifilm, link = Link.App)

    @Test
    fun brandsFromExifMake() {
        assertEquals(Brand.Sony, Brand.forMake("SONY"))
        assertEquals(Brand.Nikon, Brand.forMake("NIKON CORPORATION"))
        assertEquals(Brand.Fujifilm, Brand.forMake("FUJIFILM"))
        assertEquals(Brand.Om, Brand.forMake("OM Digital Solutions"))
        assertEquals(Brand.Om, Brand.forMake("OLYMPUS IMAGING CORP.  "))
        assertEquals(Brand.Ricoh, Brand.forMake("RICOH IMAGING COMPANY, LTD."))
        assertEquals(Brand.Leica, Brand.forMake("Leica Camera AG"))
        assertNull(Brand.forMake("Google"))
        assertNull(Brand.forMake(null))
    }

    @Test
    fun photosGoToTheirBrand() {
        val cams = listOf(sony, fuji)
        assertEquals(fuji, Camera.match(cams, "FUJIFILM", "X100V"))
        assertEquals(sony, Camera.match(cams, "SONY", "ILCE-7SM3"))
        assertNull(Camera.match(cams, "Canon", "Canon EOS R6"))
    }

    @Test
    fun twoBodiesOfTheSameBrandAreToldApartByModel() {
        val a = sony.copy(id = "a", model = "ILCE-7SM3")
        val b = sony.copy(id = "b", model = null)
        // the known body gets its photos; a new model goes to the body not yet tied to one
        assertEquals(a, Camera.match(listOf(a, b), "SONY", "ILCE-7SM3"))
        assertEquals(b, Camera.match(listOf(a, b), "SONY", "ILCE-7M4"))
        // once both are known, an unknown third body still lands on a Sony
        assertEquals(a, Camera.match(listOf(a, b.copy(model = "ILCE-7M4")), "SONY", "ILCE-1"))
    }

    @Test
    fun anotherBrandCatchesTheRest() {
        val other = Camera(id = "o", name = "My camera", brand = Brand.Other, link = Link.App)
        assertEquals(other, Camera.match(listOf(sony, other), "SIGMA", "fp"))
        assertEquals(sony, Camera.match(listOf(sony, other), "SONY", "ILCE-7SM3"))
    }

    @Test
    fun phoneFoldersAreSkipped() {
        assertTrue(PhotoWatcher.isExcluded("Pictures/Bromide/"))
        assertTrue(PhotoWatcher.isExcluded("Pictures/Bromide/Full/"))
        assertTrue(PhotoWatcher.isExcluded("DCIM/Camera/"))
        assertTrue(PhotoWatcher.isExcluded("Pictures/Screenshots/"))
        assertTrue(PhotoWatcher.isExcluded("DCIM/Screenshots/"))
        assertFalse(PhotoWatcher.isExcluded("DCIM/Imaging Edge Mobile/"))
        assertFalse(PhotoWatcher.isExcluded("Pictures/SnapBridge/"))
        assertFalse(PhotoWatcher.isExcluded("DCIM/FUJIFILM XApp/"))
        assertFalse(PhotoWatcher.isExcluded("DCIM/Camera Connect/"))
    }
}

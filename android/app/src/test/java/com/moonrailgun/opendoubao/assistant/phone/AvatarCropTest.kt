package com.moonrailgun.opendoubao.assistant.phone

import org.junit.Assert.*
import org.junit.Test

class AvatarCropTest {
    @Test fun initialCropFitsLandscapePortraitAndSmallImages() {
        assertEquals(AvatarCrop(100f, 0f, 200f), AvatarCrop.centered(400, 200))
        assertEquals(AvatarCrop(0f, 100f, 200f), AvatarCrop.centered(200, 400))
        assertEquals(AvatarCrop(0f, 0f, 1f), AvatarCrop.centered(1, 1))
    }

    @Test fun draggingSelectsTheOppositePartOfTheImageAndStopsAtItsEdges() {
        val crop = AvatarCrop.centered(400, 200)
        assertEquals(AvatarCrop(50f, 0f, 200f), crop.transform(400, 200, 200f, 50f, 0f))
        assertEquals(AvatarCrop(0f, 0f, 200f), crop.transform(400, 200, 200f, 900f, 900f))
        assertEquals(AvatarCrop(200f, 0f, 200f), crop.transform(400, 200, 200f, -900f, -900f))
    }

    @Test fun zoomKeepsTheGestureFocusAndSupportsPanningOnBothAxes() {
        val crop = AvatarCrop.centered(400, 200)
        val zoomed = crop.transform(400, 200, 200f, 0f, 0f, 2f, 50f, 150f)
        assertEquals(AvatarCrop(125f, 75f, 100f), zoomed)
        assertEquals(AvatarCrop(150f, 50f, 100f), zoomed.transform(400, 200, 200f, -50f, 50f))
    }

    @Test fun zoomLimitsKeepTheCropInsideTheImageWithoutEmptyBorders() {
        val crop = AvatarCrop.centered(400, 200)
        assertEquals(40f, crop.transform(400, 200, 200f, 0f, 0f, 100f).size, 0f)
        assertEquals(crop, crop.transform(400, 200, 200f, 0f, 0f, .1f))
        val corner = AvatarCrop(300f, 100f, 100f)
        assertEquals(AvatarCrop(200f, 0f, 200f), corner.transform(400, 200, 200f, 0f, 0f, .5f))
        assertEquals(crop, crop.transform(400, 200, 0f, 50f, 50f))
    }
}

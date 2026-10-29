package com.hamdi.tiktokcleaner

import org.junit.Assert.assertTrue
import org.junit.Test

class NavLogicTest {

    @Test
    fun recognizesArabicAndEnglishProfileLabels() {
        assertTrue(NavLogic.isProfileTabLabel("Profile"))
        assertTrue(NavLogic.isProfileTabLabel("الملف الشخصي"))
        assertTrue(NavLogic.isFollowingCounterLabel("9,946 Following"))
        assertTrue(NavLogic.isFollowingCounterLabel("أتابع 120"))
    }
}

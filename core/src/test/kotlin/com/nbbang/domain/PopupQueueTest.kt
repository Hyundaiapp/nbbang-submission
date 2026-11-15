package com.nbbang.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupQueueTest {
    @Test
    fun `떠 있는 팝업은 두고 기다리는 것만 우선순위대로`() {
        val q = PopupQueue()
            .push(Popup.Notice("안내"))
            .push(Popup.Boarding(listOf(1)))
            .push(Popup.Toll(0))

        assertEquals(Popup.Notice("안내"), q.head)
        assertEquals(listOf(Popup.Toll(0), Popup.Boarding(listOf(1))), q.pop().items)
    }

    @Test
    fun `탑승자 추가는 한 장으로 합친다`() {
        val q = PopupQueue().push(Popup.Boarding(listOf(1))).push(Popup.Boarding(listOf(2)))

        assertEquals(listOf(Popup.Boarding(listOf(1, 2))), q.items)
        assertEquals(2, (q.head as Popup.Boarding).count)
    }

    @Test
    fun `같은 톨비 팝업은 한 번만`() {
        val q = PopupQueue().push(Popup.Toll(0)).push(Popup.Toll(0)).push(Popup.Toll(1))

        assertEquals(listOf(Popup.Toll(0), Popup.Toll(1)), q.items)
        assertEquals(listOf(Popup.Toll(1)), q.without { it == Popup.Toll(0) }.items)
    }
}

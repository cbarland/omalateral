package com.lateral.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhysicalDisplayCaptureTargetTest {
    @Test fun choosesModernVirtualOutputByLogicalLayerStack() {
        val dump = """
            Display 4619827259835644672 (physical, "EMU_display_0")
              Composition Display State:
                layerFilter={layerStack=0 toInternalDisplay=true }
            Display 11529215046801423610 (virtual, "Emulator 2D Display")
              Composition Display State:
                layerFilter={layerStack=2 toInternalDisplay=false }
            Display 11529215047605898346 (virtual, "Emulator 2D Display")
              Composition Display State:
                layerFilter={layerStack=3 toInternalDisplay=false }
        """.trimIndent()

        assertEquals("11529215046801423610", PhysicalDisplayCaptureTarget.forLayerStack(dump, 2))
        assertNull(PhysicalDisplayCaptureTarget.forLayerStack(dump, 20))
    }

    @Test fun choosesPhysicalOutputByLogicalLayerStack() {
        val dump = """
            Display 4619827365318488160 (HWC display 0)
              layerStack=0 orientation=0
            Display 4619827365318488161 (HWC display 1)
              layerStack=3 orientation=0
            Display 4619827365318488162 (HWC display 2)
              layerStack=13 orientation=0
        """.trimIndent()

        assertEquals("4619827365318488161", PhysicalDisplayCaptureTarget.forLayerStack(dump, 3))
        assertNull(PhysicalDisplayCaptureTarget.forLayerStack(dump, 2))
    }

    @Test fun unknownSecondaryFormatNeverFallsBackToPhoneOutput() {
        val dump = """
            Display 4619827365318488160 (HWC display 0)
              layerStack=0 orientation=0
            Display 11529215046801423610 (vendor output)
              layerStack=2 orientation=0
        """.trimIndent()

        assertNull(PhysicalDisplayCaptureTarget.forLayerStack(dump, 2))
    }

    @Test fun ambiguousLayerStackNeverChoosesAnArbitraryOutput() {
        val dump = """
            Display 11529215046801423610 (virtual, "External A")
              layerFilter={layerStack=2 toInternalDisplay=false }
            Display 11529215047605898346 (virtual, "External B")
              layerFilter={layerStack=2 toInternalDisplay=false }
        """.trimIndent()

        assertNull(PhysicalDisplayCaptureTarget.forLayerStack(dump, 2))
    }
}

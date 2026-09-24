package com.german.portaljarviswake.core

import org.junit.Assert.*
import org.junit.Test

class SafeZipTest { @Test fun rejectsTraversalAndAbsolutePaths() { assertTrue(SafeZip.isSafe("model/am/final.mdl")); assertFalse(SafeZip.isSafe("../evil")); assertFalse(SafeZip.isSafe("/evil")); assertFalse(SafeZip.isSafe("a/../../evil")) } }

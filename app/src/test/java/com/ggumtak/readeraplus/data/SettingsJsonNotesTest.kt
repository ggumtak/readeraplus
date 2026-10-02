package com.ggumtak.readeraplus.data

import com.ggumtak.readeraplus.settings.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsJsonNotesTest {
    @Test fun notesChoicesTravelAndOldBackupKeepsDevice() {
        val a=AppSettings(highlightLook=2,listPaging=1,recordLookups=false,libraryListMode=LibraryListMode.COVERS)
        assertEquals(a,SettingsJson.appFromJson(SettingsJson.appToJson(a),AppSettings()))
        assertEquals(a,SettingsJson.appFromJson(JSONObject(),a))
    }
    @Test fun invalidChoicesUseDefaults() {
        for(v in listOf(-1,3,99)) {
            val a=SettingsJson.appFromJson(JSONObject().put("a.highlightLook",v).put("a.listPaging",v).put("a.libraryListMode","X"),AppSettings())
            assertEquals(0,a.highlightLook);assertEquals(0,a.listPaging);assertEquals(LibraryListMode.LIST,a.libraryListMode)
        }
    }
}

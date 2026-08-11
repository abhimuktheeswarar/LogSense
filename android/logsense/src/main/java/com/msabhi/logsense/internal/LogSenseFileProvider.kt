package com.msabhi.logsense.internal

import androidx.core.content.FileProvider

/**
 * A uniquely-named [FileProvider] subclass. Host apps almost always register their own
 * `androidx.core.content.FileProvider`; two providers with the same class name collide in the
 * manifest merger and one silently wins, so LogSense's authority would never register. A distinct
 * class avoids that. Paths come from the `<meta-data>` on the manifest `<provider>`: the static
 * [FileProvider.getUriForFile] reads them from there (not from any constructor), so the meta-data
 * must always be present.
 */
internal class LogSenseFileProvider : FileProvider()

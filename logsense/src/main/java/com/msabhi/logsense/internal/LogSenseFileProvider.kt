package com.msabhi.logsense.internal

import androidx.core.content.FileProvider
import com.msabhi.logsense.R

/**
 * A uniquely-named [FileProvider] subclass. Host apps almost always register their own
 * `androidx.core.content.FileProvider`; two providers with the same class name collide in the
 * manifest merger and one silently wins, so LogSense's authority would never register. A distinct
 * class avoids that. Paths come from the constructor, so no `<meta-data>` is needed.
 */
internal class LogSenseFileProvider : FileProvider(R.xml.logsense_file_paths)

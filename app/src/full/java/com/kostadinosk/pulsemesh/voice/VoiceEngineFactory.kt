// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.voice

import android.content.Context

object VoiceEngineFactory {
    fun create(context: Context): VoiceTransportEngine = WebRtcVoiceEngine(context)
}

package com.example

import android.content.Context
import android.media.MediaPlayer

object GameAudio {
    fun playSuccessPress(context: Context) {
        runCatching {
            val appContext = context.applicationContext
            MediaPlayer.create(appContext, R.raw.yup_04)?.apply {
                setOnCompletionListener { player ->
                    player.release()
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    true
                }
                start()
            }
        }
    }
}

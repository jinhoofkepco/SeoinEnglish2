package com.seoin.emojienglish.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt application root. All @InstallIn(SingletonComponent) modules merge here. */
@HiltAndroidApp
class EmojiEnglishApp : Application()

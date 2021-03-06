package com.zdy.baselibrary.base

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger

open class BaseApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        context = this

        Logger.addLogAdapter(AndroidLogAdapter())
    }

    companion object {
        /**
         * 获取上下文对象
         *
         * @return context
         */
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
    }
}

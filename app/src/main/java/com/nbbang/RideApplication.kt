package com.nbbang

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** 기록은 화면이 없어도 이어져야 한다. [RideViewModel]을 앱 전체에서 하나만 두고 화면과 기록 서비스가 같이 쓴다. */
class RideApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    val viewModel: RideViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[RideViewModel::class.java]
    }
}

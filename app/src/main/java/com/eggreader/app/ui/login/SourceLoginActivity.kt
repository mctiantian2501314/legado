package com.eggreader.app.ui.login

import android.os.Bundle
import androidx.activity.viewModels
import com.eggreader.app.R
import com.eggreader.app.base.VMBaseActivity
import com.eggreader.app.data.entities.BaseSource
import com.eggreader.app.databinding.ActivitySourceLoginBinding
import com.eggreader.app.utils.showDialogFragment
import com.eggreader.app.utils.viewbindingdelegate.viewBinding


class SourceLoginActivity : VMBaseActivity<ActivitySourceLoginBinding, SourceLoginViewModel>() {

    override val binding by viewBinding(ActivitySourceLoginBinding::inflate)
    override val viewModel by viewModels<SourceLoginViewModel>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        viewModel.initData(intent, success = { source ->
            initView(source)
        }, error = {
            finish()
        })
    }

    private fun initView(source: BaseSource) {
        if (source.loginUi.isNullOrEmpty()) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fl_fragment, WebViewLoginFragment(), "webViewLogin")
                .commit()
        } else {
            showDialogFragment<SourceLoginDialog>()
        }
    }

}

package com.eggreader.app.ui.code.config

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.eggreader.app.R
import com.eggreader.app.base.BaseDialogFragment
import com.eggreader.app.constant.PreferKey
import com.eggreader.app.databinding.DialogEditChangeThemeBinding
import com.eggreader.app.help.config.AppConfig
import com.eggreader.app.help.config.ThemeConfig
import com.eggreader.app.utils.checkByIndex
import com.eggreader.app.utils.getIndexById
import com.eggreader.app.utils.putPrefBoolean
import com.eggreader.app.utils.putPrefInt
import com.eggreader.app.utils.setLayout
import com.eggreader.app.utils.viewbindingdelegate.viewBinding


class ChangeThemeDialog() : BaseDialogFragment(R.layout.dialog_edit_change_theme) {
    private val binding by viewBinding(DialogEditChangeThemeBinding::bind)
    private var callBack: CallBack? = null
    private var isClick = false
    private var editTemeAuto = AppConfig.editTemeAuto
    private val isDark
        get() = editTemeAuto && ThemeConfig.isDarkTheme()
    private var themeIndex = -1

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is CallBack) {
            callBack = context
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initData()
        initView()
    }

    private fun initData() {
        binding.run {
            themeIndex = if (isDark) {
                AppConfig.editThemeDark
            } else {
                AppConfig.editTheme
            }
            if (themeIndex % 2 == 0) {
                chThemeL.checkByIndex(themeIndex / 2)
            } else {
                chThemeR.checkByIndex(themeIndex / 2)
            }
            switchSystemAuto.isChecked = editTemeAuto
            callBack?.upTheme(themeIndex)
        }
    }

    private fun initView() {
        binding.run {
            chThemeL.setOnCheckedChangeListener { _, checkedId ->
                if (!isClick) {
                    isClick = true
                    chThemeR.clearCheck()
                    val int = chThemeL.getIndexById(checkedId) * 2
                    if (isDark) {
                        putPrefInt(PreferKey.editThemeDark, int)
                    } else {
                        putPrefInt(PreferKey.editTheme, int)
                    }
                    callBack?.upTheme(int)
                    isClick = false
                }
            }
            chThemeR.setOnCheckedChangeListener { _, checkedId ->
                if (!isClick) {
                    isClick = true
                    chThemeL.clearCheck()
                    val int = chThemeR.getIndexById(checkedId) * 2 + 1
                    if (isDark) {
                        putPrefInt(PreferKey.editThemeDark, int)
                    } else {
                        putPrefInt(PreferKey.editTheme, int)
                    }
                    callBack?.upTheme(int)
                    isClick = false
                }
            }
            switchSystemAuto.setOnCheckedChangeListener { _, isChecked ->
                putPrefBoolean(PreferKey.editTemeAuto, isChecked)
                editTemeAuto = isChecked
                initData()
            }
        }
    }

    interface CallBack {
        fun upTheme(index: Int)
    }

}

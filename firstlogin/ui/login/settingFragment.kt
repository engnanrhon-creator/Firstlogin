package com.example.firstlogin.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.example.firstlogin.R
import com.example.firstlogin.fragments.AccountFragment
import com.example.firstlogin.fragments.AppearanceFragment
import com.example.firstlogin.fragments.PrivacyFragment

class settingFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_setting, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.accountbutton).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, AccountFragment())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<ImageView>(R.id.apperancebutton).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, AppearanceFragment())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<ImageView>(R.id.privacybutton).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PrivacyFragment())
                .addToBackStack(null)
                .commit()
        }
    }
}

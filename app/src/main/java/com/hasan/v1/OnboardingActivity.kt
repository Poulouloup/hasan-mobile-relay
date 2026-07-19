package com.hasan.v1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.button.MaterialButton
import com.hasan.v1.databinding.ActivityOnboardingBinding

/**
 * Onboarding premier lancement — 3 écrans swipables via ViewPager2.
 * Stocke "onboarding_completed" dans EncryptedSharedPreferences.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var settings: SettingsManager
    private val dots = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(this)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = OnboardingAdapter(this)
        binding.viewPager.isUserInputEnabled = true

        setupDots()
        binding.viewPager.registerOnPageChangeCallback(object :
            androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                binding.btnNext.text = if (position == 2)
                    getString(R.string.onboarding_start)
                else getString(R.string.onboarding_next)
            }
        })

        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < 2) {
                binding.viewPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }

        binding.btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun finishOnboarding() {
        settings.onboardingCompleted = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setupDots() {
        binding.indicatorContainer.removeAllViews()
        dots.clear()
        repeat(3) { i ->
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(12, 12).apply {
                    marginStart = if (i > 0) 12 else 0
                }
                setBackgroundResource(R.drawable.circle_status)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this@OnboardingActivity, R.color.hasan_text_hint)
                )
            }
            dots.add(dot)
            binding.indicatorContainer.addView(dot)
        }
        updateDots(0)
    }

    private fun updateDots(active: Int) {
        dots.forEachIndexed { i, dot ->
            val color = if (i == active) R.color.hasan_accent else R.color.hasan_text_hint
            dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, color)
            )
        }
    }

    // ─────────────────────────── ViewPager Adapter ────────────────────────────

    private inner class OnboardingAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {
        override fun getItemCount() = 3
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> WelcomePage()
            1 -> WakeWordPage()
            2 -> ReadyPage()
            else -> WelcomePage()
        }
    }

    // ─────────────────────────── Page 1 — Bienvenue ──────────────────────────

    class WelcomePage : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
            inflater.inflate(R.layout.fragment_onboarding_page, c, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            view.findViewById<ImageView>(R.id.ivPageIcon).apply {
                setImageResource(R.mipmap.ic_launcher)
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_avatar)
            }
            view.findViewById<TextView>(R.id.tvPageTitle).text =
                getString(R.string.onboarding_welcome_title)
            view.findViewById<TextView>(R.id.tvPageSubtitle).text =
                getString(R.string.onboarding_welcome_subtitle)
            view.findViewById<TextView>(R.id.tvPageDescription).text =
                getString(R.string.onboarding_welcome_desc)
        }
    }

    // ─────────────────────────── Page 2 — Wake word ──────────────────────────

    class WakeWordPage : Fragment() {
        private val requestPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            view?.findViewById<TextView>(R.id.tvPageDescription)?.text =
                if (granted) getString(R.string.onboarding_wakeword_granted)
                else getString(R.string.onboarding_wakeword_denied)
        }

        override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
            inflater.inflate(R.layout.fragment_onboarding_page, c, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            view.findViewById<ImageView>(R.id.ivPageIcon).visibility = View.GONE
            view.findViewById<TextView>(R.id.tvPageTitle).text =
                getString(R.string.onboarding_wakeword_title)
            view.findViewById<TextView>(R.id.tvPageSubtitle).visibility = View.GONE
            view.findViewById<TextView>(R.id.tvPageDescription).text =
                getString(R.string.onboarding_wakeword_desc)

            val container = view.findViewById<LinearLayout>(R.id.actionContainer)
            val ctx = requireContext()

            val alreadyGranted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (alreadyGranted) {
                view.findViewById<TextView>(R.id.tvPageDescription).text =
                    getString(R.string.onboarding_wakeword_granted)
                return
            }

            val btnActivate = MaterialButton(ctx).apply {
                text = getString(R.string.onboarding_wakeword_activate)
                setTextColor(ContextCompat.getColor(ctx, R.color.white))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.hasan_accent)
                )
                cornerRadius = 56
            }
            container.addView(btnActivate)

            btnActivate.setOnClickListener {
                requestPermission.launch(Manifest.permission.RECORD_AUDIO)
            }

            // Note Huawei
            val tvHuawei = TextView(ctx).apply {
                text = getString(R.string.onboarding_huawei_note)
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.hasan_text_hint))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 32, 0, 0)
            }
            container.addView(tvHuawei)
        }
    }

    // ─────────────────────────── Page 3 — Prêt ! ─────────────────────────────

    class ReadyPage : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
            inflater.inflate(R.layout.fragment_onboarding_page, c, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            view.findViewById<ImageView>(R.id.ivPageIcon).apply {
                setImageResource(R.mipmap.ic_launcher)
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_avatar)
                alpha = 0f
                animate().alpha(1f).setDuration(800).start()
            }
            view.findViewById<TextView>(R.id.tvPageTitle).text =
                getString(R.string.onboarding_ready_title)
            view.findViewById<TextView>(R.id.tvPageSubtitle).text =
                getString(R.string.onboarding_ready_subtitle)

            val ctx = requireContext()
            val hasAudio = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val summary = buildString {
                appendLine(if (hasAudio) "Wake word : activé" else "Wake word : désactivé")
                append("TTS : activé")
            }
            view.findViewById<TextView>(R.id.tvPageDescription).text = summary
        }
    }
}
